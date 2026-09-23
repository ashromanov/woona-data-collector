"""Import verified Woona and Drive recordings into Label Studio projects."""

import argparse
import fcntl
import html
import json
import logging
import os
import time
from collections import defaultdict
from pathlib import Path
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
from xml.etree import ElementTree

from sqlalchemy import create_engine, text

LABELS = {
    "source": ("Woona · Проверка исходных записей v1", ["Комплект пригоден", "Брак / неполный комплект", "Требует проверки"]),
    "activity": ("Woona · Виды активности v1", ["Лежит, не спит", "Спит", "Ходит", "Бегает", "Прыгает"]),
    "gait": ("Woona · Аллюр v1", ["Медленный шаг", "Быстрый шаг без перехода на рысь", "Рысь", "Галоп"]),
    "lameness": ("Woona · Хромота v1", ["Спокойная стойка", "Обычный шаг", "Лёгкая рысь", "Бордюр", "Лестница"]),
}


def config(kind: str) -> str:
    if kind == "source":
        choices = "".join(f'<Choice value="{html.escape(choice, quote=True)}"/>' for choice in LABELS[kind][1])
        return '<View><HyperText name="context" value="$html" clickableLinks="true"/>' \
               f'<Choices name="source" toName="context" choice="single">{choices}</Choices></View>'
    labels = "".join(f'<Label value="{html.escape(label, quote=True)}"/>' for label in LABELS[kind][1])
    # ponytail: source MP4s are nominally 30 FPS; use sync metadata for exact sensor alignment.
    result = '<View><Video name="video" value="$video" frameRate="30" height="400" timelineHeight="110"/>' \
             f'<TimelineLabels name="segment" toName="video">{labels}</TimelineLabels>' \
             '<Choices name="segment_quality" toName="video" choice="single" perRegion="true">' \
             '<Choice value="Чистый"/><Choice value="Брак"/></Choices>'
    if kind == "activity":
        result += '<Choices name="jump_type" toName="video" choice="single" perRegion="true" ' \
                  'visibleWhen="region-selected" whenLabelValue="Прыгает">' \
                  '<Choice value="Через препятствие"/><Choice value="На поверхность"/>' \
                  '<Choice value="С поверхности"/></Choices>'
    if kind == "gait":
        result += '<Choices name="direction" toName="video" choice="single" perRegion="true">' \
                  '<Choice value="Туда"/><Choice value="Обратно"/></Choices>'
    if kind == "lameness":
        result += '<Choices name="viewpoint" toName="video" choice="single" perRegion="true">' \
                  '<Choice value="Спереди"/><Choice value="Сзади"/>' \
                  '<Choice value="Слева направо"/><Choice value="Справа налево"/></Choices>' \
                  '<Header value="Клиническую метку указывать только по подтверждённым данным, не по видео"/>' \
                  '<Choices name="clinical_lameness" toName="video" choice="single">' \
                  '<Choice value="Да"/><Choice value="Нет"/><Choice value="Неопределённо"/></Choices>'
    return result + '<TextArea name="segment_notes" toName="video" perRegion="true" ' \
                    'displayMode="region-list" rows="2" placeholder="Номер, причина брака; для прыжка: тип, высота, отрыв/приземление"/></View>'


def file_url(relative: str) -> str:
    return "/data/local-files/?d=" + quote(relative, safe="/")


def task(source_id: str, title: str, files: list[dict], *, source: str, dog: str, date: str) -> dict:
    links = "".join(
        f'<li><a href="{html.escape(file_url(item["relative"]), quote=True)}" target="_blank">'
        f'{html.escape(item["name"])}</a> ({item["size"]} bytes, SHA-256 {html.escape(item["sha256"])})</li>'
        for item in files
    )
    videos = [item for item in files if item["name"].lower().endswith(".mp4")]
    data = {"source_id": source_id, "html": f"<h3>{html.escape(title)}</h3><p>{html.escape(dog)} · {html.escape(date)}</p><ul>{links}</ul>"}
    if videos:
        data["video"] = file_url(videos[0]["relative"])
    return {"data": data, "meta": {"schema_version": 1, "source": source, "source_id": source_id,
                                     "dog": dog, "capture_date": date}}


def historical_tasks(snapshot: Path) -> list[dict]:
    index_path = snapshot / "recordings-v1.json"
    if not index_path.exists():
        return []
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if index["schema_version"] != 1:
        raise ValueError("unsupported Drive index")
    result = []
    for session in index["sessions"]:
        files = [{"name": Path(item["path"]).name, "relative": "drive/raw/" + item["path"],
                  "size": item["size"], "sha256": item["sha256"]} for item in session["files"]]
        result.append(task("drive:" + session["id"], session["source_path"], files,
                           source="drive-2026-09-18", dog=session["dog"], date=session["capture_date"]))
    return result


