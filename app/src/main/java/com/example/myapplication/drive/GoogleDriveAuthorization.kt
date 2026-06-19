package com.example.myapplication.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

class DriveAuthorizationRequiredException : Exception("Google Drive authorization requires user interaction")

object GoogleDriveAuthorization {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun createAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
    }

    fun requestAccessToken(context: Context): String {
        val authorizationResult = Tasks.await(
            Identity.getAuthorizationClient(context.applicationContext)
                .authorize(createAuthorizationRequest()),
            ACCESS_TOKEN_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        if (authorizationResult.hasResolution()) {
            throw DriveAuthorizationRequiredException()
        }
        return authorizationResult.accessToken
            ?: throw DriveAuthorizationRequiredException()
    }

    private const val ACCESS_TOKEN_TIMEOUT_SECONDS = 30L
}
