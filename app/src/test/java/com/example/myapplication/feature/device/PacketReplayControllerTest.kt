package com.example.myapplication.feature.device

import com.example.myapplication.protocol.RecordedPacketFileParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PacketReplayControllerTest {
    @Test
    fun startReplay_invalidFileStopsReplayState() {
        val errors = mutableListOf<String>()
        val stoppedLatch = CountDownLatch(1)
        val controller = DebugPacketReplayController(
            submitFragment = { PacketSubmitResult.ACCEPTED },
            onReplayStarted = {},
            onReplayCompleted = {},
            onReplayStopped = { stoppedLatch.countDown() },
            onError = { message, _ -> errors += message },
            packetFileParser = RecordedPacketFileParser(),
        )

        controller.startReplay(byteArrayOf(0x01, 0x02, 0x03))

        assertTrue(stoppedLatch.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("Replay file is not a valid packet dump"), errors)
    }

    @Test
    fun startReplay_runtimeFailureStopsReplayState() {
        val stoppedLatch = CountDownLatch(1)
        val errors = mutableListOf<String>()
        val controller = DebugPacketReplayController(
            submitFragment = { PacketSubmitResult.OVERFLOW },
            onReplayStarted = {},
            onReplayCompleted = {},
            onReplayStopped = { stoppedLatch.countDown() },
            onError = { message, _ -> errors += message },
            packetFileParser = RecordedPacketFileParser(),
            packetIntervalMs = 1L,
        )

        controller.startReplay(validPacket())

        assertTrue(stoppedLatch.await(1, TimeUnit.SECONDS))
        assertTrue(errors.contains("Replay failed"))
    }

    @Test
    fun startReplay_replaysValidPacketDump() {
        val submittedFragments = mutableListOf<ByteArray>()
        val completedLatch = CountDownLatch(1)
        val controller = DebugPacketReplayController(
            submitFragment = {
                submittedFragments += it
                PacketSubmitResult.ACCEPTED
            },
            onReplayStarted = {},
            onReplayCompleted = { completedLatch.countDown() },
            onReplayStopped = {},
            onError = { message, throwable -> throw AssertionError(message, throwable) },
            packetFileParser = RecordedPacketFileParser(),
            packetIntervalMs = 1L,
        )

        val packet = validPacket()
        controller.startReplay(packet)

        assertTrue(completedLatch.await(1, TimeUnit.SECONDS))
        assertEquals(1, submittedFragments.size)
        assertArrayEquals(packet, submittedFragments.single())
    }

    private fun validPacket(): ByteArray {
        val length = 16
        return ByteArray(length).apply {
            this[0] = 0x33
            this[1] = 0x99.toByte()
            this[2] = 0xAA.toByte()
            this[3] = 0x55
            this[4] = (length and 0xFF).toByte()
            this[5] = ((length shr 8) and 0xFF).toByte()
            this[6] = 1
        }
    }
}
