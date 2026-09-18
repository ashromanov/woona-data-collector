"""Read-only, Label Studio-authenticated overview of recordings and labeling."""

import html
import json
import os
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request as UrlRequest, urlopen
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
from sqlalchemy import text

from server.label_sync import LABELS, file_url, paged, request_json

router = APIRouter(prefix="/dashboard", tags=["dashboard"])
KINDS = ("source", "activity", "gait", "lameness")
VIDEO_KINDS = KINDS[1:]


def require_label_user(request: Request) -> None:
    cookie = request.headers.get("cookie", "")
    if not cookie:
        raise HTTPException(401, "Sign in to Label Studio first")
    url = os.environ.get("LABEL_STUDIO_URL", "http://label_studio:8080") + "/api/current-user/whoami"
    try:
        check = UrlRequest(url, headers={
            "Cookie": cookie,
            "Host": os.environ.get("LABEL_STUDIO_HOST_HEADER", "cool-trams.digital"),
        })
        with urlopen(check, timeout=5) as response:
            user = json.load(response)
    except HTTPError as error:
        raise HTTPException(401 if error.code in (401, 403) else 503, "Label Studio authentication failed") from error
    except (URLError, ValueError, TimeoutError) as error:
        raise HTTPException(503, "Label Studio authentication unavailable") from error
    if "projects.view" not in user.get("permissions", []):
        raise HTTPException(403, "Project access required")


def records_from_sources() -> tuple[list[dict], dict]:
    snapshot = Path(os.environ["LABEL_SNAPSHOT_ROOT"])
    index = json.loads((snapshot / "recordings-v1.json").read_text(encoding="utf-8"))
    manifest = json.loads((snapshot / "manifest.json").read_text(encoding="utf-8"))
    if index["schema_version"] != 1:
        raise ValueError("unsupported Drive index")
    records = []
    indexed_ids = set()
    for session in index["sessions"]:
        files = []
        for item in session["files"]:
            indexed_ids.add(item["drive_id"])
            path = snapshot / "raw" / item["path"]
            files.append({"name": Path(item["path"]).name, "relative": "drive/raw/" + item["path"],
                          "size": item["size"],
                          "available": path.is_file() and path.stat().st_size == item["size"]})
        records.append({"source_id": "drive:" + session["id"], "origin": "Drive",
                        "dog": session["dog"], "date": session["capture_date"],
                        "files": files, "ingest_status": "verified", "profile": None,
                        "session_questionnaire": None})

    # Reuse the API's existing PostgreSQL engine; one recording ID owns all artifacts and questionnaires.
    from server.app import STORAGE_ROOT, engine
    with engine.connect() as connection:
        rows = connection.execute(text("""
            SELECT r.id, r.started_at, r.ingest_status, r.capture_status,
                   r.questionnaire_validation_state, r.session_questionnaire,
                   d.number_or_name, v.id AS profile_id, v.validation_state AS profile_state,
                   v.questionnaire AS profile_questionnaire,
                   a.artifact_type, a.file_name, a.expected_size_bytes,
                   a.server_relative_path, a.storage_status
            FROM recordings r JOIN dogs d ON d.id=r.dog_id
            JOIN dog_profile_versions v ON v.id=r.dog_profile_version_id
            LEFT JOIN artifacts a ON a.recording_id=r.id
            ORDER BY r.started_at DESC, a.id
        """)).mappings().all()
        survey_counts = {
            "dogs": connection.execute(text("SELECT count(*) FROM dogs")).scalar_one(),
            "profile_versions": connection.execute(text("SELECT count(*) FROM dog_profile_versions")).scalar_one(),
            "session_questionnaires": connection.execute(text(
                "SELECT count(*) FROM recordings WHERE questionnaire_validation_state='complete'"
            )).scalar_one(),
        }
    app_records = {}
    for row in rows:
        source_id = "woona:" + str(row["id"])
        record = app_records.setdefault(source_id, {
            "source_id": source_id, "origin": "Woona", "dog": row["number_or_name"],
            "date": row["started_at"].date().isoformat(), "files": [],
            "ingest_status": row["ingest_status"], "capture_status": row["capture_status"],
            "profile": {"version_id": str(row["profile_id"]), "state": row["profile_state"],
                        "answers": row["profile_questionnaire"]},
            "session_questionnaire": {"state": row["questionnaire_validation_state"],
                                      "answers": row["session_questionnaire"]},
        })
        if row["artifact_type"]:
            relative = row["server_relative_path"]
            path = (STORAGE_ROOT / relative).resolve() if relative else None
            record["files"].append({"name": row["file_name"], "relative": "woona/" +
                                    (relative or ""),
                                    "size": row["expected_size_bytes"],
                                    "type": row["artifact_type"],
                                    "available": row["storage_status"] == "available" and
                                    path is not None and STORAGE_ROOT in path.parents and
                                    path.is_file() and path.stat().st_size == row["expected_size_bytes"]})
    records.extend(app_records.values())
    return records, {"drive_files": len(manifest["files"]),
                     "drive_unindexed_files": len(manifest["files"]) - len(indexed_ids),
                     **survey_counts}


