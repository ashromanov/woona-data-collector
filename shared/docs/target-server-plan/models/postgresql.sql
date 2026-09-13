-- Target PostgreSQL 18 model.
-- Documentation artifact: migrations must be split into ordered Alembic steps.

BEGIN;

CREATE TABLE client_devices (
    id uuid PRIMARY KEY,
    label text NOT NULL CHECK (length(btrim(label)) BETWEEN 1 AND 150),
    token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    revoked_at timestamptz,
    app_version text,
    notes text
);

CREATE TABLE dogs (
    id uuid PRIMARY KEY,
    number_or_name text NOT NULL
        CHECK (length(btrim(number_or_name)) BETWEEN 1 AND 100),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_by_device_id uuid NOT NULL
        REFERENCES client_devices(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    CHECK (updated_at >= created_at)
);

CREATE INDEX dogs_updated_idx ON dogs (updated_at, id);
CREATE INDEX dogs_active_name_idx
    ON dogs (lower(number_or_name))
    WHERE archived_at IS NULL;

CREATE TABLE dog_profile_versions (
    id uuid PRIMARY KEY,
    dog_id uuid NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
    schema_version smallint NOT NULL CHECK (schema_version > 0),
    validation_state text NOT NULL
        CHECK (validation_state IN ('complete', 'legacy_incomplete')),
    questionnaire jsonb NOT NULL
        CHECK (jsonb_typeof(questionnaire) = 'object'),
    content_sha256 text NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_by_device_id uuid NOT NULL
        REFERENCES client_devices(id) ON DELETE RESTRICT,
    client_created_at timestamptz NOT NULL,
    server_created_at timestamptz NOT NULL DEFAULT now(),
    superseded_at timestamptz,
    UNIQUE (id, dog_id),
    UNIQUE (dog_id, content_sha256),
    CHECK (superseded_at IS NULL OR superseded_at >= server_created_at)
);

CREATE UNIQUE INDEX dog_profile_one_current_idx
    ON dog_profile_versions (dog_id)
    WHERE superseded_at IS NULL;
CREATE INDEX dog_profile_history_idx
    ON dog_profile_versions (dog_id, server_created_at DESC);
CREATE INDEX dog_profile_questionnaire_gin_idx
    ON dog_profile_versions USING gin (questionnaire);

CREATE TABLE recordings (
    id uuid PRIMARY KEY,
    dog_id uuid NOT NULL REFERENCES dogs(id) ON DELETE RESTRICT,
    dog_profile_version_id uuid NOT NULL,
    capture_device_id uuid NOT NULL
        REFERENCES client_devices(id) ON DELETE RESTRICT,
    source text NOT NULL CHECK (source IN ('live', 'replay')),
    capture_status text NOT NULL CHECK (
        capture_status IN (
            'preparing',
            'recording',
            'completed',
            'failed',
            'interrupted'
        )
    ),
    ingest_status text NOT NULL DEFAULT 'metadata_pending' CHECK (
        ingest_status IN (
            'metadata_pending',
            'uploading',
            'complete',
            'corrupt',
            'rejected'
        )
    ),
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    timezone text NOT NULL CHECK (length(btrim(timezone)) BETWEEN 1 AND 100),
    session_label text NOT NULL
        CHECK (length(btrim(session_label)) BETWEEN 1 AND 100),
    questionnaire_schema_version smallint NOT NULL
        CHECK (questionnaire_schema_version > 0),
    questionnaire_validation_state text NOT NULL CHECK (
        questionnaire_validation_state IN ('complete', 'legacy_incomplete')
    ),
    session_questionnaire jsonb NOT NULL
        CHECK (jsonb_typeof(session_questionnaire) = 'object'),
    video_requested boolean NOT NULL,
    sensor_hardware_id text,
    app_version text NOT NULL CHECK (length(btrim(app_version)) BETWEEN 1 AND 100),
    protocol_version text,
    capture_error_code text,
    capture_error_message text,
    client_created_at timestamptz NOT NULL,
    server_created_at timestamptz NOT NULL DEFAULT now(),
    server_updated_at timestamptz NOT NULL DEFAULT now(),
    server_verified_at timestamptz,
    receipt_sha256 text CHECK (
        receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'
    ),
    FOREIGN KEY (dog_profile_version_id, dog_id)
        REFERENCES dog_profile_versions(id, dog_id) ON DELETE RESTRICT,
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CHECK (server_updated_at >= server_created_at),
    CHECK (
        (ingest_status = 'complete'
            AND server_verified_at IS NOT NULL
            AND receipt_sha256 IS NOT NULL)
        OR ingest_status <> 'complete'
    )
);

CREATE INDEX recordings_dog_started_idx
    ON recordings (dog_id, started_at DESC, id);
CREATE INDEX recordings_device_created_idx
    ON recordings (capture_device_id, client_created_at DESC);
CREATE INDEX recordings_ingest_pending_idx
    ON recordings (ingest_status, server_updated_at)
    WHERE ingest_status <> 'complete';
CREATE INDEX recordings_questionnaire_gin_idx
    ON recordings USING gin (session_questionnaire);

CREATE TABLE recording_sync (
    recording_id uuid PRIMARY KEY
        REFERENCES recordings(id) ON DELETE CASCADE,
    schema_version smallint NOT NULL CHECK (schema_version > 0),
    monotonic_clock text NOT NULL CHECK (
        monotonic_clock IN ('android.elapsedRealtimeNanos', 'ios.CMClock.hostTime')
    ),
    session_zero_at_utc timestamptz NOT NULL,
    session_zero_wall_clock_ms bigint NOT NULL,
    session_zero_monotonic_ns bigint NOT NULL
        CHECK (session_zero_monotonic_ns >= 0),
    session_zero_uncertainty_ns bigint NOT NULL
        CHECK (session_zero_uncertainty_ns >= 0),
    first_sensor_packet_monotonic_ns bigint
        CHECK (first_sensor_packet_monotonic_ns >= 0),
    first_sensor_device_timer_ms bigint
        CHECK (first_sensor_device_timer_ms >= 0),
    last_sensor_packet_monotonic_ns bigint
        CHECK (last_sensor_packet_monotonic_ns >= 0),
    video_requested_monotonic_ns bigint
        CHECK (video_requested_monotonic_ns >= 0),
    media_recorder_started_monotonic_ns bigint
        CHECK (media_recorder_started_monotonic_ns >= 0),
    video_first_frame_monotonic_ns bigint
        CHECK (video_first_frame_monotonic_ns >= 0),
    video_first_frame_camera_timestamp_ns bigint
        CHECK (video_first_frame_camera_timestamp_ns >= 0),
    video_first_frame_callback_monotonic_ns bigint
        CHECK (video_first_frame_callback_monotonic_ns >= 0),
    video_first_sample_pts_us bigint
        CHECK (video_first_sample_pts_us >= 0),
    video_offset_from_sensor_ns bigint,
    camera_timestamp_source text CHECK (
        camera_timestamp_source IS NULL
        OR camera_timestamp_source IN (
            'realtime', 'avfoundation_session_clock', 'unknown'
        )
    ),
    camera_clock_quality text CHECK (
        camera_clock_quality IS NULL
        OR camera_clock_quality IN (
            'hardware_monotonic',
            'callback_estimate',
            'unavailable'
        )
    ),
    sensor_clock_quality text NOT NULL CHECK (
        sensor_clock_quality IN (
            'first_packet_arrival',
            'device_timer_mapped',
            'calibrated',
            'unavailable'
        )
    ),
    overall_sync_quality text NOT NULL CHECK (
        overall_sync_quality IN (
            'arrival_aligned',
            'callback_estimate',
            'degraded',
            'unavailable'
        )
    ),
    calibration_offset_ns bigint NOT NULL DEFAULT 0,
    estimated_drift_ppm double precision,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        last_sensor_packet_monotonic_ns IS NULL
        OR first_sensor_packet_monotonic_ns IS NULL
        OR last_sensor_packet_monotonic_ns >= first_sensor_packet_monotonic_ns
    ),
    CHECK (updated_at >= created_at)
);

