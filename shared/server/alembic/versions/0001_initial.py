"""Initial final data model.

Revision ID: 0001
"""
import os
from pathlib import Path

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    path = Path(
        os.getenv(
            "WOONA_SCHEMA_PATH",
            "/app/docs/target-server-plan/models/postgresql.sql",
        )
    )
    sql = path.read_text(encoding="utf-8").replace("BEGIN;", "").replace("COMMIT;", "")
    op.get_bind().exec_driver_sql(sql)


def downgrade() -> None:
    op.execute(
        "DROP TABLE IF EXISTS artifacts, recording_sync, recordings, "
        "dog_profile_versions, dogs, client_devices CASCADE"
    )
