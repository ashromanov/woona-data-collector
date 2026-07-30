package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.R
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.data.ArtifactType
import com.example.myapplication.data.CaptureSyncMetadata
import com.example.myapplication.data.Recording
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.RecordingStatus
import com.example.myapplication.data.SyncClockAnchor
import com.example.myapplication.data.VideoSyncMetadata
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.absoluteInstantForMonotonic
import com.example.myapplication.data.monotonicOffsetNs
import com.example.myapplication.data.toJson
import com.example.myapplication.localization.EnglishTextResolver
import com.example.myapplication.localization.TextResolver
import com.example.myapplication.storage.BleSessionCsvExporter
import com.example.myapplication.storage.FileShareIntentFactory
import com.example.myapplication.storage.SessionArchiveExporter
import com.example.myapplication.storage.SessionArchiveMetadata
import com.example.myapplication.video.AndroidVideoRecorder
import com.example.myapplication.video.AndroidVideoStartInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class DeviceFeatureController(
    private val bleSessionController: BleSessionController,
    private val packetCaptureController: PacketCaptureController,
    private val fileShareIntentFactory: FileShareIntentFactory,
    private val appTextResolver: TextResolver = EnglishTextResolver,
    private val packetReplayController: PacketReplayController? = null,
    private val uiStateHolder: DeviceUiStateHolder = DeviceUiStateHolder(),
    private val sessionCsvExporter: BleSessionCsvExporter = BleSessionCsvExporter(),
    private val sessionArchiveExporter: SessionArchiveExporter = SessionArchiveExporter(),
    private val wallClockMillisProvider: () -> Long = System::currentTimeMillis,
    private val runOnUiThread: (() -> Unit) -> Unit = { action -> action() },
    private val woonaDatabase: WoonaDatabase? = null,
    private val snapshotDirectory: File? = null,
    private val onRecordingChanged: () -> Unit = {},
    private val videoRecorder: AndroidVideoRecorder? = null,
    private val monotonicNanosProvider: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    private val exportLock = Any()
    private var pendingTransportDiagnostics = mutableListOf<String>()
    private var awaitingCaptureReady = false
    private var exportSnapshot: SessionExportSnapshot? = null
    private var exportSessionStartMillis: Long? = null
    private var exportEpoch = 0L
    private var currentRecordingId: String? = null
    private var currentRecording: Recording? = null
    private var currentRecordingDirectory: File? = null
    private var captureSyncMetadata: CaptureSyncMetadata? = null
    @Volatile
    private var recordingFinalized = true

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

    @Synchronized
    fun beginRecordingSession(recording: Recording) {
        finalizeRecording(RecordingStatus.INTERRUPTED)
        currentRecordingId = recording.id
        currentRecording = recording
        recordingFinalized = false
        packetCaptureController.stopCapture()
        currentRecordingDirectory =
            requireNotNull(woonaDatabase).recordingDirectory(recording.relativeDirectory)
        packetCaptureController.useSessionDirectory(requireNotNull(currentRecordingDirectory))
        captureSyncMetadata = CaptureSyncMetadata(
            recordingId = recording.id,
            profileId = recording.profileId,
            source = recording.source.value,
            timezone = recording.timezone,
            selectedSessionStartUtc = recording.startedAtUtc,
        )
        persistSyncMetadata()
        if (recording.source == RecordingSource.LIVE) {
            uiStateHolder.prepareVideo()
        }
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = Instant.parse(recording.startedAtUtc).toEpochMilli()
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        onRecordingChanged()
    }

    fun onDisconnectRequested() {
        packetReplayController?.stop()
        pendingTransportDiagnostics.clear()
        awaitingCaptureReady = false
        finalizeRecording(RecordingStatus.COMPLETED)
        packetCaptureController.stopCapture()
        val staleSnapshot = synchronized(exportLock) {
            invalidateExportSnapshotLocked()
        }
        deleteSnapshotFiles(staleSnapshot)
        uiStateHolder.stopCaptureSession()
        bleSessionController.disconnect()
    }

    fun finishCaptureForExport(): Boolean {
        stopVideo()
        packetReplayController?.stop()
        val finished = packetCaptureController.finishCapture(CAPTURE_FINISH_TIMEOUT_MILLIS)
        if (!finished) {
            Log.w("BLE_SHARE", "Timed out waiting for capture queue before export")
        }
        return finished
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
        packetReplayController?.stop()
        finalizeRecording(RecordingStatus.INTERRUPTED)
        bleSessionController.disconnect()
    }

    fun onBleStateChanged(state: BleSessionState) {
        when (state) {
            BleSessionState.FAILED -> finalizeRecording(RecordingStatus.FAILED)
            BleSessionState.DISCONNECTED -> finalizeRecording(RecordingStatus.INTERRUPTED)
            else -> Unit
        }
        uiStateHolder.onSessionStateChanged(state)
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
            stopVideo()
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

    fun createRecordingShareIntent(context: Context, recordingId: String): Intent? {
        val files = woonaDatabase?.artifactFiles(recordingId).orEmpty()
        return files.takeIf { it.isNotEmpty() }?.let {
            fileShareIntentFactory.createChooserIntent(context, it)
        }
    }

    fun createSessionArchive(targetDirectory: File): File? {
        return try {
            stopVideo()
            showExportPhaseOnMainThread(ExportPhase.PREPARING_SNAPSHOTS)
            val snapshot = ensureExportSnapshot() ?: return null
            val files = buildAvailableExportFiles(snapshot)
            if (files.isEmpty()) return null

            showExportPhaseOnMainThread(ExportPhase.PACKAGING_ARCHIVE)
            sessionArchiveExporter.export(
                files = files,
                targetDirectory = targetDirectory,
                sessionStartMillis = snapshot.sessionStartMillis,
                createdAtMillis = wallClockMillisProvider(),
                metadata = currentRecordingId
                    ?.let { recordingId -> woonaDatabase?.recordingSummary(recordingId) }
                    ?.let { summary ->
                        SessionArchiveMetadata(
                            profileId = summary.recording.profileId,
                            profileName = summary.profileName,
                            recordingId = summary.recording.id,
                            source = summary.recording.source.value,
                            status = summary.recording.status.value,
                            timezone = summary.recording.timezone,
                            profileQuestionnaireJson = summary.profileQuestionnaire.toJson(),
                            questionnaireJson = summary.recording.questionnaire?.toJson(),
                            synchronizationJson = currentSessionFile("sync.json")
                                ?.takeIf(File::exists)
                                ?.readText(Charsets.UTF_8),
                        )
                    },
            )
        } catch (exception: Exception) {
            showErrorOnMainThread(appTextResolver.getString(R.string.drive_backup_prepare_failed))
            Log.e("BLE_SHARE", "Failed to prepare Drive backup", exception)
            null
        }
    }

    fun canSharePacketFile(): Boolean = packetCaptureController.currentPacketFile()?.exists() == true

    fun canShareRawFile(): Boolean = packetCaptureController.currentRawFile()?.exists() == true

    fun canShareLogFile(): Boolean = packetCaptureController.currentLogFile()?.exists() == true

    fun canShareCsvFile(): Boolean = packetCaptureController.currentPacketFile()?.exists() == true

    fun canShareAllFiles(): Boolean =
        canSharePacketFile() || canShareCsvFile() || canShareRawFile() || canShareLogFile() ||
            currentSessionFile("video.mp4")?.exists() == true ||
            currentSessionFile("sync.json")?.exists() == true

    fun clearExportProgress() {
        runOnUiThread {
            uiStateHolder.clearExportProgress()
        }
    }

    override fun close() {
        packetReplayController?.close()
        finalizeRecording(RecordingStatus.INTERRUPTED)
        val staleSnapshot = synchronized(exportLock) {
            val snapshot = invalidateExportSnapshotLocked()
            exportSessionStartMillis = null
            snapshot
        }
        deleteSnapshotFiles(staleSnapshot)
        bleSessionController.close()
        packetCaptureController.close()
        videoRecorder?.close()
    }

    fun onCaptureReady() {
        if (woonaDatabase != null && recordingFinalized) {
            awaitingCaptureReady = false
            return
        }
        resetCaptureSession()
        if (!recordingFinalized) {
            currentRecordingId?.let { woonaDatabase?.markRecording(it) }
            captureSyncMetadata = captureSyncMetadata?.copy(
                sensor = captureClockAnchor("ble_capture_ready"),
            )
            persistSyncMetadata()
            if (currentRecording?.source == RecordingSource.LIVE) {
                uiStateHolder.videoReady()
            }
        }
        onRecordingChanged()
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
        finalizeRecording(RecordingStatus.FAILED)
    }

    fun startReplay(fileBytes: ByteArray) {
        startReplay { fileBytes.inputStream() }
    }

    fun startReplay(source: ReplayInputSource) {
        if (packetReplayController == null) {
            uiStateHolder.showError(appTextResolver.getString(R.string.replay_unavailable))
            return
        }

        packetReplayController.startReplay(source)
    }

    fun onReplayPreparing() {
        bleSessionController.stopScanning()
        uiStateHolder.startReplayPreparation()
    }

    fun cancelReplayPreparation() {
        packetReplayController?.stop()
    }

    fun onReplayStarted() {
        if (woonaDatabase != null && recordingFinalized) return
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
        if (!recordingFinalized) {
            currentRecordingId?.let { woonaDatabase?.markRecording(it) }
            captureSyncMetadata = captureSyncMetadata?.copy(
                sensor = captureClockAnchor("replay_started"),
            )
            persistSyncMetadata()
        }
        onRecordingChanged()
    }

    fun onReplayCompleted() {
        finalizeRecording(RecordingStatus.COMPLETED)
        uiStateHolder.finishReplaySession()
    }

    fun onReplayStopped() {
        if (uiStateHolder.uiState.isReplayPreparing) {
            finalizeRecording(RecordingStatus.INTERRUPTED)
            uiStateHolder.cancelReplayPreparation()
        } else {
            finalizeRecording(RecordingStatus.COMPLETED)
            uiStateHolder.stopCaptureSession()
        }
    }

    fun onReplayError(message: String) {
        uiStateHolder.showError(message)
        finalizeRecording(RecordingStatus.FAILED)
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

            if (
                currentPacketFile == null &&
                currentRawFile == null &&
                currentLogFile == null &&
                currentSessionFile("video.mp4")?.exists() != true &&
                currentSessionFile("sync.json")?.exists() != true
            ) {
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
            currentSessionFile("video.mp4")?.takeIf(File::exists),
            currentSessionFile("sync.json")?.takeIf(File::exists),
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
        val targetDirectory = snapshotDirectory ?: source.parentFile
        targetDirectory.mkdirs()
        val target = File(
            targetDirectory,
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
        val packetFile = packetCaptureController.currentPacketFile()?.takeIf(File::exists) ?: source
        val canonicalTarget = File(packetFile.parentFile, "channel.csv")
        sessionCsvExporter.export(
            packetFile = packetFile,
            sessionStartMillis = sessionStartMillis,
            targetFile = canonicalTarget,
        )
        currentRecordingId?.let { woonaDatabase?.registerCsv(it, canonicalTarget) }
        onRecordingChanged()
        val targetDirectory = snapshotDirectory ?: source.parentFile
        targetDirectory.mkdirs()
        val sessionBaseName = source.nameWithoutExtension.removeSuffix("_snapshot_packet")
        val snapshot = File(targetDirectory, "${sessionBaseName}_snapshot_csv.csv")
        Files.copy(
            canonicalTarget.toPath(),
            snapshot.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return snapshot
    }

    @Synchronized
    private fun finalizeRecording(status: RecordingStatus) {
        val recordingId = currentRecordingId ?: return
        if (recordingFinalized) return
        stopVideo()
        packetCaptureController.finishCapture(CAPTURE_FINISH_TIMEOUT_MILLIS)
        try {
            woonaDatabase?.finishRecording(recordingId, status)
            recordingFinalized = true
        } catch (exception: Exception) {
            Log.e("WOONA_DATABASE", "Failed to finalize recording $recordingId", exception)
        }
        onRecordingChanged()
    }

    @Synchronized
    fun startVideo() {
        val recorder = videoRecorder
        val recording = currentRecording
        val metadata = captureSyncMetadata
        if (
            recorder == null ||
            recording == null ||
            recording.source != RecordingSource.LIVE ||
            recordingFinalized ||
            metadata?.sensor == null ||
            uiState.videoState !in setOf(VideoCaptureState.READY, VideoCaptureState.FAILED)
        ) {
            return
        }

        val requestedAt = captureClockAnchor("video_requested")
        captureSyncMetadata = metadata.copy(
            video = VideoSyncMetadata(
                requestedAtUtc = requestedAt.absoluteUtc,
                requestedMonotonicNs = requestedAt.monotonicTimeNs,
            ),
        )
        persistSyncMetadata()
        uiStateHolder.videoStarting()
        val started = recorder.start(
            file = File(requireNotNull(currentRecordingDirectory), "video.mp4"),
            onStarted = { info ->
                runOnUiThread {
                    handleVideoStarted(info)
                }
            },
            onError = { message, throwable ->
                Log.e("WOONA_VIDEO", message, throwable)
                runOnUiThread {
                    handleVideoFailure()
                }
            },
        )
        if (!started) handleVideoFailure()
    }

    @Synchronized
    fun stopVideo() {
        val recorder = videoRecorder ?: return
        if (!recorder.isActive()) return
        runOnUiThread { uiStateHolder.videoStopping() }
        val result = recorder.stop()
        val metadata = captureSyncMetadata
        val video = metadata?.video
        if (metadata != null && video != null) {
            val stoppedAt = result?.stoppedMonotonicNs ?: monotonicNanosProvider()
            captureSyncMetadata = metadata.copy(
                video = video.copy(
                    stoppedAtUtc = instantForMonotonic(stoppedAt).toString(),
                    stoppedMonotonicNs = stoppedAt,
                    durationNs = result?.durationNs,
                ),
            )
            persistSyncMetadata()
        }
        currentSessionFile("video.mp4")
            ?.takeIf { it.exists() && it.length() > 0L }
            ?.let { file ->
                currentRecordingId?.let { recordingId ->
                    woonaDatabase?.registerArtifact(recordingId, ArtifactType.VIDEO, file)
                }
            }
        runOnUiThread {
            if (result == null) {
                uiStateHolder.videoFailed(appTextResolver.getString(R.string.video_recording_failed))
            } else {
                uiStateHolder.videoFinished()
            }
        }
        onRecordingChanged()
    }

    fun onVideoPermissionDenied() {
        uiStateHolder.videoFailed(appTextResolver.getString(R.string.video_camera_permission_required))
    }

    @Synchronized
    private fun handleVideoStarted(info: AndroidVideoStartInfo) {
        if (recordingFinalized) {
            stopVideo()
            return
        }
        val metadata = captureSyncMetadata ?: return
        val sensor = metadata.sensor ?: return
        val video = metadata.video ?: return
        val offsetNs = monotonicOffsetNs(sensor, info.firstFrameMonotonicNs)
        captureSyncMetadata = metadata.copy(
            video = video.copy(
                firstFrameAtUtc = instantForMonotonic(info.firstFrameMonotonicNs).toString(),
                firstFrameEpochMillis = sensor.wallClockEpochMillis + offsetNs / 1_000_000.0,
                firstFrameMonotonicNs = info.firstFrameMonotonicNs,
                firstFrameCameraTimestampNs = info.firstFrameCameraTimestampNs,
                offsetFromSensorNs = offsetNs,
                cameraTimestampSource = info.cameraTimestampSource,
                synchronizationQuality = info.synchronizationQuality,
                cameraId = info.cameraId,
                lensFacing = info.lensFacing,
                width = info.width,
                height = info.height,
                frameRate = info.frameRate,
                rotationDegrees = info.rotationDegrees,
            ),
        )
        persistSyncMetadata()
        uiStateHolder.videoStarted(offsetNs / 1_000_000.0)
        onRecordingChanged()
    }

    @Synchronized
    private fun handleVideoFailure() {
        if (recordingFinalized) return
        uiStateHolder.videoFailed(appTextResolver.getString(R.string.video_recording_failed))
        persistSyncMetadata()
        onRecordingChanged()
    }

    private fun captureClockAnchor(event: String): SyncClockAnchor {
        val before = monotonicNanosProvider()
        val wallClock = wallClockMillisProvider()
        val after = monotonicNanosProvider()
        return SyncClockAnchor(
            event = event,
            absoluteUtc = Instant.ofEpochMilli(wallClock).toString(),
            wallClockEpochMillis = wallClock,
            monotonicTimeNs = before + (after - before) / 2,
            monotonicClock = "android.elapsedRealtimeNanos",
            samplingUncertaintyNs = (after - before).coerceAtLeast(0L) / 2,
        )
    }

    private fun instantForMonotonic(monotonicNs: Long): Instant {
        val sensor = requireNotNull(captureSyncMetadata?.sensor)
        return absoluteInstantForMonotonic(sensor, monotonicNs)
    }

    private fun persistSyncMetadata() {
        val recordingId = currentRecordingId ?: return
        val metadata = captureSyncMetadata ?: return
        try {
            woonaDatabase?.writeSyncMetadata(recordingId, metadata)
        } catch (exception: Exception) {
            Log.e("WOONA_DATABASE", "Failed to write synchronization metadata", exception)
        }
    }

    private fun currentSessionFile(name: String): File? {
        return currentRecordingDirectory?.let { File(it, name) }
    }

    private fun syncProcessorSelection() {
        packetCaptureController.updateSelection(
            sensorType = uiStateHolder.uiState.selectedSensorType,
            channel = uiStateHolder.uiState.selectedChannel,
        )
    }

    companion object {
        const val PERMISSION_REQUIRED_MESSAGE = "Permissions are required!"
        private const val CAPTURE_FINISH_TIMEOUT_MILLIS = 2_000L
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
