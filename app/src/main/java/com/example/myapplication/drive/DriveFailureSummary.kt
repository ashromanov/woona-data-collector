package com.example.myapplication.drive

import com.google.android.gms.common.api.ApiException

object DriveFailureSummary {
    fun summarize(exception: Exception): String {
        return when (exception) {
            is GoogleDriveRequestException -> summarizeDriveRequest(exception)
            is ApiException -> summarizeApiException(exception)
            else -> exception.message?.take(MAX_DETAIL_LENGTH)
                ?: exception::class.java.simpleName
        }
    }

    private fun summarizeDriveRequest(exception: GoogleDriveRequestException): String {
        val detail = driveErrorDetail(exception.responseBody)
        return if (detail.isNullOrBlank()) {
            "HTTP ${exception.statusCode}"
        } else {
            "HTTP ${exception.statusCode}: ${detail.take(MAX_DETAIL_LENGTH)}"
        }
    }

    private fun summarizeApiException(exception: ApiException): String {
        val detail = exception.message
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_DETAIL_LENGTH)
        return if (detail.isNullOrBlank()) {
            "Google status ${exception.statusCode}"
        } else {
            "Google status ${exception.statusCode}: $detail"
        }
    }

    private fun driveErrorDetail(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        val message = jsonStringField(responseBody, "message").orEmpty()
        val reason = jsonStringField(responseBody, "reason").orEmpty()
        return when {
            message.isNotBlank() && reason.isNotBlank() -> "$message ($reason)"
            message.isNotBlank() -> message
            reason.isNotBlank() -> reason
            else -> responseBody
        }
    }

    private fun jsonStringField(
        body: String,
        fieldName: String,
    ): String? {
        val pattern = Regex("\"$fieldName\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private const val MAX_DETAIL_LENGTH = 160
}
