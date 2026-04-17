package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.storage.FileShareIntentFactory
import java.io.File

class DeviceFeatureController(
    private val bleSessionController: BleSessionController,
    private val packetCaptureController: PacketCaptureController,
    private val fileShareIntentFactory: FileShareIntentFactory,
    private val packetReplayController: PacketReplayController? = null,
    private val uiStateHolder: DeviceUiStateHolder = DeviceUiStateHolder(),
) : AutoCloseable {
    val uiState: DeviceUiState
        get() = uiStateHolder.uiState

    fun onStartScanRequested(requestPermissions: () -> Unit) {
        uiStateHolder.prepareForScan()
        requestPermissions()
    }

    fun onPermissionsResult(allGranted: Boolean) {
        if (allGranted) {
            bleSessionController.startScanning()
        } else {
            uiStateHolder.showError(PERMISSION_REQUIRED_MESSAGE)
        }
    }

    fun onConnectRequested(address: String) {
        packetReplayController?.stop()
        bleSessionController.stopScanning()
        bleSessionController.connect(address)
    }

    fun onDisconnectRequested() {
        packetReplayController?.stop()
        uiStateHolder.stopCaptureSession()
        bleSessionController.disconnect()
    }

    fun onSensorSelected(sensorType: Int) {
        uiStateHolder.onSensorTypeSelected(sensorType)
        syncProcessorSelection()
    }

    fun onChannelSelected(channel: Int) {
        uiStateHolder.onChannelSelected(channel)
        syncProcessorSelection()
    }

    fun onTabSelected(tab: DeviceCaptureTab) {
        uiStateHolder.onTabSelected(tab)
    }

    fun onChartWindowSelected(windowPreset: ChartWindowPreset) {
        uiStateHolder.onChartWindowSelected(windowPreset)
    }

    fun onFollowLiveChanged(enabled: Boolean) {
        uiStateHolder.onFollowLiveChanged(enabled)
    }

    fun onChartPanLeftRequested() {
        uiStateHolder.panChartLeft()
    }

    fun onChartPanRightRequested() {
        uiStateHolder.panChartRight()
    }

    fun onChartJumpToLiveRequested() {
        uiStateHolder.jumpToLive()
    }

    fun onChartZoomInRequested() {
        uiStateHolder.zoomInChart()
    }

    fun onChartZoomOutRequested() {
        uiStateHolder.zoomOutChart()
    }

    fun onChartZoomResetRequested() {
        uiStateHolder.resetChartZoom()
    }

    fun onChartPanned(deltaFraction: Float) {
        uiStateHolder.panChartByFraction(deltaFraction)
    }

    fun onChartZoomChanged(
        scaleFactor: Float,
        anchorFractionY: Float = 0.5f,
    ) {
        uiStateHolder.zoomChart(scaleFactor, anchorFractionY)
    }

    fun onPause() {
        packetCaptureController.flush()
    }

    fun createPacketShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentPacketFile(),
    )

    fun createRawShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentRawFile(),
    )

    fun createLogShareIntent(context: Context): Intent? = createShareIntent(
        context = context,
        file = packetCaptureController.currentLogFile(),
    )

    override fun close() {
        packetReplayController?.close()
        bleSessionController.close()
        packetCaptureController.close()
    }

    fun onCaptureReady() {
        resetCaptureSession()
    }

    fun startReplay(fileBytes: ByteArray) {
        if (packetReplayController == null) {
            uiStateHolder.showError("Replay is unavailable")
            return
        }

        packetReplayController.stop()
        bleSessionController.close()
        packetCaptureController.resetSession()
        uiStateHolder.startReplaySession()
        packetReplayController.startReplay(fileBytes)
    }

    fun onReplayCompleted() {
        uiStateHolder.finishReplaySession()
    }

    fun onReplayStopped() {
        uiStateHolder.stopCaptureSession()
    }

    private fun resetCaptureSession() {
        packetCaptureController.resetSession()
        uiStateHolder.resetCaptureSession()
    }

    private fun createShareIntent(
        context: Context,
        file: File?,
    ): Intent? {
        if (file == null || !file.exists()) return null

        return try {
            packetCaptureController.flush()
            fileShareIntentFactory.createChooserIntent(context, file)
        } catch (exception: Exception) {
            uiStateHolder.showError("Failed to share file")
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    private fun syncProcessorSelection() {
        packetCaptureController.updateSelection(
            sensorType = uiStateHolder.uiState.selectedSensorType,
            channel = uiStateHolder.uiState.selectedChannel,
        )
    }

    companion object {
        const val PERMISSION_REQUIRED_MESSAGE = "Нужны разрешения!"
    }
}
