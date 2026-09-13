-- Target on-device SQLite model.
-- Documentation artifact: the real v2 migration must copy/verify/swap in one
-- transaction as described in implementation-plan.md.

PRAGMA foreign_keys = ON;
PRAGMA user_version = 5;
BEGIN;

CREATE TABLE dogs (
    id TEXT PRIMARY KEY,
    number_or_name TEXT NOT NULL
        CHECK (length(trim(number_or_name)) BETWEEN 1 AND 100),
    server_revision INTEGER NOT NULL DEFAULT 0
        CHECK (server_revision >= 0),
    created_at_utc TEXT NOT NULL,
    updated_at_utc TEXT NOT NULL,
    archived_at_utc TEXT,
    CHECK (updated_at_utc >= created_at_utc)
);

CREATE INDEX dogs_updated_idx ON dogs(updated_at_utc, id);

CREATE TABLE dog_profile_versions (
    id TEXT PRIMARY KEY,
    dog_id TEXT NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    validation_state TEXT NOT NULL
        CHECK (validation_state IN ('complete', 'legacy_incomplete')),
    questionnaire_json TEXT NOT NULL,
    content_sha256 TEXT NOT NULL
        CHECK (
            length(content_sha256) = 64
            AND content_sha256 NOT GLOB '*[^0-9a-f]*'
        ),
    client_created_at_utc TEXT NOT NULL,
    superseded_at_utc TEXT,
    server_sync_state TEXT NOT NULL DEFAULT 'pending'
        CHECK (
            server_sync_state IN (
                'pending',
                'uploading',
                'synced',
                'retryable_error',
                'permanent_error'
            )
        ),
    server_revision INTEGER CHECK (server_revision > 0),
    sync_attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (sync_attempt_count >= 0),
    next_retry_at_utc TEXT,
    last_error_code TEXT,
    last_error_message TEXT,
    server_synced_at_utc TEXT,
    UNIQUE(dog_id, content_sha256),
    CHECK (
        (server_sync_state = 'synced'
            AND server_revision IS NOT NULL
            AND server_synced_at_utc IS NOT NULL)
        OR server_sync_state <> 'synced'
    )
);

CREATE UNIQUE INDEX dog_profile_one_current_idx
    ON dog_profile_versions(dog_id)
    WHERE superseded_at_utc IS NULL;
CREATE INDEX dog_profile_sync_idx
    ON dog_profile_versions(server_sync_state, client_created_at_utc)
    WHERE server_sync_state <> 'synced';

CREATE TABLE recordings (
    id TEXT PRIMARY KEY,
    dog_id TEXT NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
    dog_profile_version_id TEXT NOT NULL
        REFERENCES dog_profile_versions(id) ON DELETE RESTRICT,
    source TEXT NOT NULL CHECK (source IN ('live', 'replay')),
    capture_status TEXT NOT NULL
        CHECK (
            capture_status IN (
                'preparing',
                'starting',
                'recording',
                'finalizing',
                'completed',
                'failed',
                'interrupted'
            )
        ),
    session_label TEXT NOT NULL
        CHECK (length(trim(session_label)) BETWEEN 1 AND 100),
    questionnaire_schema_version INTEGER NOT NULL
        CHECK (questionnaire_schema_version > 0),
    questionnaire_validation_state TEXT NOT NULL
        CHECK (
            questionnaire_validation_state IN (
                'complete',
                'legacy_incomplete'
            )
        ),
    session_questionnaire_json TEXT NOT NULL,
    video_requested INTEGER NOT NULL CHECK (video_requested IN (0, 1)),
    started_at_utc TEXT,
    ended_at_utc TEXT,
    timezone TEXT NOT NULL CHECK (length(trim(timezone)) BETWEEN 1 AND 100),
    sensor_hardware_id TEXT,
    app_version TEXT NOT NULL,
    protocol_version TEXT,
    capture_error_code TEXT,
    capture_error_message TEXT,
    relative_directory TEXT NOT NULL UNIQUE
        CHECK (
            relative_directory NOT LIKE '/%'
            AND relative_directory NOT LIKE '%/../%'
            AND relative_directory NOT LIKE '../%'
        ),
    client_created_at_utc TEXT NOT NULL,
    CHECK (
        ended_at_utc IS NULL
        OR started_at_utc IS NULL
        OR ended_at_utc >= started_at_utc
    )
);

CREATE INDEX recordings_dog_started_idx
    ON recordings(dog_id, started_at_utc DESC, id);
CREATE INDEX recordings_status_idx
    ON recordings(capture_status, client_created_at_utc);

