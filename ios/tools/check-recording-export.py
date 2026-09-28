#!/usr/bin/env python3
"""Independently unpack the synthetic ZIP retained by the iOS export XCTest."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile

archives = [p for p in Path(sys.argv[1]).rglob("*") if p.is_file() and zipfile.is_zipfile(p)]
assert archives, "The XCTest result did not contain its recording export acceptance ZIP"
for archive in archives:
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None, "ZIP CRC verification failed"
        names = {PurePosixPath(n).name: n for n in z.namelist() if not n.endswith("/")}
        manifest = json.loads(z.read(names["manifest.json"]))
        assert manifest["profile"]["numberOrName"] == "Rex", "Historical dog questionnaire was replaced"
        assert manifest["recording"]["serverSyncState"] == "permanent_error"
        assert manifest["recording"]["questionnaire"]["sessionLabel"] == "Morning"
        assert manifest["synchronization"]["schemaVersion"] == 2
        assert manifest["missingFiles"] == []
        assert len(manifest["files"]) == 8
        assert {f["type"] for f in manifest["files"]} == {
            "packet", "packet_timeline", "raw", "diagnostic", "csv", "video", "imported_source", "sync"
        }
        for file in manifest["files"]:
            content = z.read(names[file["name"]])
            assert len(content) == file["size"], file["name"]
            assert hashlib.sha256(content).hexdigest() == file["sha256"], file["name"]
        assert len(names) == 9, "Unexpected or missing ZIP contents"
        print("Verified native iOS ZIP: all 8 artifact types, questionnaires, sync, size/SHA and ZIP CRC")
