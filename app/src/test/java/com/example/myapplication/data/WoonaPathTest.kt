package com.example.myapplication.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WoonaPathTest {
    @Test
    fun selectedLocalTimeControlsUtcAndDirectoryDay() {
        val questionnaire = SessionQuestionnaire(
            sessionNumber = "recording-id",
            date = "2026-07-28",
            startTime = "00:30",
        )
        val zone = ZoneId.of("Europe/Moscow")
        val start = sessionStartInstant(questionnaire, zone)

        assertEquals(Instant.parse("2026-07-27T21:30:00Z"), start)
        assertTrue(
            recordingRelativeDirectory("profile-id", questionnaire.sessionNumber, start, zone.id)
                .endsWith("/2026-07-28/recording-id"),
        )
    }

    @Test
    fun monotonicCameraTimestampProducesExactOffsetAndAbsoluteTime() {
        val anchor = SyncClockAnchor(
            event = "ble_capture_ready",
            absoluteUtc = "2026-07-29T16:00:05Z",
            wallClockEpochMillis = 1_785_340_805_000,
            monotonicTimeNs = 10_000_000_000,
            monotonicClock = "android.elapsedRealtimeNanos",
            samplingUncertaintyNs = 1_000,
        )

        assertEquals(2_250_000_000, monotonicOffsetNs(anchor, 12_250_000_000))
        assertEquals(
            Instant.ofEpochMilli(1_785_340_807_250),
            absoluteInstantForMonotonic(anchor, 12_250_000_000),
        )
    }
}
