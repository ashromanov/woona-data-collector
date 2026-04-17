package com.example.myapplication.feature.device

import com.example.myapplication.protocol.PacketAssembler
import com.example.myapplication.protocol.PacketStatsTracker
import com.example.myapplication.protocol.PacketValidationResult
import com.example.myapplication.protocol.PacketValidator
import com.example.myapplication.storage.BleDiagnosticLogFileStore
import com.example.myapplication.storage.BlePacketFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class PacketProcessingUpdate(
    val packetsReceived: Long,
    val packetsLost: Long,
    val packetsRejected: Long,
    val timerRegressionRejects: Long,
    val fragmentsReceived: Long = 0L,
    val rawBytesReceived: Long = 0L,
    val chartSamplesByStream: Map<ChartStreamKey, List<ChartPoint>>,
    val lastPacketIssue: String? = null,
    val rejectionBreakdown: String? = null,
    val diagnosticEvents: List<PacketDiagnosticEvent> = emptyList(),
)

data class PacketDiagnosticEvent(
    val id: Long,
    val type: PacketDiagnosticType,
    val message: String,
)

enum class PacketDiagnosticType {
    ACCEPTED,
    GAP,
    REJECTED,
}

enum class PacketSubmitResult {
    ACCEPTED,
    OVERFLOW,
    REJECTED,
}

interface PacketCaptureController : AutoCloseable {
    fun submit(packetFragment: ByteArray): PacketSubmitResult
    fun stopCapture()
    fun updateSelection(sensorType: Int, channel: Int)
    fun resetSession()
    fun flush()
    fun currentFile(): File?
    fun currentPacketFile(): File? = currentFile()
    fun currentRawFile(): File? = null
    fun currentLogFile(): File? = null
}

