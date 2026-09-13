package com.example.myapplication.feature.device

import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiStateHolderTest {
    @Test
    fun chartWindowPresetLabels_useRawTimerUnits() {
        assertEquals(
            listOf("30k", "60k", "300k", "900k"),
            ChartWindowPreset.entries.map { it.label },
        )
    }

    @Test
    fun addFoundDevice_ignoresDuplicateAddresses() {
        val holder = DeviceUiStateHolder()

        holder.addFoundDevice(BleDevice(name = "One", address = "AA:BB"))
        holder.addFoundDevice(BleDevice(name = "Two", address = "AA:BB"))

        assertEquals(1, holder.uiState.foundDevices.size)
        assertEquals("One", holder.uiState.foundDevices.single().name)
    }

    @Test
    fun sensorAndChannelSelection_reusesPreservedStreamHistory() {
        val holder = DeviceUiStateHolder()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 1_000L, value = 10f),
                    ),
                    ChartStreamKey(sensorType = 2, channel = 3) to listOf(
                        ChartPoint(timeMillis = 1_000L, value = 30f),
                    ),
                    ChartStreamKey(sensorType = 4, channel = 1) to listOf(
                        ChartPoint(timeMillis = 1_000L, value = 40f),
                    ),
                ),
            ),
        )

        assertEquals(listOf(10f), holder.uiState.chart.points.map { it.value })

        holder.onChannelSelected(3)
        assertEquals(listOf(30f), holder.uiState.chart.points.map { it.value })

        holder.onSensorTypeSelected(4)
        assertEquals(1, holder.uiState.selectedChannel)
        assertEquals(listOf(40f), holder.uiState.chart.points.map { it.value })
    }

    @Test
    fun chartWindowAndPan_updateViewportState() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 1f),
                        ChartPoint(timeMillis = 30_000L, value = 2f),
                        ChartPoint(timeMillis = 60_000L, value = 3f),
                        ChartPoint(timeMillis = 90_000L, value = 4f),
                    ),
                ),
            ),
        )

        assertEquals(60_000L, holder.uiState.chart.viewportStartMillis)
        assertEquals(90_000L, holder.uiState.chart.viewportEndMillis)
        assertTrue(holder.uiState.chart.isFollowingLive)

        holder.onFollowLiveChanged(enabled = false)
        holder.panChartLeft()
        assertTrue(!holder.uiState.chart.isFollowingLive)
        assertEquals(82_500L, holder.uiState.chart.viewportEndMillis)

        holder.onChartWindowSelected(ChartWindowPreset.SIXTY_SECONDS)
        assertEquals(60_000L, holder.uiState.chart.windowPreset.durationMillis)
        assertEquals(22_500L, holder.uiState.chart.viewportStartMillis)

        holder.jumpToLive()
        assertTrue(holder.uiState.chart.isFollowingLive)
        assertEquals(90_000L, holder.uiState.chart.viewportEndMillis)
    }

    @Test
    fun chartHistory_isTrimmedToFifteenMinutes() {
        val holder = DeviceUiStateHolder()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 1f),
                        ChartPoint(timeMillis = 100_000L, value = 2f),
                        ChartPoint(timeMillis = 1_000_000L, value = 3f),
                    ),
                ),
            ),
        )

        assertEquals(100_000L, holder.uiState.chart.sessionStartMillis)
        assertEquals(listOf(100_000L, 1_000_000L), holder.uiState.chart.points.map { it.timeMillis })
    }

    @Test
    fun tabSelection_isUiOnlyAndPreservesChartState() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
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
            ),
        )

        holder.onTabSelected(DeviceCaptureTab.CHART)

        assertEquals(DeviceCaptureTab.CHART, holder.uiState.selectedTab)
        assertEquals(listOf(10f), holder.uiState.chart.points.map { it.value })
    }

    @Test
    fun yZoomAndGesturePan_updateStateWithoutChangingWindow() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 1f),
                        ChartPoint(timeMillis = 30_000L, value = 2f),
                        ChartPoint(timeMillis = 60_000L, value = 3f),
                        ChartPoint(timeMillis = 90_000L, value = 4f),
                    ),
                ),
            ),
        )

        assertEquals(32_768f, holder.uiState.chart.yAxisAbsRange, 0.01f)

        holder.zoomInChart()
        assertTrue(holder.uiState.chart.canZoomOut)
        assertEquals(16_384f, holder.uiState.chart.yAxisAbsRange, 0.01f)
        assertEquals(60_000L, holder.uiState.chart.viewportStartMillis)
        assertEquals(90_000L, holder.uiState.chart.viewportEndMillis)

        holder.onFollowLiveChanged(enabled = false)
        holder.panChartByFraction(-0.5f)
        assertEquals(45_000L, holder.uiState.chart.viewportStartMillis)
        assertEquals(75_000L, holder.uiState.chart.viewportEndMillis)

        holder.resetChartZoom()
        assertEquals(32_768f, holder.uiState.chart.yAxisAbsRange, 0.01f)
        assertEquals(45_000L, holder.uiState.chart.viewportStartMillis)
        assertEquals(75_000L, holder.uiState.chart.viewportEndMillis)

        holder.jumpToLive()
        assertEquals(60_000L, holder.uiState.chart.viewportStartMillis)
        assertEquals(90_000L, holder.uiState.chart.viewportEndMillis)
    }

    @Test
    fun zoomInChart_reducesYAxisRangeEvenForShortSessions() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 1f),
                        ChartPoint(timeMillis = 5_000L, value = 2f),
                        ChartPoint(timeMillis = 10_000L, value = 3f),
                    ),
                ),
            ),
        )

        assertEquals(0L, holder.uiState.chart.viewportStartMillis)
        assertEquals(10_000L, holder.uiState.chart.viewportEndMillis)

        holder.zoomInChart()

        assertEquals(0L, holder.uiState.chart.viewportStartMillis)
        assertEquals(10_000L, holder.uiState.chart.viewportEndMillis)
        assertEquals(16_384f, holder.uiState.chart.yAxisAbsRange, 0.01f)
    }

    @Test
    fun zoomInChart_keepsOffsetSignalCenteredInVisibleYAxisRange() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 15_000f),
                        ChartPoint(timeMillis = 5_000L, value = 16_000f),
                        ChartPoint(timeMillis = 10_000L, value = 17_000f),
                    ),
                ),
            ),
        )

        assertEquals(16_000f, holder.uiState.chart.yAxisCenter, 0.01f)

        holder.zoomInChart()

        assertEquals(16_000f, holder.uiState.chart.yAxisCenter, 0.01f)
        assertEquals(16_384f, holder.uiState.chart.yAxisAbsRange, 0.01f)
    }

    @Test
    fun applyPacketUpdate_doesNotRecentreYAxisWhileZoomed() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 15_000f),
                        ChartPoint(timeMillis = 5_000L, value = 16_000f),
                        ChartPoint(timeMillis = 10_000L, value = 17_000f),
                    ),
                ),
            ),
        )

        holder.zoomInChart()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 2,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 15_000L, value = 18_000f),
                    ),
                ),
            ),
        )

        assertEquals(16_000f, holder.uiState.chart.yAxisCenter, 0.01f)
        assertEquals(16_384f, holder.uiState.chart.yAxisAbsRange, 0.01f)
    }

    @Test
    fun panChartByFraction_doesNotCrashWhenSessionSpanIsShorterThanPreset() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 0L, value = 1f),
                        ChartPoint(timeMillis = 5_000L, value = 2f),
                        ChartPoint(timeMillis = 10_000L, value = 3f),
                    ),
                ),
            ),
        )

        holder.panChartByFraction(deltaFraction = -0.2f)

        assertTrue(holder.uiState.chart.isFollowingLive)
        assertEquals(0L, holder.uiState.chart.viewportStartMillis)
        assertEquals(10_000L, holder.uiState.chart.viewportEndMillis)
    }

    @Test
    fun onSessionStateChanged_disconnectedKeepsLastSessionSummary() {
        val holder = DeviceUiStateHolder()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 5,
                packetsLost = 2,
                packetsRejected = 1,
                timerRegressionRejects = 1,
                chartSamplesByStream = mapOf(
                    ChartStreamKey(sensorType = 2, channel = 1) to listOf(
                        ChartPoint(timeMillis = 2_000L, value = 1f),
                    ),
                ),
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(
                        id = 2L,
                        type = PacketDiagnosticType.REJECTED,
                        message = "Rejected packet reason=test",
                    ),
                ),
            ),
        )
        holder.showError("boom")

        holder.onSessionStateChanged(BleSessionState.CONNECTED)
        holder.onSessionStateChanged(BleSessionState.DISCONNECTED)

        assertEquals(5L, holder.uiState.packetsReceived)
        assertEquals(2L, holder.uiState.packetsLost)
        assertEquals(1L, holder.uiState.packetsRejected)
        assertEquals(1L, holder.uiState.timerRegressionRejects)
        assertEquals(1, holder.uiState.chart.points.size)
        assertEquals(2_000L, holder.uiState.chart.latestPointMillis)
        assertEquals(1, holder.uiState.diagnosticEvents.size)
        assertNull(holder.uiState.errorMessage)
    }

    @Test
    fun onSessionStateChanged_ignoresBleTransitionsWhileReplayIsRunning() {
        val holder = DeviceUiStateHolder()

        holder.startReplaySession()
        holder.onSessionStateChanged(BleSessionState.DISCONNECTED)

        assertTrue(holder.uiState.showCaptureUi)
        assertTrue(holder.uiState.isReplayRunning)
    }

    @Test
    fun replayPreparation_preservesCurrentSessionUntilReplayStarts() {
        val holder = DeviceUiStateHolder()
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 3,
                packetsLost = 1,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = emptyMap(),
            ),
        )

        holder.startReplayPreparation()
        holder.onSessionStateChanged(BleSessionState.DISCONNECTED)

        assertTrue(holder.uiState.isReplayPreparing)
        assertEquals(3L, holder.uiState.packetsReceived)
        assertEquals(1L, holder.uiState.packetsLost)

        holder.cancelReplayPreparation()

        assertFalse(holder.uiState.isReplayPreparing)
        assertEquals(3L, holder.uiState.packetsReceived)
        assertEquals(1L, holder.uiState.packetsLost)
    }

    @Test
    fun resetCaptureSession_clearsDiagnostics() {
        val holder = DeviceUiStateHolder()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamplesByStream = emptyMap(),
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(
                        id = 7L,
                        type = PacketDiagnosticType.INFO,
                        message = "BLE MTU changed: mtu=247 status=0",
                    ),
                ),
            ),
        )

        holder.resetCaptureSession()

        assertTrue(holder.uiState.diagnosticEvents.isEmpty())
        assertEquals(0L, holder.uiState.packetsReceived)
        assertTrue(holder.uiState.showCaptureUi)
    }

    @Test
    fun exportProgress_updatesAndClearsUiState() {
        val holder = DeviceUiStateHolder()

        holder.showExportProgress(ExportPhase.PREPARING_SNAPSHOTS)
        assertEquals(ExportPhase.PREPARING_SNAPSHOTS, holder.uiState.exportPhase)

        holder.showExportProgress(ExportPhase.GENERATING_CSV)
        assertEquals(ExportPhase.GENERATING_CSV, holder.uiState.exportPhase)

        holder.clearExportProgress()
        assertNull(holder.uiState.exportPhase)
    }

    @Test
    fun videoDegraded_keepsSessionStoppableAndExposesFailure() {
        val holder = DeviceUiStateHolder()

        holder.videoStarting()
        holder.videoDegraded("camera failed")

        assertEquals(VideoCaptureState.DEGRADED, holder.uiState.videoState)
        assertEquals("camera failed", holder.uiState.errorMessage)
    }
}
