import json
import os
import unittest
import uuid

from sqlalchemy import text

from server.app import engine
from server.label_sync import app_tasks
from server.tests.test_api import request, patch_bytes
from server.tests.test_heart import heart_manifest


class CanonicalDataTest(unittest.TestCase):
    def test_label_task_uses_canonical_quality_and_requires_all_capture_files(self):
        recording_id = str(uuid.uuid4())
        manifest, contents = heart_manifest()
        session = manifest["recording"]["sessionQuestionnaire"]
        session["sessionKind"] = "activity"
        del session["heartQuestionnaire"]
        path = "/v1/recordings/" + recording_id
        request("PUT", path, manifest)
        for artifact in manifest["artifacts"]:
            patch_bytes(artifact["id"], 0, contents[artifact["type"]])
            request("POST", f'/v1/artifacts/{artifact["id"]}/complete', {})
        request("POST", path + "/complete", {})
        with engine.begin() as connection:
            connection.execute(text("UPDATE recording_sync SET alignment=CAST(:quality AS jsonb) WHERE recording_id=:id"),
                               {"id": recording_id, "quality": json.dumps({"status": "unverified", "data_quality": {"reason": "packet gaps"}})})
        tasks = {task["data"]["recording_id"]: task for task in app_tasks(os.environ["DATABASE_URL"])}
        metadata = tasks[recording_id]["meta"]
        self.assertEqual(metadata["alignment"]["status"], "unverified")
        self.assertEqual(metadata["data_quality"], {"reason": "packet gaps"})
        self.assertNotIn("import_source_id", metadata)
        with engine.begin() as connection:
            connection.execute(text("UPDATE artifacts SET storage_status='uploading' WHERE recording_id=:id AND artifact_type='raw'"), {"id": recording_id})
        self.assertNotIn(recording_id, {task["data"]["recording_id"] for task in app_tasks(os.environ["DATABASE_URL"])})