def summarize(records: list[dict], projects: list[dict], tasks_by_kind: dict, annotation_tasks: dict,
              extra: dict) -> dict:
    task_maps = {kind: {t["data"]["source_id"]: t for t in tasks_by_kind[kind]} for kind in KINDS}
    category = {}
    for kind in KINDS:
        class_sources = defaultdict(set)
        class_segments = Counter()
        for task in tasks_by_kind[kind]:
            if not task.get("is_labeled"):
                continue
            full = annotation_tasks[(kind, task["id"])]
            for annotation in full.get("annotations", []):
                for result in annotation.get("result", []):
                    values = result.get("value", {})
                    labels = values.get("timelinelabels", []) if kind != "source" else values.get("choices", [])
                    if result.get("from_name") != ("source" if kind == "source" else "segment"):
                        continue
                    for label in labels:
                        class_sources[label].add(task["data"]["source_id"])
                        class_segments[label] += 1
        project = next(p for p in projects if p["title"] == LABELS[kind][0])
        category[kind] = {"title": project["title"], "project_id": project["id"],
                          "tasks": len(tasks_by_kind[kind]),
                          "labeled": sum(bool(t.get("is_labeled")) for t in tasks_by_kind[kind]),
                          "classes": {label: {"records": len(class_sources[label]), "segments": class_segments[label]}
                                      for label in LABELS[kind][1]}}

    for record in records:
        files = [f for f in record["files"] if f["available"]]
        record["video"] = any(f.get("type") == "video" or f["name"].lower().endswith(".mp4") for f in files)
        record["ble"] = any(f.get("type") in ("packet", "packet_timeline", "raw", "imported_source")
                            or f["name"].lower().endswith((".bin", ".binlog")) for f in files)
        record["bytes"] = sum(f["size"] for f in files)
        record["reference_files"] = sum("annotat" in f["name"].lower() or "размет" in f["name"].lower()
                                        for f in files)
        record["tasks"] = {kind: {"id": task_maps[kind][record["source_id"]]["id"],
                                   "labeled": bool(task_maps[kind][record["source_id"]].get("is_labeled"))}
                           if record["source_id"] in task_maps[kind] else None for kind in KINDS}
        record["annotated_any"] = any(record["tasks"][kind] and record["tasks"][kind]["labeled"]
                                      for kind in VIDEO_KINDS)
        record["annotated_all"] = all(record["tasks"][kind] and record["tasks"][kind]["labeled"]
                                      for kind in VIDEO_KINDS)
    records.sort(key=lambda r: (r["date"], r["source_id"]), reverse=True)
    video_ble = [r for r in records if r["video"] and r["ble"]]
    return {"updated_at": datetime.now(ZoneInfo("Europe/Moscow")).isoformat(timespec="seconds"),
            "totals": {"groups": len(records), "video_ble": len(video_ble),
                       "annotated_any": sum(r["annotated_any"] for r in video_ble),
                       "annotated_all": sum(r["annotated_all"] for r in video_ble),
                       "source_reviewed": category["source"]["labeled"],
                       "files": sum(len(r["files"]) for r in records),
                       "available_files": sum(f["available"] for r in records for f in r["files"]),
                       "bytes": sum(r["bytes"] for r in records),
                       "pending_import": sum(not r["tasks"]["source"] for r in records),
                       **extra},
            "ingest": dict(Counter(r["ingest_status"] for r in records)),
            "categories": category, "records": records}


