"""Create one-time root-only production secrets on a fresh Woona host."""

import os
import secrets
import uuid
from pathlib import Path


def write_new(path: Path, values: dict[str, str]) -> None:
    with path.open("x", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")
    path.chmod(0o600)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    os.umask(0o077)
    write_new(root / ".env", {
        "POSTGRES_DB": "woona",
        "POSTGRES_USER": "woona",
        "POSTGRES_PASSWORD": secrets.token_hex(32),
        "WOONA_DEVICE_ID": str(uuid.uuid4()),
        "WOONA_DEVICE_LABEL": "Production device",
        "WOONA_DEBUG_TOKEN": secrets.token_hex(32),
        "WOONA_ALLOW_LEGACY_MIGRATION": "false",
        "WOONA_API_PORT": "8080",
        "WOONA_STORAGE_ROOT_HOST": "/srv/woona/storage",
        "WOONA_POSTGRES_ROOT": "/srv/woona/postgres",
    })
    write_new(root / ".env.label", {
        "LABEL_DB_PASSWORD": secrets.token_hex(32),
        "LABEL_ADMIN_PASSWORD": secrets.token_urlsafe(32),
        "LABEL_API_TOKEN": secrets.token_hex(20),
        "LABEL_POSTGRES_ROOT": "/srv/woona/label-postgres",
        "LABEL_SNAPSHOT_ROOT": "/srv/woona/drive-2026-09-18",
    })
    print("Created root-only .env and .env.label")
