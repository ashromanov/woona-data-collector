"""Stable external identities and audited source imports.

Revision ID: 0003
"""
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("""
        CREATE TABLE dog_external_ids (
            namespace text NOT NULL,
            external_id text NOT NULL CHECK (length(btrim(external_id)) BETWEEN 1 AND 100),
            dog_id uuid NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
            PRIMARY KEY (namespace, external_id)
        );
        CREATE TABLE source_imports (
            source_id text PRIMARY KEY,
            recording_id uuid UNIQUE REFERENCES recordings(id) ON DELETE RESTRICT,
            source_folder_id text NOT NULL,
            source_path text NOT NULL,
            status text NOT NULL CHECK (status IN ('imported','skipped')),
            provenance jsonb NOT NULL,
            issues jsonb NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now(),
            CHECK ((status='imported') = (recording_id IS NOT NULL))
        );
        CREATE TABLE source_survey_rows (
            source_id text PRIMARY KEY,
            kind text NOT NULL CHECK (kind IN ('dog','session')),
            raw_answers jsonb NOT NULL,
            questionnaire jsonb NOT NULL,
            dog_id uuid REFERENCES dogs(id) ON DELETE RESTRICT,
            profile_version_id uuid REFERENCES dog_profile_versions(id) ON DELETE RESTRICT,
            recording_id uuid REFERENCES recordings(id) ON DELETE RESTRICT,
            issues jsonb NOT NULL,
            imported_at timestamptz NOT NULL DEFAULT now()
        );
    """)


def downgrade():
    op.execute("DROP TABLE source_survey_rows, source_imports, dog_external_ids")
