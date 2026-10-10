"""Operator accounts and revisioned session questionnaire edits."""
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("""CREATE TABLE accounts (
        identifier text PRIMARY KEY CHECK (identifier ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
        created_at timestamptz NOT NULL DEFAULT now()
    )""")
    op.execute("ALTER TABLE dogs ADD COLUMN account_id text REFERENCES accounts(identifier)")
    op.execute("CREATE INDEX dogs_account_id_idx ON dogs(account_id)")
    op.execute("ALTER TABLE recordings ADD COLUMN questionnaire_revision integer NOT NULL DEFAULT 1 CHECK (questionnaire_revision > 0)")


def downgrade():
    op.execute("ALTER TABLE recordings DROP COLUMN questionnaire_revision")
    op.execute("ALTER TABLE dogs DROP COLUMN account_id")
    op.execute("DROP TABLE accounts")
