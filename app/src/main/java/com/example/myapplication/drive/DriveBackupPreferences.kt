package com.example.myapplication.drive

import android.content.Context

data class DriveBackupSettings(
    val isAuthorized: Boolean = false,
    val autoUploadEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val folderId: String? = null,
)

data class DriveBackupUiState(
    val isAuthorized: Boolean = false,
    val autoUploadEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val pendingUploadCount: Int = 0,
    val failedUploadCount: Int = 0,
    val status: DriveBackupStatus = DriveBackupStatus.IDLE,
)

enum class DriveBackupStatus(
    val isInProgress: Boolean,
    val isVisibleInTopBar: Boolean,
) {
    IDLE(isInProgress = false, isVisibleInTopBar = false),
    READY(isInProgress = false, isVisibleInTopBar = true),
    CONNECTING(isInProgress = true, isVisibleInTopBar = true),
    PREPARING(isInProgress = true, isVisibleInTopBar = true),
    QUEUED(isInProgress = false, isVisibleInTopBar = true),
    WAITING(isInProgress = false, isVisibleInTopBar = true),
    UPLOADING(isInProgress = true, isVisibleInTopBar = true),
    NEEDS_ATTENTION(isInProgress = false, isVisibleInTopBar = true),
}

class DriveBackupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun settings(): DriveBackupSettings {
        return DriveBackupSettings(
            isAuthorized = preferences.getBoolean(KEY_AUTHORIZED, false),
            autoUploadEnabled = preferences.getBoolean(KEY_AUTO_UPLOAD_ENABLED, false),
            wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true),
            folderId = preferences.getString(KEY_FOLDER_ID, null),
        )
    }

    fun setAuthorized(isAuthorized: Boolean) {
        preferences.edit()
            .putBoolean(KEY_AUTHORIZED, isAuthorized)
            .apply()
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_AUTO_UPLOAD_ENABLED, enabled)
            .apply()
    }

    fun setWifiOnly(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_WIFI_ONLY, enabled)
            .apply()
    }

    fun setFolderId(folderId: String?) {
        preferences.edit()
            .apply {
                if (folderId.isNullOrBlank()) {
                    remove(KEY_FOLDER_ID)
                } else {
                    putString(KEY_FOLDER_ID, folderId)
                }
            }
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "drive_backup"
        const val KEY_AUTHORIZED = "authorized"
        const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_FOLDER_ID = "folder_id"
    }
}
