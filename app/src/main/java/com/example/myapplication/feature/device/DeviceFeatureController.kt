package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myapplication.R
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.localization.EnglishTextResolver
import com.example.myapplication.localization.TextResolver
import com.example.myapplication.storage.BleSessionCsvExporter
import com.example.myapplication.storage.FileShareIntentFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DeviceFeatureController(
    private val bleSessionController: BleSessionController,
    private val packetCaptureController: PacketCaptureController,
    private val fileShareIntentFactory: FileShareIntentFactory,
    private val appTextResolver: TextResolver = EnglishTextResolver,
    private val packetReplayController: PacketReplayController? = null,
    private val uiStateHolder: DeviceUiStateHolder = DeviceUiStateHolder(),
    private val sessionCsvExporter: BleSessionCsvExporter = BleSessionCsvExporter(),
    private val wallClockMillisProvider: () -> Long = System::currentTimeMillis,
    private val runOnUiThread: (() -> Unit) -> Unit = { action -> action() },
) : AutoCloseable {
    private val exportLock = Any()
    private var pendingTransportDiagnostics = mutableListOf<String>()
    private var awaitingCaptureReady = false
    private var exportSnapshot: SessionExportSnapshot? = null
    private var exportSessionStartMillis: Long? = null
    private var exportEpoch = 0L

    val uiState: DeviceUiState
        get() = uiStateHolder.uiState

    fun onStartScanRequested(
        hasPermissions: () -> Boolean,
        requestPermissions: () -> Unit,
    ) {
        uiStateHolder.prepareForScan()
        if (hasPermissions()) {
            bleSessionController.startScanning()
        } else {
            requestPermissions()
        }
    }

    fun onPermissionsResult(allGranted: Boolean) {
        if (allGranted) {
            bleSessionController.startScanning()
        } else {
            uiStateHolder.showError(appTextResolver.getString(R.string.permissions_required))
        }
    }

    fun onConnectRequested(address: String) {
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = true
        val staleSnapshot = synchronized(exportLock) {
            invalidateExportSnapshotLocked()
        }
        deleteSnapshotFiles(staleSnapshot)
        packetCaptureController.stopCapture()
        bleSessionController.stopScanning()
        bleSessionController.connect(address)
    }

    fun onDisconnectRequested() {
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        val staleSnapshot = synchronized(exportLock) {
            invalidateExportSnapshotLocked()
        }
        deleteSnapshotFiles(staleSnapshot)
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
                message = appTextResolver.getString(
                    R.string.transport_profile_selected_next_connection,
                    appTextResolver.getString(profile.titleRes),
                ),
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

    fun createCsvShareIntent(context: Context): Intent? {
        return try {
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val csvSnapshot = ensureCsvSnapshot(snapshot) ?: return null
            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, csvSnapshot)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed))
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    fun createAllFilesShareIntent(context: Context): Intent? {
        return try {
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val files = buildAvailableExportFiles(snapshot)
            if (files.isEmpty()) return null
            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, files)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed))
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    fun canSharePacketFile(): Boolean = packetCaptureController.currentPacketFile()?.exists() == true

    fun canShareRawFile(): Boolean = packetCaptureController.currentRawFile()?.exists() == true

    fun canShareLogFile(): Boolean = packetCaptureController.currentLogFile()?.exists() == true

    fun canShareCsvFile(): Boolean = packetCaptureController.currentPacketFile()?.exists() == true

    fun canShareAllFiles(): Boolean =
        canSharePacketFile() || canShareCsvFile() || canShareRawFile() || canShareLogFile()

    fun clearExportProgress() {
        runOnUiThread {
            uiStateHolder.clearExportProgress()
        }
    }

    override fun close() {
        packetReplayController?.close()
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = null
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        bleSessionController.close()
        packetCaptureController.close()
    }

    fun onCaptureReady() {
        resetCaptureSession()
        flushPendingTransportDiagnostics()
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

    fun onSessionError(message: String) {
        flushPendingTransportDiagnostics()
        awaitingCaptureReady = false
        uiStateHolder.showError(message)
    }

    fun startReplay(fileBytes: ByteArray) {
        if (packetReplayController == null) {
            uiStateHolder.showError(appTextResolver.getString(R.string.replay_unavailable))
            return
        }

        packetReplayController.stop()
        bleSessionController.close()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        packetCaptureController.resetSession()
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = wallClockMillisProvider()
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
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
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = wallClockMillisProvider()
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        uiStateHolder.resetCaptureSession()
    }

    private fun flushPendingTransportDiagnostics() {
        pendingTransportDiagnostics.forEach { message ->
            packetCaptureController.recordDiagnosticEvent(
                type = PacketDiagnosticType.INFO,
                message = message,
            )
        }
        pendingTransportDiagnostics.clear()
    }

    private fun createShareIntent(
        context: Context,
        file: File?,
    ): Intent? {
        if (file == null || !file.exists()) return null

        return try {
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val snapshotFile = when (file.absolutePath) {
                snapshot.packetSourcePath -> snapshot.packetSnapshot
                snapshot.rawSourcePath -> snapshot.rawSnapshot
                snapshot.logSourcePath -> snapshot.logSnapshot
                else -> null
            } ?: return null

            showExportPhaseOnMainThread(ExportPhase.OPENING_SHARE_SHEET)
            fileShareIntentFactory.createChooserIntent(context, snapshotFile)
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.share_file_failed))
            Log.e("BLE_SHARE", "Failed to share file", exception)
            null
        }
    }

    private fun showErrorOnMainThread(message: String) {
        runOnUiThread {
            uiStateHolder.showError(message)
        }
    }

    private fun showExportPhaseOnMainThread(phase: ExportPhase) {
        runOnUiThread {
            uiStateHolder.showExportProgress(phase)
        }
    }

    private fun ensureExportSnapshot(): SessionExportSnapshot? {
        val buildPlan = synchronized(exportLock) {
            val currentPacketFile = packetCaptureController.currentPacketFile()?.takeIf(File::exists)
            val currentRawFile = packetCaptureController.currentRawFile()?.takeIf(File::exists)
            val currentLogFile = packetCaptureController.currentLogFile()?.takeIf(File::exists)
            val sessionStartMillis = exportSessionStartMillis
            val existingSnapshot = exportSnapshot

            existingSnapshot?.takeIf {
                it.matches(currentPacketFile, currentRawFile, currentLogFile, sessionStartMillis)
            }?.let { return it }

            if (currentPacketFile == null && currentRawFile == null && currentLogFile == null) {
                return null
            }

            val snapshotToDelete = exportSnapshot
            exportSnapshot = null
            ExportSnapshotBuildPlan(
                epoch = exportEpoch,
                sessionStartMillis = sessionStartMillis,
                packetFile = currentPacketFile,
                rawFile = currentRawFile,
                logFile = currentLogFile,
                staleSnapshot = snapshotToDelete,
            )
        }

        deleteSnapshotFiles(buildPlan.staleSnapshot)
        packetCaptureController.flush()
        val snapshot = SessionExportSnapshot(
            packetSourcePath = buildPlan.packetFile?.absolutePath,
            rawSourcePath = buildPlan.rawFile?.absolutePath,
            logSourcePath = buildPlan.logFile?.absolutePath,
            sessionStartMillis = buildPlan.sessionStartMillis,
            packetSnapshot = buildPlan.packetFile?.let { createSnapshotCopy(it, "packet") },
            rawSnapshot = buildPlan.rawFile?.let { createSnapshotCopy(it, "raw") },
            logSnapshot = buildPlan.logFile?.let { createSnapshotCopy(it, "log") },
            csvSnapshot = null,
        )

        return synchronized(exportLock) {
            if (exportEpoch != buildPlan.epoch) {
                deleteSnapshotFiles(snapshot)
                null
            } else {
                exportSnapshot = snapshot
                snapshot
            }
        }
    }

    private fun buildAvailableExportFiles(snapshot: SessionExportSnapshot): List<File> {
        val csvSnapshot = ensureCsvSnapshot(snapshot)
        return listOfNotNull(
            snapshot.packetSnapshot,
            csvSnapshot,
            snapshot.rawSnapshot,
            snapshot.logSnapshot,
        )
    }

    private fun ensureCsvSnapshot(snapshot: SessionExportSnapshot): File? {
        val buildEpoch = synchronized(exportLock) {
            val currentSnapshot = exportSnapshot
            if (currentSnapshot == null || !currentSnapshot.hasSameBase(snapshot)) {
                return null
            }

            currentSnapshot.csvSnapshot?.let { return it }
            exportEpoch
        }

        val packetSnapshot = snapshot.packetSnapshot ?: return null
        showExportPhaseOnMainThread(ExportPhase.GENERATING_CSV)
        val generatedCsv = createCsvSnapshot(
            source = packetSnapshot,
            sessionStartMillis = snapshot.sessionStartMillis ?: wallClockMillisProvider(),
        )

        return synchronized(exportLock) {
            val currentSnapshot = exportSnapshot
            if (exportEpoch != buildEpoch || currentSnapshot == null || !currentSnapshot.hasSameBase(snapshot)) {
                deleteSnapshotFiles(snapshot.copy(csvSnapshot = generatedCsv))
                currentSnapshot?.takeIf { it.hasSameBase(snapshot) }?.csvSnapshot
            } else {
                val updatedSnapshot = currentSnapshot.copy(csvSnapshot = generatedCsv)
                exportSnapshot = updatedSnapshot
                generatedCsv
            }
        }
    }

    private fun invalidateExportSnapshotLocked(): SessionExportSnapshot? {
        val snapshot = exportSnapshot
        exportSnapshot = null
        exportEpoch++
        return snapshot
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

    private fun createCsvSnapshot(
        source: File,
        sessionStartMillis: Long,
    ): File {
        val sessionBaseName = source.nameWithoutExtension.removeSuffix("_snapshot_packet")
        val target = File(
            source.parentFile,
            "${sessionBaseName}_snapshot_csv.csv",
        )
        return sessionCsvExporter.export(
            packetFile = source,
            sessionStartMillis = sessionStartMillis,
            targetFile = target,
        )
    }

    private fun syncProcessorSelection() {
        packetCaptureController.updateSelection(
            sensorType = uiStateHolder.uiState.selectedSensorType,
            channel = uiStateHolder.uiState.selectedChannel,
        )
    }

    companion object {
        const val PERMISSION_REQUIRED_MESSAGE = "Permissions are required!"
    }
}

