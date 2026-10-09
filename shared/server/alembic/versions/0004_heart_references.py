"""Add reference artifacts without changing historical recordings or profiles."""
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("ALTER TABLE artifacts DROP CONSTRAINT artifacts_artifact_type_check")
    op.execute("""ALTER TABLE artifacts ADD CONSTRAINT artifacts_artifact_type_check
        CHECK (artifact_type IN ('packet','packet_timeline','raw','diagnostic','csv',
               'video','sync','imported_source','ecg','heart_rate','rr'))""")
    op.execute("ALTER TABLE artifacts ADD COLUMN reference_metadata jsonb")
    op.execute("ALTER TABLE artifacts ADD COLUMN uploaded_by_device_id uuid REFERENCES client_devices(id)")


def downgrade():
    # Refuse to drop collected references silently.
    op.execute("ALTER TABLE artifacts DROP CONSTRAINT artifacts_artifact_type_check")
    op.execute("""ALTER TABLE artifacts ADD CONSTRAINT artifacts_artifact_type_check
        CHECK (artifact_type IN ('packet','packet_timeline','raw','diagnostic','csv',
               'video','sync','imported_source'))""")
    op.execute("ALTER TABLE artifacts DROP COLUMN uploaded_by_device_id")
    op.execute("ALTER TABLE artifacts DROP COLUMN reference_metadata")
