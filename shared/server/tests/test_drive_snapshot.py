import hashlib
import json

import pytest

from tools.download_drive_snapshot import verify


def test_verify_snapshot_checks_size_and_hash(tmp_path):
    raw = tmp_path / "raw"
    raw.mkdir()
    (raw / "file.bin").write_bytes(b"woona")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"schema_version": 1, "files": [
        {"id": "drive-id", "path": "file.bin", "size": 5},
    ]}))

    assert verify(manifest, tmp_path) == {"drive-id": hashlib.sha256(b"woona").hexdigest()}
    assert json.loads((tmp_path / "checksums.json").read_text())["drive-id"]

    (raw / "file.bin").write_bytes(b"bad")
    with pytest.raises(ValueError, match="wrong-size"):
        verify(manifest, tmp_path)
