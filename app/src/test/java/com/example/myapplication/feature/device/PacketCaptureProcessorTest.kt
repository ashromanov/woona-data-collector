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
    }

    private fun validPacket(
        counter: Int,
        timerMillis: Int,
    ): ByteArray = packet(
        counter = counter,
        timerMillis = timerMillis,
        measurementCount = 1,
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
