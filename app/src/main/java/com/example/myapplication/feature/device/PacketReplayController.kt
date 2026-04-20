package com.example.myapplication.feature.device

import com.example.myapplication.R
import com.example.myapplication.localization.EnglishTextResolver
import com.example.myapplication.localization.TextResolver
import com.example.myapplication.protocol.RecordedPacketFileParser
import kotlin.math.min

interface PacketReplayController : AutoCloseable {
    fun startReplay(fileBytes: ByteArray)
    fun stop()
}

class DebugPacketReplayController(
    private val submitFragment: (ByteArray) -> PacketSubmitResult,
    private val onReplayStarted: () -> Unit,
    private val onReplayCompleted: () -> Unit,
    private val onReplayStopped: () -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val appTextResolver: TextResolver = EnglishTextResolver,
    private val packetFileParser: RecordedPacketFileParser = RecordedPacketFileParser(),
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
    private val packetIntervalMs: Long = DEFAULT_PACKET_INTERVAL_MS,
) : PacketReplayController {
    private val workerLock = Any()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var workerThread: Thread? = null

    override fun startReplay(fileBytes: ByteArray) {
        if (fileBytes.isEmpty()) {
            onError(appTextResolver.getString(R.string.replay_file_empty), null)
            onReplayStopped()
            return
        }

        stop()

        val packets = try {
            packetFileParser.splitIntoPackets(fileBytes)
        } catch (exception: IllegalArgumentException) {
            onError(appTextResolver.getString(R.string.replay_file_invalid), exception)
            onReplayStopped()
            return
        }

        stopRequested = false
        val thread = Thread(
            {
                onReplayStarted()

                try {
                    replayPackets(packets)
                    if (stopRequested) {
                        onReplayStopped()
                    } else {
                        onReplayCompleted()
                    }
                } catch (exception: InterruptedException) {
                    if (stopRequested) {
                        onReplayStopped()
                    } else {
                        Thread.currentThread().interrupt()
                        onError(appTextResolver.getString(R.string.replay_interrupted), exception)
                        onReplayStopped()
                    }
                } catch (exception: Exception) {
                    onError(appTextResolver.getString(R.string.replay_failed), exception)
                    onReplayStopped()
                } finally {
                    synchronized(workerLock) {
                        if (workerThread === Thread.currentThread()) {
                            workerThread = null
                        }
                    }
                }
            },
            REPLAY_THREAD_NAME,
        )

        synchronized(workerLock) {
            workerThread = thread
        }
        thread.start()
    }

    override fun stop() {
        stopRequested = true
        val thread = synchronized(workerLock) { workerThread } ?: return
        thread.interrupt()
        thread.join(STOP_JOIN_TIMEOUT_MS)
    }

    override fun close() {
        stop()
    }

    private fun replayPackets(packets: List<ByteArray>) {
        packets.forEach { packet ->
            ensureNotStopped()
            val packetStartNanos = System.nanoTime()

            var offset = 0
            while (offset < packet.size) {
                ensureNotStopped()
                val endOffset = min(offset + chunkSize, packet.size)
                val submitResult = submitFragment(packet.copyOfRange(offset, endOffset))
                when (submitResult) {
                    PacketSubmitResult.ACCEPTED -> Unit
                    PacketSubmitResult.REJECTED -> {
                        if (!stopRequested) {
                            throw IllegalStateException("Replay fragment was rejected by capture pipeline")
                        }
                        return
                    }

                    PacketSubmitResult.OVERFLOW -> {
                        throw IllegalStateException("Replay overflowed the capture queue")
                    }
                }

                offset = endOffset
                if (offset < packet.size) {
                    Thread.sleep(chunkDelayMs)
                }
            }

            val elapsedMs = (System.nanoTime() - packetStartNanos) / NANOS_PER_MILLISECOND
            val remainingMs = packetIntervalMs - elapsedMs
            if (remainingMs > 0) {
                Thread.sleep(remainingMs)
            }
        }
    }

    private fun ensureNotStopped() {
        if (stopRequested) {
            throw InterruptedException("Replay stopped")
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 247
        const val DEFAULT_CHUNK_DELAY_MS = 15L
        const val DEFAULT_PACKET_INTERVAL_MS = 1_000L
        const val STOP_JOIN_TIMEOUT_MS = 250L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REPLAY_THREAD_NAME = "debug-packet-replay"
    }
}
