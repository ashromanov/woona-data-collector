package com.example.myapplication.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class SessionArchiveExporterTest {
    @Test
    fun export_removesPartialArchiveWhenFileReadFails() {
        val tempDirectory = File(
            System.getProperty("java.io.tmpdir"),
            "session-archive-exporter-${System.nanoTime()}",
        ).apply { mkdirs() }
        val sourceFile = File(tempDirectory, "source.txt").apply { writeText("ok") }
        val missingFile = File(tempDirectory, "missing.txt")
        val exporter = SessionArchiveExporter(
            archiveTimestampFormatter = { "broken" },
        )

        try {
            try {
                exporter.export(
                    files = listOf(sourceFile, missingFile),
                    targetDirectory = tempDirectory,
                    sessionStartMillis = null,
                    createdAtMillis = 1_000L,
                )
                fail("Expected export to fail when a source file is missing")
            } catch (_: FileNotFoundException) {
            }

            assertFalse(File(tempDirectory, "woona_session_broken.zip").exists())
            assertFalse(File(tempDirectory, "woona_session_broken.zip.tmp").exists())
        } finally {
            tempDirectory.listFiles().orEmpty().forEach(File::delete)
            tempDirectory.delete()
        }
    }
}
