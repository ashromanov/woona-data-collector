package com.example.myapplication.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

class BlePacketFileStore(
    private var directory: File,
    private val filePrefix: String = DEFAULT_FILE_PREFIX,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var currentFile: File? = null
    private var outputStream: BufferedOutputStream? = null
    private var isClosed = false
    private var sessionFileName: String? = null

    fun open(): File {
        synchronized(lock) {
            check(!isClosed) { "Packet file store is closed" }
            if (currentFile != null && outputStream != null) {
                return currentFile!!
            }

            directory.mkdirs()
            val file = File(directory, sessionFileName ?: "$filePrefix${timestampProvider()}.bin")
            val stream = BufferedOutputStream(FileOutputStream(file, true), BUFFER_SIZE_BYTES)
            currentFile = file
            outputStream = stream
            return file
        }
    }

    fun append(packetBytes: ByteArray) {
        if (packetBytes.isEmpty()) return
        synchronized(lock) {
            check(!isClosed) { "Packet file store is closed" }
            ensureOpen().write(packetBytes)
        }
    }

    fun flush() {
        synchronized(lock) {
            check(!isClosed) { "Packet file store is closed" }
            outputStream?.flush()
        }
    }

    fun close() {
        synchronized(lock) {
            if (isClosed) return
            outputStream?.close()
            outputStream = null
            isClosed = true
        }
    }

    fun resetSession() {
        synchronized(lock) {
            check(!isClosed) { "Packet file store is closed" }
            outputStream?.close()
            outputStream = null
            currentFile = null
        }
    }

    fun useSessionDirectory(directory: File) {
        synchronized(lock) {
            check(!isClosed) { "Packet file store is closed" }
            outputStream?.close()
            outputStream = null
            currentFile = null
            this.directory = directory
            sessionFileName = "packets.bin"
        }
    }

    fun currentFile(): File? = synchronized(lock) { currentFile }

    private fun ensureOpen(): BufferedOutputStream {
        check(!isClosed) { "Packet file store is closed" }
        if (outputStream == null) {
            open()
        }
        return requireNotNull(outputStream)
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 65_536
        const val DEFAULT_FILE_PREFIX = "ble_dump_"
    }
}
