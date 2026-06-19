package com.example.myapplication.drive

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException

class GoogleDriveUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val archivePath = inputData.getString(KEY_ARCHIVE_PATH) ?: return Result.failure()
        val archiveFile = File(archivePath)
        if (!archiveFile.exists()) return Result.success()

        val preferences = DriveBackupPreferences(applicationContext)
        val settings = preferences.settings()
        if (!settings.isAuthorized) return Result.success()

        val accessToken = try {
            GoogleDriveAuthorization.requestAccessToken(applicationContext)
        } catch (exception: DriveAuthorizationRequiredException) {
            Log.w(TAG, "Google Drive authorization needs user interaction", exception)
            return Result.success()
        } catch (exception: Exception) {
            Log.w(TAG, "Google Drive access token unavailable; retrying later", exception)
            return Result.retry()
        }

        return try {
            val uploadResult = GoogleDriveArchiveUploader().upload(
                accessToken = accessToken,
                archiveFile = archiveFile,
                cachedFolderId = settings.folderId,
                saveFolderId = preferences::setFolderId,
            )
            when (uploadResult) {
                DriveUploadResult.Success -> {
                    if (!archiveFile.delete()) {
                        Log.w(TAG, "Uploaded archive but failed to delete local file ${archiveFile.absolutePath}")
                        quarantineArchive(archiveFile)
                    }
                    Result.success()
                }
                is DriveUploadResult.AuthorizationRequired -> {
                    Log.w(TAG, "Google Drive upload needs foreground authorization", uploadResult.exception)
                    Result.success()
                }
                is DriveUploadResult.RetryLater -> {
                    Log.w(TAG, "Google Drive upload failed; retrying later", uploadResult.exception)
                    Result.retry()
                }
                is DriveUploadResult.PermanentFailure -> {
                    Log.e(TAG, "Google Drive upload failed permanently", uploadResult.exception)
                    quarantineArchive(archiveFile)
                    Result.success()
                }
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Google Drive upload failed; retrying later", exception)
            Result.retry()
        } catch (exception: Exception) {
            Log.e(TAG, "Google Drive upload failed", exception)
            quarantineArchive(archiveFile)
            Result.success()
        }
    }

    private fun quarantineArchive(archiveFile: File) {
        val quarantinedFile = DriveBackupQueueLimits.quarantineArchive(archiveFile)
        if (quarantinedFile == null) {
            Log.w(TAG, "Failed to quarantine archive ${archiveFile.absolutePath}")
        } else {
            Log.w(TAG, "Moved archive to failed backup queue ${quarantinedFile.absolutePath}")
        }
    }

    companion object {
        const val KEY_ARCHIVE_PATH = "archive_path"
        private const val TAG = "GOOGLE_DRIVE_UPLOAD"
    }
}
