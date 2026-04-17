package com.example.myapplication.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketStatsTrackerTest {
    @Test
    fun record_countsReceivedPackets() {
        val tracker = PacketStatsTracker()

        tracker.record(10)
        val result = tracker.record(11)

        assertEquals(2L, result.snapshot.packetsReceived)
        assertEquals(0L, result.snapshot.packetsLost)
        assertEquals(0L, result.gapCount)
    }

    @Test
    fun record_countsCounterGapsAsLostPackets() {
        val tracker = PacketStatsTracker()

        tracker.record(100)
        val result = tracker.record(103)

        assertEquals(2L, result.snapshot.packetsReceived)
        assertEquals(2L, result.snapshot.packetsLost)
        assertEquals(2L, result.gapCount)
        assertEquals(101L, result.expectedCounter)
        assertEquals(103L, result.actualCounter)
    }

    @Test
    fun record_ignoresBackwardsCounterForLossAccounting() {
        val tracker = PacketStatsTracker()

        tracker.record(10)
        val result = tracker.record(9)

        assertEquals(2L, result.snapshot.packetsReceived)
        assertEquals(0L, result.snapshot.packetsLost)
        assertEquals(0L, result.gapCount)
    }

    @Test
    fun reset_clearsCountersForNewSession() {
        val tracker = PacketStatsTracker()

        tracker.record(5)
        tracker.record(8)
        tracker.reset()
        val result = tracker.record(100)

        assertEquals(1L, result.snapshot.packetsReceived)
        assertEquals(0L, result.snapshot.packetsLost)
        assertEquals(0L, result.gapCount)
    }
}
