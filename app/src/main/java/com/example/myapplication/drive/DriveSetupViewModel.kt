package com.example.myapplication.drive

import android.app.PendingIntent
import java.io.File
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DriveSetupRequest(
    val enableAutoUploadAfterSuccess: Boolean,
    val retryPendingAfterSuccess: Boolean,
    val clearAuthorizationOnFailure: Boolean,
    val saveCurrentSessionAfterSuccess: Boolean = false,
)

internal enum class DriveSetupPhase {
    IDLE,
    AUTHORIZING,
    RESOLUTION_READY,
    AWAITING_RESOLUTION,
    PREPARING_FOLDER,
}

internal data class DriveSetupState(
    val phase: DriveSetupPhase = DriveSetupPhase.IDLE,
) {
    val isInProgress: Boolean
        get() = phase != DriveSetupPhase.IDLE
}

internal sealed interface DriveSetupEffect {
    data class LaunchResolution(val pendingIntent: PendingIntent) : DriveSetupEffect
    data class Completed(
        val pendingUploadsEnqueued: Boolean,
        val saveCurrentSessionRequested: Boolean,
    ) : DriveSetupEffect
    data class AuthorizationFailed(
        val clearStoredAuthorization: Boolean,
        val cause: Exception?,
    ) : DriveSetupEffect
    data class SetupFailed(val cause: Exception) : DriveSetupEffect
}

internal data class DriveSetupActions(
    val prepareBackupFolder: (String) -> DriveUploadResult,
    val setAuthorized: (Boolean) -> Unit,
    val setAutoUploadEnabled: (Boolean) -> Unit,
    val enqueuePendingUploads: () -> Unit,
    val discardManualSave: () -> Unit = {},
)

