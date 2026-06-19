package com.example.myapplication.drive

import java.io.File
import java.io.IOException

sealed class DriveUploadResult {
    object Success : DriveUploadResult()
    data class AuthorizationRequired(val exception: Exception) : DriveUploadResult()
    data class RetryLater(val exception: Exception) : DriveUploadResult()
    data class PermanentFailure(val exception: Exception) : DriveUploadResult()
}

class GoogleDriveArchiveUploader(
    private val uploader: DriveUploader = GoogleDriveUploader(),
) {
    fun prepareBackupFolder(
        accessToken: String,
        saveFolderId: (String?) -> Unit,
    ): DriveUploadResult {
        return try {
            findOrCreateAndRememberFolder(
                accessToken = accessToken,
                saveFolderId = saveFolderId,
            )
            DriveUploadResult.Success
        } catch (exception: GoogleDriveRequestException) {
            exception.toUploadResult()
        } catch (exception: IOException) {
            DriveUploadResult.RetryLater(exception)
        }
    }

    fun upload(
        accessToken: String,
        cachedFolderId: String?,
        archiveFile: File,
        saveFolderId: (String?) -> Unit,
    ): DriveUploadResult {
        return try {
            val folderId = cachedFolderId ?: findOrCreateAndRememberFolder(
                accessToken = accessToken,
                saveFolderId = saveFolderId,
            )
            uploadWithFolderRecovery(
                accessToken = accessToken,
                cachedFolderId = cachedFolderId,
                folderId = folderId,
                archiveFile = archiveFile,
                saveFolderId = saveFolderId,
            )
            DriveUploadResult.Success
        } catch (exception: GoogleDriveRequestException) {
            exception.toUploadResult()
        } catch (exception: IOException) {
            DriveUploadResult.RetryLater(exception)
        }
    }

    private fun uploadWithFolderRecovery(
        accessToken: String,
        cachedFolderId: String?,
        folderId: String,
        archiveFile: File,
        saveFolderId: (String?) -> Unit,
    ) {
        try {
            uploader.uploadArchive(
                accessToken = accessToken,
                folderId = folderId,
                archiveFile = archiveFile,
            )
        } catch (exception: GoogleDriveRequestException) {
            if (!exception.isNotFound() || cachedFolderId == null) {
                throw exception
            }

            saveFolderId(null)
            val replacementFolderId = findOrCreateAndRememberFolder(
                accessToken = accessToken,
                saveFolderId = saveFolderId,
            )
            uploader.uploadArchive(
                accessToken = accessToken,
                folderId = replacementFolderId,
                archiveFile = archiveFile,
            )
        }
    }

    private fun findOrCreateAndRememberFolder(
        accessToken: String,
        saveFolderId: (String?) -> Unit,
    ): String {
        val existingFolderId = uploader.findBackupFolder(accessToken)
        if (!existingFolderId.isNullOrBlank()) {
            saveFolderId(existingFolderId)
            return existingFolderId
        }

        return createAndRememberFolder(
            accessToken = accessToken,
            saveFolderId = saveFolderId,
        )
    }

    private fun createAndRememberFolder(
        accessToken: String,
        saveFolderId: (String?) -> Unit,
    ): String {
        val folderId = uploader.createBackupFolder(accessToken)
        saveFolderId(folderId)
        return folderId
    }

    private fun GoogleDriveRequestException.toUploadResult(): DriveUploadResult {
        return when {
            isAuthorizationFailure() -> DriveUploadResult.AuthorizationRequired(this)
            isRetryable() -> DriveUploadResult.RetryLater(this)
            else -> DriveUploadResult.PermanentFailure(this)
        }
    }
}
