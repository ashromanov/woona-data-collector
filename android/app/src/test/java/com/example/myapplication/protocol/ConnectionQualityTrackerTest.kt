package com.example.myapplication.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionQualityTrackerTest {
    @Test
    fun snapshot_detectsWeakAndLostStream_thenRecovers() {
        val tracker = ConnectionQualityTracker()

        assertEquals(ConnectionQuality.WAITING, tracker.connect(0L).quality)
        assertEquals(ConnectionQuality.GOOD, tracker.record(1_000L, 3L, 0L, 0L).quality)

        val weak = tracker.record(2_000L, 30L, 2L, 0L)
        assertEquals(ConnectionQuality.WEAK, weak.quality)
        assertTrue(weak.lossPercent > 3.0)

        assertEquals(ConnectionQuality.LOST, tracker.snapshot(7_000L).quality)
        assertEquals(ConnectionQuality.GOOD, tracker.record(32_100L, 130L, 2L, 0L).quality)
    }

    @Test
    fun snapshot_usesOnlyRecentThirtySecondWindow() {
        val tracker = ConnectionQualityTracker()
        tracker.connect(0L)
        tracker.record(1_000L, 10L, 2L, 0L)

        val recovered = tracker.record(32_000L, 110L, 2L, 0L)

        assertEquals(ConnectionQuality.GOOD, recovered.quality)
        assertEquals(0.0, recovered.lossPercent, 0.001)
    }

    @Test
    fun disconnectKeepsRecentQualityDetails() {
        val tracker = ConnectionQualityTracker()
        tracker.connect(0L)
        tracker.record(1_000L, 10L, 2L, 0L)

        val disconnected = tracker.disconnect(2_000L)

        assertEquals(ConnectionQuality.LOST, disconnected.quality)
        assertTrue(disconnected.lossPercent > 0.0)
    }
}
