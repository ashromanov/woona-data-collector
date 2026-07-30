package com.example.myapplication.storage

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class BleRawFragmentFileStore(
    private var directory: File,
    private val filePrefix: String = DEFAULT_FILE_PREFIX,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var currentFile: File? = null
    private var outputStream: DataOutputStream? = null
    private var isClosed = false
    private var sessionFileName: String? = null

    fun appendFragment(
        sequence: Long,
        receivedAtMillis: Long,
        fragmentBytes: ByteArray,
    ) {
        if (fragmentBytes.isEmpty()) return
        synchronized(lock) {
            check(!isClosed) { "Raw fragment file store is closed" }
            val stream = ensureOpen()
            stream.writeLong(sequence)
            stream.writeLong(receivedAtMillis)
            stream.writeInt(fragmentBytes.size)
            stream.write(fragmentBytes)
        }
    }

    fun flush() {
        synchronized(lock) {
            check(!isClosed) { "Raw fragment file store is closed" }
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
            check(!isClosed) { "Raw fragment file store is closed" }
            outputStream?.close()
            outputStream = null
            currentFile = null
        }
    }

    fun useSessionDirectory(directory: File) {
        synchronized(lock) {
            check(!isClosed) { "Raw fragment file store is closed" }
            outputStream?.close()
            outputStream = null
            currentFile = null
            this.directory = directory
            sessionFileName = "raw_fragments.binlog"
        }
    }

    fun currentFile(): File? = synchronized(lock) { currentFile }

    private fun ensureOpen(): DataOutputStream {
        check(!isClosed) { "Raw fragment file store is closed" }
        if (outputStream == null) {
            directory.mkdirs()
            val file = File(directory, sessionFileName ?: "$filePrefix${timestampProvider()}.binlog")
            val stream = DataOutputStream(
                BufferedOutputStream(FileOutputStream(file, true), BUFFER_SIZE_BYTES),
            )
            stream.write(MAGIC_HEADER)
            currentFile = file
            outputStream = stream
        }
        return requireNotNull(outputStream)
    }

    private companion object {
        val MAGIC_HEADER = byteArrayOf(
            'B'.code.toByte(),
            'L'.code.toByte(),
            'E'.code.toByte(),
            'R'.code.toByte(),
            'A'.code.toByte(),
            'W'.code.toByte(),
            '1'.code.toByte(),
            0,
        )
        const val BUFFER_SIZE_BYTES = 65_536
        const val DEFAULT_FILE_PREFIX = "ble_raw_"
    }
}