CREATE TABLE recording_sync (
    recording_id TEXT PRIMARY KEY
        REFERENCES recordings(id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    monotonic_clock TEXT NOT NULL
        CHECK (monotonic_clock = 'android.elapsedRealtimeNanos'),
    session_zero_at_utc TEXT NOT NULL,
    session_zero_wall_clock_ms INTEGER NOT NULL,
    session_zero_monotonic_ns INTEGER NOT NULL
        CHECK (session_zero_monotonic_ns >= 0),
    session_zero_uncertainty_ns INTEGER NOT NULL
        CHECK (session_zero_uncertainty_ns >= 0),
    first_sensor_packet_monotonic_ns INTEGER,
    first_sensor_device_timer_ms INTEGER,
    last_sensor_packet_monotonic_ns INTEGER,
    video_requested_monotonic_ns INTEGER,
    media_recorder_started_monotonic_ns INTEGER,
    video_first_frame_monotonic_ns INTEGER,
    video_first_frame_camera_timestamp_ns INTEGER,
    video_first_frame_callback_monotonic_ns INTEGER,
    video_first_sample_pts_us INTEGER,
    video_offset_from_sensor_ns INTEGER,
    camera_timestamp_source TEXT
        CHECK (
            camera_timestamp_source IS NULL
            OR camera_timestamp_source IN ('realtime', 'unknown')
        ),
    camera_clock_quality TEXT,
    sensor_clock_quality TEXT NOT NULL,
    overall_sync_quality TEXT NOT NULL,
    calibration_offset_ns INTEGER NOT NULL DEFAULT 0,
    estimated_drift_ppm REAL,
    updated_at_utc TEXT NOT NULL
);

CREATE TABLE artifacts (
    id TEXT PRIMARY KEY,
    recording_id TEXT NOT NULL
        REFERENCES recordings(id) ON DELETE CASCADE,
    artifact_type TEXT NOT NULL
        CHECK (
            artifact_type IN (
                'packet',
                'packet_timeline',
                'raw',
                'diagnostic',
                'csv',
                'video',
                'sync',
                'imported_source'
            )
        ),
    file_name TEXT NOT NULL
        CHECK (
            length(trim(file_name)) BETWEEN 1 AND 255
            AND instr(file_name, '/') = 0
            AND instr(file_name, char(92)) = 0
        ),
    mime_type TEXT NOT NULL,
    relative_path TEXT,
    expected_size_bytes INTEGER NOT NULL CHECK (expected_size_bytes >= 0),
    sha256 TEXT
        CHECK (
            sha256 IS NULL
            OR (
                length(sha256) = 64
                AND sha256 NOT GLOB '*[^0-9a-f]*'
            )
        ),
    hash_state TEXT NOT NULL DEFAULT 'pending'
        CHECK (hash_state IN ('pending', 'verified', 'failed')),
    local_presence TEXT NOT NULL DEFAULT 'local'
        CHECK (
            local_presence IN ('local', 'remote_only', 'both', 'missing')
        ),
    upload_state TEXT NOT NULL DEFAULT 'pending'
        CHECK (
            upload_state IN (
                'pending',
                'uploading',
                'available',
                'retryable_error',
                'permanent_error'
            )
        ),
    uploaded_bytes INTEGER NOT NULL DEFAULT 0 CHECK (uploaded_bytes >= 0),
    server_relative_path TEXT,
    server_verified_at_utc TEXT,
    last_error_code TEXT,
    last_error_message TEXT,
    client_created_at_utc TEXT NOT NULL,
    UNIQUE(id, recording_id),
    UNIQUE(recording_id, file_name),
    CHECK (uploaded_bytes <= expected_size_bytes),
    CHECK (
        relative_path IS NULL
        OR (
            relative_path NOT LIKE '/%'
            AND relative_path NOT LIKE '%/../%'
            AND relative_path NOT LIKE '../%'
        )
    ),
    CHECK (
        local_presence = 'remote_only'
        OR relative_path IS NOT NULL
        OR local_presence = 'missing'
    ),
    CHECK (
        (upload_state = 'available'
            AND sha256 IS NOT NULL
            AND uploaded_bytes = expected_size_bytes
            AND server_relative_path IS NOT NULL
            AND server_verified_at_utc IS NOT NULL)
        OR upload_state <> 'available'
    )
);

CREATE INDEX artifacts_recording_idx
    ON artifacts(recording_id, artifact_type, id);
CREATE INDEX artifacts_upload_idx
    ON artifacts(upload_state, recording_id)
    WHERE upload_state <> 'available';

CREATE TABLE server_sync_state (
    recording_id TEXT PRIMARY KEY
        REFERENCES recordings(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'pending'
        CHECK (
            state IN (
                'pending',
                'uploading',
                'synced',
                'retryable_error',
                'permanent_error'
            )
        ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_retry_at_utc TEXT,
    current_artifact_id TEXT,
    last_error_code TEXT,
    last_error_message TEXT,
    manifest_sha256 TEXT
        CHECK (
            manifest_sha256 IS NULL
            OR (
                length(manifest_sha256) = 64
                AND manifest_sha256 NOT GLOB '*[^0-9a-f]*'
            )
        ),
    server_receipt_sha256 TEXT
        CHECK (
            server_receipt_sha256 IS NULL
            OR (
                length(server_receipt_sha256) = 64
                AND server_receipt_sha256 NOT GLOB '*[^0-9a-f]*'
            )
        ),
    server_receipt_at_utc TEXT,
    updated_at_utc TEXT NOT NULL,
    FOREIGN KEY(current_artifact_id, recording_id)
        REFERENCES artifacts(id, recording_id),
    CHECK (
        (state = 'synced'
            AND server_receipt_sha256 IS NOT NULL
            AND server_receipt_at_utc IS NOT NULL)
        OR state <> 'synced'
    )
);

CREATE INDEX server_sync_pending_idx
    ON server_sync_state(state, next_retry_at_utc)
    WHERE state <> 'synced';

COMMIT;
