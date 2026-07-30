package com.example.myapplication.drive

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSetupViewModelTest {
    @Test
    fun begin_rejectsDuplicateRequestsUntilTheFlowFinishes() {
        val viewModel = createViewModel()
        val request = DriveSetupRequest(
            enableAutoUploadAfterSuccess = false,
            retryPendingAfterSuccess = false,
            clearAuthorizationOnFailure = true,
        )

        assertTrue(viewModel.begin(request))
        assertFalse(viewModel.begin(request.copy(enableAutoUploadAfterSuccess = true)))
        assertEquals(DriveSetupPhase.AUTHORIZING, viewModel.state.value.phase)

        viewModel.onAuthorizationFailed(null)

        assertEquals(DriveSetupPhase.IDLE, viewModel.state.value.phase)
        assertTrue(viewModel.begin(request))
    }

    @Test
    fun restoredResolutionResult_preservesRequestIntentAndCompletesSetup() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val original = createViewModel(savedStateHandle = savedStateHandle)
        val request = DriveSetupRequest(
            enableAutoUploadAfterSuccess = true,
            retryPendingAfterSuccess = true,
            clearAuthorizationOnFailure = false,
            saveCurrentSessionAfterSuccess = true,
        )
        assertTrue(original.begin(request))
        assertTrue(original.markResolutionReady())
        original.markResolutionLaunched()

        val authorizedValues = mutableListOf<Boolean>()
        val autoUploadValues = mutableListOf<Boolean>()
        var enqueuePendingCalls = 0
        val restored = createViewModel(
            savedStateHandle = savedStateHandle,
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.Success },
                setAuthorized = authorizedValues::add,
                setAutoUploadEnabled = autoUploadValues::add,
                enqueuePendingUploads = { enqueuePendingCalls++ },
            ),
        )

        assertEquals(DriveSetupPhase.IDLE, restored.state.value.phase)

        restored.onResolutionAccessToken("token")

        assertEquals(DriveSetupPhase.IDLE, restored.state.value.phase)
        assertEquals(listOf(true), authorizedValues)
        assertEquals(listOf(true), autoUploadValues)
        assertEquals(1, enqueuePendingCalls)
        assertEquals(
            DriveSetupEffect.Completed(
                pendingUploadsEnqueued = true,
                saveCurrentSessionRequested = true,
            ),
            restored.effects.first(),
        )
    }

    @Test
    fun manualSaveAuthorization_resumesOnlyTheCurrentSessionSave() = runBlocking {
        var enqueuePendingCalls = 0
        val autoUploadValues = mutableListOf<Boolean>()
        val viewModel = createViewModel(
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.Success },
                setAuthorized = {},
                setAutoUploadEnabled = autoUploadValues::add,
                enqueuePendingUploads = { enqueuePendingCalls++ },
            ),
        )
        viewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = false,
                retryPendingAfterSuccess = false,
                clearAuthorizationOnFailure = true,
                saveCurrentSessionAfterSuccess = true,
            ),
        )

        viewModel.onAuthorizationAccessToken("token")

        assertEquals(0, enqueuePendingCalls)
        assertTrue(autoUploadValues.isEmpty())
        assertEquals(
            DriveSetupEffect.Completed(
                pendingUploadsEnqueued = false,
                saveCurrentSessionRequested = true,
            ),
            viewModel.effects.first(),
        )
    }

    @Test
    fun manualSaveAuthorizationFailure_discardsThePreparedArtifact() = runBlocking {
        var discardCalls = 0
        val failure = IllegalStateException("cancelled")
        val viewModel = createViewModel(
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.Success },
                setAuthorized = {},
                setAutoUploadEnabled = {},
                enqueuePendingUploads = {},
                discardManualSave = { discardCalls++ },
            ),
        )
        viewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = false,
                retryPendingAfterSuccess = false,
                clearAuthorizationOnFailure = true,
                saveCurrentSessionAfterSuccess = true,
            ),
        )

        viewModel.onAuthorizationFailed(failure)

        assertEquals(1, discardCalls)
        assertEquals(
            DriveSetupEffect.AuthorizationFailed(
                clearStoredAuthorization = true,
                cause = failure,
            ),
            viewModel.effects.first(),
        )
    }

    @Test
    fun manualSaveSetupFailure_discardsThePreparedArtifact() = runBlocking {
        var discardCalls = 0
        val failure = IllegalStateException("folder setup failed")
        val viewModel = createViewModel(
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.PermanentFailure(failure) },
                setAuthorized = {},
                setAutoUploadEnabled = {},
                enqueuePendingUploads = {},
                discardManualSave = { discardCalls++ },
            ),
        )
        viewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = false,
                retryPendingAfterSuccess = false,
                clearAuthorizationOnFailure = true,
                saveCurrentSessionAfterSuccess = true,
            ),
        )

        viewModel.onAuthorizationAccessToken("token")

        assertEquals(1, discardCalls)
        assertEquals(DriveSetupEffect.SetupFailed(failure), viewModel.effects.first())
    }

    @Test
    fun nonResumableAuthorizationRequest_resetsAfterProcessRecreation() {
        val savedStateHandle = SavedStateHandle()
        val original = createViewModel(savedStateHandle = savedStateHandle)
        val request = DriveSetupRequest(
            enableAutoUploadAfterSuccess = false,
            retryPendingAfterSuccess = false,
            clearAuthorizationOnFailure = true,
        )
        assertTrue(original.begin(request))

        val restored = createViewModel(savedStateHandle = savedStateHandle)

        assertEquals(DriveSetupPhase.IDLE, restored.state.value.phase)
        assertTrue(restored.begin(request))
    }

    @Test
    fun authorizationFailure_preservesExistingAuthorizationWhenRequested() = runBlocking {
        val authorizedValues = mutableListOf<Boolean>()
        val autoUploadValues = mutableListOf<Boolean>()
        val viewModel = createViewModel(
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.Success },
                setAuthorized = authorizedValues::add,
                setAutoUploadEnabled = autoUploadValues::add,
                enqueuePendingUploads = {},
            ),
        )
        viewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = false,
                retryPendingAfterSuccess = true,
                clearAuthorizationOnFailure = false,
            ),
        )
        val failure = IllegalStateException("cancelled")

        viewModel.onAuthorizationFailed(failure)

        assertTrue(authorizedValues.isEmpty())
        assertTrue(autoUploadValues.isEmpty())
        assertEquals(
            DriveSetupEffect.AuthorizationFailed(
                clearStoredAuthorization = false,
                cause = failure,
            ),
            viewModel.effects.first(),
        )
    }

    @Test
    fun retrySchedulingFailure_finishesFlowAndReportsTheFailure() = runBlocking {
        val failure = IllegalStateException("scheduler unavailable")
        val viewModel = createViewModel(
            actions = DriveSetupActions(
                prepareBackupFolder = { DriveUploadResult.Success },
                setAuthorized = {},
                setAutoUploadEnabled = {},
                enqueuePendingUploads = { throw failure },
            ),
        )
        viewModel.begin(
            DriveSetupRequest(
                enableAutoUploadAfterSuccess = false,
                retryPendingAfterSuccess = true,
                clearAuthorizationOnFailure = false,
            ),
        )

        viewModel.onAuthorizationAccessToken("token")

        assertEquals(DriveSetupPhase.IDLE, viewModel.state.value.phase)
        assertEquals(DriveSetupEffect.SetupFailed(failure), viewModel.effects.first())
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        actions: DriveSetupActions = DriveSetupActions(
            prepareBackupFolder = { DriveUploadResult.Success },
            setAuthorized = {},
            setAutoUploadEnabled = {},
            enqueuePendingUploads = {},
        ),
    ): DriveSetupViewModel {
        val dispatcher = Dispatchers.Unconfined
        return DriveSetupViewModel(
            savedStateHandle = savedStateHandle,
            actions = actions,
            ioDispatcher = dispatcher,
            operationScope = CoroutineScope(dispatcher),
        )
    }
}
