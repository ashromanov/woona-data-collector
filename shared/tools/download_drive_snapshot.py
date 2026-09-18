"""Download the pinned public Drive snapshot without changing source files."""

import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def download(manifest: Path, target: Path, gdown: str) -> None:
    source = json.loads(manifest.read_text(encoding="utf-8"))
    if source["schema_version"] != 1:
        raise ValueError("unsupported snapshot schema")
    target.mkdir(parents=True, exist_ok=True)
    checksums_path = target / "checksums.json"
    checksums = json.loads(checksums_path.read_text()) if checksums_path.exists() else {}
    failures = []
    for index, item in enumerate(source["files"], 1):
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts or not relative.parts:
            raise ValueError(f"unsafe Drive path: {relative}")
        destination = target / "raw" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and destination.stat().st_size == item["size"]:
            actual = digest(destination)
            if checksums.get(item["id"]) == actual:
                continue
        temporary = destination.with_name(destination.name + ".part")
        try:
            subprocess.run([gdown, item["id"], "-O", str(temporary), "-q", "--retries", "5"], check=True)
        except subprocess.CalledProcessError:
            failures.append(item["path"])
            continue
        if temporary.stat().st_size != item["size"]:
            raise ValueError(f"size mismatch: {relative}")
        actual = digest(temporary)
        temporary.replace(destination)
        checksums[item["id"]] = actual
        pending = checksums_path.with_suffix(".tmp")
        pending.write_text(json.dumps(checksums, indent=2, sort_keys=True) + "\n")
        pending.replace(checksums_path)
        print(f"{index}/{len(source['files'])}: {relative}", flush=True)
        time.sleep(1)
    if failures:
        raise RuntimeError(f"{len(failures)} Drive files failed: {failures}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--gdown", default="gdown")
    args = parser.parse_args()
    download(args.manifest, args.target, args.gdown)
