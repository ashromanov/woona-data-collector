package com.example.myapplication.storage

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class BleDiagnosticLogFileStore(
    private val directory: File,
    private val filePrefix: String = DEFAULT_FILE_PREFIX,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var isClosed = false

    fun appendEvent(
        eventId: Long,
        type: String,
        message: String,
    ) {
        synchronized(lock) {
            check(!isClosed) { "Diagnostic log file store is closed" }
            val currentTimestamp = timestampProvider()
            ensureOpen().apply {
                append(currentTimestamp.toString())
                append('\t')
                append(eventId.toString())
                append('\t')
                append(type)
                append('\t')
                append(message)
                append('\n')
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            check(!isClosed) { "Diagnostic log file store is closed" }
            writer?.flush()
        }
    }

    fun close() {
        synchronized(lock) {
            if (isClosed) return
            writer?.close()
            writer = null
            isClosed = true
        }
    }

    fun resetSession() {
        synchronized(lock) {
            check(!isClosed) { "Diagnostic log file store is closed" }
            writer?.close()
            writer = null
            currentFile = null
        }
    }

    fun currentFile(): File? = synchronized(lock) { currentFile }

    private fun ensureOpen(): BufferedWriter {
        check(!isClosed) { "Diagnostic log file store is closed" }
        if (writer == null) {
            val file = File(directory, "$filePrefix${timestampProvider()}.log")
            currentFile = file
            writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE_BYTES)
        }
        return requireNotNull(writer)
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 65_536
        const val DEFAULT_FILE_PREFIX = "ble_log_"
    }
}
