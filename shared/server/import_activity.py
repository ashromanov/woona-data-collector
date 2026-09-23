"""Audited one-off Drive/Sheets import into the normal Woona recording model.

Run --plan first; --apply uses the same deterministic checks. Only complete,
unambiguous dog + session + video + BLE sets are eligible. All original bytes
and survey answers remain in the source snapshot and provenance tables.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import uuid
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from sqlalchemy import create_engine, text

from server.questionnaires import read_sheet, validate_sheet

NAMESPACE = uuid.UUID("293f17a6-faa2-4c3f-98ca-b91c5ae13aee")
FOLDER_ID = "1W2LGVqKdi7ckSonGUn7SHSvy1P54n5jE"
IMPORT_DEVICE = uuid.uuid5(NAMESPACE, "drive-import-device")
TYPES = {"packets.bin": ("packet", "application/octet-stream"),
         "packet_timeline.bin": ("packet_timeline", "application/octet-stream"),
         "raw_fragments.binlog": ("raw", "application/octet-stream"),
         "diagnostics.log": ("diagnostic", "text/plain"), "sync.json": ("sync", "application/json")}


def normalized(value):
    return " ".join(value.strip().casefold().split())


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_path(root, relative):
    path = (root / relative).resolve()
    if root.resolve() not in path.parents:
        raise ValueError("unsafe source path")
    return path


def build_plan(root: Path, surveys: Path) -> dict:
    manifest = json.loads((root / "manifest.json").read_text())
    if manifest["source_folder_id"] != FOLDER_ID or manifest["schema_version"] != 2:
        raise ValueError("wrong Drive batch")
    inventory = json.loads((surveys / "inventory.json").read_text())
    dogs = read_sheet(surveys / "dogs.csv", "dog")
    sessions = read_sheet(surveys / "sessions.csv", "session")
    probes = json.loads((root / "video-probes.json").read_text())
    dog_ids = Counter(normalized(d["answers"].get("animalId") or "") for d in dogs)
    groups = defaultdict(list)
    expected = defaultdict(set)
    for row in inventory:
        expected[row["path"].split("/")[0]].add(row["id"])
    for row in manifest["files"]:
        groups[row["path"].split("/")[0]].append(row)
    video_hashes = defaultdict(set)
    for folder, files in groups.items():
        for row in files:
            if row["path"].lower().endswith(".mp4"):
                video_hashes[row["sha256"]].add(folder)
    entries = []
    for folder, ids in sorted(expected.items()):
        entry = {"source_id": "drive-folder:" + str(uuid.uuid5(NAMESPACE, FOLDER_ID + "/" + folder)),
                 "path": folder, "status": "skipped", "issues": [], "findings": [], "files": groups[folder]}
        entries.append(entry)
        issues = entry["issues"]
        if "экг" in folder.casefold():
            issues.append("Категория ЭКГ/сердце исключена по запросу")
            continue
        if folder.lower().endswith(".zip"):
            issues.append("Корневой архив: категория и собака не подтверждены")
            continue
        missing = ids - {row["id"] for row in entry["files"]}
        if missing:
            issues.append("Не скачаны файлы Drive: " + ", ".join(sorted(missing)))
        match = re.fullmatch(r"(.+?)(?:\s+(\d+))?\s*(?:\[✓\])?", folder)
        name = normalized(match[1]) if match else ""
        session_number = match[2] or "1" if match else ""
        candidates = [d for d in dogs if normalized(d["answers"]["numberOrName"]) == name or normalized(d["answers"].get("animalId") or "") == name]
        if len(candidates) != 1:
            issues.append("Нет однозначной анкеты собаки в таблице")
        else:
            dog = candidates[0]
            entry["dog_row"] = dog["row"]
            entry["dog_answers"] = dog["answers"]
            issues.extend("Анкета собаки: " + issue for issue in dog["issues"])
            if dog_ids[normalized(dog["answers"]["animalId"])] != 1:
                issues.append("Повторяющийся ID животного в таблице")
            session_candidates = [s for s in sessions if normalized(s["answers"].get("animalId") or "") == normalized(dog["answers"]["animalId"]) and s["answers"]["sessionLabel"] == session_number]
            if len(session_candidates) != 1:
                issues.append("Нет однозначной анкеты сессии в таблице")
            else:
                session = session_candidates[0]
                entry["session_row"] = session["row"]
                entry["session_answers"] = session["answers"]
                issues.extend("Анкета сессии: " + issue for issue in session["issues"])
                if not set(session["answers"]["plannedActivities"]) & {"Аллюр/движение", "Активность"}:
                    issues.append("Сессия не относится к активности/аллюру")
        by_name = defaultdict(list)
        for row in entry["files"]:
            by_name[Path(row["path"]).name].append(row)
        archives = [row for row in entry["files"] if row["path"].lower().endswith(".zip")]
        archived_names = []
        for archive in archives:
            with zipfile.ZipFile(source_path(root, archive["relative"])) as source:
                archived_names.extend(source.namelist())
                entry["findings"].append("Содержимое исходного ZIP: " + ", ".join(source.namelist()))
                if "manifest.json" in source.namelist():
                    entry["archive_manifest"] = json.loads(source.read("manifest.json"))
            issues.append("Старый ZIP: нет проверенной связи с видео и современной технической метаинформации; требуется отдельная миграция")
        for required in TYPES:
            archive_suffix = {"packets.bin": "_packet.bin", "raw_fragments.binlog": "_raw.binlog", "diagnostics.log": "_log.log"}.get(required)
            if archive_suffix and any(name.endswith(archive_suffix) for name in archived_names):
                continue
            if len(by_name[required]) != 1:
                issues.append(f"Требуется один {required}, найдено {len(by_name[required])}")
        videos = [f for f in entry["files"] if f["path"].lower().endswith(".mp4")]
        if len(videos) != 1:
            issues.append(f"Требуется одно видео, найдено {len(videos)}")
        else:
            video = videos[0]
            if len(video_hashes[video["sha256"]]) > 1:
                issues.append("Одинаковое видео в разных сессиях: " + ", ".join(sorted(video_hashes[video["sha256"]])))
            probe = probes.get(video["id"], {})
            entry["video_probe"] = probe
            if not any(s.get("codec_name") == "h264" for s in probe.get("streams", [])):
                issues.append("Не подтверждён воспроизводимый H.264 видеопоток")
        if len(by_name["sync.json"]) == 1:
            sync = json.loads(source_path(root, by_name["sync.json"][0]["relative"]).read_text())
            entry["original_sync"] = sync
            source_dog = sync.get("dogQuestionnaire", {}).get("numberOrName", "")
            if normalized(source_dog) != name:
                issues.append(f"Собака в sync.json ({source_dog}) не совпадает с папкой ({name})")
            try:
                uuid.UUID(sync["recordingId"])
                started = datetime.fromisoformat(sync["selectedSessionStartUtc"])
                sensor = sync["sensor"]
                duration = (sync["lastSensorPacketMonotonicNs"] - sensor["monotonicTimeNs"]) / 1e9
                if duration <= 0:
                    raise ValueError("non-positive sensor duration")
                entry["sensor_duration_seconds"] = duration
                if "session_answers" in entry:
                    survey = entry["session_answers"]
                    date = started.astimezone(ZoneInfo(sync["timezone"])).date().isoformat()
                    if survey["sessionDate"] != date:
                        issues.append(f"Дата анкеты {survey['sessionDate']} не совпадает с технической датой {date}")
                    survey_start = datetime.fromisoformat(survey["sessionDate"] + "T" + survey["startTime"]).replace(tzinfo=ZoneInfo(sync["timezone"]))
                    if abs((survey_start - started).total_seconds()) > 5 * 60:
                        issues.append("Время начала анкеты расходится с sync.json более чем на 5 минут")
                    original = sync.get("sessionQuestionnaire", {})
                    if original.get("activityGroup") not in ("locomotion", "daily_living", "stationary"):
                        issues.append("Неподтверждённый исходный протокол")
                    original_number = re.search(r"\d+", original.get("sessionLabel", ""))
                    if original_number and original_number[0] != session_number:
                        issues.append("Номер сессии в sync.json отличается от папки/анкеты")
                    entry["findings"].append("Анкета таблицы сохранена как V2; исходная анкета sync.json сохранена отдельно без изменений")
                if len(videos) == 1 and entry.get("video_probe", {}).get("format", {}).get("duration"):
                    video_duration = float(entry["video_probe"]["format"]["duration"])
                    if not 0.5 <= duration / video_duration <= 1.5:
                        issues.append(f"Несопоставимые длительности: BLE {duration:.1f} с, видео {video_duration:.1f} с")
                    elif abs(duration - video_duration) > 60:
                        entry["findings"].append(f"Длительности различаются: BLE {duration:.1f} с, видео {video_duration:.1f} с; точное выравнивание не установлено")
                entry["findings"].append("Внешнее видео не имеет camera anchor: не считать BLE/видео аппаратно синхронизированными")
            except (KeyError, ValueError, TypeError) as error:
                issues.append(f"Неполные/некорректные технические времена: {error}")
        if not issues:
            for row in entry["files"]:
                path = source_path(root, row["relative"])
                if not path.is_file() or path.stat().st_size != row["size"] or file_sha(path) != row["sha256"]:
                    issues.append(f"Не пройдена проверка размера/SHA-256: {row['path']}")
            if not issues:
                entry["status"] = "ready"
    return {"schema_version": 1, "source_folder_id": FOLDER_ID, "entries": entries,
            "dog_rows": dogs, "session_rows": sessions, "download_failures": manifest.get("download_failures", []),
            "duplicate_inventory_ids": [id for id, count in Counter(r["id"] for r in inventory).items() if count > 1]}


def apply_plan(plan: dict, root: Path, storage: Path, database_url: str) -> None:
    engine = create_engine(database_url)
    linked_dogs, linked_sessions = {}, {}
    with engine.begin() as c:
        c.execute(text("SELECT pg_advisory_xact_lock(73209121)"))
        c.execute(text("""INSERT INTO client_devices(id,label,token_hash,revoked_at)
                          VALUES(:id,'Historical Drive import',:hash,now()) ON CONFLICT DO NOTHING"""),
                  {"id": IMPORT_DEVICE, "hash": sha(str(uuid.uuid4()))})
        for entry in plan["entries"]:
            rid = None
            if entry["status"] == "ready":
                dog = entry["dog_answers"]
                session = entry["session_answers"]
                validate_sheet("dog", dog)
                validate_sheet("session", session)
                animal_id = normalized(dog["animalId"])
                dog_id = c.execute(text("SELECT dog_id FROM dog_external_ids WHERE namespace='woona' AND external_id=:id"), {"id": animal_id}).scalar_one_or_none()
                dog_id = dog_id or uuid.uuid5(NAMESPACE, "dog:" + animal_id)
                profile_hash = sha(canonical(dog))
                profile_id = uuid.uuid5(dog_id, profile_hash)
                sync = entry["original_sync"]
                rid = uuid.UUID(sync["recordingId"])
                started = datetime.fromisoformat(sync["selectedSessionStartUtc"])
                ended = datetime.fromisoformat(sync["sensor"]["absoluteUtc"]) + timedelta(seconds=entry["sensor_duration_seconds"])
                existing = c.execute(text("SELECT status, provenance FROM source_imports WHERE source_id=:id FOR UPDATE"),
                                     {"id": entry["source_id"]}).mappings().one_or_none()
                if existing and existing["status"] == "imported" and existing["provenance"] != entry:
                    raise ValueError("immutable import provenance changed: " + entry["path"])
                conflict = c.execute(text("SELECT id FROM recordings WHERE id=:id"), {"id": rid}).scalar_one_or_none()
                if conflict and (not existing or existing["status"] == "skipped"):
                    raise ValueError("recording UUID already exists outside this import: " + str(rid))
                if not existing or existing["status"] == "skipped":
                    c.execute(text("INSERT INTO dogs(id,number_or_name,created_by_device_id) VALUES(:id,:name,:device) ON CONFLICT DO NOTHING"),
                              {"id": dog_id, "name": dog["numberOrName"], "device": IMPORT_DEVICE})
                    c.execute(text("INSERT INTO dog_external_ids VALUES('woona',:external,:dog) ON CONFLICT DO NOTHING"), {"external": animal_id, "dog": dog_id})
                    current = c.execute(text("SELECT id FROM dog_profile_versions WHERE dog_id=:dog AND superseded_at IS NULL"), {"dog": dog_id}).scalar_one_or_none()
                    if current is not None and current != profile_id:
                        raise ValueError("dog already has a different profile; explicit version reconciliation required")
                    c.execute(text("""INSERT INTO dog_profile_versions(id,dog_id,schema_version,validation_state,questionnaire,
                        content_sha256,created_by_device_id,client_created_at)
                        VALUES(:id,:dog,2,'complete',CAST(:q AS jsonb),:hash,:device,:created) ON CONFLICT DO NOTHING"""),
                        {"id": profile_id, "dog": dog_id, "q": canonical(dog), "hash": profile_hash, "device": IMPORT_DEVICE,
                         "created": datetime.fromisoformat(dog["savedAtLocal"]).replace(tzinfo=ZoneInfo("Europe/Moscow"))})
                    c.execute(text("""INSERT INTO recordings(id,dog_id,dog_profile_version_id,capture_device_id,source,capture_status,
                        ingest_status,started_at,ended_at,timezone,session_label,questionnaire_schema_version,
                        questionnaire_validation_state,session_questionnaire,video_requested,app_version,client_created_at,
                        server_verified_at,receipt_sha256)
                        VALUES(:id,:dog,:profile,:device,'live','completed','complete',:start,:end,:zone,:label,2,
                        'complete',CAST(:q AS jsonb),false,'drive-import-v2',:start,now(),:receipt)"""),
                        {"id": rid, "dog": dog_id, "profile": profile_id, "device": IMPORT_DEVICE, "start": started, "end": ended,
                         "zone": sync["timezone"], "label": session["sessionLabel"], "q": canonical(session), "receipt": sha(canonical(entry))})
                    sensor = sync["sensor"]
                    c.execute(text("""INSERT INTO recording_sync(recording_id,schema_version,monotonic_clock,session_zero_at_utc,
                        session_zero_wall_clock_ms,session_zero_monotonic_ns,session_zero_uncertainty_ns,
                        first_sensor_packet_monotonic_ns,first_sensor_device_timer_ms,last_sensor_packet_monotonic_ns,
                        camera_clock_quality,sensor_clock_quality,overall_sync_quality)
                        VALUES(:id,2,:clock,:utc,:wall,:mono,:uncertainty,:mono,:timer,:last,'unavailable','first_packet_arrival','unavailable')"""),
                        {"id": rid, "clock": sensor["monotonicClock"], "utc": sensor["absoluteUtc"], "wall": sensor["wallClockEpochMillis"],
                         "mono": sensor["monotonicTimeNs"], "uncertainty": sensor["samplingUncertaintyNs"],
                         "timer": sync.get("firstSensorDeviceTimerMillis"), "last": sync["lastSensorPacketMonotonicNs"]})
                    for row in entry["files"]:
                        name = Path(row["path"]).name
                        kind, mime = ("video", "video/mp4") if name.lower().endswith(".mp4") else TYPES[name]
                        relative = f"recordings/{dog_id}/{started.date()}/{rid}/{name}"
                        destination = storage / relative
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        if destination.exists():
                            if file_sha(destination) != row["sha256"]:
                                raise ValueError("destination checksum conflict")
                        else:
                            pending = destination.with_name(destination.name + ".import-part")
                            shutil.copyfile(source_path(root, row["relative"]), pending)
                            if file_sha(pending) != row["sha256"]:
                                raise ValueError("copy checksum mismatch")
                            pending.chmod(0o644)
                            pending.replace(destination)
                        c.execute(text("""INSERT INTO artifacts(id,recording_id,artifact_type,file_name,mime_type,
                            expected_size_bytes,stored_size_bytes,sha256,storage_status,server_relative_path,client_created_at,verified_at)
                            VALUES(:id,:rid,:kind,:name,:mime,:size,:size,:hash,'available',:path,:created,now())"""),
                            {"id": uuid.uuid5(rid, row["id"]), "rid": rid, "kind": kind, "name": name, "mime": mime,
                             "size": row["size"], "hash": row["sha256"], "path": relative, "created": started})
                linked_dogs[entry["dog_row"]] = (dog_id, profile_id)
                linked_sessions[entry["session_row"]] = (dog_id, profile_id, rid)
            c.execute(text("""INSERT INTO source_imports(source_id,recording_id,source_folder_id,source_path,status,provenance,issues)
                VALUES(:id,:rid,:folder,:path,:status,CAST(:p AS jsonb),CAST(:issues AS jsonb))
                ON CONFLICT(source_id) DO UPDATE SET recording_id=EXCLUDED.recording_id,
                    status=EXCLUDED.status, provenance=EXCLUDED.provenance, issues=EXCLUDED.issues
                WHERE source_imports.status='skipped' AND EXCLUDED.status='imported'"""),
                {"id": entry["source_id"], "rid": rid, "folder": FOLDER_ID, "path": entry["path"],
                 "status": "imported" if rid else "skipped", "p": canonical(entry), "issues": canonical(entry["issues"])})
        for kind, rows in (("dog", plan["dog_rows"]), ("session", plan["session_rows"])):
            for row in rows:
                link = linked_dogs.get(row["row"]) if kind == "dog" else linked_sessions.get(row["row"])
                issues = row["issues"] + ([] if link else ["Нет полного непротиворечивого комплекта; сохранено только в журнале импорта"])
                source_id = f"sheet:{kind}:" + sha(canonical(row["raw"]))
                c.execute(text("""INSERT INTO source_survey_rows(source_id,kind,raw_answers,questionnaire,dog_id,profile_version_id,recording_id,issues)
                    VALUES(:id,:kind,CAST(:raw AS jsonb),CAST(:q AS jsonb),:dog,:profile,:rid,CAST(:issues AS jsonb))
                    ON CONFLICT(source_id) DO UPDATE SET dog_id=EXCLUDED.dog_id,
                        profile_version_id=EXCLUDED.profile_version_id,
                        recording_id=EXCLUDED.recording_id, issues=EXCLUDED.issues
                    WHERE source_survey_rows.dog_id IS NULL AND EXCLUDED.dog_id IS NOT NULL"""),
                    {"id": source_id, "kind": kind, "raw": canonical(row["raw"]), "q": canonical(row["answers"]),
                     "dog": link[0] if link else None, "profile": link[1] if link else None,
                     "rid": link[2] if link and kind == "session" else None, "issues": canonical(issues)})
    engine.dispose()


def report(plan: dict, path: Path, applied: bool) -> None:
    lines = ["# Отчёт импорта: Активность и Аллюр", "", "Источник: " + FOLDER_ID,
             "", "Режим: " + ("импорт выполнен" if applied else "проверка без изменения БД"), "",
             "В проект допускается только полный комплект: анкета собаки + анкета сессии + видео + BLE + технические метаданные.", ""]
    for entry in plan["entries"]:
        lines += ["## " + entry["path"], "", "**" + ("Импортировано" if applied else "Готово") + "**" if entry["status"] == "ready" else "**Пропущено**"]
        lines += ["- " + issue for issue in entry["issues"] + entry["findings"]]
        if entry["status"] == "ready":
            lines += ["- Recording UUID: `" + entry["original_sync"]["recordingId"] + "`",
                      f"- Строки таблиц: собака {entry['dog_row']}, сессия {entry['session_row']}."]
        lines.append("")
    lines += ["## Анкеты вне полных комплектов", ""]
    for kind, rows, key in (("Собака", plan["dog_rows"], "dog_row"), ("Сессия", plan["session_rows"], "session_row")):
        accepted = {e[key] for e in plan["entries"] if e["status"] == "ready"}
        for row in rows:
            if row["row"] not in accepted:
                lines.append(f"- {kind}, строка {row['row']}: " + ("; ".join(row["issues"]) or "нет полного непротиворечивого комплекта"))
    lines += ["", "## Технический аудит", "", f"- Повторяющиеся ID в выдаче Drive: {len(plan['duplicate_inventory_ids'])}; учтены один раз.",
              f"- Ошибки скачивания: {len(plan['download_failures'])}.",
              "- Полный машиночитаемый журнал с исходными ответами и SHA-256: `import-audit.json`.",
              "- Никаких меток/аннотаций автоматически не создавалось."]
    path.write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("surveys", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    plan = build_plan(args.snapshot, args.surveys)
    if args.apply:
        apply_plan(plan, args.snapshot, Path(os.environ["WOONA_STORAGE_ROOT"]), os.environ["DATABASE_URL"])
    (args.surveys / "import-audit.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2))
    report(plan, args.surveys / "IMPORT_REPORT.md", args.apply)
    print(json.dumps({"ready": [e["path"] for e in plan["entries"] if e["status"] == "ready"],
                      "skipped": len([e for e in plan["entries"] if e["status"] != "ready"])} , ensure_ascii=False))
