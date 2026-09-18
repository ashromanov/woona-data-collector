import json
import tempfile
import unittest
from pathlib import Path

from server.label_sync import config, historical_tasks


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


if __name__ == "__main__":
    unittest.main()
