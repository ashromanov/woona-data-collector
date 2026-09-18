import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from server.label_sync import config, historical_tasks, paged


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
            self.assertIn("clickableLinks", config("quality"))
            self.assertIn('value="$video"', config("behavior"))

    def test_community_task_pagination(self):
        with patch("server.label_sync.request_json", side_effect=[
            {"total": 2, "tasks": [{"id": 1}]},
            {"total": 2, "tasks": [{"id": 2}]},
        ]):
            self.assertEqual([row["id"] for row in paged("/api/tasks?project=1")], [1, 2])


if __name__ == "__main__":
    unittest.main()
