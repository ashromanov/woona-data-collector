import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from server.label_sync import LABELS, config, ensure_storage, historical_tasks, paged


class LabelSyncTest(unittest.TestCase):
    def test_historical_video_task_and_safe_links(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory)
            (snapshot / "recordings-v1.json").write_text(json.dumps({
                "schema_version": 1,
                "sessions": [{"id": "source-id", "capture_date": "2026-08-14",
                              "dog": "jena", "source_path": "Джена <видео>",
                              "files": [{"path": "Джена <видео>/clip.mp4", "size": 3,
                                         "sha256": "a" * 64}]},
                            ],
            }))
            item = historical_tasks(snapshot)[0]
            self.assertEqual(item["data"]["source_id"], "drive:source-id")
            self.assertIn("%D0%94", item["data"]["video"])
            self.assertIn("&lt;видео&gt;", item["data"]["html"])
            self.assertIn("clickableLinks", config("source"))
            for kind, count in (("activity", 5), ("gait", 4), ("lameness", 5)):
                self.assertEqual(len(LABELS[kind][1]), count)
                self.assertIn('value="$video"', config(kind))
                self.assertIn('<TimelineLabels name="segment" toName="video">', config(kind))
                self.assertIn('frameRate="30"', config(kind))
                self.assertIn('perRegion="true"', config(kind))
            self.assertIn('value="Прыгает"', config("activity"))
            self.assertIn('name="clinical_lameness"', config("lameness"))

    def test_community_task_pagination(self):
        with patch("server.label_sync.request_json", side_effect=[
            {"total": 2, "tasks": [{"id": 1}]},
            {"total": 2, "tasks": [{"id": 2}]},
        ]):
            self.assertEqual([row["id"] for row in paged("/api/tasks?project=1")], [1, 2])

    def test_storage_connections_are_idempotent(self):
        with patch("server.label_sync.request_json", side_effect=[
            [{"path": "/label-studio/files/woona"}], {"id": 2},
        ]) as api:
            ensure_storage(1)
            self.assertEqual("/label-studio/files/drive", api.call_args.args[2]["path"])


if __name__ == "__main__":
    unittest.main()
