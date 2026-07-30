package com.example.myapplication.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionArchiveMetadata(
    val profileId: String,
    val profileName: String,
    val recordingId: String,
    val source: String,
    val status: String,
    val timezone: String,
    val profileQuestionnaireJson: String?,
    val questionnaireJson: String?,
    val synchronizationJson: String?,
)

class SessionArchiveExporter(
    private val archiveTimestampFormatter: (Long) -> String = { millis ->
        ARCHIVE_TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    },
) {
    fun export(
        files: List<File>,
        targetDirectory: File,
        sessionStartMillis: Long?,
        createdAtMillis: Long,
        metadata: SessionArchiveMetadata? = null,
    ): File {
        require(files.isNotEmpty()) { "At least one session file is required" }
        targetDirectory.mkdirs()
        val archiveFile = uniqueArchiveFile(
            directory = targetDirectory,
            baseName = "woona_session_${archiveTimestampFormatter(createdAtMillis)}",
        )
        val tempFile = File(targetDirectory, "${archiveFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        return try {
            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(tempFile), BUFFER_SIZE_BYTES),
            ).use { zip ->
                writeManifest(
                    zip = zip,
                    files = files,
                    sessionStartMillis = sessionStartMillis,
                    createdAtMillis = createdAtMillis,
                    metadata = metadata,
                )
                files.forEach { file ->
                    addFile(
                        zip = zip,
                        file = file,
                        entryName = sanitizeZipEntryName(file.name),
                    )
                }
            }

            if (!tempFile.renameTo(archiveFile)) {
                throw IOException("Failed to finalize session archive")
            }
            archiveFile
        } catch (exception: Exception) {
            tempFile.delete()
            archiveFile.delete()
            throw exception
        }
    }

    private fun writeManifest(
        zip: ZipOutputStream,
        files: List<File>,
        sessionStartMillis: Long?,
        createdAtMillis: Long,
        metadata: SessionArchiveMetadata?,
    ) {
        val fileEntries = files.joinToString(separator = ",\n") { file ->
            "    {\"name\":\"${jsonEscape(file.name)}\",\"size\":${file.length()}}"
        }
        val manifest = buildString {
            append("{\n")
            append("  \"createdAtMillis\": $createdAtMillis,\n")
            append("  \"sessionStartMillis\": ")
            append(sessionStartMillis?.toString() ?: "null")
            append(",\n")
            append("  \"profile\": ")
            append(
                metadata?.let {
                    "{\"id\":\"${jsonEscape(it.profileId)}\",\"numberOrName\":\"${jsonEscape(it.profileName)}\"," +
                        "\"questionnaire\":${it.profileQuestionnaireJson ?: "null"}}"
                } ?: "null",
            )
            append(",\n")
            append("  \"recording\": ")
            append(
                metadata?.let {
                    "{\"id\":\"${jsonEscape(it.recordingId)}\",\"source\":\"${jsonEscape(it.source)}\"," +
                        "\"status\":\"${jsonEscape(it.status)}\",\"timezone\":\"${jsonEscape(it.timezone)}\"," +
                        "\"questionnaire\":${it.questionnaireJson ?: "null"}}"
                } ?: "null",
            )
            append(",\n")
            append("  \"synchronization\": ")
            append(metadata?.synchronizationJson ?: "null")
            append(",\n")
            append("  \"files\": [\n")
            append(fileEntries)
            append("\n  ]\n")
            append("}\n")
        }
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFile(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            input.copyTo(zip, BUFFER_SIZE_BYTES)
        }
        zip.closeEntry()
    }

    private fun uniqueArchiveFile(
        directory: File,
        baseName: String,
    ): File {
        var candidate = File(directory, "$baseName.zip")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(directory, "$baseName-$suffix.zip")
            suffix++
        }
        return candidate
    }

    private fun sanitizeZipEntryName(name: String): String {
        val sanitized = name
            .replace('\\', '_')
            .replace('/', '_')
            .ifBlank { "session_file" }
        return sanitized.lowercase(Locale.US)
    }

    private fun jsonEscape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 65_536
        const val MANIFEST_ENTRY_NAME = "manifest.json"
        val ARCHIVE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
