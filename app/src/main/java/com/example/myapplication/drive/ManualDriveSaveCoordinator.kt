package com.example.myapplication.drive

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class ManualDriveSaveCoordinator(
    private val store: ManualDriveSaveStore,
    private val hasPendingCapacity: () -> Boolean,
    private val createArchive: () -> File?,
    private val enqueueArchive: (File, Boolean) -> Unit,
) {
    fun save(
        isAuthorized: Boolean,
        wifiOnly: Boolean,
    ): ManualDriveSaveResult {
        return try {
            val archiveFile = store.pendingArchive() ?: run {
                if (!hasPendingCapacity()) {
                    return ManualDriveSaveResult.PendingLimitReached
                }

                val createdArchive = createArchive() ?: return ManualDriveSaveResult.NoFiles
                try {
                    store.savePendingArchive(createdArchive)
                } catch (exception: Exception) {
                    createdArchive.delete()
                    throw exception
                }
                createdArchive
            }

            if (!isAuthorized) return ManualDriveSaveResult.AuthorizationRequired
            enqueueArchive(archiveFile, wifiOnly)
            store.clear()
            ManualDriveSaveResult.Queued
        } catch (exception: Exception) {
            ManualDriveSaveResult.Failed(exception)
        }
    }

    companion object {
        private val runtimeClaim = AtomicBoolean(false)

        fun tryClaim(): Boolean = runtimeClaim.compareAndSet(false, true)

        fun releaseClaim() {
            runtimeClaim.set(false)
        }
    }
}

internal sealed interface ManualDriveSaveResult {
    data object AuthorizationRequired : ManualDriveSaveResult
    data object Queued : ManualDriveSaveResult
    data object NoFiles : ManualDriveSaveResult
    data object PendingLimitReached : ManualDriveSaveResult
    data class Failed(val cause: Exception) : ManualDriveSaveResult
}
