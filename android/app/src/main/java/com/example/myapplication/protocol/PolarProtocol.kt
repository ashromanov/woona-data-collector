package com.example.myapplication.protocol

data class PolarHeartRateFrame(
    val beatsPerMinute: Int,
    val rrMillis: List<Long>,
)

data class PolarEcgFrame(
    val timestampNanos: ULong,
    val samplesMicrovolts: List<Int>,
)

data class PolarAccelerationSample(
    val xMilliG: Int,
    val yMilliG: Int,
    val zMilliG: Int,
)

data class PolarAccelerationFrame(
    val timestampNanos: ULong,
    val samples: List<PolarAccelerationSample>,
)

fun parsePolarHeartRate(value: ByteArray): PolarHeartRateFrame? {
    if (value.size < 2) return null
    val flags = value[0].toInt() and 0xFF
    var offset = 1
    val heartRate = if (flags and 1 != 0) {
        if (value.size < 3) return null
        value.u16(offset).also { offset += 2 }
    } else {
        (value[offset].toInt() and 0xFF).also { offset++ }
    }
    if (flags and 8 != 0) offset += 2
    if (offset > value.size) return null
    val rr = if (flags and 16 != 0) {
        buildList {
            while (offset + 1 < value.size) {
                add(value.u16(offset) * 1_000L / 1_024L)
                offset += 2
            }
        }
    } else {
        emptyList()
    }
    return PolarHeartRateFrame(heartRate, rr)
}

fun parsePolarEcg(value: ByteArray): PolarEcgFrame? {
    if (value.firstOrNull()?.toInt() != 0 || value.size < 13 || (value.size - 10) % 3 != 0) return null
    val samples = (10 until value.size step 3).map { offset ->
        var raw = (value[offset].toInt() and 0xFF) or
            ((value[offset + 1].toInt() and 0xFF) shl 8) or
            ((value[offset + 2].toInt() and 0xFF) shl 16)
        if (raw and 0x800000 != 0) raw -= 0x1000000
        raw
    }
    return PolarEcgFrame(value.u64(1), samples)
}

fun parsePolarAcceleration(value: ByteArray): PolarAccelerationFrame? {
    if (value.firstOrNull()?.toInt() != 2 || value.size < 16 || value[9].toInt() != 1 ||
        (value.size - 10) % 6 != 0
    ) return null
    val samples = (10 until value.size step 6).map { offset ->
        PolarAccelerationSample(
            xMilliG = value.i16(offset),
            yMilliG = value.i16(offset + 2),
            zMilliG = value.i16(offset + 4),
        )
    }
    return PolarAccelerationFrame(value.u64(1), samples)
}

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.i16(offset: Int): Int = u16(offset).let { if (it and 0x8000 != 0) it - 0x10000 else it }

private fun ByteArray.u64(offset: Int): ULong {
    var result = 0UL
    repeat(8) { index -> result = result or ((this[offset + index].toULong() and 0xFFUL) shl (index * 8)) }
    return result
}