CREATE TABLE artifacts (
    id uuid PRIMARY KEY,
    recording_id uuid NOT NULL
        REFERENCES recordings(id) ON DELETE CASCADE,
    artifact_type text NOT NULL CHECK (
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
    file_name text NOT NULL CHECK (
        length(btrim(file_name)) BETWEEN 1 AND 255
        AND file_name !~ '[/\\]'
    ),
    mime_type text NOT NULL CHECK (length(btrim(mime_type)) BETWEEN 1 AND 150),
    expected_size_bytes bigint NOT NULL CHECK (expected_size_bytes >= 0),
    stored_size_bytes bigint NOT NULL DEFAULT 0 CHECK (stored_size_bytes >= 0),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_status text NOT NULL DEFAULT 'pending' CHECK (
        storage_status IN (
            'pending',
            'uploading',
            'available',
            'corrupt',
            'rejected',
            'missing'
        )
    ),
    server_relative_path text,
    client_created_at timestamptz NOT NULL,
    server_created_at timestamptz NOT NULL DEFAULT now(),
    upload_updated_at timestamptz NOT NULL DEFAULT now(),
    verified_at timestamptz,
    error_code text,
    error_message text,
    UNIQUE (recording_id, file_name),
    CHECK (stored_size_bytes <= expected_size_bytes),
    CHECK (
        server_relative_path IS NULL
        OR (
            server_relative_path !~ '^/'
            AND server_relative_path !~ '(^|/)\.\.(/|$)'
            AND length(server_relative_path) <= 1000
        )
    ),
    CHECK (
        (storage_status = 'available'
            AND stored_size_bytes = expected_size_bytes
            AND server_relative_path IS NOT NULL
            AND verified_at IS NOT NULL)
        OR storage_status <> 'available'
    )
);

CREATE INDEX artifacts_recording_idx
    ON artifacts (recording_id, artifact_type, id);
CREATE INDEX artifacts_incomplete_idx
    ON artifacts (storage_status, upload_updated_at)
    WHERE storage_status <> 'available';
CREATE UNIQUE INDEX artifacts_server_path_idx
    ON artifacts (server_relative_path)
    WHERE server_relative_path IS NOT NULL;

COMMIT;