private data class SessionExportSnapshot(
    val packetSourcePath: String?,
    val rawSourcePath: String?,
    val logSourcePath: String?,
    val sessionStartMillis: Long?,
    val packetSnapshot: File?,
    val rawSnapshot: File?,
    val logSnapshot: File?,
    val csvSnapshot: File?,
) {
    fun matches(
        packetFile: File?,
        rawFile: File?,
        logFile: File?,
        sessionStartMillis: Long?,
    ): Boolean {
        return packetSourcePath == packetFile?.absolutePath &&
            rawSourcePath == rawFile?.absolutePath &&
            logSourcePath == logFile?.absolutePath &&
            this.sessionStartMillis == sessionStartMillis
    }

    fun hasSameBase(other: SessionExportSnapshot): Boolean {
        return packetSourcePath == other.packetSourcePath &&
            rawSourcePath == other.rawSourcePath &&
            logSourcePath == other.logSourcePath &&
            sessionStartMillis == other.sessionStartMillis &&
            packetSnapshot?.absolutePath == other.packetSnapshot?.absolutePath &&
            rawSnapshot?.absolutePath == other.rawSnapshot?.absolutePath &&
            logSnapshot?.absolutePath == other.logSnapshot?.absolutePath
    }
}

private data class ExportSnapshotBuildPlan(
    val epoch: Long,
    val sessionStartMillis: Long?,
    val packetFile: File?,
    val rawFile: File?,
    val logFile: File?,
    val staleSnapshot: SessionExportSnapshot?,
)

private fun deleteSnapshotFiles(snapshot: SessionExportSnapshot?) {
    listOfNotNull(snapshot?.packetSnapshot, snapshot?.rawSnapshot, snapshot?.logSnapshot, snapshot?.csvSnapshot)
        .forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (exception: Exception) {
                Log.w("BLE_SHARE", "Failed to delete snapshot file ${file.absolutePath}", exception)
            }
        }
}