def app_tasks(database_url: str) -> list[dict]:
    engine = create_engine(database_url, pool_pre_ping=True)
    with engine.connect() as connection:
        rows = connection.execute(text("""
            SELECT r.id, r.started_at, r.dog_id, r.dog_profile_version_id,
                   r.session_questionnaire, v.questionnaire AS dog_questionnaire,
                   d.number_or_name, i.source_id AS import_source_id, i.source_path, i.provenance,
                   a.file_name,
                   a.server_relative_path, a.expected_size_bytes, a.sha256
            FROM recordings r JOIN dogs d ON d.id=r.dog_id
            JOIN dog_profile_versions v ON v.id=r.dog_profile_version_id
            LEFT JOIN source_imports i ON i.recording_id=r.id
            JOIN artifacts a ON a.recording_id=r.id
            WHERE r.ingest_status='complete' AND a.storage_status='available'
            ORDER BY r.id, a.id
        """)).mappings().all()
    engine.dispose()
    groups = defaultdict(list)
    for row in rows:
        groups[str(row["id"])].append(row)
    result = []
    for recording_id, members in groups.items():
        first = members[0]
        questionnaire = first["session_questionnaire"]
        if questionnaire.get("schemaVersion") != 2 or not set(questionnaire.get("plannedActivities", [])) & {"Аллюр/движение", "Активность"}:
            continue
        names = {row["file_name"] for row in members}
        if not any(name.lower().endswith(".mp4") for name in names) or not any(name.lower().endswith((".bin", ".binlog")) for name in names):
            continue
        files = [{"name": row["file_name"], "relative": "woona/" + row["server_relative_path"],
                  "size": row["expected_size_bytes"], "sha256": row["sha256"]} for row in members]
        result.append(task("woona:" + recording_id, "Woona recording " + recording_id, files,
                           source="woona-api-v1", dog=first["number_or_name"],
                            date=first["started_at"].date().isoformat()))
        result[-1]["data"].update({"recording_id": recording_id, "dog_id": str(first["dog_id"]),
                                  "dog_profile_version_id": str(first["dog_profile_version_id"]),
                                  "dog": first["number_or_name"], "session": questionnaire["sessionLabel"]})
        result[-1]["meta"].update({"dog_questionnaire": first["dog_questionnaire"], "session_questionnaire": questionnaire})
        if first["import_source_id"]:
            result[-1]["meta"].update({"import_source_id": first["import_source_id"], "source_path": first["source_path"],
                                       "drive_files": first["provenance"]["files"],
                                       "alignment": "unavailable: external video has no camera anchor"})
    return result


def request_json(method: str, path: str, payload=None):
    base = os.environ["LABEL_STUDIO_URL"].rstrip("/")
    data = json.dumps(payload).encode() if payload is not None else None
    request = Request(base + path, data=data, method=method, headers={
        "Host": os.environ["LABEL_STUDIO_HOST_HEADER"],
        "Authorization": "Token " + os.environ["LABEL_STUDIO_API_TOKEN"],
        "Content-Type": "application/json",
    })
    with urlopen(request, timeout=60) as response:
        body = response.read()
        return json.loads(body) if body else None


def paged(path: str) -> list[dict]:
    results = []
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        body = request_json("GET", f"{path}{separator}{urlencode({'page': page, 'page_size': 100})}")
        if isinstance(body, list):
            return body
        batch = body.get("results", body.get("tasks", []))
        if not batch and body.get("total", body.get("count", 0)) > len(results):
            raise RuntimeError(f"pagination stopped early: {path}")
        results.extend(batch)
        if not body.get("next") and len(results) >= body.get("total", body.get("count", len(results))):
            return results
        page += 1


def ensure_storage(project: int) -> None:
    current = {item["path"] for item in request_json("GET", f"/api/storages/localfiles/?project={project}")}
    for name in ("woona",):
        path = "/label-studio/files/" + name
        if path not in current:
            request_json("POST", "/api/storages/localfiles/", {
                "project": project, "path": path, "title": name,
                "synchronizable": False, "use_blob_urls": True,
            })


def sync() -> dict[str, tuple[int, int]]:
    # ponytail: one shared container lock; use a database advisory lock if workers are replicated.
    with open("/tmp/woona-label-sync.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _sync_unlocked()


def _sync_unlocked() -> dict[str, tuple[int, int]]:
    project = active_project()
    project_id = project["id"]
    ensure_storage(project_id)
    expected = app_tasks(os.environ["DATABASE_URL"])
    current = {row["data"].get("source_id") for row in paged(f"/api/tasks?project={project_id}")}
    missing = [item for item in expected if item["data"]["source_id"] not in current]
    for offset in range(0, len(missing), 50):
        request_json("POST", f"/api/projects/{project_id}/import", missing[offset:offset + 50])
    rows = paged(f"/api/tasks?project={project_id}")
    actual = [row["data"].get("source_id") for row in rows]
    if not {item["data"]["source_id"] for item in expected}.issubset(actual):
        raise RuntimeError("import not verified")
    if len(actual) != len(set(actual)):
        raise RuntimeError("duplicate source IDs in labeling project")
    return {"activity": (len(expected), len(actual))}


def active_project() -> dict:
    """Use the user's project and read its labels; never create/reconfigure it."""
    project = request_json("GET", f'/api/projects/{int(os.environ.get("LABEL_PROJECT_ID", "21"))}/')
    root = ElementTree.fromstring(project["label_config"])
    timelines = root.findall(".//TimelineLabels")
    videos = root.findall(".//Video")
    if len(timelines) != 1 or len(videos) != 1 or videos[0].get("value") != "$video" or timelines[0].get("toName") != videos[0].get("name"):
        raise ValueError("Expected the existing single video timeline project")
    return {**project, "timeline_name": timelines[0].get("name"),
            "labels": [label.attrib["value"] for label in timelines[0].findall("Label")]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    while True:
        try:
            logging.info("Label Studio sync: %s", sync())
        except Exception:
            logging.exception("Label Studio sync failed")
            if args.once:
                raise
        if args.once:
            break
        time.sleep(60)
