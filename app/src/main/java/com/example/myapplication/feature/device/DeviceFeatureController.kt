package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.storage.FileShareIntentFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DeviceFeatureController(
    private val bleSessionController: BleSessionController,
    private val packetCaptureController: PacketCaptureController,
    private val fileShareIntentFactory: FileShareIntentFactory,
    private val packetReplayController: PacketReplayController? = null,
    private val uiStateHolder: DeviceUiStateHolder = DeviceUiStateHolder(),
) : AutoCloseable {
    private var pendingTransportDiagnostics = mutableListOf<String>()
    private var awaitingCaptureReady = false
    private var exportSnapshot: SessionExportSnapshot? = null

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
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = true
        invalidateExportSnapshot()
        packetCaptureController.stopCapture()
        bleSessionController.stopScanning()
        bleSessionController.connect(address)
    }

    fun onDisconnectRequested() {
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        invalidateExportSnapshot()
        uiStateHolder.stopCaptureSession()
        bleSessionController.disconnect()
    }

    fun onSensorSelected(sensorType: Int) {
        uiStateHolder.onSensorTypeSelected(sensorType)
        syncProcessorSelection()
    }

    fun onTransportProfileSelected(profile: BleTransportProfile) {
        uiStateHolder.onTransportProfileSelected(profile)
        bleSessionController.updateTransportProfile(profile)

        if (uiState.showCaptureUi || awaitingCaptureReady) {
            packetCaptureController.recordDiagnosticEvent(
                type = PacketDiagnosticType.INFO,
                message = "BLE transport profile selected: ${profile.title}. Changes apply on next connection.",
            )
        }
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
        invalidateExportSnapshot()
        bleSessionController.close()
        packetCaptureController.close()
    }

    fun onCaptureReady() {
        resetCaptureSession()
        pendingTransportDiagnostics.forEach { message ->
            packetCaptureController.recordDiagnosticEvent(
                type = PacketDiagnosticType.INFO,
                message = message,
            )
        }
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
    }

    fun onTransportDiagnostic(message: String) {
        if (awaitingCaptureReady) {
            pendingTransportDiagnostics += message
            return
        }

        packetCaptureController.recordDiagnosticEvent(
            type = PacketDiagnosticType.INFO,
            message = message,
        )
    }

    fun startReplay(fileBytes: ByteArray) {
        if (packetReplayController == null) {
            uiStateHolder.showError("Replay is unavailable")
            return
        }

        packetReplayController.stop()
        bleSessionController.close()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        invalidateExportSnapshot()
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
        invalidateExportSnapshot()
        packetCaptureController.resetSession()
        uiStateHolder.resetCaptureSession()
    }

    private fun createShareIntent(
        context: Context,
        file: File?,
    ): Intent? {
        if (file == null || !file.exists()) return null

        return try {
            val snapshot = ensureExportSnapshot()
            val snapshotFile = when (file.absolutePath) {
                snapshot.packetSourcePath -> snapshot.packetSnapshot
                snapshot.rawSourcePath -> snapshot.rawSnapshot
                snapshot.logSourcePath -> snapshot.logSnapshot
                else -> null
            } ?: return null

            fileShareIntentFactory.createChooserIntent(context, snapshotFile)
        } catch (exception: Exception) {
            uiStateHolder.showError("Failed to share file")
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    private fun ensureExportSnapshot(): SessionExportSnapshot {
        val currentPacketFile = packetCaptureController.currentPacketFile()?.takeIf(File::exists)
        val currentRawFile = packetCaptureController.currentRawFile()?.takeIf(File::exists)
        val currentLogFile = packetCaptureController.currentLogFile()?.takeIf(File::exists)

        val existingSnapshot = exportSnapshot
        if (
            existingSnapshot != null &&
            existingSnapshot.packetSourcePath == currentPacketFile?.absolutePath &&
            existingSnapshot.rawSourcePath == currentRawFile?.absolutePath &&
            existingSnapshot.logSourcePath == currentLogFile?.absolutePath
        ) {
            return existingSnapshot
        }

        invalidateExportSnapshot()
        packetCaptureController.flush()
        val snapshot = SessionExportSnapshot(
            packetSourcePath = currentPacketFile?.absolutePath,
            rawSourcePath = currentRawFile?.absolutePath,
            logSourcePath = currentLogFile?.absolutePath,
            packetSnapshot = currentPacketFile?.let { createSnapshotCopy(it, "packet") },
            rawSnapshot = currentRawFile?.let { createSnapshotCopy(it, "raw") },
            logSnapshot = currentLogFile?.let { createSnapshotCopy(it, "log") },
        )
        exportSnapshot = snapshot
        return snapshot
    }

    private fun invalidateExportSnapshot() {
        val snapshot = exportSnapshot ?: return
        listOf(snapshot.packetSnapshot, snapshot.rawSnapshot, snapshot.logSnapshot)
            .filterNotNull()
            .forEach { file ->
                try {
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (exception: Exception) {
                    Log.w("BLE_SHARE", "Failed to delete snapshot file ${file.absolutePath}", exception)
                }
            }
        exportSnapshot = null
    }

    private fun createSnapshotCopy(
        source: File,
        suffix: String,
    ): File {
        val target = File(
            source.parentFile,
            "${source.nameWithoutExtension}_snapshot_$suffix.${source.extension.ifBlank { "bin" }}",
        )
        Files.copy(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return target
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

private data class SessionExportSnapshot(
    val packetSourcePath: String?,
    val rawSourcePath: String?,
    val logSourcePath: String?,
    val packetSnapshot: File?,
    val rawSnapshot: File?,
    val logSnapshot: File?,
)
