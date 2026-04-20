package com.example.myapplication.protocol

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class RecordedPacketFileParser {
    fun splitIntoPackets(fileBytes: ByteArray): List<ByteArray> {
        if (fileBytes.isEmpty()) return emptyList()

        val packets = mutableListOf<ByteArray>()
        forEachPacket(fileBytes) { packetBytes ->
            packets += packetBytes
        }

        return packets
    }

    fun forEachPacket(
        fileBytes: ByteArray,
        onPacket: (ByteArray) -> Unit,
    ) {
        if (fileBytes.isEmpty()) return

        var offset = 0
        while (offset < fileBytes.size) {
            if (offset + MIN_PACKET_SIZE > fileBytes.size) {
                throw IllegalArgumentException("Truncated packet header at offset $offset")
            }

            if (!hasStartMarker(fileBytes, offset)) {
                throw IllegalArgumentException("Invalid packet start marker at offset $offset")
            }

            val packetLength = decodeLength(fileBytes, offset)
            if (packetLength < MIN_PACKET_SIZE) {
                throw IllegalArgumentException("Invalid packet length $packetLength at offset $offset")
            }

            val endOffset = offset + packetLength
            if (endOffset > fileBytes.size) {
                throw IllegalArgumentException(
                    "Truncated packet body at offset $offset: expected $packetLength bytes",
                )
            }

            onPacket(fileBytes.copyOfRange(offset, endOffset))
            offset = endOffset
        }
    }

    fun forEachPacket(
        packetFile: File,
        onPacket: (ByteArray) -> Unit,
    ) {
        if (!packetFile.exists() || packetFile.length() == 0L) return

        BufferedInputStream(FileInputStream(packetFile), FILE_BUFFER_SIZE_BYTES).use { inputStream ->
            forEachPacket(inputStream, onPacket)
        }
    }

    fun forEachPacket(
        inputStream: InputStream,
        onPacket: (ByteArray) -> Unit,
    ) {
        var offset = 0L

        while (true) {
            val header = ByteArray(MIN_PACKET_SIZE)
            val headerBytesRead = readHeader(inputStream, header)
            if (headerBytesRead == 0) return
            if (headerBytesRead < MIN_PACKET_SIZE) {
                throw IllegalArgumentException("Truncated packet header at offset $offset")
            }

            if (!hasStartMarker(header, 0)) {
                throw IllegalArgumentException("Invalid packet start marker at offset $offset")
            }

            val packetLength = decodeLength(header, 0)
            if (packetLength < MIN_PACKET_SIZE) {
                throw IllegalArgumentException("Invalid packet length $packetLength at offset $offset")
            }

            val packetBytes = ByteArray(packetLength)
            System.arraycopy(header, 0, packetBytes, 0, MIN_PACKET_SIZE)

            val remainingBytes = packetLength - MIN_PACKET_SIZE
            if (remainingBytes > 0) {
                try {
                    readFully(
                        inputStream = inputStream,
                        buffer = packetBytes,
                        offset = MIN_PACKET_SIZE,
                        byteCount = remainingBytes,
                    )
                } catch (_: EOFException) {
                    throw IllegalArgumentException(
                        "Truncated packet body at offset $offset: expected $packetLength bytes",
                    )
                }
            }

            onPacket(packetBytes)
            offset += packetLength.toLong()
        }
    }

    private fun hasStartMarker(packetBytes: ByteArray, offset: Int): Boolean {
        return packetBytes[offset] == 0x33.toByte() &&
            packetBytes[offset + 1] == 0x99.toByte() &&
            packetBytes[offset + 2] == 0xAA.toByte() &&
            packetBytes[offset + 3] == 0x55.toByte()
    }

    private fun decodeLength(packetBytes: ByteArray, offset: Int): Int {
        return (packetBytes[offset + LENGTH_OFFSET].toInt() and 0xFF) or
            ((packetBytes[offset + LENGTH_OFFSET + 1].toInt() and 0xFF) shl 8)
    }

    private fun readFully(
        inputStream: InputStream,
        buffer: ByteArray,
        offset: Int,
        byteCount: Int,
    ) {
        var totalRead = 0
        while (totalRead < byteCount) {
            val bytesRead = inputStream.read(buffer, offset + totalRead, byteCount - totalRead)
            if (bytesRead == -1) {
                throw EOFException("Unexpected end of packet stream")
            }
            totalRead += bytesRead
        }
    }

    private fun readHeader(
        inputStream: InputStream,
        header: ByteArray,
    ): Int {
        var totalRead = 0
        while (totalRead < header.size) {
            val bytesRead = inputStream.read(header, totalRead, header.size - totalRead)
            if (bytesRead == -1) {
                return totalRead
            }
            totalRead += bytesRead
        }
        return totalRead
    }

    private companion object {
        const val LENGTH_OFFSET = 4
        const val MIN_PACKET_SIZE = 16
        const val FILE_BUFFER_SIZE_BYTES = 65_536
    }
}
