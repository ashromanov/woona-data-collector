package com.example.myapplication.protocol

enum class ConnectionQuality {
    IDLE,
    WAITING,
    GOOD,
    WARNING,
    WEAK,
    LOST,
}

data class ConnectionQualitySnapshot(
    val quality: ConnectionQuality,
    val silenceMillis: Long = 0L,
    val lossPercent: Double = 0.0,
    val rejectedPercent: Double = 0.0,
)

class ConnectionQualityTracker {
    private val samples = ArrayDeque<Sample>()
    private var connectedAtMillis: Long? = null
    private var lastPacketAtMillis: Long? = null
    private var lastPacketsReceived = 0L

    fun connect(nowMillis: Long): ConnectionQualitySnapshot {
        samples.clear()
        samples += Sample(nowMillis, 0L, 0L, 0L)
        connectedAtMillis = nowMillis
        lastPacketAtMillis = null
        lastPacketsReceived = 0L
        return snapshot(nowMillis)
    }

    fun disconnect(nowMillis: Long): ConnectionQualitySnapshot {
        val wasConnected = connectedAtMillis != null
        val lastSnapshot = snapshot(nowMillis)
        connectedAtMillis = null
        return lastSnapshot.copy(quality = if (wasConnected) ConnectionQuality.LOST else ConnectionQuality.IDLE)
    }

    fun record(
        nowMillis: Long,
        packetsReceived: Long,
        packetsLost: Long,
        packetsRejected: Long,
    ): ConnectionQualitySnapshot {
        if (connectedAtMillis == null) connect(nowMillis)
        if (packetsReceived > lastPacketsReceived) lastPacketAtMillis = nowMillis
        lastPacketsReceived = packetsReceived
        samples += Sample(nowMillis, packetsReceived, packetsLost, packetsRejected)
        trim(nowMillis)
        return snapshot(nowMillis)
    }

    fun snapshot(nowMillis: Long): ConnectionQualitySnapshot {
        val connectedAt = connectedAtMillis ?: return ConnectionQualitySnapshot(ConnectionQuality.IDLE)
        trim(nowMillis)
        val silence = silenceMillis(nowMillis)
        val first = samples.firstOrNull() ?: Sample(connectedAt, 0L, 0L, 0L)
        val last = samples.lastOrNull() ?: first
        val received = (last.received - first.received).coerceAtLeast(0L)
        val lost = (last.lost - first.lost).coerceAtLeast(0L)
        val rejected = (last.rejected - first.rejected).coerceAtLeast(0L)
        val lossPercent = percent(lost, received + lost)
        val rejectedPercent = percent(rejected, received + rejected)
        val quality = when {
            silence >= LOST_AFTER_MILLIS -> ConnectionQuality.LOST
            nowMillis - connectedAt < WARMUP_MILLIS && last.received < 3L -> ConnectionQuality.WAITING
            silence >= WEAK_AFTER_MILLIS || lossPercent > 3.0 || rejectedPercent > 10.0 -> ConnectionQuality.WEAK
            lossPercent >= 0.5 || rejectedPercent >= 5.0 -> ConnectionQuality.WARNING
            else -> ConnectionQuality.GOOD
        }
        return ConnectionQualitySnapshot(quality, silence, lossPercent, rejectedPercent)
    }

    fun reset() {
        samples.clear()
        connectedAtMillis = null
        lastPacketAtMillis = null
        lastPacketsReceived = 0L
    }

    private fun trim(nowMillis: Long) {
        val cutoff = nowMillis - WINDOW_MILLIS
        while (samples.size > 1 && samples[1].timeMillis <= cutoff) samples.removeFirst()
    }

    private fun silenceMillis(nowMillis: Long): Long {
        val since = lastPacketAtMillis ?: connectedAtMillis ?: nowMillis
        return (nowMillis - since).coerceAtLeast(0L)
    }

    private fun percent(part: Long, total: Long): Double =
        if (total == 0L) 0.0 else part.toDouble() * 100.0 / total.toDouble()

    private data class Sample(
        val timeMillis: Long,
        val received: Long,
        val lost: Long,
        val rejected: Long,
    )

    private companion object {
        const val WINDOW_MILLIS = 30_000L
        const val WARMUP_MILLIS = 8_000L
        const val WEAK_AFTER_MILLIS = 2_500L
        const val LOST_AFTER_MILLIS = 5_000L
    }
}
