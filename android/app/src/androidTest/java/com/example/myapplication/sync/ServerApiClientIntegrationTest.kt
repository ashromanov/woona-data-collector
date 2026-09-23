package com.example.myapplication.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.ArtifactSyncRecord
import com.example.myapplication.data.DogQuestionnaire
import com.example.myapplication.data.ProfileSyncRecord
import com.example.myapplication.data.RecordingSyncClock
import com.example.myapplication.data.RecordingSyncRecord
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.canonicalJsonSha256
import com.example.myapplication.data.toJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerApiClientIntegrationTest {
    @Test
    fun embeddedServerDefaults_configureFreshInstall() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServerSettingsStore(context)
        store.clearToken()

        val settings = store.get()

        assertEquals(BuildConfig.WOONA_SERVER_BASE_URL, settings.baseUrl)
        assertEquals(BuildConfig.WOONA_SERVER_TOKEN, settings.token)
        assertEquals(BuildConfig.WOONA_SERVER_TOKEN.isNotBlank(), settings.isConfigured)
    }

    @Test
    fun uploadRestoreAndRangeDownload_roundTripAgainstDockerApi() {
        val baseUrl = InstrumentationRegistry.getArguments()
            .getString("serverBaseUrl")
            ?: "http://10.0.2.2:8080"
        val token = InstrumentationRegistry.getArguments()
            .getString("serverToken")
            ?: "change-me-local-token"
        val client = ServerApiClient(
            ServerSettings(
                baseUrl = baseUrl,
                token = token,
                deviceId = "unused",
                wifiOnly = false,
            ),
            chunkBytes = 1024 * 1024,
        )
        assumeTrue("Docker API is not reachable at $baseUrl", runCatching(client::readiness).getOrDefault(false))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "server-e2e").apply { mkdirs() }
        val dogId = UUID.randomUUID().toString()
        val profileId = UUID.randomUUID().toString()
        val recordingId = UUID.randomUUID().toString()
        val animalId = "android-e2e-$dogId"
        val dogJson = completeDog().copy(schemaVersion = 2, animalId = animalId).toJson()
        val profile = ProfileSyncRecord(
            dogId = dogId,
            dogName = "Android E2E",
            expectedRevision = 0,
            profileVersionId = profileId,
            schemaVersion = 2,
            validationState = "complete",
            questionnaireJson = dogJson,
            contentSha256 = canonicalJsonSha256(dogJson),
            clientCreatedAtUtc = "2026-07-30T20:00:00Z",
        )
        val contents = linkedMapOf(
            "packet" to "packet".toByteArray(),
            "packet_timeline" to "timeline".toByteArray(),
            "raw" to "raw-data".toByteArray(),
            "diagnostic" to "diagnostic".toByteArray(),
            "sync" to """{"schemaVersion":2}""".toByteArray(),
        )
        val artifacts = contents.entries.map { (type, bytes) ->
            val extension = when (type) {
                "packet", "packet_timeline" -> "bin"
                "raw" -> "binlog"
                "diagnostic" -> "log"
                else -> "json"
            }
            val file = File(directory, "$type.$extension").apply { writeBytes(bytes) }
            ArtifactSyncRecord(
                id = UUID.randomUUID().toString(),
                type = type,
                fileName = file.name,
                mimeType = when (type) {
                    "diagnostic" -> "text/plain"
                    "sync" -> "application/json"
                    else -> "application/octet-stream"
                },
                relativePath = file.absolutePath,
                sizeBytes = file.length(),
                sha256 = sha256(bytes),
                uploadedBytes = 0,
                uploadState = "pending",
                clientCreatedAtUtc = "2026-07-30T20:00:00Z",
            )
        }
        val recording = RecordingSyncRecord(
            id = recordingId,
            dogId = dogId,
            profileVersionId = profileId,
            source = "live",
            captureStatus = "completed",
            sessionLabel = "Android server E2E",
            questionnaireSchemaVersion = 2,
            questionnaireValidationState = "complete",
            sessionQuestionnaireJson = completeSession().copy(
                schemaVersion = 2,
                animalId = animalId,
                plannedActivities = listOf("Аллюр/движение"),
            ).toJson(),
            videoRequested = false,
            startedAtUtc = "2026-07-30T20:00:00Z",
            endedAtUtc = "2026-07-30T20:01:00Z",
            timezone = "UTC",
            sensorHardwareId = "fake",
            appVersion = "instrumentation",
            protocolVersion = "1",
            captureErrorCode = null,
            captureErrorMessage = null,
            profile = profile,
            sync = RecordingSyncClock(
                schemaVersion = 2,
                sessionZeroAtUtc = "2026-07-30T20:00:00Z",
                sessionZeroWallClockMs = 1_785_441_600_000,
                sessionZeroMonotonicNs = 100,
                sessionZeroUncertaintyNs = 1,
                firstSensorPacketMonotonicNs = 110,
                lastSensorPacketMonotonicNs = 120,
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
            artifacts = artifacts,
        )

        client.uploadProfile(profile)
        client.uploadRecording(
            record = recording,
            fileForArtifact = { id -> File(requireNotNull(artifacts.find { it.id == id }).relativePath) },
            onProgress = { _, _, _ -> },
        )
        val snapshot = client.fetchRestoreSnapshot()
        assertTrue(snapshot.dogs.any { it.id == dogId })
        assertTrue(snapshot.recordings.any { it.id == recordingId })

        val raw = requireNotNull(artifacts.find { it.type == "raw" })
        val rawBytes = contents.getValue("raw")
        val target = File(directory, "downloaded-${raw.fileName}")
        File(directory, "${target.name}.download.part").writeBytes(rawBytes.copyOfRange(0, 3))
        client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, target)
        assertArrayEquals(rawBytes, target.readBytes())
    }
}

private fun completeDog() = DogQuestionnaire(
    numberOrName = "Android E2E",
    shelterOrPlace = "Test",
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

private fun completeSession() = SessionQuestionnaire(
    sessionLabel = "Android server E2E",
    operatorName = "Instrumentation",
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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
