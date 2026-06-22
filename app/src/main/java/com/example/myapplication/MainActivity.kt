package com.example.myapplication

import android.Manifest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.AppShellEntryPoint
import com.example.myapplication.DeviceFeatureModuleEntryPoint
import com.example.myapplication.drive.DriveUploadResult
import com.example.myapplication.ble.BleTransportProfilePreferences
import com.example.myapplication.drive.DriveBackupPreferences
import com.example.myapplication.drive.DriveBackupScheduler
import com.example.myapplication.drive.DriveBackupStatus
import com.example.myapplication.drive.DriveBackupUiState
import com.example.myapplication.drive.DriveFailureSummary
import com.example.myapplication.drive.GoogleDriveArchiveUploader
import com.example.myapplication.drive.GoogleDriveAuthorization
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLocalizationEntryPoint
import com.example.myapplication.localization.AppLanguagePreferences
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.ui.theme.AppThemeMode
import com.example.myapplication.ui.theme.AppThemeEntryPoint
import com.example.myapplication.ui.theme.AppThemePreferences
import com.example.myapplication.ui.theme.applyAppThemeMode
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val appLanguagePreferences by lazy {
        AppLanguagePreferences(applicationContext)
    }
    private val appThemePreferences by lazy {
        AppThemePreferences(applicationContext)
    }
    private val bleTransportProfilePreferences by lazy {
        BleTransportProfilePreferences(applicationContext)
    }
    private val driveBackupPreferences by lazy {
        DriveBackupPreferences(applicationContext)
    }
    private val driveBackupScheduler by lazy {
        DriveBackupScheduler(applicationContext)
    }
    private var selectedLanguage by mutableStateOf(AppLanguage.ENGLISH)
    private var selectedThemeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private var driveBackupState by mutableStateOf(DriveBackupUiState())
    private var isChartFullscreenActive = false
    private var isStopSessionInProgress = false
    private var enableAutoUploadAfterDriveAuthorization = false
    private var retryPendingAfterDriveAuthorization = false
    private var clearDriveAuthorizationOnFailure = true
    private var driveForegroundStatus: DriveBackupStatus? = null
    private var driveUploadWorkInfos: List<WorkInfo> = emptyList()
    private var awaitingDriveWorkObservation = false
    private val appTextResolver by lazy {
        AppTextResolver(applicationContext) { selectedLanguage }
    }
    private val backgroundExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "main-activity-io").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        deviceFeatureController.onPermissionsResult(allGranted)
        if (!allGranted) {
            Toast.makeText(
                this,
                appTextResolver.getString(R.string.permissions_required),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val replayFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        deviceFeatureController.startReplay { cancellationToken ->
            val cancellationSignal = CancellationSignal()
            cancellationToken.invokeOnCancellation(cancellationSignal::cancel)
            val descriptor = contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                ?: return@startReplay null
            try {
                descriptor.createInputStream()
            } catch (exception: Exception) {
                descriptor.close()
                throw exception
            }
        }
    }

    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            onDriveAuthorizationSucceeded(authorizationResult.accessToken)
        } catch (exception: ApiException) {
            onDriveAuthorizationFailed(
                clearStoredAuthorization = clearDriveAuthorizationOnFailure,
                cause = exception,
            )
        }
    }

    private val deviceFeatureController: DeviceFeatureController by lazy {
        DeviceFeatureModuleEntryPoint.create(
            context = this,
            filesDir = filesDir,
            fileProviderAuthority = "$packageName.provider",
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            initialTransportProfile = bleTransportProfilePreferences.selectedTransportProfile(),
            appTextResolver = appTextResolver,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedLanguage = appLanguagePreferences.selectedLanguage()
        selectedThemeMode = appThemePreferences.selectedThemeMode()
        applyThemeMode(selectedThemeMode)

        super.onCreate(savedInstanceState)

        refreshDriveBackupState()
        observeDriveUploadWork()
        deviceFeatureController
        enableEdgeToEdge()
        setContent {
            AppLocalizationEntryPoint.Provide(
                language = selectedLanguage,
                textResolver = appTextResolver,
            ) {
                val uiState = deviceFeatureController.uiState
                AppThemeEntryPoint.Render(themeMode = selectedThemeMode) {
                    AppShellEntryPoint.Render(
                        uiState = uiState,
                        selectedLanguage = selectedLanguage,
                        selectedThemeMode = selectedThemeMode,
                        driveBackupState = driveBackupState,
                        onStartScan = {
                            deviceFeatureController.onStartScanRequested(
                                hasPermissions = ::hasBlePermissions,
                                requestPermissions = {
                                    requestPermissionLauncher.launch(blePermissions)
                                },
                            )
                        },
                        onConnect = { mac -> deviceFeatureController.onConnectRequested(mac) },
                        onTransportProfileSelect = { profile ->
                            bleTransportProfilePreferences.setSelectedTransportProfile(profile)
                            deviceFeatureController.onTransportProfileSelected(profile)
                        },
                        onLanguageSelect = { language ->
                            appLanguagePreferences.setSelectedLanguage(language)
                            selectedLanguage = language
                        },
                        onThemeModeSelect = { themeMode ->
                            appThemePreferences.setSelectedThemeMode(themeMode)
                            selectedThemeMode = themeMode
                            applyThemeMode(themeMode)
                        },
                        onDriveConnect = {
                            requestDriveAuthorization(
                                enableAutoUploadAfterSuccess = false,
                                retryPendingAfterSuccess = driveBackupState.retryableUploadCount > 0,
                                clearAuthorizationOnFailure = !driveBackupState.isAuthorized,
                            )
                        },
                        onDriveAutoUploadChange = { enabled ->
                            if (enabled && !driveBackupState.isAuthorized) {
                                requestDriveAuthorization(
                                    enableAutoUploadAfterSuccess = true,
                                    retryPendingAfterSuccess = driveBackupState.retryableUploadCount > 0,
                                    clearAuthorizationOnFailure = true,
                                )
                            } else {
                                driveBackupPreferences.setAutoUploadEnabled(enabled)
                                refreshDriveBackupState()
                            }
                        },
                        onDriveWifiOnlyChange = { enabled ->
                            driveBackupPreferences.setWifiOnly(enabled)
                            refreshDriveBackupState()
                        },
                        onDriveRetryPending = { retryPendingDriveBackups() },
                        onDisconnect = { stopSessionAndMaybeQueueDriveBackup() },
                        onSensorSelect = { type -> deviceFeatureController.onSensorSelected(type) },
                        onChannelSelect = { channel -> deviceFeatureController.onChannelSelected(channel) },
                        onChartWindowSelect = { windowPreset ->
                            deviceFeatureController.onChartWindowSelected(windowPreset)
                        },
                        onFollowLiveChange = { enabled ->
                            deviceFeatureController.onFollowLiveChanged(enabled)
                        },
                        onChartPanLeft = { deviceFeatureController.onChartPanLeftRequested() },
                        onChartPanRight = { deviceFeatureController.onChartPanRightRequested() },
                        onChartZoomIn = { deviceFeatureController.onChartZoomInRequested() },
                        onChartZoomOut = { deviceFeatureController.onChartZoomOutRequested() },
                        onChartZoomReset = { deviceFeatureController.onChartZoomResetRequested() },
                        onChartPanGesture = { deltaFraction ->
                            deviceFeatureController.onChartPanned(deltaFraction)
                        },
                        onChartZoomGesture = { scaleFactor, anchorFractionY ->
                            deviceFeatureController.onChartZoomChanged(scaleFactor, anchorFractionY)
                        },
                        onChartFullscreenChange = ::setChartFullscreenActive,
                        canShareAllFiles = deviceFeatureController.canShareAllFiles(),
                        canSharePacketFile = deviceFeatureController.canSharePacketFile(),
                        canShareCsvFile = deviceFeatureController.canShareCsvFile(),
                        canShareRawFile = deviceFeatureController.canShareRawFile(),
                        canShareLogFile = deviceFeatureController.canShareLogFile(),
                        onShareAllFiles = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createAllFilesShareIntent(this)
                            }
                        },
                        onSharePacketFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createPacketShareIntent(this)
                            }
                        },
                        onShareCsvFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createCsvShareIntent(this)
                            }
                        },
                        onShareRawFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createRawShareIntent(this)
                            }
                        },
                        onShareLogFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createLogShareIntent(this)
                            }
                        },
                        showReplayAction = true,
                        onReplayRequest = {
                            if (deviceFeatureController.uiState.isReplayPreparing) {
                                deviceFeatureController.cancelReplayPreparation()
                            } else {
                                replayFilePickerLauncher.launch("*/*")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        deviceFeatureController.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshDriveBackupState()
    }

    override fun onDestroy() {
        setChartFullscreenActive(false)
        if (!isChangingConfigurations) {
            deviceFeatureController.close()
        }
        backgroundExecutor.shutdownNow()

        super.onDestroy()
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun applyThemeMode(themeMode: AppThemeMode) {
        applyAppThemeMode(this, themeMode)
    }

    private fun setChartFullscreenActive(active: Boolean) {
        if (isChartFullscreenActive == active) return

        isChartFullscreenActive = active

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (active) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestDriveAuthorization(
        enableAutoUploadAfterSuccess: Boolean,
        retryPendingAfterSuccess: Boolean,
        clearAuthorizationOnFailure: Boolean,
    ) {
        enableAutoUploadAfterDriveAuthorization = enableAutoUploadAfterSuccess
        retryPendingAfterDriveAuthorization = retryPendingAfterSuccess
        clearDriveAuthorizationOnFailure = clearAuthorizationOnFailure
        setDriveForegroundStatus(DriveBackupStatus.CONNECTING)
        Identity.getAuthorizationClient(this)
            .authorize(GoogleDriveAuthorization.createAuthorizationRequest())
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent ?: run {
                        onDriveAuthorizationFailed(
                            clearStoredAuthorization = clearDriveAuthorizationOnFailure,
                            cause = IllegalStateException("Google Drive authorization did not return a resolution"),
                        )
                        return@addOnSuccessListener
                    }
                    driveAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                    )
                } else {
                    onDriveAuthorizationSucceeded(authorizationResult.accessToken)
                }
            }
            .addOnFailureListener {
                onDriveAuthorizationFailed(
                    clearStoredAuthorization = clearDriveAuthorizationOnFailure,
                    cause = it,
                )
            }
    }

    private fun onDriveAuthorizationSucceeded(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            onDriveAuthorizationFailed(
                clearStoredAuthorization = clearDriveAuthorizationOnFailure,
                cause = IllegalStateException("Google Drive authorization did not return an access token"),
            )
            return
        }

        val enableAutoUpload = enableAutoUploadAfterDriveAuthorization
        val retryPendingUploads = retryPendingAfterDriveAuthorization
        backgroundExecutor.execute {
            val setupResult = GoogleDriveArchiveUploader().prepareBackupFolder(
                accessToken = accessToken,
                saveFolderId = driveBackupPreferences::setFolderId,
            )
            runOnUiThread {
                onDriveBackupFolderPrepared(
                    setupResult = setupResult,
                    enableAutoUpload = enableAutoUpload,
                    retryPendingUploads = retryPendingUploads,
                )
            }
        }
    }

    private fun onDriveBackupFolderPrepared(
        setupResult: DriveUploadResult,
        enableAutoUpload: Boolean,
        retryPendingUploads: Boolean,
    ) {
        when (setupResult) {
            DriveUploadResult.Success -> {
                driveBackupPreferences.setAuthorized(true)
                if (enableAutoUpload) {
                    driveBackupPreferences.setAutoUploadEnabled(true)
                }
                enableAutoUploadAfterDriveAuthorization = false
                retryPendingAfterDriveAuthorization = false
                driveForegroundStatus = null
                refreshDriveBackupState()
                if (retryPendingUploads) {
                    enqueuePendingDriveBackups()
                }
                Toast.makeText(
                    this,
                    appTextResolver.getString(R.string.drive_backup_authorized),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is DriveUploadResult.AuthorizationRequired -> onDriveAuthorizationFailed(
                clearStoredAuthorization = clearDriveAuthorizationOnFailure,
                cause = setupResult.exception,
            )
            is DriveUploadResult.PermanentFailure -> onDriveSetupFailed(setupResult.exception)
            is DriveUploadResult.RetryLater -> onDriveSetupFailed(setupResult.exception)
        }
    }

    private fun onDriveAuthorizationFailed(
        clearStoredAuthorization: Boolean = true,
        cause: Exception? = null,
    ) {
        if (cause == null) {
            Log.w(TAG, "Google Drive authorization failed")
        } else {
            Log.w(TAG, "Google Drive authorization failed", cause)
        }
        if (clearStoredAuthorization) {
            driveBackupPreferences.setAuthorized(false)
            driveBackupPreferences.setAutoUploadEnabled(false)
        }
        enableAutoUploadAfterDriveAuthorization = false
        retryPendingAfterDriveAuthorization = false
        clearDriveAuthorizationOnFailure = true
        driveForegroundStatus = null
        refreshDriveBackupState()
        val message = if (cause == null) {
            appTextResolver.getString(R.string.drive_backup_authorization_failed)
        } else {
            appTextResolver.getString(
                R.string.drive_backup_authorization_failed_with_reason,
                driveFailureSummary(cause),
            )
        }
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun onDriveSetupFailed(cause: Exception) {
        Log.w(TAG, "Google Drive setup failed", cause)
        enableAutoUploadAfterDriveAuthorization = false
        retryPendingAfterDriveAuthorization = false
        clearDriveAuthorizationOnFailure = true
        driveForegroundStatus = null
        refreshDriveBackupState()
        Toast.makeText(
            this,
            appTextResolver.getString(
                R.string.drive_backup_setup_failed_with_reason,
                driveFailureSummary(cause),
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun driveFailureSummary(exception: Exception): String {
        return DriveFailureSummary.summarize(exception)
    }

    private fun stopSessionAndMaybeQueueDriveBackup() {
        if (isStopSessionInProgress) return

        val settings = driveBackupPreferences.settings()
        val shouldQueueDriveBackup = settings.autoUploadEnabled && settings.isAuthorized
        if (!shouldQueueDriveBackup) {
            deviceFeatureController.onDisconnectRequested()
            refreshDriveBackupState()
            return
        }

        isStopSessionInProgress = true
        setDriveForegroundStatus(DriveBackupStatus.PREPARING)
        backgroundExecutor.execute {
            try {
                val captureFinished = deviceFeatureController.finishCaptureForExport()
                disconnectSessionOnMainThread()
                when {
                    !captureFinished -> runOnUiThread {
                        handleSkippedDriveBackup(R.string.drive_backup_capture_not_ready)
                    }
                    !driveBackupScheduler.hasPendingCapacity(driveUploadDirectory()) -> runOnUiThread {
                        handleSkippedDriveBackup(R.string.drive_backup_pending_limit_reached)
                    }
                    else -> {
                        val archiveFile = deviceFeatureController.createSessionArchive(driveUploadDirectory())
                        runOnUiThread {
                            handlePreparedDriveBackup(
                                archiveFile = archiveFile,
                                wifiOnly = settings.wifiOnly,
                            )
                        }
                    }
                }
            } finally {
                runOnUiThread {
                    if (driveForegroundStatus == DriveBackupStatus.PREPARING) {
                        driveForegroundStatus = null
                        refreshDriveBackupState()
                    }
                    isStopSessionInProgress = false
                }
            }
        }
    }

    private fun handleSkippedDriveBackup(messageRes: Int) {
        try {
            driveForegroundStatus = null
            Toast.makeText(
                this,
                appTextResolver.getString(messageRes),
                Toast.LENGTH_SHORT,
            ).show()
        } finally {
            deviceFeatureController.clearExportProgress()
            refreshDriveBackupState()
        }
    }

    private fun disconnectSessionOnMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deviceFeatureController.onDisconnectRequested()
            return
        }

        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                deviceFeatureController.onDisconnectRequested()
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(MAIN_THREAD_DISCONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for disconnect before Drive backup")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handlePreparedDriveBackup(
        archiveFile: File?,
        wifiOnly: Boolean,
    ) {
        try {
            if (archiveFile == null) {
                driveForegroundStatus = null
                Toast.makeText(
                    this,
                    appTextResolver.getString(R.string.drive_backup_no_files),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                driveBackupScheduler.enqueueArchive(
                    archiveFile = archiveFile,
                    wifiOnly = wifiOnly,
                )
                awaitingDriveWorkObservation = true
                driveForegroundStatus = null
                Toast.makeText(
                    this,
                    appTextResolver.getString(R.string.drive_backup_queued),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } finally {
            deviceFeatureController.clearExportProgress()
            refreshDriveBackupState()
        }
    }

    private fun retryPendingDriveBackups() {
        if (driveBackupScheduler.retryableUploadCount(driveUploadDirectory()) <= 0) {
            refreshDriveBackupState()
            return
        }
        requestDriveAuthorization(
            enableAutoUploadAfterSuccess = false,
            retryPendingAfterSuccess = true,
            clearAuthorizationOnFailure = false,
        )
    }

    private fun enqueuePendingDriveBackups() {
        val settings = driveBackupPreferences.settings()
        awaitingDriveWorkObservation = true
        driveBackupScheduler.enqueuePendingArchives(
            uploadDirectory = driveUploadDirectory(),
            wifiOnly = settings.wifiOnly,
        )
        refreshDriveBackupState()
    }

    private fun observeDriveUploadWork() {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(DriveBackupScheduler.WORK_TAG)
            .observe(this) { workInfos ->
                driveUploadWorkInfos = workInfos.orEmpty()
                if (
                    driveUploadWorkInfos.any { !it.state.isFinished } ||
                    awaitingDriveWorkObservation && driveUploadWorkInfos.isNotEmpty()
                ) {
                    awaitingDriveWorkObservation = false
                }
                refreshDriveBackupState()
            }
    }

    private fun setDriveForegroundStatus(status: DriveBackupStatus) {
        driveForegroundStatus = status
        refreshDriveBackupState()
    }

    private fun refreshDriveBackupState() {
        val settings = driveBackupPreferences.settings()
        val pendingUploadCount = driveBackupScheduler.pendingUploadCount(driveUploadDirectory())
        val failedUploadCount = driveBackupScheduler.failedUploadCount(driveUploadDirectory())
        driveBackupState = DriveBackupUiState(
            isAuthorized = settings.isAuthorized,
            autoUploadEnabled = settings.autoUploadEnabled,
            wifiOnly = settings.wifiOnly,
            pendingUploadCount = pendingUploadCount,
            failedUploadCount = failedUploadCount,
            status = driveBackupStatus(
                isAuthorized = settings.isAuthorized,
                pendingUploadCount = pendingUploadCount,
                failedUploadCount = failedUploadCount,
            ),
        )
    }

    private fun driveBackupStatus(
        isAuthorized: Boolean,
        pendingUploadCount: Int,
        failedUploadCount: Int,
    ): DriveBackupStatus {
        driveForegroundStatus?.let { return it }

        val hasRunningUpload = driveUploadWorkInfos.any { workInfo ->
            workInfo.state == WorkInfo.State.RUNNING
        }
        if (hasRunningUpload && pendingUploadCount > 0) return DriveBackupStatus.UPLOADING

        val hasWaitingUpload = driveUploadWorkInfos.any { workInfo ->
            workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.BLOCKED
        }
        return when {
            pendingUploadCount + failedUploadCount > 0 && !isAuthorized -> DriveBackupStatus.NEEDS_ATTENTION
            failedUploadCount > 0 -> DriveBackupStatus.NEEDS_ATTENTION
            hasWaitingUpload && pendingUploadCount > 0 -> DriveBackupStatus.WAITING
            awaitingDriveWorkObservation && pendingUploadCount > 0 -> DriveBackupStatus.QUEUED
            pendingUploadCount > 0 -> DriveBackupStatus.NEEDS_ATTENTION
            isAuthorized -> DriveBackupStatus.READY
            else -> DriveBackupStatus.IDLE
        }
    }

    private val DriveBackupUiState.retryableUploadCount: Int
        get() = pendingUploadCount + failedUploadCount

    private fun driveUploadDirectory(): File {
        return File(filesDir, DRIVE_UPLOAD_DIRECTORY_NAME)
    }

    private fun prepareShareIntentAsync(
        createIntent: () -> android.content.Intent?,
    ) {
        backgroundExecutor.execute {
            val intent = try {
                createIntent()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                try {
                    intent?.let(::startActivity)
                } finally {
                    deviceFeatureController.clearExportProgress()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MAIN_ACTIVITY"
        const val DRIVE_UPLOAD_DIRECTORY_NAME = "drive_uploads"
        const val MAIN_THREAD_DISCONNECT_TIMEOUT_SECONDS = 5L
    }
}
