"""Hashed deletion guards and server-verified recording alignment."""
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("""CREATE TABLE retired_entities (
        kind text NOT NULL CHECK (kind IN ('dog','profile','recording','artifact')),
        id_sha256 text NOT NULL CHECK (id_sha256 ~ '^[0-9a-f]{64}$'),
        PRIMARY KEY(kind,id_sha256))""")
    op.execute("ALTER TABLE recording_sync ADD COLUMN alignment jsonb")


def downgrade():
    op.execute("ALTER TABLE recording_sync DROP COLUMN alignment")
    op.execute("DROP TABLE retired_entities")
