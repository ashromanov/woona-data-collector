package com.example.myapplication.protocol

data class PacketStatsSnapshot(
    val packetsReceived: Long,
    val packetsLost: Long,
)

data class PacketRecordResult(
    val snapshot: PacketStatsSnapshot,
    val gapCount: Long = 0L,
    val expectedCounter: Long? = null,
    val actualCounter: Long? = null,
)

class PacketStatsTracker {
    private var lastCounter = UNINITIALIZED_COUNTER
    private var packetsReceived = 0L
    private var packetsLost = 0L

    fun reset() {
        lastCounter = UNINITIALIZED_COUNTER
        packetsReceived = 0L
        packetsLost = 0L
    }

    fun record(counter: Long): PacketRecordResult {
        var gapCount = 0L
        var expectedCounter: Long? = null
        if (lastCounter != UNINITIALIZED_COUNTER) {
            expectedCounter = lastCounter + 1
            if (counter > expectedCounter) {
                gapCount = counter - expectedCounter
                packetsLost += gapCount
            }
        }

        packetsReceived++
        lastCounter = counter

        return PacketRecordResult(
            snapshot = snapshot(),
            gapCount = gapCount,
            expectedCounter = expectedCounter,
            actualCounter = counter,
        )
    }

    fun snapshot(): PacketStatsSnapshot {
        return PacketStatsSnapshot(
            packetsReceived = packetsReceived,
            packetsLost = packetsLost,
        )
    }

    private companion object {
        const val UNINITIALIZED_COUNTER = -1L
    }
}
