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
            ageYears = 4,
            weightKg = 18.5,
            observedSigns = listOf("cough", "distress"),
        )
        val profile = database.saveProfile(questionnaire, now = Instant.parse("2026-07-28T08:00:00Z"))
        assertEquals(questionnaire, database.profile(profile.id)?.questionnaire)

        val session1 = SessionQuestionnaire(
            sessionNumber = "11111111-1111-1111-1111-111111111111",
            date = "2026-07-28",
            startTime = "12:30",
            pulseBpm = 72,
        )
        val session2 = session1.copy(sessionNumber = "22222222-2222-2222-2222-222222222222")
        val first = database.beginRecording(
            profile.id,
            RecordingSource.LIVE,
            session1,
            ZoneId.of("Europe/Moscow"),
        )
        val second = database.beginRecording(
            profile.id,
            RecordingSource.REPLAY,
            session2,
            ZoneId.of("Europe/Moscow"),
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
                video = VideoSyncMetadata(
                    requestedAtUtc = "2026-07-28T09:30:02Z",
                    requestedMonotonicNs = 12_000_000_000,
                    offsetFromSensorNs = 2_250_000_000,
                ),
            ),
        )
        val syncJson = JSONObject(syncFile.readText())
        assertEquals(2_250_000_000, syncJson.getJSONObject("video").getLong("offsetFromSensorNs"))
        assertEquals("D-17", syncJson.getJSONObject("dogQuestionnaire").getString("numberOrName"))
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
        assertEquals(profile.id, database.recording(first.id)?.profileId)

        database.interruptUnfinished()
        assertEquals(RecordingStatus.INTERRUPTED, database.recording(second.id)?.status)

        database.writableDatabase.rawQuery("PRAGMA foreign_keys", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }
}
