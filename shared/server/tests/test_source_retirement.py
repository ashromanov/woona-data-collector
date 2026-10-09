import importlib
import json
import unittest
import uuid

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import text

from server.app import engine


class SourceRetirementTest(unittest.TestCase):
    def test_migration_preserves_capture_evidence_before_dropping_import_tables(self):
        migration = importlib.import_module("server.alembic.versions.0006_unified_recordings")
        schema = "migration_test_" + uuid.uuid4().hex
        with engine.begin() as connection:
            connection.execute(text(f'CREATE SCHEMA "{schema}"'))
            try:
                connection.execute(text(f'SET LOCAL search_path TO "{schema}"'))
                connection.execute(text("CREATE TABLE recording_sync(recording_id text, alignment jsonb)"))
                connection.execute(text("CREATE TABLE source_imports(recording_id text, provenance jsonb)"))
                connection.execute(text("CREATE TABLE source_survey_rows(id text)"))
                connection.execute(text("INSERT INTO recording_sync VALUES('one',CAST(:alignment AS jsonb))"),
                                   {"alignment": json.dumps({"status": "unverified", "data_quality": {"packets": 321}})})
                connection.execute(text("INSERT INTO source_imports VALUES('one',CAST(:evidence AS jsonb))"),
                                   {"evidence": json.dumps({"original_sync": {"sensor": {"monotonicTimeNs": 123}}, "recovery": {"additional_valid_packets": 766}})})
                with Operations.context(MigrationContext.configure(connection)):
                    migration.upgrade()
                self.assertEqual(connection.execute(text("SELECT alignment FROM recording_sync")).scalar_one(),
                                 {"status": "unverified", "data_quality": {"packets": 321},
                                  "original_capture_sync": {"sensor": {"monotonicTimeNs": 123}},
                                  "recovery": {"additional_valid_packets": 766}})
                self.assertIsNone(connection.execute(text("SELECT to_regclass('source_imports')")).scalar_one())
                self.assertIsNone(connection.execute(text("SELECT to_regclass('source_survey_rows')")).scalar_one())
            finally:
                connection.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
