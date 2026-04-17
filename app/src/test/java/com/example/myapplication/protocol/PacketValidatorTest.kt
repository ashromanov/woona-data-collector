package com.example.myapplication.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketValidatorTest {
    private val validator = PacketValidator()

    @Test
    fun validate_acceptsPacketWithKnownTopLevelFields() {
        val result = validator.validate(
            packetBytes = testPacket(
                counter = 7,
                timerMillis = 99,
                measurementCount = 1,
            ),
        )

        assertTrue(result is PacketValidationResult.Accepted)
        val packet = (result as PacketValidationResult.Accepted).packet
        assertEquals(7L, packet.counter)
        assertEquals(99L, packet.timerMillis)
        assertEquals(1, packet.measurementCount)
    }

    @Test
    fun validate_rejectsPacketWithZeroMeasurementCount() {
        val result = validator.validate(
            packetBytes = testPacket(
                counter = 7,
                timerMillis = 99,
                measurementCount = 0,
            ),
        )

        assertEquals(
            PacketValidationFailureReason.INVALID_MEASUREMENT_COUNT,
            (result as PacketValidationResult.Rejected).reason,
        )
    }

    @Test
    fun validate_rejectsPacketWithLengthMismatch() {
        val packet = testPacket(counter = 1, timerMillis = 10, measurementCount = 1).copyOf(17)

        val result = validator.validate(packet)

        assertEquals(
            PacketValidationFailureReason.INVALID_LENGTH,
            (result as PacketValidationResult.Rejected).reason,
        )
    }

    private fun testPacket(
        counter: Int,
        timerMillis: Int,
        measurementCount: Int,
    ): ByteArray {
        val length = 16
        return ByteArray(length).apply {
            this[0] = 0x33
            this[1] = 0x99.toByte()
            this[2] = 0xAA.toByte()
            this[3] = 0x55
            this[4] = (length and 0xFF).toByte()
            this[5] = ((length shr 8) and 0xFF).toByte()
            this[6] = measurementCount.toByte()
            this[7] = (counter and 0xFF).toByte()
            this[8] = ((counter shr 8) and 0xFF).toByte()
            this[9] = ((counter shr 16) and 0xFF).toByte()
            this[10] = ((counter shr 24) and 0xFF).toByte()
            this[11] = (timerMillis and 0xFF).toByte()
            this[12] = ((timerMillis shr 8) and 0xFF).toByte()
            this[13] = ((timerMillis shr 16) and 0xFF).toByte()
            this[14] = ((timerMillis shr 24) and 0xFF).toByte()
        }
    }
}
