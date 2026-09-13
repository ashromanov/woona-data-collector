from __future__ import annotations

import argparse
import getpass
import hashlib
import uuid

from sqlalchemy import text

from server.app import engine


def main() -> None:
    parser = argparse.ArgumentParser(description="Provision Woona client devices")
    subparsers = parser.add_subparsers(dest="command", required=True)

    provision = subparsers.add_parser("provision")
    provision.add_argument("device_id", type=uuid.UUID)
    provision.add_argument("label")

    revoke = subparsers.add_parser("revoke")
    revoke.add_argument("device_id", type=uuid.UUID)

    args = parser.parse_args()
    with engine.begin() as connection:
        if args.command == "provision":
            token = getpass.getpass("Bearer token: ")
            if not token:
                parser.error("token must not be empty")
            connection.execute(
                text(
                    """
                    INSERT INTO client_devices(id,label,token_hash)
                    VALUES(:id,:label,:hash)
                    ON CONFLICT(id) DO UPDATE SET
                      label=EXCLUDED.label,token_hash=EXCLUDED.token_hash,
                      revoked_at=NULL
                    """
                ),
                {
                    "id": args.device_id,
                    "label": args.label,
                    "hash": hashlib.sha256(token.encode()).hexdigest(),
                },
            )
        else:
            result = connection.execute(
                text("UPDATE client_devices SET revoked_at=now() WHERE id=:id"),
                {"id": args.device_id},
            )
            if result.rowcount != 1:
                parser.error("device not found")


if __name__ == "__main__":
    main()
