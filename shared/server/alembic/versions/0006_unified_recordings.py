"""Keep capture evidence in recordings and retire one-off import tables."""
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("""UPDATE recording_sync s SET alignment =
        jsonb_strip_nulls(jsonb_build_object(
            'original_capture_sync', i.provenance->'original_sync',
            'recovery', i.provenance->'recovery')) || COALESCE(s.alignment, '{}'::jsonb)
        FROM source_imports i WHERE i.recording_id=s.recording_id""")
    op.execute("DROP TABLE source_survey_rows, source_imports")


def downgrade():
    raise RuntimeError("Restore the pre-migration backup to recover retired import tables")
