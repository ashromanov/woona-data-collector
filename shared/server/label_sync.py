"""Import verified Woona and Drive recordings into Label Studio projects."""

import argparse
import html
import json
import logging
import os
import time
from collections import defaultdict
from pathlib import Path
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from sqlalchemy import create_engine, text

LABELS = {
    "breathing": ("Woona · Дыхание v1", ["спокойное", "частое дыхание / пыхтение", "одышка", "неопределимо"]),
    "quality": ("Woona · Качество сигнала v1", ["годная", "частично годная", "негодная"]),
    "behavior": ("Woona · Поведение на видео v1", ["покой", "ходьба", "бег", "другое"]),
}


def config(kind: str) -> str:
    control = "behavior" if kind == "behavior" else kind
    object_name = "video" if kind == "behavior" else "context"
    object_tag = '<Video name="video" value="$video"/>' if kind == "behavior" else '<HyperText name="context" value="$html" clickableLinks="true"/>'
    choices = "".join(f'<Choice value="{html.escape(choice, quote=True)}"/>' for choice in LABELS[kind][1])
    return f'<View>{object_tag}<Choices name="{control}" toName="{object_name}" choice="single">{choices}</Choices></View>'


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
            SELECT r.id, r.started_at, d.number_or_name, a.file_name,
                   a.server_relative_path, a.expected_size_bytes, a.sha256
            FROM recordings r JOIN dogs d ON d.id=r.dog_id
            JOIN artifacts a ON a.recording_id=r.id
            WHERE r.ingest_status='complete' AND a.storage_status='available'
            ORDER BY r.id, a.id
        """)).mappings().all()
    groups = defaultdict(list)
    for row in rows:
        groups[str(row["id"])].append(row)
    result = []
    for recording_id, members in groups.items():
        first = members[0]
        files = [{"name": row["file_name"], "relative": "woona/" + row["server_relative_path"],
                  "size": row["expected_size_bytes"], "sha256": row["sha256"]} for row in members]
        result.append(task("woona:" + recording_id, "Woona recording " + recording_id, files,
                           source="woona-api-v1", dog=first["number_or_name"],
                           date=first["started_at"].date().isoformat()))
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
        return json.load(response)


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


def sync() -> dict[str, tuple[int, int]]:
    existing_projects = {project["title"]: project["id"] for project in paged("/api/projects")}
    sources = historical_tasks(Path(os.environ["LABEL_SNAPSHOT_ROOT"])) + app_tasks(os.environ["DATABASE_URL"])
    counts = {}
    for kind, (title, _) in LABELS.items():
        project = existing_projects.get(title)
        if project is None:
            project = request_json("POST", "/api/projects", {"title": title, "label_config": config(kind)})["id"]
        expected = [item for item in sources if kind != "behavior" or "video" in item["data"]]
        current = {row["data"].get("source_id") for row in paged(f"/api/tasks?project={project}")}
        missing = [item for item in expected if item["data"]["source_id"] not in current]
        for offset in range(0, len(missing), 50):
            request_json("POST", f"/api/projects/{project}/import", missing[offset:offset + 50])
        actual = {row["data"].get("source_id") for row in paged(f"/api/tasks?project={project}")}
        if not {item["data"]["source_id"] for item in expected}.issubset(actual):
            raise RuntimeError(f"import not verified: {title}")
        counts[kind] = (len(expected), len(actual))
    return counts


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