internal class DriveSetupViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val actions: DriveSetupActions,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val operationScope: CoroutineScope? = null,
) : ViewModel() {
    private val restoredPhase = savedStateHandle.get<String>(KEY_PHASE)
        ?.let { phaseName ->
            DriveSetupPhase.entries.firstOrNull { phase -> phase.name == phaseName }
        }
        ?: DriveSetupPhase.IDLE

    private var hasRecoverableResolutionResult =
        restoredPhase == DriveSetupPhase.AWAITING_RESOLUTION

    private val mutableState = MutableStateFlow(DriveSetupState())
    val state: StateFlow<DriveSetupState> = mutableState

    private val effectChannel = Channel<DriveSetupEffect>(Channel.UNLIMITED)
    val effects = effectChannel.receiveAsFlow()

    init {
        when (restoredPhase) {
            DriveSetupPhase.AWAITING_RESOLUTION -> savedStateHandle.remove<String>(KEY_PHASE)
            DriveSetupPhase.IDLE -> Unit
            else -> clearSavedRequest()
        }
    }

    fun begin(request: DriveSetupRequest): Boolean {
        if (mutableState.value.isInProgress) return false

        hasRecoverableResolutionResult = false
        saveRequest(request)
        transitionTo(DriveSetupPhase.AUTHORIZING)
        return true
    }

    fun requestResolution(pendingIntent: PendingIntent) {
        if (!markResolutionReady()) return
        effectChannel.trySend(DriveSetupEffect.LaunchResolution(pendingIntent))
    }

    internal fun markResolutionReady(): Boolean {
        if (mutableState.value.phase != DriveSetupPhase.AUTHORIZING) return false
        transitionTo(DriveSetupPhase.RESOLUTION_READY)
        return true
    }

    fun markResolutionLaunched() {
        if (mutableState.value.phase == DriveSetupPhase.RESOLUTION_READY) {
            transitionTo(DriveSetupPhase.AWAITING_RESOLUTION)
        }
    }

    fun onAuthorizationAccessToken(accessToken: String?) {
        if (mutableState.value.phase != DriveSetupPhase.AUTHORIZING) return
        prepareBackupFolder(accessToken)
    }

    fun onResolutionAccessToken(accessToken: String?) {
        when (mutableState.value.phase) {
            DriveSetupPhase.RESOLUTION_READY,
            DriveSetupPhase.AWAITING_RESOLUTION,
            -> prepareBackupFolder(accessToken)

            DriveSetupPhase.IDLE -> {
                if (!hasRecoverableResolutionResult) {
                    saveRequest(DEFAULT_RECOVERED_REQUEST)
                }
                hasRecoverableResolutionResult = false
                transitionTo(DriveSetupPhase.AWAITING_RESOLUTION)
                prepareBackupFolder(accessToken)
            }

            DriveSetupPhase.AUTHORIZING,
            DriveSetupPhase.PREPARING_FOLDER,
            -> Unit
        }
    }

    fun onAuthorizationFailed(cause: Exception?) {
        if (!mutableState.value.isInProgress) {
            if (!hasRecoverableResolutionResult) {
                saveRequest(DEFAULT_RECOVERED_REQUEST)
            }
            hasRecoverableResolutionResult = false
            transitionTo(DriveSetupPhase.AUTHORIZING)
        }

        val request = activeRequest()
        if (request.clearAuthorizationOnFailure) {
            actions.setAuthorized(false)
            actions.setAutoUploadEnabled(false)
        }
        if (request.saveCurrentSessionAfterSuccess) {
            actions.discardManualSave()
        }
        finishFlow()
        effectChannel.trySend(
            DriveSetupEffect.AuthorizationFailed(
                clearStoredAuthorization = request.clearAuthorizationOnFailure,
                cause = cause,
            ),
        )
    }

    private fun prepareBackupFolder(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            onAuthorizationFailed(
                IllegalStateException("Google Drive authorization did not return an access token"),
            )
            return
        }

        transitionTo(DriveSetupPhase.PREPARING_FOLDER)
        (operationScope ?: viewModelScope).launch {
            val setupResult = withContext(ioDispatcher) {
                try {
                    actions.prepareBackupFolder(accessToken)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    DriveUploadResult.PermanentFailure(exception)
                }
            }
            handleFolderPreparationResult(setupResult)
        }
    }

    private suspend fun handleFolderPreparationResult(setupResult: DriveUploadResult) {
        if (mutableState.value.phase != DriveSetupPhase.PREPARING_FOLDER) return

        when (setupResult) {
            DriveUploadResult.Success -> {
                val request = activeRequest()
                actions.setAuthorized(true)
                if (request.enableAutoUploadAfterSuccess) {
                    actions.setAutoUploadEnabled(true)
                }
                val retryFailure = if (request.retryPendingAfterSuccess) {
                    withContext(ioDispatcher) {
                        try {
                            actions.enqueuePendingUploads()
                            null
                        } catch (exception: Exception) {
                            exception
                        }
                    }
                } else {
                    null
                }
                if (retryFailure != null && request.saveCurrentSessionAfterSuccess) {
                    actions.discardManualSave()
                }
                finishFlow()
                if (retryFailure == null) {
                    effectChannel.trySend(
                        DriveSetupEffect.Completed(
                            pendingUploadsEnqueued = request.retryPendingAfterSuccess,
                            saveCurrentSessionRequested = request.saveCurrentSessionAfterSuccess,
                        ),
                    )
                } else {
                    effectChannel.trySend(DriveSetupEffect.SetupFailed(retryFailure))
                }
            }

            is DriveUploadResult.AuthorizationRequired -> onAuthorizationFailed(setupResult.exception)
            is DriveUploadResult.PermanentFailure -> finishWithSetupFailure(setupResult.exception)
            is DriveUploadResult.RetryLater -> finishWithSetupFailure(setupResult.exception)
        }
    }

    private fun finishWithSetupFailure(cause: Exception) {
        val request = activeRequest()
        if (request.saveCurrentSessionAfterSuccess) {
            actions.discardManualSave()
        }
        finishFlow()
        effectChannel.trySend(DriveSetupEffect.SetupFailed(cause))
    }

    private fun activeRequest(): DriveSetupRequest {
        return DriveSetupRequest(
            enableAutoUploadAfterSuccess = savedStateHandle[KEY_ENABLE_AUTO_UPLOAD] ?: false,
            retryPendingAfterSuccess = savedStateHandle[KEY_RETRY_PENDING] ?: false,
            clearAuthorizationOnFailure = savedStateHandle[KEY_CLEAR_AUTHORIZATION] ?: true,
            saveCurrentSessionAfterSuccess = savedStateHandle[KEY_SAVE_CURRENT_SESSION] ?: false,
        )
    }

    private fun saveRequest(request: DriveSetupRequest) {
        savedStateHandle[KEY_ENABLE_AUTO_UPLOAD] = request.enableAutoUploadAfterSuccess
        savedStateHandle[KEY_RETRY_PENDING] = request.retryPendingAfterSuccess
        savedStateHandle[KEY_CLEAR_AUTHORIZATION] = request.clearAuthorizationOnFailure
        savedStateHandle[KEY_SAVE_CURRENT_SESSION] = request.saveCurrentSessionAfterSuccess
    }

    private fun transitionTo(phase: DriveSetupPhase) {
        savedStateHandle[KEY_PHASE] = phase.name
        mutableState.value = DriveSetupState(phase)
    }

    private fun finishFlow() {
        transitionTo(DriveSetupPhase.IDLE)
        clearSavedRequest()
    }

    private fun clearSavedRequest() {
        savedStateHandle.remove<String>(KEY_PHASE)
        savedStateHandle.remove<Boolean>(KEY_ENABLE_AUTO_UPLOAD)
        savedStateHandle.remove<Boolean>(KEY_RETRY_PENDING)
        savedStateHandle.remove<Boolean>(KEY_CLEAR_AUTHORIZATION)
        savedStateHandle.remove<Boolean>(KEY_SAVE_CURRENT_SESSION)
    }

    companion object {
        private const val KEY_PHASE = "drive_setup_phase"
        private const val KEY_ENABLE_AUTO_UPLOAD = "drive_setup_enable_auto_upload"
        private const val KEY_RETRY_PENDING = "drive_setup_retry_pending"
        private const val KEY_CLEAR_AUTHORIZATION = "drive_setup_clear_authorization"
        private const val KEY_SAVE_CURRENT_SESSION = "drive_setup_save_current_session"
        private val DEFAULT_RECOVERED_REQUEST = DriveSetupRequest(
            enableAutoUploadAfterSuccess = false,
            retryPendingAfterSuccess = false,
            clearAuthorizationOnFailure = true,
            saveCurrentSessionAfterSuccess = false,
        )

        fun factory(
            preferences: DriveBackupPreferences,
            scheduler: DriveBackupScheduler,
            uploadDirectory: File,
            manualSaveStore: ManualDriveSaveStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DriveSetupViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    actions = DriveSetupActions(
                        prepareBackupFolder = { accessToken ->
                            GoogleDriveArchiveUploader().prepareBackupFolder(
                                accessToken = accessToken,
                                saveFolderId = preferences::setFolderId,
                            )
                        },
                        setAuthorized = preferences::setAuthorized,
                        setAutoUploadEnabled = preferences::setAutoUploadEnabled,
                        enqueuePendingUploads = {
                            scheduler.enqueuePendingArchives(
                                uploadDirectory = uploadDirectory,
                                wifiOnly = preferences.settings().wifiOnly,
                            )
                        },
                        discardManualSave = manualSaveStore::discardPendingArchive,
                    ),
                )
            }
        }
    }
}
