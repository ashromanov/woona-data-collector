"""Build stable, checked recording metadata from the immutable Drive snapshot."""

import hashlib
import json
import re
from pathlib import Path

DOGS = {
    "патрик": "patrik", "птарик": "patrik", "лозла": "lozla",
    "джена": "jena", "слива": "sliva", "яра": "yara",
}


def build_index(source: dict, checksums: dict[str, str]) -> dict:
    files = source["files"]
    if len(checksums) != len(files):
        raise ValueError("snapshot download is incomplete")
    groups = {}
    for item in files:
        parent = item["path"].rsplit("/", 1)[0]
        if parent.endswith("/edf"):
            parent = parent[:-4]
        groups.setdefault(parent, []).append(item)
    sessions = []
    for parent, members in sorted(groups.items()):
        raw = [item for item in members if item["path"].endswith(".binlog")]
        if not raw:
            continue
        if len(raw) != 1:
            raise ValueError(f"ambiguous recording: {parent}")
        dates = set()
        for item in members:
            dates.update(re.findall(r"2026-\d{2}-\d{2}", item["path"]))
        if len(dates) != 1:
            raise ValueError(f"ambiguous recording date: {parent}: {dates}")
        dog_matches = {normalized for original, normalized in DOGS.items() if original in parent.casefold()}
        if len(dog_matches) > 1:
            raise ValueError(f"ambiguous dog: {parent}")
        session_files = []
        for item in sorted(members, key=lambda value: value["path"]):
            sha256 = checksums.get(item["id"])
            if not sha256 or not re.fullmatch(r"[0-9a-f]{64}", sha256):
                raise ValueError(f"missing checksum: {item['id']}")
            session_files.append({"drive_id": item["id"], "path": item["path"],
                                  "size": item["size"], "sha256": sha256})
        sessions.append({"id": raw[0]["id"], "capture_date": dates.pop(),
                         "dog": next(iter(dog_matches), "unknown"),
                         "source_path": parent, "files": session_files})
    return {"schema_version": 1, "source_folder_id": source["source_folder_id"],
            "snapshot_date": source["snapshot_date"], "sessions": sessions}


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.snapshot / "manifest.json").read_text())
    checksums = json.loads((args.snapshot / "checksums.json").read_text())
    index = build_index(manifest, checksums)
    target = args.snapshot / "recordings-v1.json"
    target.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n")
    print(f"{len(index['sessions'])} normalized recordings; {hashlib.sha256(target.read_bytes()).hexdigest()}")
