package com.example.myapplication.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DriveBackupQueueLimitsTest {
    @Test
    fun hasPendingCapacity_allowsNewArchivesBelowLimit() {
        val directory = Files.createTempDirectory("drive-queue-small").toFile()
        try {
            repeat(DriveBackupQueueLimits.MAX_PENDING_ARCHIVES - 1) { index ->
                File(directory, "archive-$index.zip").writeText("zip")
            }

            assertTrue(DriveBackupQueueLimits.hasPendingCapacity(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun hasPendingCapacity_blocksNewArchivesAtLimit() {
        val directory = Files.createTempDirectory("drive-queue-full").toFile()
        try {
            repeat(DriveBackupQueueLimits.MAX_PENDING_ARCHIVES) { index ->
                File(directory, "archive-$index.zip").writeText("zip")
            }

            assertFalse(DriveBackupQueueLimits.hasPendingCapacity(directory))
            assertEquals(
                DriveBackupQueueLimits.MAX_PENDING_ARCHIVES,
                DriveBackupQueueLimits.pendingArchiveCount(directory),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun quarantineArchive_movesFileOutOfPendingQueue() {
        val directory = Files.createTempDirectory("drive-queue-quarantine").toFile()
        try {
            val archive = File(directory, "archive.zip").apply { writeText("zip") }

            val quarantinedFile = DriveBackupQueueLimits.quarantineArchive(archive)

            assertFalse(archive.exists())
            assertEquals(0, DriveBackupQueueLimits.pendingArchiveCount(directory))
            assertTrue(requireNotNull(quarantinedFile).exists())
            assertEquals(
                DriveBackupQueueLimits.FAILED_DIRECTORY_NAME,
                quarantinedFile.parentFile?.name,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoreFailedArchives_movesFailedFilesBackToPendingQueue() {
        val directory = Files.createTempDirectory("drive-queue-restore").toFile()
        try {
            val archive = File(directory, "archive.zip").apply { writeText("zip") }
            DriveBackupQueueLimits.quarantineArchive(archive)

            val restoredFiles = DriveBackupQueueLimits.restoreFailedArchives(directory)

            assertEquals(1, restoredFiles.size)
            assertEquals(1, DriveBackupQueueLimits.pendingArchiveCount(directory))
            assertEquals(0, DriveBackupQueueLimits.failedArchiveCount(directory))
            assertTrue(File(directory, "archive.zip").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
