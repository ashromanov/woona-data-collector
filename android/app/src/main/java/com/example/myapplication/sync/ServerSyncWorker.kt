package com.example.myapplication.sync

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.data.WoonaDatabase
import com.example.myapplication.data.artifactFile
import com.example.myapplication.data.hashPendingArtifacts
import com.example.myapplication.data.markArtifactProgress
import com.example.myapplication.data.markProfileSyncError
import com.example.myapplication.data.markProfileSynced
import com.example.myapplication.data.markProfileUploading
import com.example.myapplication.data.markRecordingSyncError
import com.example.myapplication.data.markRecordingSynced
import com.example.myapplication.data.markRecordingUploading
import com.example.myapplication.data.pendingProfileVersionIds
import com.example.myapplication.data.pendingRecordingIds
import com.example.myapplication.data.profileSyncRecord
import com.example.myapplication.data.recordingSyncRecord
import com.example.myapplication.R
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProfileSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val profileVersionId = inputData.getString(KEY_ID) ?: return Result.failure()
        val database = WoonaDatabase(applicationContext)
        return try {
            val settings = ServerSettingsStore(applicationContext).get()
            if (!settings.isConfigured) {
                database.markProfileSyncError(
                    profileVersionId,
                    retryable = false,
                    code = "server_not_configured",
                    message = "Server URL or token is missing",
                )
                return Result.failure()
            }
            val profile = database.profileSyncRecord(profileVersionId) ?: return Result.failure()
            database.markProfileUploading(profileVersionId)
            val receipt = ServerApiClient(settings).uploadProfile(profile)
            database.markProfileSynced(
                profileVersionId,
                profile.dogId,
                receipt.dogRevision,
            )
            Result.success()
        } catch (exception: Exception) {
            val retryable = exception.isRetryable()
            database.markProfileSyncError(
                profileVersionId,
                retryable,
                exception.errorCode(),
                exception.message.orEmpty(),
            )
            if (retryable) Result.retry() else Result.failure()
        } finally {
            database.close()
        }
    }
}

class ArtifactHashWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val database = WoonaDatabase(applicationContext)
        return try {
            database.hashPendingArtifacts()
            ServerSyncScheduler.enqueueReady(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            database.close()
        }
    }
}

class RecordingSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_ID) ?: return Result.failure()
        setForeground(foregroundInfo(recordingId))
        val database = WoonaDatabase(applicationContext)
        return try {
            val settings = ServerSettingsStore(applicationContext).get()
            if (!settings.isConfigured) {
                database.markRecordingSyncError(
                    recordingId,
                    retryable = false,
                    code = "server_not_configured",
                    message = "Server URL or token is missing",
                )
                return Result.failure()
            }
            var record = database.recordingSyncRecord(recordingId) ?: return Result.failure()
            val client = ServerApiClient(settings)
            val profileReceipt = client.uploadProfile(record.profile)
            database.markProfileSynced(
                record.profileVersionId,
                record.dogId,
                profileReceipt.dogRevision,
            )
            record = database.recordingSyncRecord(recordingId) ?: return Result.failure()
            database.markRecordingUploading(recordingId)
            val artifactsById = record.artifacts.associateBy { it.id }
            val receipt = client.uploadRecording(
                record = record,
                fileForArtifact = { artifactId ->
                    database.artifactFile(requireNotNull(artifactsById[artifactId]))
                },
                onProgress = { artifactId, uploadedBytes, available ->
                    database.markArtifactProgress(artifactId, uploadedBytes, available)
                },
            )
            database.markRecordingSynced(
                recordingId,
                receipt.receiptSha256,
                receipt.verifiedAtUtc,
            )
            Result.success()
        } catch (exception: Exception) {
            val retryable = exception.isRetryable()
            database.markRecordingSyncError(
                recordingId,
                retryable,
                exception.errorCode(),
                exception.message.orEmpty(),
            )
            if (retryable) Result.retry() else Result.failure()
        } finally {
            database.close()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_ID).orEmpty())

    private fun foregroundInfo(recordingId: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Server synchronization",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Woona")
            .setContentText("Uploading recording ${recordingId.take(8)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "woona_server_sync"
        const val NOTIFICATION_ID = 3401
    }
}

object ServerSyncScheduler {
    const val WORK_TAG = "woona-server-sync"

    fun enqueuePending(context: Context) {
        val database = WoonaDatabase(context.applicationContext)
        try {
            database.pendingProfileVersionIds().forEach { enqueueProfile(context, it) }
            database.pendingRecordingIds().forEach { enqueueRecording(context, it) }
        } finally {
            database.close()
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "server-artifact-hash",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ArtifactHashWorker>()
                .addTag(WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    internal fun enqueueReady(context: Context) {
        val database = WoonaDatabase(context.applicationContext)
        try {
            database.pendingProfileVersionIds().forEach { enqueueProfile(context, it) }
            database.pendingRecordingIds().forEach { enqueueRecording(context, it) }
        } finally {
            database.close()
        }
    }

    fun enqueueProfile(context: Context, profileVersionId: String) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "server-profile-$profileVersionId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProfileSyncWorker>()
                .addTag(WORK_TAG)
                .setInputData(workDataOf(KEY_ID to profileVersionId))
                .setConstraints(networkConstraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun enqueueRecording(context: Context, recordingId: String) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "server-sync-$recordingId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecordingSyncWorker>()
                .addTag(WORK_TAG)
                .setInputData(workDataOf(KEY_ID to recordingId))
                .setConstraints(networkConstraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun networkConstraints(context: Context): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (ServerSettingsStore(context).get().wifiOnly) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            )
            .build()
}

private fun Exception.isRetryable(): Boolean = when (this) {
    is ServerHttpException -> status in setOf(408, 425, 429) || status >= 500
    is IOException -> true
    else -> false
}

private fun Exception.errorCode(): String = when (this) {
    is ServerHttpException -> "http_$status"
    is IOException -> "network_error"
    else -> "local_data_error"
}

private const val KEY_ID = "id"
