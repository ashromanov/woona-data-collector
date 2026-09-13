package com.example.myapplication.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class RecordedPacketFileParserTest {
    private val parser = RecordedPacketFileParser()

    @Test
    fun splitIntoPackets_returnsSequentialPacketsFromDump() {
        val first = testPacket(length = 16, counter = 1)
        val second = testPacket(length = 18, counter = 2)

        val packets = parser.splitIntoPackets(first + second)

        assertEquals(2, packets.size)
        assertArrayEquals(first, packets[0])
        assertArrayEquals(second, packets[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitIntoPackets_rejectsTruncatedPacket() {
        parser.splitIntoPackets(testPacket(length = 18, counter = 1).copyOf(17))
    }

    @Test
    fun forEachPacket_streamsSequentialPacketsFromFile() {
        val first = testPacket(length = 16, counter = 1)
        val second = testPacket(length = 18, counter = 2)
        val packetFile = Files.createTempFile("packet-dump", ".bin").toFile().apply {
            writeBytes(first + second)
        }
        val packets = mutableListOf<ByteArray>()

        parser.forEachPacket(packetFile) { packetBytes ->
            packets += packetBytes
        }

        assertEquals(2, packets.size)
        assertArrayEquals(first, packets[0])
        assertArrayEquals(second, packets[1])
    }

    private fun testPacket(
        length: Int,
        counter: Int,
    ): ByteArray {
        return ByteArray(length).apply {
            this[0] = 0x33
            this[1] = 0x99.toByte()
            this[2] = 0xAA.toByte()
            this[3] = 0x55
            this[4] = (length and 0xFF).toByte()
            this[5] = ((length shr 8) and 0xFF).toByte()
            this[6] = 1
            this[7] = (counter and 0xFF).toByte()
            this[8] = ((counter shr 8) and 0xFF).toByte()
            this[9] = ((counter shr 16) and 0xFF).toByte()
            this[10] = ((counter shr 24) and 0xFF).toByte()
        }
    }
}
