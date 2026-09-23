"""Download an explicit Drive inventory, preserving duplicate names by file ID.

Inventory is produced by gdown.download_folder(skip_download=True). ECG folders
are excluded from this activity/gait batch. Root archives are retained for audit
but require explicit classification before they can become labeling tasks.
"""

import argparse
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import gdown


def download(inventory: Path, root: Path) -> None:
    rows = json.loads(inventory.read_text())
    selected = {}
    for row in rows:
        path = Path(row["path"])
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("unsafe source path")
        if "экг" in row["path"].casefold():
            continue
        selected[row["id"]] = row
    root.mkdir(parents=True, exist_ok=True)

    def fetch(row):
        target = root / "raw" / row["id"] / Path(row["path"]).name
        target.parent.mkdir(parents=True, exist_ok=True)
        receipt = target.parent / "receipt.json"
        if receipt.exists() and target.exists():
            previous = json.loads(receipt.read_text())
            if previous["size"] == target.stat().st_size and previous["sha256"] == digest(target):
                return previous
        pending = target.with_name(target.name + ".part")
        gdown.download(id=row["id"], output=str(pending), quiet=True, use_cookies=False, resume=True)
        if not pending.is_file() or pending.stat().st_size == 0:
            raise ValueError(f"empty download: {row['path']}")
        result = {**row, "relative": str(target.relative_to(root)),
                  "size": pending.stat().st_size, "sha256": digest(pending)}
        pending.replace(target)
        receipt.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(f"Downloaded {row['path']} ({result['size']} bytes)", flush=True)
        return result

    failures = []

    def attempt(row):
        try:
            return fetch(row)
        except Exception as error:
            failures.append({**row, "error": str(error)})
            print(f"SKIP {row['path']}: {type(error).__name__}", flush=True)
            return None

    with ThreadPoolExecutor(max_workers=3) as pool:
        files = [row for row in pool.map(attempt, selected.values()) if row is not None]
    manifest = {"schema_version": 2, "source_folder_id": "1W2LGVqKdi7ckSonGUn7SHSvy1P54n5jE",
                "files": files, "download_failures": failures}
    (root / "manifest.pending").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    (root / "manifest.pending").replace(root / "manifest.json")
    print(f"Verified {len(files)} files, {sum(f['size'] for f in files)} bytes", flush=True)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("inventory", type=Path)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    download(args.inventory, args.root)
