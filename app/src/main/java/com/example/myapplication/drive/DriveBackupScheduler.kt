package com.example.myapplication.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class DriveBackupScheduler(
    private val context: Context,
) {
    fun enqueueArchive(
        archiveFile: File,
        wifiOnly: Boolean,
    ): Operation {
        val networkType = if (wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<GoogleDriveUploadWorker>()
            .setInputData(workDataOf(GoogleDriveUploadWorker.KEY_ARCHIVE_PATH to archiveFile.absolutePath))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_TAG)
            .build()

        return WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
    }

    fun enqueuePendingArchives(
        uploadDirectory: File,
        wifiOnly: Boolean,
    ) {
        DriveBackupQueueLimits.restoreFailedArchives(uploadDirectory)
        DriveBackupQueueLimits.pendingArchives(uploadDirectory).forEach { archive ->
            enqueueArchive(archiveFile = archive, wifiOnly = wifiOnly)
        }
    }

    fun pendingUploadCount(uploadDirectory: File): Int {
        return DriveBackupQueueLimits.pendingArchiveCount(uploadDirectory)
    }

    fun failedUploadCount(uploadDirectory: File): Int {
        return DriveBackupQueueLimits.failedArchiveCount(uploadDirectory)
    }

    fun retryableUploadCount(uploadDirectory: File): Int {
        return pendingUploadCount(uploadDirectory) + failedUploadCount(uploadDirectory)
    }

    fun hasPendingCapacity(uploadDirectory: File): Boolean {
        return DriveBackupQueueLimits.hasPendingCapacity(uploadDirectory)
    }

    companion object {
        const val WORK_NAME = "google-drive-session-upload-queue"
        const val WORK_TAG = "google-drive-session-upload"
        const val BACKOFF_DELAY_SECONDS = 30L
    }
}

internal object DriveBackupQueueLimits {
    const val MAX_PENDING_ARCHIVES = 25
    const val FAILED_DIRECTORY_NAME = "failed"

    fun hasPendingCapacity(uploadDirectory: File): Boolean {
        return pendingArchiveCount(uploadDirectory) < MAX_PENDING_ARCHIVES
    }

    fun pendingArchiveCount(uploadDirectory: File): Int {
        return pendingArchives(uploadDirectory).size
    }

    fun failedArchiveCount(uploadDirectory: File): Int {
        return failedArchives(uploadDirectory).size
    }

    fun pendingArchives(uploadDirectory: File): List<File> {
        return uploadDirectory
            .listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            .orEmpty()
            .sortedBy(File::lastModified)
    }

    fun failedArchives(uploadDirectory: File): List<File> {
        return File(uploadDirectory, FAILED_DIRECTORY_NAME)
            .listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            .orEmpty()
            .sortedBy(File::lastModified)
    }

    fun restoreFailedArchives(uploadDirectory: File): List<File> {
        uploadDirectory.mkdirs()
        return failedArchives(uploadDirectory).mapNotNull { archive ->
            val targetFile = uniqueArchiveFile(
                directory = uploadDirectory,
                fileName = archive.name,
            )
            try {
                Files.move(
                    archive.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                ).toFile()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun quarantineArchive(archiveFile: File): File? {
        val uploadDirectory = archiveFile.parentFile ?: return null
        val failedDirectory = File(uploadDirectory, FAILED_DIRECTORY_NAME).apply {
            mkdirs()
        }
        val targetFile = uniqueArchiveFile(
            directory = failedDirectory,
            fileName = archiveFile.name,
        )
        return try {
            Files.move(
                archiveFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            ).toFile()
        } catch (_: Exception) {
            null
        }
    }

    private fun uniqueArchiveFile(
        directory: File,
        fileName: String,
    ): File {
        val sourceName = File(fileName)
        val baseName = sourceName.nameWithoutExtension.ifBlank { "archive" }
        val extension = sourceName.extension
        var candidate = File(directory, fileName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(
                directory,
                if (extension.isBlank()) {
                    "$baseName-$suffix"
                } else {
                    "$baseName-$suffix.$extension"
                },
            )
            suffix++
        }
        return candidate
    }
}