class PacketCaptureProcessor(
    private val packetFileStore: BlePacketFileStore,
    private val rawFragmentFileStore: BleRawFragmentFileStore,
    private val diagnosticLogFileStore: BleDiagnosticLogFileStore,
    private val onPacketProcessed: (PacketProcessingUpdate) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val packetAssembler: PacketAssembler = PacketAssembler(),
    private val packetStats: PacketStatsTracker = PacketStatsTracker(),
    private val packetValidator: PacketValidator = PacketValidator(),
) : PacketCaptureController {
    private val processingLock = Any()
    private val rawCaptureLock = Any()
    private val queue = ArrayBlockingQueue<QueuedFragment>(MAX_PENDING_FRAGMENTS)

    @Volatile
    private var isAcceptingFragments = true

    @Volatile
    private var isRunning = true

    @Volatile
    private var sessionId = 0L

    @Volatile
    private var rejectedPackets = 0L

    @Volatile
    private var lastAcceptedTimerMillis = UNINITIALIZED_TIMER_MILLIS

    @Volatile
    private var timerRegressionRejects = 0L

    @Volatile
    private var receivedFragmentCount = 0L

    @Volatile
    private var receivedRawBytes = 0L

    private val rejectionCounts = linkedMapOf<String, Long>()
    private var nextDiagnosticEventId = 0L

    private val workerThread = Thread(::runLoop, WORKER_THREAD_NAME).apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    override fun submit(packetFragment: ByteArray): PacketSubmitResult {
        if (packetFragment.isEmpty()) return PacketSubmitResult.ACCEPTED
        if (!isAcceptingFragments) return PacketSubmitResult.REJECTED

        try {
            synchronized(rawCaptureLock) {
                rawFragmentFileStore.appendFragment(
                    sequence = receivedFragmentCount,
                    receivedAtMillis = System.currentTimeMillis(),
                    fragmentBytes = packetFragment,
                )
                receivedFragmentCount++
                receivedRawBytes += packetFragment.size.toLong()
            }
        } catch (exception: Exception) {
            onError("Failed to persist raw BLE fragment", exception)
        }

        return if (queue.offer(
            QueuedFragment(
                sessionId = sessionId,
                bytes = packetFragment,
            ),
        )
        ) {
            PacketSubmitResult.ACCEPTED
        } else {
            PacketSubmitResult.OVERFLOW
        }
    }

    override fun updateSelection(sensorType: Int, channel: Int) {
        // Chart history now keeps all streams, so selection is handled entirely in UI state.
    }

    override fun stopCapture() {
        synchronized(processingLock) {
            isAcceptingFragments = false
            sessionId++
            queue.clear()
        }
    }

    override fun resetSession() {
        try {
            synchronized(processingLock) {
                isAcceptingFragments = true
                sessionId++
                queue.clear()
                packetAssembler.reset()
                packetStats.reset()
                rejectedPackets = 0L
                lastAcceptedTimerMillis = UNINITIALIZED_TIMER_MILLIS
                timerRegressionRejects = 0L
                receivedFragmentCount = 0L
                receivedRawBytes = 0L
                rejectionCounts.clear()
                nextDiagnosticEventId = 0L
                packetFileStore.resetSession()
                rawFragmentFileStore.resetSession()
                diagnosticLogFileStore.resetSession()
            }
        } catch (exception: Exception) {
            onError("Failed to reset capture session", exception)
        }
    }

    override fun flush() {
        try {
            diagnosticLogFileStore.flush()
            rawFragmentFileStore.flush()
            packetFileStore.flush()
        } catch (exception: Exception) {
            onError("Failed to flush buffered output", exception)
        }
    }

    override fun currentFile(): File? = packetFileStore.currentFile()

    override fun currentPacketFile(): File? = packetFileStore.currentFile()

    override fun currentRawFile(): File? = rawFragmentFileStore.currentFile()

    override fun currentLogFile(): File? = diagnosticLogFileStore.currentFile()

    override fun close() {
        isAcceptingFragments = false
        isRunning = false
        workerThread.interrupt()

        try {
            workerThread.join()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            onError("Interrupted while waiting for packet processor shutdown", exception)
        }

        try {
            diagnosticLogFileStore.flush()
            diagnosticLogFileStore.close()
            rawFragmentFileStore.flush()
            rawFragmentFileStore.close()
            packetFileStore.flush()
            packetFileStore.close()
        } catch (exception: Exception) {
            onError("Failed to close buffered output", exception)
        }
    }

    private fun runLoop() {
        while (isRunning || !queue.isEmpty()) {
            try {
                val chunk = queue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                processChunk(chunk)
            } catch (exception: InterruptedException) {
                if (!isRunning) {
                    continue
                }
                Thread.currentThread().interrupt()
                return
            } catch (exception: Exception) {
                onError("Background packet processing failed", exception)
            }
        }
    }

    private fun processChunk(fragment: QueuedFragment) {
        val updates = synchronized(processingLock) {
            if (fragment.sessionId != sessionId) {
                return@synchronized emptyList()
            }

            val updates = mutableListOf<PacketProcessingUpdate>()

            val packets = packetAssembler.append(fragment.bytes)
            for (packet in packets) {
                when (val validation = packetValidator.validate(packet.bytes)) {
                    is PacketValidationResult.Accepted -> {
                        if (lastAcceptedTimerMillis != UNINITIALIZED_TIMER_MILLIS &&
                            validation.packet.timerMillis < lastAcceptedTimerMillis
                        ) {
                            registerRejection(TIMER_REGRESSION_MESSAGE)
                            timerRegressionRejects++
                            val statsSnapshot = packetStats.snapshot()
                            updates += PacketProcessingUpdate(
                                packetsReceived = statsSnapshot.packetsReceived,
                                packetsLost = statsSnapshot.packetsLost,
                                packetsRejected = rejectedPackets,
                                timerRegressionRejects = timerRegressionRejects,
                                fragmentsReceived = receivedFragmentCount,
                                rawBytesReceived = receivedRawBytes,
                                chartSamplesByStream = emptyMap(),
                                lastPacketIssue = TIMER_REGRESSION_MESSAGE,
                                rejectionBreakdown = buildRejectionBreakdown(),
                                diagnosticEvents = listOf(
                                    createDiagnosticEvent(
                                        type = PacketDiagnosticType.REJECTED,
                                        message = buildRejectedPacketMessage(
                                            reason = TIMER_REGRESSION_MESSAGE,
                                            packetLength = validation.packet.bytes.size,
                                            counter = validation.packet.counter,
                                            timerMillis = validation.packet.timerMillis,
                                        ),
                                    ),
                                ),
                            )
                            continue
                        }

                        val diagnosticEvents = mutableListOf<PacketDiagnosticEvent>()
                        try {
                            packetFileStore.append(validation.packet.bytes)
                        } catch (exception: Exception) {
                            registerRejection(PACKET_WRITE_FAILURE_MESSAGE)
                            onError("Failed to append validated packet to output file", exception)
                            updates += PacketProcessingUpdate(
                                packetsReceived = packetStats.snapshot().packetsReceived,
                                packetsLost = packetStats.snapshot().packetsLost,
                                packetsRejected = rejectedPackets,
                                timerRegressionRejects = timerRegressionRejects,
                                fragmentsReceived = receivedFragmentCount,
                                rawBytesReceived = receivedRawBytes,
                                chartSamplesByStream = emptyMap(),
                                lastPacketIssue = PACKET_WRITE_FAILURE_MESSAGE,
                                rejectionBreakdown = buildRejectionBreakdown(),
                                diagnosticEvents = listOf(
                                    createDiagnosticEvent(
                                        type = PacketDiagnosticType.REJECTED,
                                        message = "Rejected packet reason=$PACKET_WRITE_FAILURE_MESSAGE, len=${validation.packet.bytes.size}, counter=${validation.packet.counter}, timer=${validation.packet.timerMillis}",
                                    ),
                                ),
                            )
                            continue
                        }

                        val previousTimerMillis = if (lastAcceptedTimerMillis == UNINITIALIZED_TIMER_MILLIS) {
                            null
                        } else {
                            lastAcceptedTimerMillis
                        }
                        lastAcceptedTimerMillis = validation.packet.timerMillis
                        val recordResult = packetStats.record(validation.packet.counter)
                        if (recordResult.gapCount > 0) {
                            diagnosticEvents += createDiagnosticEvent(
                                type = PacketDiagnosticType.GAP,
                                message = buildGapMessage(
                                    expectedCounter = recordResult.expectedCounter,
                                    actualCounter = recordResult.actualCounter,
                                    gapCount = recordResult.gapCount,
                                ),
                            )
                        }
                        diagnosticEvents += createDiagnosticEvent(
                            type = PacketDiagnosticType.ACCEPTED,
                            message = buildAcceptedPacketMessage(validation.packet),
                        )

                        updates += PacketProcessingUpdate(
                            packetsReceived = recordResult.snapshot.packetsReceived,
                            packetsLost = recordResult.snapshot.packetsLost,
                            packetsRejected = rejectedPackets,
                            timerRegressionRejects = timerRegressionRejects,
                            fragmentsReceived = receivedFragmentCount,
                            rawBytesReceived = receivedRawBytes,
                            chartSamplesByStream = buildChartSamplesByStream(
                                packet = validation.packet,
                                previousTimerMillis = if (recordResult.gapCount > 0) null else previousTimerMillis,
                                startsNewSegment = recordResult.gapCount > 0,
                            ),
                            rejectionBreakdown = buildRejectionBreakdown(),
                            diagnosticEvents = diagnosticEvents,
                        )
                    }

                    is PacketValidationResult.Rejected -> {
                        registerRejection(validation.reason.description)
                        val statsSnapshot = packetStats.snapshot()
                        updates += PacketProcessingUpdate(
                            packetsReceived = statsSnapshot.packetsReceived,
                            packetsLost = statsSnapshot.packetsLost,
                            packetsRejected = rejectedPackets,
                            timerRegressionRejects = timerRegressionRejects,
                            fragmentsReceived = receivedFragmentCount,
                            rawBytesReceived = receivedRawBytes,
                            chartSamplesByStream = emptyMap(),
                            lastPacketIssue = validation.reason.description,
                            rejectionBreakdown = buildRejectionBreakdown(),
                            diagnosticEvents = listOf(
                                createDiagnosticEvent(
                                    type = PacketDiagnosticType.REJECTED,
                                    message = buildRejectedPacketMessage(
                                        reason = validation.reason.description,
                                        packetLength = packet.bytes.size,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }

            updates
        }

        updates.forEach(onPacketProcessed)
    }

    private fun buildChartSamplesByStream(
        packet: com.example.myapplication.protocol.ValidatedPacket,
        previousTimerMillis: Long?,
        startsNewSegment: Boolean,
    ): Map<ChartStreamKey, List<ChartPoint>> {
        val samplesByStream = linkedMapOf<ChartStreamKey, MutableList<Float>>()

        packet.sensorBlocks.forEach { sensorBlock ->
            sensorBlock.channelSamples.forEachIndexed { channelIndex, channelSamples ->
                val sampledChannel = downsample(channelSamples)
                if (sampledChannel.isEmpty()) return@forEachIndexed

                val streamKey = ChartStreamKey(
                    sensorType = sensorBlock.sensorType,
                    channel = channelIndex + 1,
                )
                samplesByStream.getOrPut(streamKey) { mutableListOf() }.addAll(sampledChannel)
            }
        }

        return samplesByStream.mapValues { (_, values) ->
            buildTimedChartPoints(
                samples = values,
                currentTimerMillis = packet.timerMillis,
                previousTimerMillis = previousTimerMillis,
                startsNewSegment = startsNewSegment,
            )
        }
    }

    private fun buildTimedChartPoints(
        samples: List<Float>,
        currentTimerMillis: Long,
        previousTimerMillis: Long?,
        startsNewSegment: Boolean,
    ): List<ChartPoint> {
        if (samples.isEmpty()) return emptyList()

        val intervalMillis = when {
            previousTimerMillis == null -> null
            currentTimerMillis <= previousTimerMillis -> null
            else -> currentTimerMillis - previousTimerMillis
        }

        return samples.mapIndexed { index, value ->
            val pointTimeMillis = if (samples.size == 1) {
                currentTimerMillis
            } else if (intervalMillis == null) {
                currentTimerMillis
            } else {
                requireNotNull(previousTimerMillis) + (intervalMillis * (index + 1) / samples.size)
            }
            ChartPoint(
                timeMillis = pointTimeMillis,
                value = value,
                startsNewSegment = startsNewSegment && index == 0,
            )
        }
    }

    private fun downsample(samples: List<Float>): List<Float> {
        if (samples.size <= CHART_DOWNSAMPLE_THRESHOLD) return samples

        return samples.filterIndexed { index, _ -> index % CHART_DOWNSAMPLE_STEP == 0 }
    }

    private fun registerRejection(reason: String) {
        rejectedPackets++
        rejectionCounts[reason] = (rejectionCounts[reason] ?: 0L) + 1L
    }

    private fun buildRejectionBreakdown(): String? {
        if (rejectionCounts.isEmpty()) return null

        return rejectionCounts.entries.joinToString(separator = " | ") { (reason, count) ->
            "$reason: $count"
        }
    }

    private fun nextDiagnosticId(): Long {
        val id = nextDiagnosticEventId
        nextDiagnosticEventId++
        return id
    }

    private fun createDiagnosticEvent(
        type: PacketDiagnosticType,
        message: String,
    ): PacketDiagnosticEvent {
        val event = PacketDiagnosticEvent(
            id = nextDiagnosticId(),
            type = type,
            message = message,
        )
        try {
            diagnosticLogFileStore.appendEvent(
                eventId = event.id,
                type = event.type.name,
                message = event.message,
            )
        } catch (exception: Exception) {
            onError("Failed to persist diagnostic log event", exception)
        }
        return event
    }

    private fun buildAcceptedPacketMessage(packet: com.example.myapplication.protocol.ValidatedPacket): String {
        return "Accepted packet counter=${packet.counter}, timer=${packet.timerMillis}, len=${packet.bytes.size}, meas=${packet.measurementCount}"
    }

    private fun buildGapMessage(
        expectedCounter: Long?,
        actualCounter: Long?,
        gapCount: Long,
    ): String {
        return "Gap detected: expected=${expectedCounter ?: "?"}, actual=${actualCounter ?: "?"}, missing=$gapCount"
    }

    private fun buildRejectedPacketMessage(
        reason: String,
        packetLength: Int,
        counter: Long? = null,
        timerMillis: Long? = null,
    ): String {
        val details = buildList {
            add("reason=$reason")
            add("len=$packetLength")
            if (counter != null) add("counter=$counter")
            if (timerMillis != null) add("timer=$timerMillis")
        }
        return "Rejected packet ${details.joinToString(separator = ", ")}"
    }

    private companion object {
        const val MAX_PENDING_FRAGMENTS = 512
        const val CHART_DOWNSAMPLE_THRESHOLD = 500
        const val CHART_DOWNSAMPLE_STEP = 4
        const val QUEUE_POLL_TIMEOUT_MS = 100L
        const val WORKER_THREAD_NAME = "packet-capture-processor"
        const val UNINITIALIZED_TIMER_MILLIS = -1L
        const val TIMER_REGRESSION_MESSAGE = "Packet timer regressed"
        const val PACKET_WRITE_FAILURE_MESSAGE = "Failed to write accepted packet"
    }

    private data class QueuedFragment(
        val sessionId: Long,
        val bytes: ByteArray,
    )
}
