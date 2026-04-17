package com.example.myapplication.feature.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionState

data class DeviceListItem(
    val name: String,
    val address: String,
)

data class DeviceUiState(
    val points: List<Float> = emptyList(),
    val foundDevices: List<DeviceListItem> = emptyList(),
    val selectedSensorType: Int = DEFAULT_SENSOR_TYPE_VALUE,
    val selectedChannel: Int = DEFAULT_CHANNEL_VALUE,
    val showCaptureUi: Boolean = false,
    val isReplayRunning: Boolean = false,
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val packetsReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val packetsRejected: Long = 0L,
    val timerRegressionRejects: Long = 0L,
    val fragmentsReceived: Long = 0L,
    val rawBytesReceived: Long = 0L,
    val lastPacketIssue: String? = null,
    val rejectionBreakdown: String? = null,
    val diagnosticEvents: List<PacketDiagnosticEvent> = emptyList(),
    val errorMessage: String? = null,
)

class DeviceUiStateHolder {
    var uiState by mutableStateOf(DeviceUiState())
        private set

    fun prepareForScan() {
        uiState = uiState.copy(
            foundDevices = emptyList(),
            errorMessage = null,
        )
    }

    fun addFoundDevice(device: BleDevice) {
        if (uiState.foundDevices.any { it.address == device.address }) return

        uiState = uiState.copy(
            foundDevices = uiState.foundDevices + DeviceListItem(
                name = device.name,
                address = device.address,
            ),
        )
    }

    fun onSessionStateChanged(state: BleSessionState) {
        if (uiState.isReplayRunning) return

        val updatedState = uiState.copy(
            showCaptureUi = uiState.showCaptureUi || state == BleSessionState.CONNECTED,
            isScanning = state == BleSessionState.SCANNING,
            isConnected = state == BleSessionState.CONNECTED,
            errorMessage = when (state) {
                BleSessionState.SCANNING,
                BleSessionState.CONNECTED,
                -> null

                else -> uiState.errorMessage
            },
        )

        uiState = if (state == BleSessionState.DISCONNECTED) {
            updatedState.copy(
                showCaptureUi = false,
                isReplayRunning = false,
                points = emptyList(),
                packetsReceived = 0L,
                packetsLost = 0L,
                packetsRejected = 0L,
                timerRegressionRejects = 0L,
                fragmentsReceived = 0L,
                rawBytesReceived = 0L,
                lastPacketIssue = null,
                rejectionBreakdown = null,
                diagnosticEvents = emptyList(),
                errorMessage = null,
            )
        } else {
            updatedState
        }
    }

    fun applyPacketUpdate(update: PacketProcessingUpdate) {
        val nextPoints = (uiState.points + update.chartSamples).takeLast(MAX_CHART_POINTS)
        uiState = uiState.copy(
            packetsReceived = update.packetsReceived,
            packetsLost = update.packetsLost,
            packetsRejected = update.packetsRejected,
            timerRegressionRejects = update.timerRegressionRejects,
            fragmentsReceived = update.fragmentsReceived,
            rawBytesReceived = update.rawBytesReceived,
            points = nextPoints,
            lastPacketIssue = update.lastPacketIssue ?: uiState.lastPacketIssue,
            rejectionBreakdown = update.rejectionBreakdown ?: uiState.rejectionBreakdown,
            diagnosticEvents = (update.diagnosticEvents + uiState.diagnosticEvents).take(MAX_DIAGNOSTIC_EVENTS),
        )
    }

    fun startReplaySession() {
        uiState = uiState.copy(
            showCaptureUi = true,
            isReplayRunning = true,
            isConnected = false,
            isScanning = false,
            points = emptyList(),
            packetsReceived = 0L,
            packetsLost = 0L,
            packetsRejected = 0L,
            timerRegressionRejects = 0L,
            fragmentsReceived = 0L,
            rawBytesReceived = 0L,
            lastPacketIssue = null,
            rejectionBreakdown = null,
            diagnosticEvents = emptyList(),
            errorMessage = null,
        )
    }

    fun finishReplaySession() {
        uiState = uiState.copy(isReplayRunning = false)
    }

    fun stopCaptureSession() {
        uiState = uiState.copy(
            showCaptureUi = false,
            isReplayRunning = false,
            isConnected = false,
            isScanning = false,
            points = emptyList(),
            packetsReceived = 0L,
            packetsLost = 0L,
            packetsRejected = 0L,
            timerRegressionRejects = 0L,
            fragmentsReceived = 0L,
            rawBytesReceived = 0L,
            lastPacketIssue = null,
            rejectionBreakdown = null,
            diagnosticEvents = emptyList(),
        )
    }

    fun onSensorTypeSelected(sensorType: Int) {
        uiState = uiState.copy(
            selectedSensorType = sensorType,
            selectedChannel = DEFAULT_CHANNEL_VALUE,
            points = emptyList(),
        )
    }

    fun onChannelSelected(channel: Int) {
        uiState = uiState.copy(
            selectedChannel = channel,
            points = emptyList(),
        )
    }

    fun resetCaptureSession() {
        uiState = uiState.copy(
            showCaptureUi = true,
            points = emptyList(),
            packetsReceived = 0L,
            packetsLost = 0L,
            packetsRejected = 0L,
            timerRegressionRejects = 0L,
            fragmentsReceived = 0L,
            rawBytesReceived = 0L,
            lastPacketIssue = null,
            rejectionBreakdown = null,
            diagnosticEvents = emptyList(),
        )
    }

    fun showError(message: String) {
        uiState = uiState.copy(errorMessage = message)
    }

    private companion object {
        const val MAX_CHART_POINTS = 1_500
        const val MAX_DIAGNOSTIC_EVENTS = 40
    }
}

private const val DEFAULT_SENSOR_TYPE_VALUE = 2
private const val DEFAULT_CHANNEL_VALUE = 1
