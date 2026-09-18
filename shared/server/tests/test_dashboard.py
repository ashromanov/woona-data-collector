import unittest
from pathlib import Path
from fastapi import HTTPException
from starlette.requests import Request

from server.dashboard import KINDS, available_file, render, require_label_user, summarize
from server.label_sync import LABELS


class DashboardTest(unittest.TestCase):
    def test_missing_file_is_unavailable_not_an_error(self):
        self.assertFalse(available_file(Path("/nonexistent-woona-dashboard-file"), 0))

    def test_record_counts_and_class_progress(self):
        records = [
            {"source_id": "woona:one", "origin": "Woona", "dog": "Тест <пёс>",
             "date": "2026-09-19", "ingest_status": "complete",
             "files": [{"name": "clip.mp4", "relative": "woona/clip.mp4", "size": 100, "available": True},
                       {"name": "sensor.binlog", "relative": "woona/sensor.binlog", "size": 20, "available": True}],
             "profile": {"version_id": "v1", "answers": {}},
             "session_questionnaire": {"answers": {}}},
            {"source_id": "drive:two", "origin": "Drive", "dog": "unknown",
             "date": "2026-09-18", "ingest_status": "verified",
             "files": [{"name": "sensor.binlog", "relative": "drive/raw/sensor.binlog", "size": 20,
                        "available": True}], "profile": None, "session_questionnaire": None},
        ]
        projects = [{"id": n, "title": LABELS[kind][0]} for n, kind in enumerate(KINDS, 1)]
        tasks = {kind: [{"id": n, "data": {"source_id": "woona:one"},
                         "is_labeled": kind in ("source", "activity")}] for n, kind in enumerate(KINDS, 1)}
        tasks["source"].append({"id": 10, "data": {"source_id": "drive:two"}, "is_labeled": False})
        annotations = {
            ("source", 1): {"annotations": [{"result": [{"from_name": "source",
                                                        "value": {"choices": ["Комплект пригоден"]}}]}]},
            ("activity", 2): {"annotations": [{"result": [{"from_name": "segment",
                                                          "value": {"timelinelabels": ["Ходит"]}},
                                                         {"from_name": "segment",
                                                          "value": {"timelinelabels": ["Ходит"]}}]}]},
        }
        data = summarize(records, projects, tasks, annotations,
                         {"drive_files": 1, "drive_unindexed_files": 0,
                          "dogs": 1, "profile_versions": 1, "session_questionnaires": 1})
        self.assertEqual(data["totals"]["groups"], 2)
        self.assertEqual(data["totals"]["video_ble"], 1)
        self.assertEqual(data["totals"]["annotated_any"], 1)
        self.assertEqual(data["totals"]["annotated_all"], 0)
        self.assertEqual(data["categories"]["activity"]["classes"]["Ходит"],
                         {"records": 1, "segments": 2})
        self.assertEqual(data["totals"]["available_files"], 3)
        page = render(data, "<")
        self.assertIn("&lt;", page)
        self.assertNotIn("Тест <пёс>", page)
        self.assertIn('content="60"', page)

    def test_anonymous_user_cannot_read_dashboard(self):
        request = Request({"type": "http", "headers": [], "method": "GET", "path": "/dashboard"})
        with self.assertRaises(HTTPException) as raised:
            require_label_user(request)
        self.assertEqual(raised.exception.status_code, 401)


if __name__ == "__main__":
    unittest.main()
