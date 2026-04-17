package com.example.myapplication.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketAssemblerTest {
    @Test
    fun append_emitsPacketWhenWholePacketArrives() {
        val assembler = PacketAssembler()
        val packet = testPacket(counter = 7)

        val packets = assembler.append(packet)

        assertEquals(1, packets.size)
        assertArrayEquals(packet, packets.single().bytes)
        assertEquals(7L, packets.single().counter)
        assertTrue(packets.single().sensorBlocks.isEmpty())
    }

    @Test
    fun append_reassemblesFragmentsAcrossChunks() {
        val assembler = PacketAssembler()
        val packet = testPacket(counter = 42, payloadSize = 8)

        val firstHalf = assembler.append(packet.copyOfRange(0, 10))
        val secondHalf = assembler.append(packet.copyOfRange(10, packet.size))

        assertTrue(firstHalf.isEmpty())
        assertEquals(1, secondHalf.size)
        assertArrayEquals(packet, secondHalf.single().bytes)
        assertEquals(42L, secondHalf.single().counter)
    }

    @Test
    fun append_discardsLeadingGarbageBeforeHeader() {
        val assembler = PacketAssembler()
        val packet = testPacket(counter = 99)
        val chunk = byteArrayOf(0x00, 0x11, 0x22) + packet

        val packets = assembler.append(chunk)

        assertEquals(1, packets.size)
        assertArrayEquals(packet, packets.single().bytes)
    }

    @Test
    fun append_emitsMultiplePacketsFromSingleChunk() {
        val assembler = PacketAssembler()
        val first = testPacket(counter = 1)
        val second = testPacket(counter = 2, payloadSize = 4)

        val packets = assembler.append(first + second)

        assertEquals(listOf(1L, 2L), packets.map { it.counter })
    }

    @Test
    fun append_skipsMalformedLengthAndResynchronizes() {
        val assembler = PacketAssembler()
        val malformed = byteArrayOf(
            0x33,
            0x99.toByte(),
            0xAA.toByte(),
            0x55,
            0x01,
            0x00,
        ) + ByteArray(10)
        val valid = testPacket(counter = 5)

        val packets = assembler.append(malformed + valid)

        assertEquals(1, packets.size)
        assertEquals(5L, packets.single().counter)
        assertArrayEquals(valid, packets.single().bytes)
    }

    @Test
    fun append_parsesSensorBlocksIntoCompletedPacket() {
        val assembler = PacketAssembler()
        val packet = testPacket(
            counter = 12,
            measurementCount = 1,
            blocks = listOf(
                sensorBlock(
                    sensorType = 2,
                    channelSamples = listOf(
                        listOf(10, 20),
                        listOf(30, 40),
                    ),
                ),
            ),
        )

        val completedPacket = assembler.append(packet).single()

        assertEquals(listOf(30f, 40f), completedPacket.channelSamples(sensorType = 2, channel = 2))
    }

    @Test
    fun reset_discardsBufferedFragmentsFromPreviousSession() {
        val assembler = PacketAssembler()
        val packet = testPacket(counter = 42, payloadSize = 8)

        val firstHalf = assembler.append(packet.copyOfRange(0, 10))
        assembler.reset()
        val secondHalf = assembler.append(packet.copyOfRange(10, packet.size))

        assertTrue(firstHalf.isEmpty())
        assertTrue(secondHalf.isEmpty())
    }

    private fun testPacket(
        counter: Int,
        payloadSize: Int = 0,
        measurementCount: Int = 0,
        blocks: List<ByteArray> = emptyList(),
    ): ByteArray {
        val blockPayload = blocks.fold(ByteArray(0)) { acc, block -> acc + block }
        val resolvedPayloadSize = if (blocks.isNotEmpty()) blockPayload.size else payloadSize
        val length = 16 + resolvedPayloadSize
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

            if (blocks.isNotEmpty()) {
                System.arraycopy(blockPayload, 0, this, 16, blockPayload.size)
            } else {
                for (index in 16 until length) {
                    this[index] = index.toByte()
                }
            }
        }
    }

    private fun sensorBlock(
        sensorType: Int,
        channelSamples: List<List<Int>>,
    ): ByteArray {
        val channelCount = channelSamples.size
        val samplesPerChannel = channelSamples.firstOrNull()?.size ?: 0
        val payloadSize = channelCount * samplesPerChannel * 2

        return ByteArray(6 + payloadSize).apply {
            this[0] = sensorType.toByte()
            this[1] = channelCount.toByte()
            this[2] = (samplesPerChannel and 0xFF).toByte()
            this[3] = ((samplesPerChannel shr 8) and 0xFF).toByte()

            var offset = 6
            channelSamples.forEach { samples ->
                samples.forEach { sample ->
                    this[offset] = (sample and 0xFF).toByte()
                    this[offset + 1] = ((sample shr 8) and 0xFF).toByte()
                    offset += 2
                }
            }
        }
    }
}
