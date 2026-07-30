package com.example.myapplication.drive

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class ManualDriveSaveStore(
    private val stateDirectory: File,
) {
    private val lock = Any()
    private val recordFile = File(stateDirectory, RECORD_FILE_NAME)

    fun pendingArchive(): File? = synchronized(lock) {
        val archivePath = try {
            recordFile.takeIf(File::isFile)?.readText()?.trim()
        } catch (_: Exception) {
            null
        }
        val archive = archivePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
        if (archive == null) {
            recordFile.delete()
        }
        archive
    }

    fun savePendingArchive(archiveFile: File) {
        synchronized(lock) {
            require(archiveFile.isFile) { "Manual Drive archive does not exist" }
            stateDirectory.mkdirs()
            val temporaryRecord = File(stateDirectory, "$RECORD_FILE_NAME.tmp")
            temporaryRecord.writeText(archiveFile.absolutePath)
            try {
                Files.move(
                    temporaryRecord.toPath(),
                    recordFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryRecord.toPath(),
                    recordFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            Files.deleteIfExists(recordFile.toPath())
        }
    }

    fun discardPendingArchive() {
        synchronized(lock) {
            pendingArchive()?.delete()
            Files.deleteIfExists(recordFile.toPath())
        }
    }

    private companion object {
        const val RECORD_FILE_NAME = "pending-archive"
    }
}
