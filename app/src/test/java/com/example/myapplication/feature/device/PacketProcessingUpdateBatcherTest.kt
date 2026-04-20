package com.example.myapplication.feature.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketProcessingUpdateBatcherTest {
    @Test
    fun submit_mergesPendingUpdatesBeforeDispatch() {
        val scheduler = FakeScheduler()
        val dispatchedUpdates = mutableListOf<Pair<Long, PacketProcessingUpdate>>()
        val batcher = PacketProcessingUpdateBatcher(
            dispatchIntervalMillis = 100L,
            schedule = scheduler::schedule,
            cancel = scheduler::cancel,
            dispatch = { generation, update -> dispatchedUpdates += generation to update },
        )

        batcher.submit(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 1_000L, value = 10f),
                    ),
                ),
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(id = 1L, type = PacketDiagnosticType.INFO, message = "first"),
                ),
            ),
        )
        batcher.submit(
            PacketProcessingUpdate(
                packetsReceived = 2,
                packetsLost = 1,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 1_100L, value = 20f),
                    ),
                ),
                lastPacketIssue = "gap",
                rejectionBreakdown = "gap=1",
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(id = 2L, type = PacketDiagnosticType.GAP, message = "second"),
                ),
            ),
        )

        scheduler.runScheduled()

        assertEquals(1, dispatchedUpdates.size)
        val (generation, update) = dispatchedUpdates.single()
        assertTrue(batcher.isGenerationCurrent(generation))
        assertEquals(2L, update.packetsReceived)
        assertEquals(1L, update.packetsLost)
        assertEquals("gap", update.lastPacketIssue)
        assertEquals("gap=1", update.rejectionBreakdown)
        assertEquals(2, requireNotNull(update.chartSamplesByStream[ChartStreamKey(sensorType = 2, channel = 1)]).size)
        assertEquals(listOf("first", "second"), update.diagnosticEvents.map { it.message })
    }

    @Test
    fun clearPending_discardsScheduledUpdates() {
        val scheduler = FakeScheduler()
        val dispatchedUpdates = mutableListOf<Pair<Long, PacketProcessingUpdate>>()
        val batcher = PacketProcessingUpdateBatcher(
            dispatchIntervalMillis = 100L,
            schedule = scheduler::schedule,
            cancel = scheduler::cancel,
            dispatch = { generation, update -> dispatchedUpdates += generation to update },
        )

        batcher.submit(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = emptyMap(),
            ),
        )
        batcher.clearPending()
        scheduler.runScheduled()

        assertTrue(dispatchedUpdates.isEmpty())
    }

    @Test
    fun clearPending_invalidatesPreviouslyDispatchedGeneration() {
        val scheduler = FakeScheduler()
        val dispatchedGenerations = mutableListOf<Long>()
        val batcher = PacketProcessingUpdateBatcher(
            dispatchIntervalMillis = 100L,
            schedule = scheduler::schedule,
            cancel = scheduler::cancel,
            dispatch = { generation, _ -> dispatchedGenerations += generation },
        )

        batcher.submit(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = emptyMap(),
            ),
        )
        scheduler.runScheduled()
        val dispatchedGeneration = dispatchedGenerations.single()

        batcher.clearPending()

        assertTrue(!batcher.isGenerationCurrent(dispatchedGeneration))
    }

    @Test
    fun batchingController_stopCapture_dropsUpdateEmittedDuringDelegateStop() {
        val scheduler = FakeScheduler()
        val dispatchedUpdates = mutableListOf<Pair<Long, PacketProcessingUpdate>>()
        val batcher = PacketProcessingUpdateBatcher(
            dispatchIntervalMillis = 100L,
            schedule = scheduler::schedule,
            cancel = scheduler::cancel,
            dispatch = { generation, update -> dispatchedUpdates += generation to update },
        )
        val controller = BatchingPacketCaptureController(
            delegate = StopEmittingPacketCaptureController(
                onStopCapture = {
                    batcher.submit(
                        PacketProcessingUpdate(
                            packetsReceived = 1,
                            packetsLost = 0,
                            packetsRejected = 0,
                            timerRegressionRejects = 0,
                            chartSamplesByStream = emptyMap(),
                        ),
                    )
                },
            ),
            batcher = batcher,
        )

        controller.stopCapture()
        scheduler.runScheduled()

        assertTrue(dispatchedUpdates.isEmpty())
    }
}

private class FakeScheduler {
    private var runnable: Runnable? = null

    fun schedule(runnable: Runnable, delayMillis: Long) {
        require(delayMillis >= 0L)
        this.runnable = runnable
    }

    fun cancel(runnable: Runnable) {
        if (this.runnable === runnable) {
            this.runnable = null
        }
    }

    fun runScheduled() {
        val scheduledRunnable = runnable ?: return
        runnable = null
        scheduledRunnable.run()
    }
}

private class StopEmittingPacketCaptureController(
    private val onStopCapture: () -> Unit = {},
) : PacketCaptureController {
    override fun submit(packetFragment: ByteArray): PacketSubmitResult = PacketSubmitResult.ACCEPTED

    override fun recordDiagnosticEvent(type: PacketDiagnosticType, message: String) = Unit

    override fun stopCapture() {
        onStopCapture()
    }

    override fun updateSelection(sensorType: Int, channel: Int) = Unit

    override fun resetSession() = Unit

    override fun flush() = Unit

    override fun currentFile() = null

    override fun close() = Unit
}
