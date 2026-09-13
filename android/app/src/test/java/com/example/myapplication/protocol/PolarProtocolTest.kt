package com.example.myapplication.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolarProtocolTest {
    @Test
    fun parsesHeartRateWithRrIntervals() {
        val frame = parsePolarHeartRate(byteArrayOf(0x10, 72, 0x00, 0x04, 0x33, 0x03))

        assertEquals(72, frame?.beatsPerMinute)
        assertEquals(listOf(1_000L, 799L), frame?.rrMillis)
    }

    @Test
    fun parsesSignedEcgAndAccelerationSamples() {
        val ecg = parsePolarEcg(
            byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0xE8.toByte(), 0x03, 0, 0x18, 0xFC.toByte(), 0xFF.toByte()),
        )
        val acc = parsePolarAcceleration(
            byteArrayOf(2, 2, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0xFE.toByte(), 0xFF.toByte(), 3, 0),
        )

        assertEquals(1UL, ecg?.timestampNanos)
        assertEquals(listOf(1_000, -1_000), ecg?.samplesMicrovolts)
        assertEquals(2UL, acc?.timestampNanos)
        assertEquals(PolarAccelerationSample(1, -2, 3), acc?.samples?.single())
        assertNull(parsePolarAcceleration(byteArrayOf(2, 0)))
    }
}
