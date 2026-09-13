package com.example.myapplication.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class WoonaDatabaseTest {
    private lateinit var root: File
    private lateinit var database: WoonaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        root = File(context.cacheDir, "woona-db-test-${System.nanoTime()}")
        database = WoonaDatabase(context, root)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun profilesRecordingsAndArtifactsRoundTrip() {
        val questionnaire = DogQuestionnaire(
            numberOrName = "D-17",
            shelterOrPlace = "Shelter 1",
            breedStatus = "unknown",
            size = "medium",
            ageStatus = "unknown",
            ageSource = "unknown",
            sex = "unknown",
            sterilizationStatus = "unknown",
            weightStatus = "unknown",
            bodyConditionStatus = "unable",
            muscleMass = "unable",
            neckCircumferenceStatus = "not_measured",
            coatLength = "unknown",
            undercoat = "unknown",
            shavedAreasStatus = "unknown",
            observedSigns = listOf("cough", "distress"),
            diagnosesStatus = "unknown",
            housing = "unknown",
            walksStatus = "unknown",
            cohabitants = "unknown",
            shelterPermission = "unknown",
            notesStatus = "none",
        )
        val profile = database.saveProfile(questionnaire, now = Instant.parse("2026-07-28T08:00:00Z"))
        assertEquals(questionnaire, database.profile(profile.id)?.questionnaire)

        val session1 = SessionQuestionnaire(
            sessionLabel = "Baseline",
            operatorName = "Operator",
            activityGroup = "stationary",
            activityType = "rest",
            location = "indoors",
            surface = "concrete",
            airTemperatureStatus = "not_measured",
            sensorPosition = "dorsal_neck",
            collarTightness = "snug",
            preMeasurementState = "rest",
            pulseStatus = "measured",
            pulseBpm = 72,
            respirationStatus = "not_measured",
            bodyTemperatureStatus = "not_measured",
            measurementAtUtc = "2026-07-28T09:29:00Z",
        )
        val session2 = session1.copy(sessionLabel = "Replay")
        val first = database.beginRecording(
            profile.id,
            RecordingSource.LIVE,
            session1,
            ZoneId.of("Europe/Moscow"),
            Instant.parse("2026-07-28T09:30:00Z"),
        )
        val second = database.beginRecording(
            profile.id,
            RecordingSource.REPLAY,
            session2,
            ZoneId.of("Europe/Moscow"),
            Instant.parse("2026-07-28T09:31:00Z"),
        )
        assertNotEquals(first.relativeDirectory, second.relativeDirectory)
        assertTrue(first.relativeDirectory.contains("/2026-07-28/"))

        val firstDirectory = database.recordingDirectory(first.relativeDirectory)
        File(firstDirectory, "packets.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(firstDirectory, "diagnostics.log").writeText("ok")
        File(firstDirectory, "video.mp4").writeBytes(byteArrayOf(4, 5, 6))
        val sensorAnchor = SyncClockAnchor(
            event = "ble_capture_ready",
            absoluteUtc = "2026-07-28T09:30:00Z",
            wallClockEpochMillis = 1_775_899_800_000,
            monotonicTimeNs = 10_000_000_000,
            monotonicClock = "android.elapsedRealtimeNanos",
            samplingUncertaintyNs = 1_000,
        )
        val syncFile = database.writeSyncMetadata(
            first.id,
            CaptureSyncMetadata(
                recordingId = first.id,
                profileId = profile.id,
                source = "live",
                timezone = "Europe/Moscow",
                selectedSessionStartUtc = first.startedAtUtc,
                sensor = sensorAnchor,
                firstSensorDeviceTimerMillis = 321,
                video = VideoSyncMetadata(
                    requestedAtUtc = "2026-07-28T09:30:02Z",
                    requestedMonotonicNs = 12_000_000_000,
                    firstFrameMonotonicNs = 12_250_000_000,
                    firstFrameCameraTimestampNs = 77,
                    firstFrameCallbackMonotonicNs = 12_260_000_000,
                    firstVideoSamplePtsUs = 0,
                    offsetFromSensorNs = 2_250_000_000,
                    cameraTimestampSource = "unknown",
                    synchronizationQuality = "callback_estimate",
                ),
            ),
        )
        val syncJson = JSONObject(syncFile.readText())
        assertEquals(2_250_000_000, syncJson.getJSONObject("video").getLong("offsetFromSensorNs"))
        assertEquals("D-17", syncJson.getJSONObject("dogQuestionnaire").getString("numberOrName"))
        val syncRecord = requireNotNull(database.recordingSyncRecord(first.id))
        assertEquals("callback_estimate", syncRecord.sync.cameraClockQuality)
        assertEquals("callback_estimate", syncRecord.sync.overallSyncQuality)
        assertEquals(321L, syncRecord.sync.firstSensorDeviceTimerMillis)
        assertEquals(12_260_000_000L, syncRecord.sync.videoFirstFrameCallbackMonotonicNs)
        assertEquals(0L, syncRecord.sync.videoFirstSamplePtsUs)
        database.markRecording(first.id)
        database.finishRecording(first.id, RecordingStatus.COMPLETED)

        val recent = database.recentRecordings(profile.id)
        val completed = recent.first { it.id == first.id }
        assertEquals(RecordingStatus.COMPLETED, completed.status)
        assertEquals(
            setOf("packets.bin", "diagnostics.log", "video.mp4", "sync.json"),
            completed.artifacts.map { it.relativePath.substringAfterLast('/') }.toSet(),
        )

        val edited = database.saveProfile(
            questionnaire.copy(numberOrName = "Rex"),
            profileId = profile.id,
        )
        assertEquals(profile.id, edited.id)
        assertNotEquals(profile.profileVersionId, edited.profileVersionId)
        assertEquals(profile.profileVersionId, database.recording(first.id)?.profileVersionId)
        assertEquals(profile.id, database.recording(first.id)?.profileId)

        database.interruptUnfinished()
        assertEquals(RecordingStatus.INTERRUPTED, database.recording(second.id)?.status)

        database.writableDatabase.rawQuery("PRAGMA foreign_keys", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun v2DatabaseMigratesWithoutLosingProfileRecordingOrArtifact() {
        database.close()
        root.deleteRecursively()
        root.mkdirs()
        val dbFile = File(root, "woona.sqlite")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE dog_profiles(id TEXT PRIMARY KEY,number_or_name TEXT NOT NULL,questionnaire_json TEXT NOT NULL,created_at_utc TEXT NOT NULL,updated_at_utc TEXT NOT NULL)",
            )
            legacy.execSQL(
                "CREATE TABLE recordings(id TEXT PRIMARY KEY,profile_id TEXT NOT NULL REFERENCES dog_profiles(id),source TEXT NOT NULL,status TEXT NOT NULL,started_at_utc TEXT NOT NULL,ended_at_utc TEXT,timezone TEXT NOT NULL,questionnaire_json TEXT,relative_directory TEXT NOT NULL UNIQUE)",
            )
            legacy.execSQL(
                "CREATE TABLE artifacts(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(id),type TEXT NOT NULL,relative_path TEXT NOT NULL,size_bytes INTEGER NOT NULL,created_at_utc TEXT NOT NULL,UNIQUE(recording_id,relative_path))",
            )
            val legacyDog = """{"numberOrName":"Old Rex","observedSigns":[]}"""
            legacy.execSQL(
                "INSERT INTO dog_profiles VALUES(?,?,?,?,?)",
                arrayOf("dog-1", "Old Rex", legacyDog, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z"),
            )
            legacy.execSQL(
                "INSERT INTO recordings VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    "recording-1",
                    "dog-1",
                    "live",
                    "completed",
                    "2026-07-01T00:00:00Z",
                    "2026-07-01T00:01:00Z",
                    "UTC",
                    null,
                    "recordings/dog-1/2026-07-01/recording-1",
                ),
            )
            val packet = File(root, "recordings/dog-1/2026-07-01/recording-1/packets.bin")
            packet.parentFile?.mkdirs()
            packet.writeBytes(byteArrayOf(1, 2, 3))
            legacy.execSQL(
                "INSERT INTO artifacts VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>("artifact-1", "recording-1", "packet", "recordings/dog-1/2026-07-01/recording-1/packets.bin", 3, "2026-07-01T00:01:00Z"),
            )
            legacy.version = 2
        }

        database = WoonaDatabase(
            ApplicationProvider.getApplicationContext(),
            root,
        )
        assertEquals("Old Rex", database.profile("dog-1")?.numberOrName)
        val migrated = database.recording("recording-1")
        assertEquals(RecordingStatus.COMPLETED, migrated?.status)
        assertEquals("0".repeat(64), migrated?.artifacts?.single()?.sha256)
        assertEquals(1, database.hashPendingArtifacts())
        assertNotEquals("0".repeat(64), database.recording("recording-1")?.artifacts?.single()?.sha256)
        val syncRecord = requireNotNull(database.recordingSyncRecord("recording-1"))
        assertEquals(1, syncRecord.questionnaireSchemaVersion)
        assertEquals("legacy_incomplete", syncRecord.questionnaireValidationState)
        assertEquals("{}", syncRecord.sessionQuestionnaireJson)
        database.readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
    }

    @Test
    fun v4DatabaseRepairsCanonicalProfileHashForServerParity() {
        val questionnaire = completeDogQuestionnaire("Hash repair")
        val saved = database.saveProfile(questionnaire)
        database.close()
        val dbFile = File(root, "woona.sqlite")
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { legacy ->
            legacy.execSQL(
                "UPDATE dog_profile_versions SET content_sha256=?,server_sync_state='permanent_error' WHERE id=?",
                arrayOf("f".repeat(64), saved.profileVersionId),
            )
            legacy.version = 4
        }

        database = WoonaDatabase(ApplicationProvider.getApplicationContext(), root)

        val repaired = requireNotNull(database.profileSyncRecord(saved.profileVersionId))
        assertEquals(canonicalJsonSha256(questionnaire.toJson()), repaired.contentSha256)
        assertEquals(saved.profileVersionId, database.pendingProfileVersionIds().single())
    }

    @Test
    fun canonicalJsonHashMatchesServerFormat() {
        assertEquals(
            "af876e788c47cf273845053cacf80c925cda1119ee33b1dcb40239cf5f64d75d",
            canonicalJsonSha256("""{"b":2,"a":[true,null,"Привет"]}"""),
        )
    }

    @Test
    fun serverMetadataRestore_isIdempotentAndKeepsArtifactsRemoteOnly() {
        val dogJson = completeDogQuestionnaire("Remote Rex").toJson()
        val sessionJson = completeSessionQuestionnaire().toJson()
        val profile = RemoteProfileVersion(
            id = "20000000-0000-0000-0000-000000000001",
            schemaVersion = 1,
            validationState = "complete",
            questionnaireJson = dogJson,
            contentSha256 = canonicalJsonSha256(dogJson),
            clientCreatedAtUtc = "2026-07-30T10:00:00Z",
        )
        val dog = RemoteDog(
            id = "10000000-0000-0000-0000-000000000001",
            numberOrName = "Remote Rex",
            revision = 1,
            profile = profile,
            profileVersions = listOf(
                RemoteProfileVersion(
                    id = "20000000-0000-0000-0000-000000000000",
                    schemaVersion = 1,
                    validationState = "complete",
                    questionnaireJson = completeDogQuestionnaire("Old Remote Rex").toJson(),
                    contentSha256 = canonicalJsonSha256(completeDogQuestionnaire("Old Remote Rex").toJson()),
                    clientCreatedAtUtc = "2026-07-29T10:00:00Z",
                    supersededAtUtc = "2026-07-30T10:00:00Z",
                ),
                profile,
            ),
        )
        val recording = RemoteRecording(
            id = "30000000-0000-0000-0000-000000000001",
            dogId = dog.id,
            profile = profile,
            source = "live",
            captureStatus = "completed",
            startedAtUtc = "2026-07-30T10:01:00Z",
            endedAtUtc = "2026-07-30T10:02:00Z",
            timezone = "UTC",
            sessionLabel = "Remote session",
            questionnaireSchemaVersion = 1,
            questionnaireValidationState = "complete",
            sessionQuestionnaireJson = sessionJson,
            videoRequested = false,
            sensorHardwareId = "sensor",
            appVersion = "test",
            protocolVersion = "1",
            captureErrorCode = null,
            captureErrorMessage = null,
            receiptSha256 = "a".repeat(64),
            verifiedAtUtc = "2026-07-30T10:03:00Z",
            sync = RecordingSyncClock(
                schemaVersion = 2,
                sessionZeroAtUtc = "2026-07-30T10:01:00Z",
                sessionZeroWallClockMs = 1_775_899_260_000,
                sessionZeroMonotonicNs = 10,
                sessionZeroUncertaintyNs = 1,
                firstSensorPacketMonotonicNs = 11,
                lastSensorPacketMonotonicNs = 12,
                videoRequestedMonotonicNs = null,
                mediaRecorderStartedMonotonicNs = null,
                videoFirstFrameMonotonicNs = null,
                videoFirstFrameCameraTimestampNs = null,
                videoOffsetFromSensorNs = null,
                cameraTimestampSource = null,
                cameraClockQuality = "unavailable",
                sensorClockQuality = "first_packet_arrival",
                overallSyncQuality = "arrival_aligned",
                calibrationOffsetNs = 0,
                estimatedDriftPpm = null,
            ),
            artifacts = listOf(
                RemoteArtifact(
                    id = "40000000-0000-0000-0000-000000000001",
                    type = "packet",
                    fileName = "packets.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 3,
                    sha256 = "b".repeat(64),
                    storageStatus = "available",
                ),
            ),
        )

        val first = database.restoreServerMetadata(listOf(dog), listOf(recording))
        val second = database.restoreServerMetadata(listOf(dog), listOf(recording))

        assertEquals(RestoreResult(1, 1, 1, 0), first)
        assertEquals(RestoreResult(0, 0, 0, 0), second)
        assertEquals("Remote Rex", database.profile(dog.id)?.numberOrName)
        database.readableDatabase.rawQuery(
            "SELECT count(*) FROM dog_profile_versions WHERE dog_id=?",
            arrayOf(dog.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        assertEquals("remote_only", database.recording(recording.id)?.artifacts?.single()?.localPresence)
        assertEquals(1, database.remoteArtifacts(recording.id).size)

        database.writableDatabase.execSQL(
            "UPDATE artifacts SET local_presence='local' WHERE id=?",
            arrayOf(recording.artifacts.single().id),
        )
        database.writableDatabase.execSQL(
            "UPDATE server_sync_state SET state='pending' WHERE recording_id=?",
            arrayOf(recording.id),
        )
        assertEquals(RestoreResult(0, 0, 0, 0), database.restoreServerMetadata(listOf(dog), listOf(recording)))
        assertEquals("both", database.recording(recording.id)?.artifacts?.single()?.localPresence)
        assertEquals("synced", database.recording(recording.id)?.serverSyncState)
    }
}

private fun completeDogQuestionnaire(name: String) = DogQuestionnaire(
    numberOrName = name,
    shelterOrPlace = "Shelter",
    breedStatus = "unknown",
    size = "medium",
    ageStatus = "unknown",
    ageSource = "unknown",
    sex = "unknown",
    sterilizationStatus = "unknown",
    weightStatus = "unknown",
    bodyConditionStatus = "unable",
    muscleMass = "unable",
    neckCircumferenceStatus = "not_measured",
    coatLength = "unknown",
    undercoat = "unknown",
    shavedAreasStatus = "unknown",
    observedSigns = listOf("none"),
    diagnosesStatus = "unknown",
    housing = "unknown",
    walksStatus = "unknown",
    cohabitants = "unknown",
    shelterPermission = "unknown",
    notesStatus = "none",
)

private fun completeSessionQuestionnaire() = SessionQuestionnaire(
    sessionLabel = "Remote session",
    operatorName = "Operator",
    activityGroup = "stationary",
    activityType = "rest",
    location = "indoors",
    surface = "concrete",
    airTemperatureStatus = "not_measured",
    sensorPosition = "dorsal_neck",
    collarTightness = "snug",
    preMeasurementState = "rest",
    pulseStatus = "not_measured",
    respirationStatus = "not_measured",
    bodyTemperatureStatus = "not_measured",
    videoRequested = false,
)
