import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from server.label_sync import ensure_storage, task, paged, sync


class LabelSyncTest(unittest.TestCase):
    def test_video_links_escape_file_names_and_titles(self):
        item = task("woona:one", "Dog <video>",
                    [{"name": "clip.mp4", "relative": "woona/Джена <видео>/clip.mp4", "size": 3, "sha256": "a" * 64}],
                    source="app", dog="Джена", date="2026-08-14")
        self.assertIn("%D0%94", item["data"]["video"])
        self.assertIn("%3C", item["data"]["video"])
        self.assertIn("&lt;video&gt;", item["data"]["html"])

    def test_community_task_pagination(self):
        with patch("server.label_sync.request_json", side_effect=[
            {"total": 2, "tasks": [{"id": 1}]},
            {"total": 2, "tasks": [{"id": 2}]},
        ]):
            self.assertEqual([row["id"] for row in paged("/api/tasks?project=1")], [1, 2])

    def test_storage_connections_are_idempotent(self):
        with patch("server.label_sync.request_json", side_effect=[
            [{"path": "/label-studio/files/woona"}],
        ]) as api:
            ensure_storage(1)
            api.assert_called_once_with("GET", "/api/storages/localfiles/?project=1")

    def test_parallel_sync_calls_do_not_create_duplicate_projects(self):
        active = 0
        peak = 0

        def run_once():
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            time.sleep(0.02)
            active -= 1
            return {}

        with patch("server.label_sync._sync_unlocked", side_effect=run_once):
            with ThreadPoolExecutor(max_workers=2) as pool:
                self.assertEqual(list(pool.map(lambda _: sync(), range(2))), [{}, {}])
        self.assertEqual(peak, 1)


if __name__ == "__main__":
    unittest.main()
