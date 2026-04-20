package com.example.myapplication.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class BleSessionCsvExporterTest {
    private val exporter = BleSessionCsvExporter()

    @Test
    fun export_writesTimeColumnAndOneColumnPerChannel() {
        val directory = Files.createTempDirectory("ble-session-csv").toFile()
        val packetFile = directory.resolve("capture.bin").apply {
            writeBytes(
                packet(
                    counter = 9,
                    timerMillis = 50,
                    blocks = listOf(
                        sensorBlock(
                            sensorType = 4,
                            channelSamples = listOf(
                                listOf(50, 60),
                            ),
                        ),
                    ),
                ) + packet(
                    counter = 10,
                    timerMillis = 60,
                    blocks = listOf(
                        sensorBlock(
                            sensorType = 2,
                            channelSamples = listOf(
                                listOf(10, 20),
                                listOf(30, 40),
                            ),
                        ),
                    ),
                ),
            )
        }
        val targetFile = directory.resolve("capture.csv")

        exporter.export(
            packetFile = packetFile,
            sessionStartMillis = 1_000L,
            targetFile = targetFile,
        )

        assertEquals(
            listOf(
                "time_millis,packet_device_time_millis,sample_device_time_millis,sample_device_time_normalized_millis,axl_sensor_2_ch_1,axl_sensor_2_ch_2,mic_sensor_4_ch_1",
                "1000,50,50,0,,,50",
                "1001,50,50,0,,,60",
                "1002,60,55,5,10,30,",
                "1003,60,60,10,20,40,",
            ),
            targetFile.readLines(),
        )
    }
}

private fun packet(
    counter: Int,
    timerMillis: Int,
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
        this[6] = blocks.size.toByte()
        this[7] = (counter and 0xFF).toByte()
        this[8] = ((counter shr 8) and 0xFF).toByte()
        this[9] = ((counter shr 16) and 0xFF).toByte()
        this[10] = ((counter shr 24) and 0xFF).toByte()
        this[11] = (timerMillis and 0xFF).toByte()
        this[12] = ((timerMillis shr 8) and 0xFF).toByte()
        this[13] = ((timerMillis shr 16) and 0xFF).toByte()
        this[14] = ((timerMillis shr 24) and 0xFF).toByte()

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
