import csv
import json
import os
import tempfile
import unittest
import uuid
from copy import deepcopy
from pathlib import Path

from sqlalchemy import text

from server.import_activity import FOLDER_ID, TYPES, apply_plan, build_plan, file_sha
from server.questionnaires import DOG_COLUMNS, SESSION_COLUMNS


def fixture(root):
    animal = "dog-" + uuid.uuid4().hex[:10]
    folder = animal + " 1"
    rid = str(uuid.uuid4())
    sync = {"recordingId": rid, "selectedSessionStartUtc": "2026-09-04T16:08:00Z", "timezone": "Europe/Moscow",
            "dogQuestionnaire": {"numberOrName": animal}, "sessionQuestionnaire": {"sessionLabel": "1", "activityGroup": "locomotion"},
            "sensor": {"absoluteUtc": "2026-09-04T16:08:00Z", "wallClockEpochMillis": 1788538080000,
                       "monotonicTimeNs": 1000000000, "samplingUncertaintyNs": 0, "monotonicClock": "android.elapsedRealtimeNanos"},
            "lastSensorPacketMonotonicNs": 121000000000, "firstSensorDeviceTimerMillis": 0}
    files = []
    for name in [*TYPES, "video.mp4"]:
        relative = "raw/" + name
        path = root / relative
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(sync) if name == "sync.json" else "fixture " + name)
        files.append({"id": name, "path": folder + "/" + name, "relative": relative,
                      "size": path.stat().st_size, "sha256": file_sha(path)})
    (root / "manifest.json").write_text(json.dumps({"schema_version": 2, "source_folder_id": FOLDER_ID, "files": files}))
    (root / "inventory.json").write_text(json.dumps(files))
    (root / "video-probes.json").write_text(json.dumps({"video.mp4": {"streams": [{"codec_name": "h264"}], "format": {"duration": "120"}}}))
    for name, columns, values in [
        ("dogs", DOG_COLUMNS, {"animalId": animal, "numberOrName": animal, "savedAtLocal": "2026-09-04 20:00:00"}),
        ("sessions", SESSION_COLUMNS, {"animalId": animal, "sessionLabel": "1", "sessionDate": "2026-09-04", "startTime": "19:08", "plannedActivities": "Аллюр/движение"}),
    ]:
        with (root / (name + ".csv")).open("w") as stream:
            writer = csv.writer(stream)
            writer.writerow([title for _, title in columns])
            writer.writerow([values.get(key, "") for key, _ in columns])
    return rid


class ImportPlanTest(unittest.TestCase):
    def test_complete_set_and_identity_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            self.assertEqual(build_plan(root, root)["entries"][0]["status"], "ready")
            sync = json.loads((root / "raw/sync.json").read_text())
            sync["dogQuestionnaire"]["numberOrName"] = "another dog"
            (root / "raw/sync.json").write_text(json.dumps(sync))
            entry = build_plan(root, root)["entries"][0]
            self.assertEqual(entry["status"], "skipped")
            self.assertTrue(any("не совпадает с папкой" in issue for issue in entry["issues"]))

    def test_partial_or_corrupt_files_never_become_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            (root / "raw/packets.bin").write_bytes(b"corrupted")
            entry = build_plan(root, root)["entries"][0]
            self.assertEqual(entry["status"], "skipped")
            self.assertTrue(any("SHA-256" in issue for issue in entry["issues"]))


@unittest.skipUnless(os.environ.get("WOONA_TEST_IMPORT_DB") == "1", "requires isolated migrated test database")
class ImportDatabaseTest(unittest.TestCase):
    def test_import_is_idempotent_and_restorable_by_mobile_api(self):
        from server.app import engine, STORAGE_ROOT
        from server.tests.test_api import request
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rid = fixture(root)
            plan = build_plan(root, root)
            skipped = deepcopy(plan)
            skipped["entries"][0]["status"] = "skipped"
            skipped["entries"][0]["issues"] = ["pending source review"]
            apply_plan(skipped, root, STORAGE_ROOT, os.environ["DATABASE_URL"])
            apply_plan(plan, root, STORAGE_ROOT, os.environ["DATABASE_URL"])
            apply_plan(deepcopy(plan), root, STORAGE_ROOT, os.environ["DATABASE_URL"])
            with engine.connect() as c:
                dog_id = c.execute(text("SELECT dog_id FROM recordings WHERE id=:id"), {"id": rid}).scalar_one()
                self.assertEqual(c.execute(text("SELECT count(*) FROM artifacts WHERE recording_id=:id"), {"id": rid}).scalar_one(), 6)
                self.assertEqual(c.execute(text("SELECT count(*) FROM source_imports WHERE recording_id=:id"), {"id": rid}).scalar_one(), 1)
                self.assertEqual(c.execute(text("SELECT count(*) FROM source_survey_rows WHERE dog_id=:id"),
                                           {"id": dog_id}).scalar_one(), 2)
                self.assertEqual(c.execute(text("SELECT count(*) FROM source_survey_rows WHERE recording_id=:id"),
                                           {"id": rid}).scalar_one(), 1)
            status, _, body = request("GET", f"/v1/recordings/{rid}")
            self.assertEqual(status, 200)
            restored = json.loads(body)
            self.assertIn(rid, json.dumps(restored))
            self.assertIn(str(dog_id), json.dumps(restored))
            self.assertIn("plannedActivities", json.dumps(restored))
