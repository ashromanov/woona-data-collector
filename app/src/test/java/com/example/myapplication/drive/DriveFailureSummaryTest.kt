package com.example.myapplication.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveFailureSummaryTest {
    @Test
    fun summarize_includesGoogleDriveErrorMessageAndReason() {
        val exception = GoogleDriveRequestException(
            statusCode = 403,
            responseBody = """
                {
                  "error": {
                    "message": "Google Drive API has not been used in project",
                    "errors": [
                      { "reason": "accessNotConfigured" }
                    ]
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            "HTTP 403: Google Drive API has not been used in project (accessNotConfigured)",
            DriveFailureSummary.summarize(exception),
        )
    }

    @Test
    fun summarize_fallsBackToStatusWhenResponseBodyIsEmpty() {
        val exception = GoogleDriveRequestException(statusCode = 500, responseBody = "")

        assertEquals("HTTP 500", DriveFailureSummary.summarize(exception))
    }
}
