"""Allow iOS synchronization clocks.

Revision ID: 0002
"""

from alembic import op


revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE recording_sync
          DROP CONSTRAINT IF EXISTS recording_sync_monotonic_clock_check,
          ADD CONSTRAINT recording_sync_monotonic_clock_check CHECK (
            monotonic_clock IN (
              'android.elapsedRealtimeNanos', 'ios.CMClock.hostTime'
            )
          ),
          DROP CONSTRAINT IF EXISTS recording_sync_camera_timestamp_source_check,
          ADD CONSTRAINT recording_sync_camera_timestamp_source_check CHECK (
            camera_timestamp_source IS NULL OR camera_timestamp_source IN (
              'realtime', 'avfoundation_session_clock', 'unknown'
            )
          )
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE recording_sync
          DROP CONSTRAINT IF EXISTS recording_sync_monotonic_clock_check,
          ADD CONSTRAINT recording_sync_monotonic_clock_check CHECK (
            monotonic_clock = 'android.elapsedRealtimeNanos'
          ),
          DROP CONSTRAINT IF EXISTS recording_sync_camera_timestamp_source_check,
          ADD CONSTRAINT recording_sync_camera_timestamp_source_check CHECK (
            camera_timestamp_source IS NULL OR camera_timestamp_source IN (
              'realtime', 'unknown'
            )
          )
        """
    )
