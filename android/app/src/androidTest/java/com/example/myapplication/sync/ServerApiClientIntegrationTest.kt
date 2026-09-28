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
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.restoreServerMetadata
import com.example.myapplication.data.remoteArtifacts
import com.example.myapplication.data.artifactDownloadTarget
import com.example.myapplication.data.markArtifactDownloaded
import com.example.myapplication.data.serverSyncCounts
import com.example.myapplication.data.resetFailedSync
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.RecordingStatus
import com.example.myapplication.data.CaptureSyncMetadata
import com.example.myapplication.data.hashPendingArtifacts
import androidx.test.core.app.ActivityScenario
import com.example.myapplication.MainActivity
import androidx.work.WorkManager
import java.io.IOException
import android.util.Log
import java.net.URL
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
    fun realWorkManager_invalidTokenIsVisibleAndManualRetrySucceeds() {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("serverBaseUrl")
        assumeTrue("Local API was not supplied", baseUrl != null)
        val token = arguments.getString("serverToken") ?: "change-me-local-token"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settingsStore = ServerSettingsStore(context)
        val original = settingsStore.get()
        val preferences = context.getSharedPreferences("woona_server", android.content.Context.MODE_PRIVATE)
        val database = WoonaDatabase(context)
        fun configure(value: String, url: String? = baseUrl) {
            settingsStore.save("https://local-worker-test.invalid", value, false)
            preferences.edit().putString("base_url", url).commit()
        }
        val profile = database.saveProfile(completeDog().copy(schemaVersion = 2, species = "собака", animalId = "worker-${UUID.randomUUID()}"))
        fun state(): String = database.readableDatabase.rawQuery("SELECT server_sync_state FROM dog_profile_versions WHERE id=?", arrayOf(profile.profileVersionId)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        fun waitFor(expected: String, seconds: Long = 30) {
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (state() != expected && System.nanoTime() < deadline) Thread.sleep(100)
            assertEquals(expected, state())
        }
        try {
            configure("invalid-test-token")
            ServerSyncScheduler.enqueueProfile(context, profile.profileVersionId)
            waitFor("permanent_error")
            assertTrue(database.serverSyncCounts().failed > 0)
            assertTrue(database.serverSyncCounts().lastErrorMessage.orEmpty().contains("invalid_bearer_token"))
            ActivityScenario.launch(MainActivity::class.java).use {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isVisibleToUser && node.text?.contains(label) == true) return node
                    return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
                }
                fun visible(label: String) = find(automation.rootInActiveWindow, label)
                fun screenshot(name: String) = File(context.cacheDir, name).outputStream().use { output -> automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                val startDeadline = System.nanoTime() + 10_000_000_000L
                while (visible("Settings") == null && visible("Настройки") == null && System.nanoTime() < startDeadline) Thread.sleep(100)
                screenshot("actual-worker-before-settings.png")
                var settingsNode = requireNotNull(visible("Settings") ?: visible("Настройки"))
                while (!settingsNode.isClickable && settingsNode.parent != null) settingsNode = settingsNode.parent
                assertTrue(settingsNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    return (0 until node.childCount).any { scroll(node.getChild(it)) }
                }
                repeat(8) {
                    if (visible("invalid_bearer_token") == null) {
                        scroll(automation.rootInActiveWindow)
                        Thread.sleep(200)
                    }
                }
                val errorNode = requireNotNull(visible("invalid_bearer_token"))
                val retryNode = requireNotNull(visible("Retry uploads") ?: visible("Повторить выгрузку"))
                assertTrue(retryNode.isEnabled)
                File(context.cacheDir, "actual-worker-401.png").outputStream().use { output -> automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                File(context.cacheDir, "actual-worker-401.txt").writeText("${errorNode.text}\nRetry enabled=${retryNode.isEnabled}\n")
                Log.i("WOONA_E2E", "PASS actualMainActivity worker401Visible=true retryEnabled=true screenshot=actual-worker-401.png")
            }
            val work = WorkManager.getInstance(context)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (work.getWorkInfosForUniqueWork("server-profile-${profile.profileVersionId}").get().any { !it.state.isFinished } && System.nanoTime() < deadline) Thread.sleep(100)
            configure(token)
            database.resetFailedSync()
            val faultUrl = arguments.getString("serverFaultBaseUrl")
            if (faultUrl != null) {
                URL("$faultUrl/__fault/503").openStream().use { it.readBytes() }
                configure(token, faultUrl)
            }
            ServerSyncScheduler.enqueueProfile(context, profile.profileVersionId)
            if (faultUrl != null) {
                waitFor("retryable_error")
                assertTrue(database.serverSyncCounts().lastErrorMessage.orEmpty().contains("HTTP 503"))
                Log.i("WOONA_E2E", "PASS WorkManager http503Persisted=retryable_error beforeAutomaticRetry")
            }
            waitFor("synced", 60)
            Log.i("WOONA_E2E", "PASS WorkManager profile401Visible=true manualRetry=synced")
            configure(token)
            val recording = database.beginRecording(profile.id, RecordingSource.LIVE, completeSession().copy(
                schemaVersion = 2,
                animalId = profile.questionnaire.animalId,
                plannedActivities = listOf("Аллюр/движение"),
            ))
            val directory = database.recordingDirectory(recording.relativeDirectory)
            listOf("packets.bin", "packet_timeline.bin", "raw_fragments.binlog", "diagnostics.log", "channel.csv", "polar_hr.csv", "polar_ecg.csv", "polar_acc.csv").forEach { name -> File(directory, name).writeText("mock capture $name") }
            database.writeSyncMetadata(recording.id, CaptureSyncMetadata(
                recordingId = recording.id, profileId = profile.id, source = "live", timezone = recording.timezone, selectedSessionStartUtc = recording.startedAtUtc,
            ))
            database.finishRecording(recording.id, RecordingStatus.COMPLETED)
            database.hashPendingArtifacts()
            ActivityScenario.launch(MainActivity::class.java).use {
                ServerSyncScheduler.enqueueRecording(context, recording.id)
                val finishDeadline = System.nanoTime() + 30_000_000_000L
                while (database.recording(recording.id)?.serverSyncState != "synced" && System.nanoTime() < finishDeadline) Thread.sleep(100)
                assertEquals("synced", database.recording(recording.id)?.serverSyncState)
            }
            val remote = ServerApiClient(ServerSettings(requireNotNull(baseUrl), token, "worker", false)).fetchRestoreSnapshot().recordings.single { it.id == recording.id }
            assertEquals(9, remote.artifacts.size)
            assertEquals(setOf("channel.csv", "polar_hr.csv", "polar_ecg.csv", "polar_acc.csv"), remote.artifacts.filter { it.type == "csv" }.map { it.fileName }.toSet())
            assertTrue(!remote.receiptSha256.isNullOrBlank())
            assertTrue(remote.artifacts.all { it.storageStatus == "available" })
            Log.i("WOONA_E2E", "PASS WorkManager recordingUploadBytes=9Artifacts polarCsvAll=true receiptVerified=true recording=${recording.id}")
        } finally {
            if (original.isConfigured) settingsStore.save(original.baseUrl, original.token, original.wifiOnly) else settingsStore.clearToken()
            preferences.edit().putString("base_url", original.baseUrl).putBoolean("wifi_only", original.wifiOnly).commit()
            database.close()
        }
    }

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
        val directory = File(context.cacheDir, "server-e2e-${System.nanoTime()}").apply { mkdirs() }
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
            "raw" to ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() },
            "diagnostic" to "diagnostic".toByteArray(),
            "csv" to "time,value\n0,12\n".toByteArray(),
            "imported_source" to "replay source".toByteArray(),
            "sync" to """{"schemaVersion":2}""".toByteArray(),
        )
        val artifacts = contents.entries.map { (type, bytes) ->
            val extension = when (type) {
                "packet", "packet_timeline" -> "bin"
                "raw" -> "binlog"
                "diagnostic" -> "log"
                "csv" -> "csv"
                "imported_source" -> "bin"
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
                    "csv" -> "text/csv"
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
        expectFailure(409, "animal_id_already_exists") {
            client.uploadProfile(profile.copy(dogId = UUID.randomUUID().toString(), profileVersionId = UUID.randomUUID().toString()))
        }
        expectFailure(409, "revision_conflict") {
            client.uploadProfile(profile.copy(profileVersionId = UUID.randomUUID().toString(), expectedRevision = 999))
        }
        val invalidClient = ServerApiClient(ServerSettings(baseUrl, "invalid-test-token", "unused", false))
        expectFailure(401, "invalid_bearer_token") { invalidClient.uploadProfile(profile) }
        InstrumentationRegistry.getArguments().getString("serverRevokedToken")?.let { revoked ->
            expectFailure(401, "invalid_bearer_token") { ServerApiClient(ServerSettings(baseUrl, revoked, "unused", false)).uploadProfile(profile) }
        }
        InstrumentationRegistry.getArguments().getString("serverFaultBaseUrl")?.let { faultUrl ->
            val faultClient = ServerApiClient(ServerSettings(faultUrl, token, "unused", false), readTimeoutMillis = 500)
            URL("$faultUrl/__fault/503").openStream().use { it.readBytes() }
            expectFailure(503, "intentional_e2e_fault") { faultClient.uploadProfile(profile) }
            faultClient.uploadProfile(profile)
            URL("$faultUrl/__fault/delay").openStream().use { it.readBytes() }
            assertTrue(runCatching { faultClient.uploadProfile(profile) }.exceptionOrNull() is IOException)
            faultClient.uploadProfile(profile)
            Log.i("WOONA_E2E", "PASS server503Retry=true readTimeoutRetry=true")
        }
        expectFailure(422, "missing_required_artifacts") {
            client.uploadRecording(recording.copy(id = UUID.randomUUID().toString(), artifacts = artifacts.take(1)), { error("unexpected file read") }, { _, _, _ -> })
        }
        val changedSize = recording.copy(id = UUID.randomUUID().toString(), artifacts = artifacts.map { it.copy(id = UUID.randomUUID().toString(), sizeBytes = it.sizeBytes + 1) })
        val sizeFailure = runCatching { client.uploadRecording(changedSize, { id -> File(changedSize.artifacts.first { it.id == id }.relativePath) }, { _, _, _ -> }) }.exceptionOrNull()
        assertTrue(sizeFailure is IllegalArgumentException && sizeFailure.message.orEmpty().contains("missing or changed"))
        val badHash = recording.copy(id = UUID.randomUUID().toString(), artifacts = artifacts.map { it.copy(id = UUID.randomUUID().toString(), sha256 = "0".repeat(64)) })
        expectFailure(422, "hash_mismatch") {
            client.uploadRecording(badHash, { id -> File(badHash.artifacts.first { it.id == id }.relativePath) }, { _, _, _ -> })
        }
        var interruptedOffset = 0L
        val interrupted = runCatching {
            client.uploadRecording(recording, { id -> File(artifacts.first { it.id == id }.relativePath) }) { id, bytes, available ->
                if (id == artifacts.first { it.type == "raw" }.id && bytes > 0 && !available) {
                    interruptedOffset = bytes
                    throw IOException("Intentional E2E interruption after persisted upload chunk")
                }
            }
        }.exceptionOrNull()
        assertTrue(interrupted is IOException)
        assertEquals(1024 * 1024L, interruptedOffset)
        var resumedOffset = -1L
        client.uploadRecording(
            record = recording,
            fileForArtifact = { id -> File(requireNotNull(artifacts.find { it.id == id }).relativePath) },
            onProgress = { id, bytes, _ -> if (id == artifacts.first { it.type == "raw" }.id && resumedOffset < 0) resumedOffset = bytes },
        )
        assertEquals(interruptedOffset, resumedOffset)
        client.uploadRecording(recording, { id -> File(artifacts.first { it.id == id }.relativePath) }, { _, _, _ -> })
        listOf("replay", "camera_failed", "interrupted", "failed").forEach { scenario ->
            val variant = recording.copy(
                id = UUID.randomUUID().toString(),
                source = if (scenario == "replay") "replay" else "live",
                captureStatus = if (scenario in listOf("interrupted", "failed")) scenario else "completed",
                captureErrorCode = if (scenario == "camera_failed") "camera_failed" else null,
                captureErrorMessage = if (scenario == "camera_failed") "Intentional mocked camera failure" else null,
                videoRequested = scenario == "camera_failed",
                artifacts = artifacts.filter { scenario != "replay" || it.type in setOf("packet", "diagnostic", "sync", "imported_source") }.map { it.copy(id = UUID.randomUUID().toString()) },
            )
            client.uploadRecording(variant, { id -> File(variant.artifacts.first { it.id == id }.relativePath) }, { _, _, _ -> })
            val remote = client.fetchRestoreSnapshot().recordings.single { it.id == variant.id }
            assertEquals(variant.captureStatus, remote.captureStatus)
            assertEquals(variant.source, remote.source)
            assertEquals(variant.artifacts.size, remote.artifacts.size)
            Log.i("WOONA_E2E", "PASS recordingScenario=$scenario artifacts=${variant.artifacts.size}")
        }
        InstrumentationRegistry.getArguments().getString("serverSecondaryToken")?.let { secondary ->
            val secondaryClient = ServerApiClient(ServerSettings(baseUrl, secondary, "unused", false))
            expectFailure(403, "recording_not_owned") { secondaryClient.uploadRecording(recording, { id -> File(artifacts.first { it.id == id }.relativePath) }, { _, _, _ -> }) }
        }
        val snapshot = client.fetchRestoreSnapshot()
        assertTrue(snapshot.dogs.any { it.id == dogId })
        assertTrue(snapshot.recordings.any { it.id == recordingId })

        val raw = requireNotNull(artifacts.find { it.type == "raw" })
        val rawBytes = contents.getValue("raw")
        InstrumentationRegistry.getArguments().getString("serverFaultBaseUrl")?.let { faultUrl ->
            val faultClient = ServerApiClient(ServerSettings(faultUrl, token, "unused", false))
            val throughCompressionProxy = File(directory, "identity-download.bin")
            faultClient.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, throughCompressionProxy)
            assertArrayEquals(rawBytes, throughCompressionProxy.readBytes())
            Log.i("WOONA_E2E", "PASS compressedETagProxyIdentityDownload=true")
        }
        val target = File(directory, "downloaded-${raw.fileName}")
        File(directory, "${target.name}.download.part").writeBytes(rawBytes.copyOfRange(0, 3))
        client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, target)
        assertArrayEquals(rawBytes, target.readBytes())
        client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, target)
        val conflicting = File(directory, "conflicting.bin").apply { writeText("keep local data") }
        assertTrue(runCatching { client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, conflicting) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("keep local data", conflicting.readText())
        val corrupt = File(directory, "corrupt.bin")
        File(directory, "${corrupt.name}.download.part").writeBytes(byteArrayOf(0, 0, 0))
        assertTrue(runCatching { client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, corrupt) }.exceptionOrNull() is IOException)
        assertTrue(!File(directory, "${corrupt.name}.download.part").exists())
        client.downloadArtifact(raw.id, raw.sizeBytes, raw.sha256, corrupt)
        assertArrayEquals(rawBytes, corrupt.readBytes())
        val restoreRoot = File(directory, "restored-db-$recordingId")
        val database = WoonaDatabase(context, restoreRoot)
        try {
            val remoteDog = snapshot.dogs.single { it.id == dogId }
            val remoteRecording = snapshot.recordings.single { it.id == recordingId }
            val restored = database.restoreServerMetadata(listOf(remoteDog), listOf(remoteRecording))
            assertEquals(1, restored.dogs)
            assertEquals(1, restored.recordings)
            assertEquals(artifacts.size, restored.artifacts)
            val repeated = database.restoreServerMetadata(listOf(remoteDog), listOf(remoteRecording))
            assertEquals(0, repeated.dogs + repeated.recordings + repeated.artifacts + repeated.conflicts)
            database.remoteArtifacts(recordingId).forEach { artifact ->
                val destination = database.artifactDownloadTarget(artifact)
                client.downloadArtifact(artifact.id, artifact.sizeBytes, requireNotNull(artifact.sha256), destination)
                database.markArtifactDownloaded(artifact.id)
                assertArrayEquals(contents.getValue(artifact.type.value), destination.readBytes())
            }
            assertTrue(database.remoteArtifacts(recordingId).isEmpty())
            Log.i("WOONA_E2E", "PASS recording=$recordingId artifactTypes=${contents.keys} rawBytes=${raw.sizeBytes} resumedOffset=$resumedOffset invalidAuth=401 missing=422 hash=422 sqliteRestoreTwice=PASS downloadAll=PASS")
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}

private fun expectFailure(status: Int, code: String, action: () -> Unit) {
    val exception = runCatching(action).exceptionOrNull()
    assertTrue("Expected HTTP $status/$code, received $exception", exception is ServerHttpException)
    exception as ServerHttpException
    assertEquals(status, exception.status)
    assertTrue(exception.responseBody, exception.responseBody.contains(code))
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
