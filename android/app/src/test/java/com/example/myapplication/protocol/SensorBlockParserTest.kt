package com.example.myapplication.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorBlockParserTest {
    private val parser = SensorBlockParser()

    @Test
    fun extractChannelSamples_returnsRequestedChannelForMatchingSensorType() {
        val packet = testPacket(
            measurementCount = 1,
            blocks = listOf(
                sensorBlock(
                    sensorType = 2,
                    channelSamples = listOf(
                        listOf(10, 20),
                        listOf(30, 40),
                        listOf(50, 60),
                    ),
                ),
            ),
        )

        val samples = parser.extractChannelSamples(packet, sensorType = 2, channel = 2)

        assertEquals(listOf(30f, 40f), samples)
    }

    @Test
    fun extractChannelSamples_ignoresNonMatchingSensorBlocks() {
        val packet = testPacket(
            measurementCount = 2,
            blocks = listOf(
                sensorBlock(sensorType = 1, channelSamples = listOf(listOf(1, 2))),
                sensorBlock(sensorType = 2, channelSamples = listOf(listOf(3, 4))),
            ),
        )

        val samples = parser.extractChannelSamples(packet, sensorType = 2, channel = 1)

        assertEquals(listOf(3f, 4f), samples)
    }

    @Test
    fun extractChannelSamples_returnsEmptyListForOutOfRangeChannel() {
        val packet = testPacket(
            measurementCount = 1,
            blocks = listOf(
                sensorBlock(sensorType = 2, channelSamples = listOf(listOf(10, 20))),
            ),
        )

        val samples = parser.extractChannelSamples(packet, sensorType = 2, channel = 2)

        assertEquals(emptyList<Float>(), samples)
    }

    private fun testPacket(
        measurementCount: Int,
        blocks: List<ByteArray>,
    ): ByteArray {
        val payload = blocks.fold(ByteArray(0)) { acc, block -> acc + block }
        val length = 16 + payload.size

        return ByteArray(length).apply {
            this[0] = 0x33
            this[1] = 0x99.toByte()
            this[2] = 0xAA.toByte()
            this[3] = 0x55
            this[4] = (length and 0xFF).toByte()
            this[5] = ((length shr 8) and 0xFF).toByte()
            this[6] = measurementCount.toByte()
            System.arraycopy(payload, 0, this, 16, payload.size)
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
