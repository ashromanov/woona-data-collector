package com.example.myapplication.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

interface DriveUploader {
    fun findBackupFolder(accessToken: String): String?

    fun createBackupFolder(accessToken: String): String

    fun uploadArchive(
        accessToken: String,
        folderId: String,
        archiveFile: File,
    ): String
}

class GoogleDriveRequestException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Google Drive request failed: HTTP $statusCode $responseBody") {
    fun isAuthorizationFailure(): Boolean {
        if (statusCode == HTTP_UNAUTHORIZED) return true
        if (statusCode != HTTP_FORBIDDEN) return false

        val detail = errorDetail()
        return AUTHORIZATION_FAILURE_REASONS.any(detail::contains)
    }

    fun isNotFound(): Boolean = statusCode == HTTP_NOT_FOUND

    fun isRetryable(): Boolean {
        return statusCode == HTTP_REQUEST_TIMEOUT ||
            statusCode == HTTP_TOO_MANY_REQUESTS ||
            statusCode in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ||
            statusCode == HTTP_FORBIDDEN && RETRYABLE_FORBIDDEN_REASONS.any(errorDetail()::contains)
    }

    private fun errorDetail(): String {
        return "$responseBody ${jsonStringField(responseBody, "reason")}"
            .lowercase(Locale.US)
    }

    private fun jsonStringField(
        body: String,
        fieldName: String,
    ): String? {
        val pattern = Regex("\"$fieldName\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.find(body)?.groupValues?.getOrNull(1)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR_START = 500
        const val HTTP_SERVER_ERROR_END = 599
        val AUTHORIZATION_FAILURE_REASONS = listOf(
            "autherror",
            "insufficientpermissions",
        )
        val RETRYABLE_FORBIDDEN_REASONS = listOf(
            "ratelimitexceeded",
            "userratelimitexceeded",
            "quotaexceeded",
        )
    }
}

class GoogleDriveUploader : DriveUploader {
    override fun findBackupFolder(accessToken: String): String? {
        val markedFolder = findFolders(
            accessToken = accessToken,
            query = "mimeType='$FOLDER_MIME_TYPE' and name='$BACKUP_FOLDER_NAME' and trashed=false " +
                "and appProperties has { key='$APP_PROPERTY_BACKUP_FOLDER' and value='$APP_PROPERTY_TRUE' }",
            pageSize = 1,
        ).firstOrNull()
        if (markedFolder != null) return markedFolder

        val sameNamedFolders = findFolders(
            accessToken = accessToken,
            query = "mimeType='$FOLDER_MIME_TYPE' and name='$BACKUP_FOLDER_NAME' and trashed=false",
            pageSize = 2,
        )
        return sameNamedFolders.singleOrNull()?.also { folderId ->
            markBackupFolder(accessToken = accessToken, folderId = folderId)
        }
    }

    override fun createBackupFolder(accessToken: String): String {
        val metadata = JSONObject()
            .put("name", BACKUP_FOLDER_NAME)
            .put("mimeType", FOLDER_MIME_TYPE)
            .put(
                "appProperties",
                JSONObject().put(APP_PROPERTY_BACKUP_FOLDER, APP_PROPERTY_TRUE),
            )

        val connection = openConnection(
            url = "https://www.googleapis.com/drive/v3/files?fields=id",
            accessToken = accessToken,
            method = "POST",
        ).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        connection.outputStream.use { output ->
            output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        }

        val body = connection.readSuccessfulBody()
        return JSONObject(body).getString("id")
    }

    private fun findFolders(
        accessToken: String,
        query: String,
        pageSize: Int,
    ): List<String> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = openConnection(
            url = "https://www.googleapis.com/drive/v3/files" +
                "?q=$encodedQuery&spaces=drive&pageSize=$pageSize&fields=files(id)",
            accessToken = accessToken,
            method = "GET",
        ).readSuccessfulBody()

        val files = JSONObject(body).getJSONArray("files")
        return List(files.length()) { index ->
            files.getJSONObject(index).optString("id")
        }.filter(String::isNotBlank)
    }

    private fun markBackupFolder(
        accessToken: String,
        folderId: String,
    ) {
        val metadata = JSONObject()
            .put(
                "appProperties",
                JSONObject().put(APP_PROPERTY_BACKUP_FOLDER, APP_PROPERTY_TRUE),
            )
        val encodedFolderId = URLEncoder.encode(folderId, Charsets.UTF_8.name())
        val connection = openConnection(
            url = "https://www.googleapis.com/drive/v3/files/$encodedFolderId?fields=id",
            accessToken = accessToken,
            method = "PATCH",
        ).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        connection.outputStream.use { output ->
            output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        connection.requireSuccess()
    }

    override fun uploadArchive(
        accessToken: String,
        folderId: String,
        archiveFile: File,
    ): String {
        val metadata = JSONObject()
            .put("name", archiveFile.name)
            .put("mimeType", ARCHIVE_MIME_TYPE)
            .put("parents", JSONArray().put(folderId))

        val sessionConnection = openConnection(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id",
            accessToken = accessToken,
            method = "POST",
        ).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", ARCHIVE_MIME_TYPE)
            setRequestProperty("X-Upload-Content-Length", archiveFile.length().toString())
        }

        sessionConnection.outputStream.use { output ->
            output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        sessionConnection.requireSuccess()
        val uploadUrl = sessionConnection.getHeaderField("Location")
            ?: throw IOException("Google Drive did not return an upload URL")

        val uploadConnection = openConnection(
            url = uploadUrl,
            accessToken = accessToken,
            method = "PUT",
        ).apply {
            doOutput = true
            setFixedLengthStreamingMode(archiveFile.length())
            setRequestProperty("Content-Type", ARCHIVE_MIME_TYPE)
        }

        BufferedInputStream(archiveFile.inputStream(), BUFFER_SIZE_BYTES).use { input ->
            uploadConnection.outputStream.use { output ->
                input.copyTo(output, BUFFER_SIZE_BYTES)
            }
        }

        val body = uploadConnection.readSuccessfulBody()
        return JSONObject(body).getString("id")
    }

    private fun openConnection(
        url: String,
        accessToken: String,
        method: String,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun HttpURLConnection.readSuccessfulBody(): String {
        requireSuccess()
        return inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun HttpURLConnection.requireSuccess() {
        val responseCode = responseCode
        if (responseCode in 200..299) return

        val errorBody = errorStream
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        throw GoogleDriveRequestException(
            statusCode = responseCode,
            responseBody = errorBody,
        )
    }

    private companion object {
        const val BACKUP_FOLDER_NAME = "Woona sessions"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val ARCHIVE_MIME_TYPE = "application/zip"
        const val APP_PROPERTY_BACKUP_FOLDER = "woonaBackupFolder"
        const val APP_PROPERTY_TRUE = "true"
        const val BUFFER_SIZE_BYTES = 65_536
        const val TIMEOUT_MILLIS = 30_000
    }
}
