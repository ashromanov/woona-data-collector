package com.example.myapplication

import android.Manifest
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.AppShellEntryPoint
import com.example.myapplication.DeviceFeatureModuleEntryPoint
import com.example.myapplication.ble.BleTransportProfilePreferences
import com.example.myapplication.data.DogProfile
import com.example.myapplication.data.Recording
import com.example.myapplication.data.RecordingSource
import com.example.myapplication.data.SessionQuestionnaire
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.drive.DriveBackupPreferences
import com.example.myapplication.drive.DriveBackupScheduler
import com.example.myapplication.drive.DriveBackupStatus
import com.example.myapplication.drive.DriveBackupUiState
import com.example.myapplication.drive.DriveFailureSummary
import com.example.myapplication.drive.DriveSetupEffect
import com.example.myapplication.drive.DriveSetupRequest
import com.example.myapplication.drive.DriveSetupViewModel
import com.example.myapplication.drive.GoogleDriveAuthorization
import com.example.myapplication.drive.ManualDriveSaveCoordinator
import com.example.myapplication.drive.ManualDriveSaveResult
import com.example.myapplication.drive.ManualDriveSaveStore
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLocalizationEntryPoint
import com.example.myapplication.localization.AppLanguagePreferences
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.profile.DogQuestionnaireDialog
import com.example.myapplication.profile.ProfilesOverview
import com.example.myapplication.profile.SessionQuestionnaireDialog
import com.example.myapplication.ui.theme.AppThemeMode
import com.example.myapplication.ui.theme.AppThemeEntryPoint
import com.example.myapplication.ui.theme.AppThemePreferences
import com.example.myapplication.ui.theme.applyAppThemeMode
import com.google.android.gms.auth.api.identity.Identity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val woonaDatabase by lazy {
        WoonaDatabase(applicationContext)
    }
    private val profilePreferences by lazy {
        getSharedPreferences(PROFILE_PREFERENCES_NAME, MODE_PRIVATE)
    }
    private val manualDriveSaveStore by lazy {
        ManualDriveSaveStore(File(filesDir, MANUAL_DRIVE_SAVE_STATE_DIRECTORY_NAME))
    }
    private val driveSetupViewModel by viewModels<DriveSetupViewModel> {
        DriveSetupViewModel.factory(
            preferences = driveBackupPreferences,
            scheduler = driveBackupScheduler,
            uploadDirectory = driveUploadDirectory(),
            manualSaveStore = manualDriveSaveStore,
        )
    }
    private var selectedLanguage by mutableStateOf(AppLanguage.ENGLISH)
    private var selectedThemeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private var driveBackupState by mutableStateOf(DriveBackupUiState())
    private var dogProfiles by mutableStateOf<List<DogProfile>>(emptyList())
    private var selectedProfileId by mutableStateOf<String?>(null)
    private var recentRecordings by mutableStateOf<List<Recording>>(emptyList())
    private var isDogQuestionnaireVisible by mutableStateOf(false)
    private var editingProfile by mutableStateOf<DogProfile?>(null)
    private var isSessionQuestionnaireVisible by mutableStateOf(false)
    private var pendingLiveAddress: String? = null
    private var pendingReplayQuestionnaire: SessionQuestionnaire? = null
    private var isChartFullscreenActive = false
    private var isStopSessionInProgress = false
    private var isManualDriveSaveInProgress = false
    private var isCameraPermissionRequestInProgress = false
    private var isActivityDestroyed = false
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

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isCameraPermissionRequestInProgress = false
        if (granted) {
            deviceFeatureController.startVideo()
        } else {
            deviceFeatureController.onVideoPermissionDenied()
        }
    }

    private val replayFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val profileId = selectedProfileId ?: return@registerForActivityResult
        val recording = try {
            woonaDatabase.beginRecording(
                profileId = profileId,
                source = RecordingSource.REPLAY,
                questionnaire = pendingReplayQuestionnaire,
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to prepare replay recording", exception)
            return@registerForActivityResult
        }
        deviceFeatureController.beginRecordingSession(recording)
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
            val authorizationResult = Identity.getAuthorizationClient(applicationContext)
                .getAuthorizationResultFromIntent(result.data)
            driveSetupViewModel.onResolutionAccessToken(authorizationResult.accessToken)
        } catch (exception: Exception) {
            driveSetupViewModel.onAuthorizationFailed(exception)
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
            woonaDatabase = woonaDatabase,
            snapshotDirectory = File(cacheDir, "woona_export_snapshots"),
            onRecordingChanged = {
                runOnUiThread(::refreshProfilesAndRecordings)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedLanguage = appLanguagePreferences.selectedLanguage()
        selectedThemeMode = appThemePreferences.selectedThemeMode()
        applyThemeMode(selectedThemeMode)

        super.onCreate(savedInstanceState)

        woonaDatabase.interruptUnfinished()
        refreshProfilesAndRecordings()
        observeDriveSetup()
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
                            if (selectedProfileId == null) {
                                editingProfile = null
                                isDogQuestionnaireVisible = true
                            } else {
                                deviceFeatureController.onStartScanRequested(
                                    hasPermissions = ::hasBlePermissions,
                                    requestPermissions = {
                                        requestPermissionLauncher.launch(blePermissions)
                                    },
                                )
                            }
                        },
                        onConnect = { mac ->
                            pendingLiveAddress = mac
                            isSessionQuestionnaireVisible = true
                        },
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
                        onSaveToGoogleDrive = { saveCurrentSessionToGoogleDrive() },
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
                                pendingLiveAddress = null
                                pendingReplayQuestionnaire = null
                                isSessionQuestionnaireVisible = true
                            }
                        },
                        overviewProfileContent = {
                            ProfilesOverview(
                                profiles = dogProfiles,
                                selectedProfileId = selectedProfileId,
                                recordings = recentRecordings,
                                language = selectedLanguage,
                                onSelectProfile = ::selectProfile,
                                onCreateProfile = {
                                    editingProfile = null
                                    isDogQuestionnaireVisible = true
                                },
                                onEditProfile = {
                                    editingProfile = it
                                    isDogQuestionnaireVisible = true
                                },
                                onShareRecording = { recordingId ->
                                    prepareShareIntentAsync {
                                        deviceFeatureController.createRecordingShareIntent(
                                            this,
                                            recordingId,
                                        )
                                    }
                                },
                                videoState = uiState.videoState,
                                videoOffsetMillis = uiState.videoOffsetMillis,
                                onVideoAction = ::toggleVideoRecording,
                            )
                        },
                    )
                    if (dogProfiles.isEmpty() || isDogQuestionnaireVisible) {
                        DogQuestionnaireDialog(
                            initial = editingProfile?.questionnaire,
                            required = dogProfiles.isEmpty(),
                            language = selectedLanguage,
                            onDismiss = {
                                editingProfile = null
                                isDogQuestionnaireVisible = false
                            },
                            onSave = { questionnaire ->
                                val saved = woonaDatabase.saveProfile(
                                    questionnaire = questionnaire,
                                    profileId = editingProfile?.id,
                                )
                                editingProfile = null
                                isDogQuestionnaireVisible = false
                                refreshProfilesAndRecordings(saved.id)
                            },
                        )
                    }
                    if (isSessionQuestionnaireVisible) {
                        SessionQuestionnaireDialog(
                            language = selectedLanguage,
                            allowSkip = pendingLiveAddress == null,
                            onDismiss = {
                                pendingLiveAddress = null
                                isSessionQuestionnaireVisible = false
                            },
                            onSkip = {
                                isSessionQuestionnaireVisible = false
                                pendingReplayQuestionnaire = null
                                replayFilePickerLauncher.launch("*/*")
                            },
                            onSave = { questionnaire ->
                                isSessionQuestionnaireVisible = false
                                val address = pendingLiveAddress
                                pendingLiveAddress = null
                                if (address == null) {
                                    pendingReplayQuestionnaire = questionnaire
                                    replayFilePickerLauncher.launch("*/*")
                                } else {
                                    startLiveRecording(address, questionnaire)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isCameraPermissionRequestInProgress) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                deviceFeatureController.onPause()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDriveBackupState()
        resumePendingManualDriveSaveIfReady()
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        setChartFullscreenActive(false)
        if (!isManualDriveSaveInProgress) {
            deviceFeatureController.close()
            woonaDatabase.close()
        }
        backgroundExecutor.shutdownNow()

        super.onDestroy()
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleVideoRecording() {
        when (deviceFeatureController.uiState.videoState) {
            com.example.myapplication.feature.device.VideoCaptureState.STARTING,
            com.example.myapplication.feature.device.VideoCaptureState.RECORDING,
            -> lifecycleScope.launch(Dispatchers.IO) {
                deviceFeatureController.stopVideo()
            }

            com.example.myapplication.feature.device.VideoCaptureState.READY,
            com.example.myapplication.feature.device.VideoCaptureState.FAILED,
            -> {
                if (
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    deviceFeatureController.startVideo()
                } else {
                    isCameraPermissionRequestInProgress = true
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            else -> Unit
        }
    }

    private fun selectProfile(profileId: String) {
        if (dogProfiles.none { it.id == profileId }) return
        selectedProfileId = profileId
        profilePreferences.edit().putString(LAST_PROFILE_ID_KEY, profileId).apply()
        recentRecordings = woonaDatabase.recentRecordings(profileId)
    }

    private fun refreshProfilesAndRecordings(preferredProfileId: String? = null) {
        val profiles = woonaDatabase.profiles()
        dogProfiles = profiles
        val storedId = profilePreferences.getString(LAST_PROFILE_ID_KEY, null)
        val selectedId = sequenceOf(preferredProfileId, selectedProfileId, storedId)
            .filterNotNull()
            .firstOrNull { candidate -> profiles.any { it.id == candidate } }
            ?: profiles.firstOrNull()?.id
        selectedProfileId = selectedId
        if (selectedId == null) {
            recentRecordings = emptyList()
            editingProfile = null
            isDogQuestionnaireVisible = true
        } else {
            profilePreferences.edit().putString(LAST_PROFILE_ID_KEY, selectedId).apply()
            recentRecordings = woonaDatabase.recentRecordings(selectedId)
        }
    }

    private fun startLiveRecording(
        address: String,
        questionnaire: SessionQuestionnaire,
    ) {
        val profileId = selectedProfileId ?: return
        try {
            val recording = woonaDatabase.beginRecording(
                profileId = profileId,
                source = RecordingSource.LIVE,
                questionnaire = questionnaire,
            )
            deviceFeatureController.beginRecordingSession(recording)
            deviceFeatureController.onConnectRequested(address)
            refreshProfilesAndRecordings()
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to prepare live recording", exception)
            Toast.makeText(
                this,
                if (selectedLanguage == AppLanguage.RUSSIAN) {
                    "Не удалось создать запись"
                } else {
                    "Failed to create recording"
                },
                Toast.LENGTH_SHORT,
            ).show()
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
        saveCurrentSessionAfterSuccess: Boolean = false,
    ) {
        val started = driveSetupViewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = enableAutoUploadAfterSuccess,
                retryPendingAfterSuccess = retryPendingAfterSuccess,
                clearAuthorizationOnFailure = clearAuthorizationOnFailure,
                saveCurrentSessionAfterSuccess = saveCurrentSessionAfterSuccess,
            ),
        )
        if (!started) return

        try {
            Identity.getAuthorizationClient(applicationContext)
                .authorize(GoogleDriveAuthorization.createAuthorizationRequest())
                .addOnSuccessListener { authorizationResult ->
                    if (authorizationResult.hasResolution()) {
                        val pendingIntent = authorizationResult.pendingIntent
                        if (pendingIntent == null) {
                            driveSetupViewModel.onAuthorizationFailed(
                                IllegalStateException(
                                    "Google Drive authorization did not return a resolution",
                                ),
                            )
                        } else {
                            driveSetupViewModel.requestResolution(pendingIntent)
                        }
                    } else {
                        driveSetupViewModel.onAuthorizationAccessToken(authorizationResult.accessToken)
                    }
                }
                .addOnFailureListener(driveSetupViewModel::onAuthorizationFailed)
                .addOnCanceledListener {
                    driveSetupViewModel.onAuthorizationFailed(
                        java.util.concurrent.CancellationException(
                            "Google Drive authorization was cancelled",
                        ),
                    )
                }
        } catch (exception: Exception) {
            driveSetupViewModel.onAuthorizationFailed(exception)
        }
    }

    private fun observeDriveSetup() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                driveSetupViewModel.state.collect {
                    refreshDriveBackupState()
                    resumePendingManualDriveSaveIfReady()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                driveSetupViewModel.effects.collect(::handleDriveSetupEffect)
            }
        }
    }

    private fun handleDriveSetupEffect(effect: DriveSetupEffect) {
        when (effect) {
            is DriveSetupEffect.LaunchResolution -> {
                try {
                    driveAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(effect.pendingIntent.intentSender).build(),
                    )
                    driveSetupViewModel.markResolutionLaunched()
                } catch (exception: Exception) {
                    driveSetupViewModel.onAuthorizationFailed(exception)
                }
            }

            is DriveSetupEffect.Completed -> {
                if (effect.pendingUploadsEnqueued) {
                    awaitingDriveWorkObservation = true
                }
                refreshDriveBackupState()
                if (effect.saveCurrentSessionRequested) {
                    resumePendingManualDriveSaveIfReady()
                } else {
                    Toast.makeText(
                        this,
                        appTextResolver.getString(
                            if (effect.pendingUploadsEnqueued) {
                                R.string.drive_backup_queued
                            } else {
                                R.string.drive_backup_authorized
                            },
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            is DriveSetupEffect.AuthorizationFailed -> showDriveAuthorizationFailure(effect.cause)
            is DriveSetupEffect.SetupFailed -> showDriveSetupFailure(effect.cause)
        }
    }

    private fun showDriveAuthorizationFailure(cause: Exception?) {
        if (cause == null) {
            Log.w(TAG, "Google Drive authorization failed")
        } else {
            Log.w(TAG, "Google Drive authorization failed", cause)
        }
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

    private fun showDriveSetupFailure(cause: Exception) {
        Log.w(TAG, "Google Drive setup failed", cause)
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
        isStopSessionInProgress = true
        if (shouldQueueDriveBackup) {
            setDriveForegroundStatus(DriveBackupStatus.PREPARING)
        }
        lifecycleScope.launch {
            try {
                val captureFinished = withContext(Dispatchers.IO) {
                    deviceFeatureController.finishCaptureForExport()
                }
                deviceFeatureController.onDisconnectRequested()
                if (!shouldQueueDriveBackup) {
                    refreshDriveBackupState()
                    return@launch
                }

                when {
                    !captureFinished -> handleSkippedDriveBackup(R.string.drive_backup_capture_not_ready)
                    !withContext(Dispatchers.IO) {
                        driveBackupScheduler.hasPendingCapacity(driveUploadDirectory())
                    } -> handleSkippedDriveBackup(R.string.drive_backup_pending_limit_reached)
                    else -> {
                        val archiveFile = withContext(Dispatchers.IO) {
                            deviceFeatureController.createSessionArchive(driveUploadDirectory())
                        }
                        handlePreparedDriveBackup(
                            archiveFile = archiveFile,
                            wifiOnly = settings.wifiOnly,
                        )
                    }
                }
            } finally {
                if (driveForegroundStatus == DriveBackupStatus.PREPARING) {
                    driveForegroundStatus = null
                    refreshDriveBackupState()
                }
                isStopSessionInProgress = false
            }
        }
    }

    private fun resumePendingManualDriveSaveIfReady() {
        if (!driveBackupPreferences.settings().isAuthorized) return
        if (manualDriveSaveStore.pendingArchive() == null) return
        saveCurrentSessionToGoogleDrive()
    }

    private fun saveCurrentSessionToGoogleDrive(): Boolean {
        if (
            driveSetupViewModel.state.value.isInProgress ||
            isManualDriveSaveInProgress ||
            !ManualDriveSaveCoordinator.tryClaim()
        ) {
            return false
        }

        val settings = driveBackupPreferences.settings()
        isManualDriveSaveInProgress = true
        setDriveForegroundStatus(DriveBackupStatus.PREPARING)
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val coordinator = ManualDriveSaveCoordinator(
                store = manualDriveSaveStore,
                hasPendingCapacity = {
                    driveBackupScheduler.hasPendingCapacity(driveUploadDirectory())
                },
                createArchive = {
                    deviceFeatureController.createSessionArchive(driveUploadDirectory())
                },
                enqueueArchive = { archiveFile, wifiOnly ->
                    driveBackupScheduler.enqueueArchive(
                        archiveFile = archiveFile,
                        wifiOnly = wifiOnly,
                    ).result.get()
                },
            )
            val result = try {
                withContext(NonCancellable + Dispatchers.IO) {
                    coordinator.save(
                        isAuthorized = settings.isAuthorized,
                        wifiOnly = settings.wifiOnly,
                    )
                }
            } catch (exception: Exception) {
                ManualDriveSaveResult.Failed(exception)
            }

            ManualDriveSaveCoordinator.releaseClaim()
            isManualDriveSaveInProgress = false
            if (isActivityDestroyed) {
                deviceFeatureController.close()
            } else {
                driveForegroundStatus = null
                deviceFeatureController.clearExportProgress()
                when (result) {
                    ManualDriveSaveResult.AuthorizationRequired -> {
                        requestDriveAuthorization(
                            enableAutoUploadAfterSuccess = false,
                            retryPendingAfterSuccess = false,
                            clearAuthorizationOnFailure = true,
                            saveCurrentSessionAfterSuccess = true,
                        )
                    }

                    ManualDriveSaveResult.Queued -> {
                        awaitingDriveWorkObservation = true
                        Toast.makeText(
                            this@MainActivity,
                            appTextResolver.getString(R.string.drive_backup_queued),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    ManualDriveSaveResult.NoFiles -> Toast.makeText(
                        this@MainActivity,
                        appTextResolver.getString(R.string.drive_backup_no_files),
                        Toast.LENGTH_SHORT,
                    ).show()

                    ManualDriveSaveResult.PendingLimitReached -> Toast.makeText(
                        this@MainActivity,
                        appTextResolver.getString(R.string.drive_backup_pending_limit_reached),
                        Toast.LENGTH_SHORT,
                    ).show()

                    is ManualDriveSaveResult.Failed -> {
                        Log.e(TAG, "Failed to queue manual Google Drive save", result.cause)
                        Toast.makeText(
                            this@MainActivity,
                            appTextResolver.getString(R.string.drive_backup_prepare_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                refreshDriveBackupState()
            }
        }
        return true
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
        if (driveSetupViewModel.state.value.isInProgress) return DriveBackupStatus.CONNECTING
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
        const val MANUAL_DRIVE_SAVE_STATE_DIRECTORY_NAME = "manual_drive_save"
        const val PROFILE_PREFERENCES_NAME = "woona_profiles"
        const val LAST_PROFILE_ID_KEY = "last_profile_id"
    }
}
