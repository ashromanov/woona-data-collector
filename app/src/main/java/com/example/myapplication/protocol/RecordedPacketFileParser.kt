package com.example.myapplication.protocol

class RecordedPacketFileParser {
    fun splitIntoPackets(fileBytes: ByteArray): List<ByteArray> {
        if (fileBytes.isEmpty()) return emptyList()

        val packets = mutableListOf<ByteArray>()
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

            packets += fileBytes.copyOfRange(offset, endOffset)
            offset = endOffset
        }

        return packets
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

    private companion object {
        const val LENGTH_OFFSET = 4
        const val MIN_PACKET_SIZE = 16
    }
}