def load_dashboard() -> dict:
    records, extra = records_from_sources()
    projects = paged("/api/projects")
    tasks = {kind: paged(f'/api/tasks?project={next(p["id"] for p in projects if p["title"] == LABELS[kind][0])}')
             for kind in KINDS}
    labeled = {(kind, t["id"]): request_json("GET", f'/api/tasks/{t["id"]}')
               for kind in KINDS for t in tasks[kind] if t.get("is_labeled")}
    return summarize(records, projects, tasks, labeled, extra)


def render(data: dict, query: str) -> str:
    escape = html.escape
    totals = data["totals"]
    cards = [("Группы файлов", totals["groups"]), ("Видео + BLE", totals["video_ble"]),
             ("Размечено ≥1 категории", totals["annotated_any"]),
             ("Все 3 видеокатегории", totals["annotated_all"]),
             ("Анкеты собак / сессий", f'{totals["profile_versions"]} / {totals["session_questionnaires"]}')]
    card_html = "".join(f'<div class="card"><small>{escape(name)}</small><strong>{value}</strong></div>'
                        for name, value in cards)
    category_rows = []
    for kind in KINDS:
        c = data["categories"][kind]
        percent = round(100 * c["labeled"] / c["tasks"]) if c["tasks"] else 0
        classes = " · ".join(f'{escape(name)}: {stats["records"]} записей / {stats["segments"]} интервалов'
                             for name, stats in c["classes"].items())
        category_rows.append(f'<tr><td><a href="/projects/{c["project_id"]}/data">{escape(c["title"])}</a>'
                             f'<div class="sub">{classes}</div></td><td>{c["labeled"]} / {c["tasks"]}'
                             f'<div class="bar"><i style="width:{percent}%"></i></div></td></tr>')
    rows = []
    for record in data["records"]:
        if query and query.casefold() not in (record["source_id"] + " " + record["dog"] + " " + record["origin"]).casefold():
            continue
        status = " / ".join("✓" if record["tasks"][kind] and record["tasks"][kind]["labeled"] else "—"
                            for kind in VIDEO_KINDS)
        links = " ".join(f'<a href="/projects/{data["categories"][kind]["project_id"]}/data?task={record["tasks"][kind]["id"]}">{escape(kind)}</a>'
                         for kind in KINDS if record["tasks"][kind])
        files = "".join(f'<li><a href="{escape(file_url(f["relative"]), quote=True)}">{escape(f["name"])}</a>'
                        f' · {f["size"]:,} B</li>' for f in record["files"] if f["available"])
        files += "".join(f'<li>{escape(f["name"])} · {f["size"]:,} B · недоступен/ожидает загрузки</li>'
                         for f in record["files"] if not f["available"])
        surveys = ""
        if record["profile"]:
            surveys = '<details><summary>Анкеты</summary><p>Версия профиля: ' + escape(record["profile"]["version_id"]) + '</p>' \
                      '<pre>' + escape(json.dumps({"dog": record["profile"]["answers"],
                                                   "session": record["session_questionnaire"]["answers"]},
                                                  ensure_ascii=False, indent=2)) + '</pre></details>'
        else:
            surveys = '<span class="muted">Анкета Woona не связана</span>'
        rows.append(f'<tr><td><code>{escape(record["source_id"])}</code><div class="sub">{escape(record["dog"])} · '
                    f'{escape(record["date"])} · {escape(record["origin"])}</div></td>'
                    f'<td>{"✓" if record["video"] else "—"} / {"✓" if record["ble"] else "—"}</td>'
                    f'<td>{escape(record["ingest_status"])}<div class="sub">{len(record["files"])} файлов · '
                    f'{record["bytes"] / 1048576:.1f} MiB · референсы: {record["reference_files"]}</div></td>'
                    f'<td>{status}<div class="sub">{links}</div></td><td>{surveys}'
                    f'<details><summary>Файлы</summary><ul>{files}</ul></details></td></tr>')
    return '''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="60"><title>Woona · Данные и разметка</title>
<style>body{font:15px system-ui,sans-serif;background:#f5f6f1;color:#182326;margin:0}main{max-width:1440px;margin:auto;padding:30px}
a{color:#126a61}header{display:flex;justify-content:space-between;align-items:center;gap:20px}h1{font-size:30px;margin:0}h2{margin:36px 0 14px}
.muted,.sub,small{color:#667579}.sub{font-size:12px;margin-top:5px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(185px,1fr));gap:12px;margin-top:26px}
.card{background:white;border:1px solid #d9e2dd;border-radius:14px;padding:18px}.card small{display:block}.card strong{display:block;font-size:28px;margin-top:7px}
.note{background:#e5f3ef;padding:14px;border-radius:10px;margin-top:18px}.table{overflow:auto;background:white;border:1px solid #d9e2dd;border-radius:12px}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:13px;vertical-align:top;border-bottom:1px solid #e6ebe8}th{white-space:nowrap;color:#526366}
.bar{height:8px;background:#e4ebe7;border-radius:9px;margin-top:8px;min-width:130px}.bar i{display:block;background:#23a58c;height:100%;border-radius:9px}
code{font-size:12px;word-break:break-all}details{max-width:400px}pre{white-space:pre-wrap;word-break:break-word;max-height:350px;overflow:auto}
form{margin:16px 0}input{padding:10px;border:1px solid #bac9c1;border-radius:8px;width:min(330px,70vw)}button{padding:10px;border:0;border-radius:8px;background:#126a61;color:white}</style>
<main><header><div><h1>Woona · данные и разметка</h1><div class="muted">Обновлено: ''' + escape(data["updated_at"]) + ''' · автообновление каждые 60 с</div></div><a href="/projects/">Label Studio ↗</a></header>
<div class="cards">''' + card_html + '''</div><div class="note">«Группа файлов» не равна полной записи. Полная разметка = видео + BLE + отправленные аннотации во всех трёх видеопроектах. Старые CSV-референсы не засчитываются.</div>
<p class="muted">Файлов в индексированных группах: ''' + str(totals["available_files"]) + ''' / ''' + str(totals["files"]) + ''' доступны · объём доступных: ''' + f'{totals["bytes"] / 1073741824:.2f}' + ''' GiB · вне индекса Drive: ''' + str(totals["drive_unindexed_files"]) + ''' · ожидают импорта в Label Studio: ''' + str(totals["pending_import"]) + '''</p>
<h2>Категории разметки</h2><div class="table"><table><thead><tr><th>Проект и классы</th><th>Размечено / задач</th></tr></thead><tbody>''' + "".join(category_rows) + '''</tbody></table></div>
<h2>Записи и анкеты</h2><form><input name="q" value="''' + escape(query, quote=True) + '''" placeholder="Поиск по ID, собаке или источнику"><button>Найти</button></form>
<div class="table"><table><thead><tr><th>Группа</th><th>Видео / BLE</th><th>Загрузка и файлы</th><th>Активности / аллюр / хромота</th><th>Анкеты и файлы</th></tr></thead><tbody>''' + "".join(rows) + '''</tbody></table></div></main></html>'''


@router.get("", response_class=HTMLResponse, dependencies=[Depends(require_label_user)])
def dashboard(q: str = Query("", max_length=100)):
    return HTMLResponse(render(load_dashboard(), q), headers={"Cache-Control": "no-store"})


@router.get("/data", dependencies=[Depends(require_label_user)])
def dashboard_data():
    return JSONResponse(load_dashboard(), headers={"Cache-Control": "no-store"})
