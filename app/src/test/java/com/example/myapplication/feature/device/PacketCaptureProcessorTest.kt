package com.example.myapplication.feature.device

import com.example.myapplication.storage.BlePacketFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import com.example.myapplication.storage.BleDiagnosticLogFileStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PacketCaptureProcessorTest {
    @Test
    fun submit_writesOnlyValidatedPackets() {
        val directory = Files.createTempDirectory("packet-processor-valid").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(1)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 1L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 11L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 111L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(validPacket(counter = 10, timerMillis = 50))

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        val file = requireNotNull(processor.currentFile())
        assertArrayEquals(validPacket(counter = 10, timerMillis = 50), file.readBytes())
        assertEquals(0L, updates.single().packetsRejected)
        assertEquals(1L, updates.single().fragmentsReceived)
        assertEquals(validPacket(counter = 10, timerMillis = 50).size.toLong(), updates.single().rawBytesReceived)
        assertTrue(updates.single().chartSamplesByStream.isEmpty())
        assertEquals(1, updates.single().diagnosticEvents.size)
        assertTrue(updates.single().diagnosticEvents.single().message.contains("Accepted packet counter=10"))
        assertNotNull(directory.listFiles()?.firstOrNull { it.name.startsWith("ble_raw_") })
        val logFile = directory.listFiles()?.firstOrNull { it.name.startsWith("ble_log_") }
        assertNotNull(logFile)
        assertTrue(requireNotNull(logFile).readText().contains("Accepted packet counter=10"))
    }

    @Test
    fun submit_rejectsPacketsWithInvalidMeasurementCount() {
        val directory = Files.createTempDirectory("packet-processor-invalid").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(1)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 2L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 22L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 222L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(invalidPacket(counter = 10, timerMillis = 50))

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        assertNull(processor.currentFile())
        assertEquals(1L, updates.single().packetsRejected)
        assertEquals("Invalid measurement count", updates.single().lastPacketIssue)
        assertTrue(updates.single().diagnosticEvents.single().message.contains("Rejected packet"))
    }

    @Test
    fun submit_reportsGapBeforeAcceptedPacket() {
        val directory = Files.createTempDirectory("packet-processor-gap").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(2)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 3L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 33L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 333L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(validPacket(counter = 10, timerMillis = 50))
        processor.submit(validPacket(counter = 12, timerMillis = 60))

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        val secondUpdate = updates.last()
        assertEquals(1L, secondUpdate.packetsLost)
        assertEquals(2, secondUpdate.diagnosticEvents.size)
        assertTrue(secondUpdate.diagnosticEvents.first().message.contains("Gap detected"))
        assertTrue(secondUpdate.diagnosticEvents.last().message.contains("Accepted packet counter=12"))
        assertTrue(secondUpdate.chartSamplesByStream.isEmpty())
    }

    @Test
    fun submit_gapPacketStartsNewSegmentWithoutBackfillingMissingInterval() {
        val directory = Files.createTempDirectory("packet-processor-gap-chart").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(2)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 31L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 331L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 3331L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(
            validPacket(
                counter = 10,
                timerMillis = 50,
                blocks = listOf(
                    sensorBlock(sensorType = 2, channelSamples = listOf(listOf(10, 20))),
                ),
            ),
        )
        processor.submit(
            validPacket(
                counter = 12,
                timerMillis = 90,
                blocks = listOf(
                    sensorBlock(sensorType = 2, channelSamples = listOf(listOf(30, 40))),
                ),
            ),
        )

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        val chartPoints = updates.last().chartSamplesByStream.getValue(
            ChartStreamKey(sensorType = 2, channel = 1),
        )
        assertEquals(listOf(90L, 90L), chartPoints.map { it.timeMillis })
        assertTrue(chartPoints.first().startsNewSegment)
    }

    @Test
    fun submit_firstAcceptedPacketDoesNotInventSampleSpacing() {
        val directory = Files.createTempDirectory("packet-processor-first-chart").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(1)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 41L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 441L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 4441L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(
            validPacket(
                counter = 10,
                timerMillis = 50,
                blocks = listOf(
                    sensorBlock(sensorType = 2, channelSamples = listOf(listOf(10, 20))),
                ),
            ),
        )

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        val chartPoints = updates.single().chartSamplesByStream.getValue(
            ChartStreamKey(sensorType = 2, channel = 1),
        )
        assertEquals(listOf(50L, 50L), chartPoints.map { it.timeMillis })
    }

    @Test
    fun submit_emitsTimestampedSamplesForAllStreams() {
        val directory = Files.createTempDirectory("packet-processor-streams").toFile()
        val updates = mutableListOf<PacketProcessingUpdate>()
        val latch = CountDownLatch(2)
        val processor = PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = directory, timestampProvider = { 4L }),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = directory, timestampProvider = { 44L }),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = directory, timestampProvider = { 444L }),
            onPacketProcessed = {
                updates += it
                latch.countDown()
            },
            onError = { message, throwable -> throw AssertionError(message, throwable) },
        )

        processor.submit(
            validPacket(
                counter = 10,
                timerMillis = 50,
                blocks = listOf(
                    sensorBlock(sensorType = 2, channelSamples = listOf(listOf(10, 20), listOf(30, 40))),
                    sensorBlock(sensorType = 4, channelSamples = listOf(listOf(50, 60))),
                ),
            ),
        )
        processor.submit(
            validPacket(
                counter = 11,
                timerMillis = 70,
                blocks = listOf(
                    sensorBlock(sensorType = 2, channelSamples = listOf(listOf(11, 21), listOf(31, 41))),
                    sensorBlock(sensorType = 4, channelSamples = listOf(listOf(51, 61))),
                ),
            ),
        )

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        processor.close()

        val secondUpdate = updates.last()
        assertEquals(setOf(
            ChartStreamKey(sensorType = 2, channel = 1),
            ChartStreamKey(sensorType = 2, channel = 2),
            ChartStreamKey(sensorType = 4, channel = 1),
        ), secondUpdate.chartSamplesByStream.keys)
        assertEquals(listOf(60L, 70L), secondUpdate.chartSamplesByStream.getValue(
            ChartStreamKey(sensorType = 2, channel = 1),
        ).map { it.timeMillis })
    }

    private fun validPacket(
        counter: Int,
        timerMillis: Int,
        blocks: List<ByteArray> = emptyList(),
    ): ByteArray = packet(
        counter = counter,
        timerMillis = timerMillis,
        measurementCount = if (blocks.isEmpty()) 1 else blocks.size,
        blocks = blocks,
    )

    private fun invalidPacket(
        counter: Int,
        timerMillis: Int,
    ): ByteArray = packet(
        counter = counter,
        timerMillis = timerMillis,
        measurementCount = 0,
    )

    private fun packet(
        counter: Int,
        timerMillis: Int,
        measurementCount: Int,
        blocks: List<ByteArray> = emptyList(),
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
            this[7] = (counter and 0xFF).toByte()
            this[8] = ((counter shr 8) and 0xFF).toByte()
            this[9] = ((counter shr 16) and 0xFF).toByte()
            this[10] = ((counter shr 24) and 0xFF).toByte()
            this[11] = (timerMillis and 0xFF).toByte()
            this[12] = ((timerMillis shr 8) and 0xFF).toByte()
            this[13] = ((timerMillis shr 16) and 0xFF).toByte()
            this[14] = ((timerMillis shr 24) and 0xFF).toByte()

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, this, 16, payload.size)
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
