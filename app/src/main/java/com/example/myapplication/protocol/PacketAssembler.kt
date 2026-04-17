package com.example.myapplication.protocol

data class CompletedPacket(
    val bytes: ByteArray,
    val counter: Long,
    val sensorBlocks: List<SensorBlock>,
) {
    fun channelSamples(
        sensorType: Int,
        channel: Int,
    ): List<Float> {
        if (channel <= 0) return emptyList()

        return sensorBlocks
            .asSequence()
            .filter { it.sensorType == sensorType && channel <= it.channelSamples.size }
            .flatMap { it.channelSamples[channel - 1].asSequence() }
            .toList()
    }
}

class PacketAssembler(
    initialCapacity: Int = DEFAULT_CAPACITY,
    private val sensorBlockParser: SensorBlockParser = SensorBlockParser(),
) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun reset() {
        size = 0
    }

    fun append(chunk: ByteArray): List<CompletedPacket> {
        if (chunk.isEmpty()) return emptyList()

        ensureCapacity(size + chunk.size)
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size += chunk.size

        val packets = mutableListOf<CompletedPacket>()

        while (true) {
            if (size < MIN_PACKET_SIZE) {
                return packets
            }

            val headerIndex = findHeader(buffer, size)
            if (headerIndex == -1) {
                keepTailForPartialHeader()
                return packets
            }

            if (headerIndex > 0) {
                shiftLeft(headerIndex)
            }

            if (size < MIN_PACKET_SIZE) {
                return packets
            }

            val expectedLength = decodeLength(buffer)
            if (expectedLength < MIN_PACKET_SIZE) {
                // Invalid length after what looked like a header. Shift by one byte and resync.
                shiftLeft(1)
                continue
            }

            if (size < expectedLength) {
                return packets
            }

            val packetBytes = buffer.copyOfRange(0, expectedLength)
            packets += CompletedPacket(
                bytes = packetBytes,
                counter = decodeCounter(packetBytes),
                sensorBlocks = sensorBlockParser.parse(packetBytes),
            )
            shiftLeft(expectedLength)
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return

        var newCapacity = buffer.size
        while (newCapacity < required) {
            newCapacity *= 2
        }

        val expanded = ByteArray(newCapacity)
        System.arraycopy(buffer, 0, expanded, 0, size)
        buffer = expanded
    }

    private fun keepTailForPartialHeader() {
        if (size <= HEADER_SIZE - 1) return

        val tailSize = HEADER_SIZE - 1
        System.arraycopy(buffer, size - tailSize, buffer, 0, tailSize)
        size = tailSize
    }

    private fun shiftLeft(count: Int) {
        val remaining = size - count
        if (remaining > 0) {
            System.arraycopy(buffer, count, buffer, 0, remaining)
        }
        size = remaining.coerceAtLeast(0)
    }

    private fun decodeLength(packetBuffer: ByteArray): Int {
        return (packetBuffer[4].toInt() and 0xFF) or
            ((packetBuffer[5].toInt() and 0xFF) shl 8)
    }

    private fun decodeCounter(packetBytes: ByteArray): Long {
        val c0 = packetBytes[7].toLong() and 0xFF
        val c1 = packetBytes[8].toLong() and 0xFF
        val c2 = packetBytes[9].toLong() and 0xFF
        val c3 = packetBytes[10].toLong() and 0xFF
        return c0 or (c1 shl 8) or (c2 shl 16) or (c3 shl 24)
    }

    private fun findHeader(packetBuffer: ByteArray, packetSize: Int): Int {
        for (index in 0..packetSize - HEADER_SIZE) {
            if (
                packetBuffer[index] == 0x33.toByte() &&
                packetBuffer[index + 1] == 0x99.toByte() &&
                packetBuffer[index + 2] == 0xAA.toByte() &&
                packetBuffer[index + 3] == 0x55.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512 * 1024
        const val HEADER_SIZE = 4
        const val MIN_PACKET_SIZE = 16
    }
}
