package com.example.myapplication.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GoogleDriveArchiveUploaderTest {
    @Test
    fun prepareBackupFolder_reusesExistingBackupFolderBeforeCreatingANewOne() {
        val fakeUploader = FakeDriveUploader(existingFolderId = "existing-folder")
        val savedFolderIds = mutableListOf<String?>()
        val uploader = GoogleDriveArchiveUploader(fakeUploader)

        val result = uploader.prepareBackupFolder(
            accessToken = "token",
            saveFolderId = savedFolderIds::add,
        )

        assertTrue(result is DriveUploadResult.Success)
        assertEquals(0, fakeUploader.createFolderCalls)
        assertEquals(listOf("existing-folder"), savedFolderIds)
    }

    @Test
    fun prepareBackupFolder_reportsAuthorizationRequiredForPermissionFailures() {
        val fakeUploader = FakeDriveUploader(
            findFolderBehavior = {
                throw GoogleDriveRequestException(
                    statusCode = 403,
                    responseBody = driveErrorBody(reason = "insufficientPermissions"),
                )
            },
        )

        val result = GoogleDriveArchiveUploader(fakeUploader).prepareBackupFolder(
            accessToken = "token",
            saveFolderId = {},
        )

        assertTrue(result is DriveUploadResult.AuthorizationRequired)
    }

    @Test
    fun upload_reusesExistingBackupFolderBeforeCreatingANewOne() {
        val archiveFile = tempArchive()
        val fakeUploader = FakeDriveUploader(existingFolderId = "existing-folder")
        val savedFolderIds = mutableListOf<String?>()
        val uploader = GoogleDriveArchiveUploader(fakeUploader)

        val result = uploader.upload(
            accessToken = "token",
            cachedFolderId = null,
            archiveFile = archiveFile,
            saveFolderId = savedFolderIds::add,
        )

        assertTrue(result is DriveUploadResult.Success)
        assertEquals(0, fakeUploader.createFolderCalls)
        assertEquals(listOf("existing-folder"), fakeUploader.uploadedFolderIds)
        assertEquals(listOf("existing-folder"), savedFolderIds)
    }

    @Test
    fun upload_recreatesBackupFolderWhenCachedFolderIsMissing() {
        val archiveFile = tempArchive()
        val fakeUploader = FakeDriveUploader(
            createdFolderId = "new-folder",
            uploadBehavior = { folderId ->
                if (folderId == "old-folder") {
                    throw GoogleDriveRequestException(statusCode = 404, responseBody = "missing")
                }
            },
        )
        val savedFolderIds = mutableListOf<String?>()
        val uploader = GoogleDriveArchiveUploader(fakeUploader)

        val result = uploader.upload(
            accessToken = "token",
            cachedFolderId = "old-folder",
            archiveFile = archiveFile,
            saveFolderId = savedFolderIds::add,
        )

        assertTrue(result is DriveUploadResult.Success)
        assertEquals(listOf("old-folder", "new-folder"), fakeUploader.uploadedFolderIds)
        assertEquals(listOf(null, "new-folder"), savedFolderIds)
    }

    @Test
    fun upload_reportsAuthorizationRequiredForPermissionFailures() {
        val result = uploadResultForHttpStatus(
            statusCode = 403,
            responseBody = driveErrorBody(reason = "insufficientPermissions"),
        )

        assertTrue(result is DriveUploadResult.AuthorizationRequired)
    }

    @Test
    fun upload_reportsPermanentFailureWhenDriveApiIsNotEnabled() {
        val result = uploadResultForHttpStatus(
            statusCode = 403,
            responseBody = driveErrorBody(reason = "accessNotConfigured"),
        )

        assertTrue(result is DriveUploadResult.PermanentFailure)
    }

    @Test
    fun upload_retriesForbiddenRateLimits() {
        val result = uploadResultForHttpStatus(
            statusCode = 403,
            responseBody = driveErrorBody(reason = "rateLimitExceeded"),
        )

        assertTrue(result is DriveUploadResult.RetryLater)
    }

    @Test
    fun upload_retriesServerFailures() {
        val result = uploadResultForHttpStatus(500)

        assertTrue(result is DriveUploadResult.RetryLater)
    }

    @Test
    fun upload_retriesNetworkFailures() {
        val fakeUploader = FakeDriveUploader(
            uploadBehavior = { throw IOException("offline") },
        )

        val result = GoogleDriveArchiveUploader(fakeUploader).upload(
            accessToken = "token",
            cachedFolderId = "folder",
            archiveFile = tempArchive(),
            saveFolderId = {},
        )

        assertTrue(result is DriveUploadResult.RetryLater)
    }

    @Test
    fun upload_reportsPermanentFailureForBadRequests() {
        val result = uploadResultForHttpStatus(400)

        assertTrue(result is DriveUploadResult.PermanentFailure)
    }

    private fun uploadResultForHttpStatus(
        statusCode: Int,
        responseBody: String = "error",
    ): DriveUploadResult {
        val fakeUploader = FakeDriveUploader(
            uploadBehavior = {
                throw GoogleDriveRequestException(statusCode = statusCode, responseBody = responseBody)
            },
        )
        return GoogleDriveArchiveUploader(fakeUploader).upload(
            accessToken = "token",
            cachedFolderId = "folder",
            archiveFile = tempArchive(),
            saveFolderId = {},
        )
    }

    private fun tempArchive(): File {
        return Files.createTempFile("drive-upload", ".zip").toFile().apply {
            deleteOnExit()
        }
    }

    private fun driveErrorBody(reason: String): String {
        return """
            {
              "error": {
                "message": "Drive error",
                "errors": [
                  { "reason": "$reason" }
                ]
              }
            }
        """.trimIndent()
    }
}

private class FakeDriveUploader(
    private val existingFolderId: String? = null,
    private val createdFolderId: String = "created-folder",
    private val findFolderBehavior: () -> Unit = {},
    private val uploadBehavior: (String) -> Unit = {},
) : DriveUploader {
    val uploadedFolderIds = mutableListOf<String>()
    var createFolderCalls = 0

    override fun findBackupFolder(accessToken: String): String? {
        findFolderBehavior()
        return existingFolderId
    }

    override fun createBackupFolder(accessToken: String): String {
        createFolderCalls++
        return createdFolderId
    }

    override fun uploadArchive(
        accessToken: String,
        folderId: String,
        archiveFile: File,
    ): String {
        uploadedFolderIds += folderId
        uploadBehavior(folderId)
        return "file-id"
    }
}
