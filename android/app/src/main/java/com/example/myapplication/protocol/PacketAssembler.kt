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

enum class PacketAssemblyFailureReason(val description: String) {
    OVERLAPPING_PACKET_START("New packet start marker found before previous packet completed"),
}

sealed interface PacketAssemblyResult {
    data class Completed(
        val packet: CompletedPacket,
    ) : PacketAssemblyResult

    data class Rejected(
        val reason: PacketAssemblyFailureReason,
    ) : PacketAssemblyResult
}

class PacketAssembler(
    initialCapacity: Int = DEFAULT_CAPACITY,
    private val sensorBlockParser: SensorBlockParser = SensorBlockParser(),
) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0
    private var pendingBoundaryCandidate = ByteArray(0)

    fun reset() {
        size = 0
        pendingBoundaryCandidate = ByteArray(0)
    }

    fun append(chunk: ByteArray): List<PacketAssemblyResult> {
        if (chunk.isEmpty()) return emptyList()

        val results = mutableListOf<PacketAssemblyResult>()
        val chunkToAppend = resolveBoundaryCandidate(chunk, results)
        if (chunkToAppend.isEmpty()) {
            return results
        }

        ensureCapacity(size + chunkToAppend.size)
        System.arraycopy(chunkToAppend, 0, buffer, size, chunkToAppend.size)
        size += chunkToAppend.size

        while (true) {
            if (size < MIN_PACKET_SIZE) {
                return results
            }

            val headerIndex = findHeader(buffer, size)
            if (headerIndex == -1) {
                keepTailForPartialHeader()
                return results
            }

            if (headerIndex > 0) {
                shiftLeft(headerIndex)
            }

            if (size < MIN_PACKET_SIZE) {
                return results
            }

            val expectedLength = decodeLength(buffer)
            if (expectedLength < MIN_PACKET_SIZE) {
                // Invalid length after what looked like a header. Shift by one byte and resync.
                shiftLeft(1)
                continue
            }

            if (size < expectedLength) {
                return results
            }

            val packetBytes = buffer.copyOfRange(0, expectedLength)
            results += PacketAssemblyResult.Completed(
                packet = CompletedPacket(
                    bytes = packetBytes,
                    counter = decodeCounter(packetBytes),
                    sensorBlocks = sensorBlockParser.parse(packetBytes),
                ),
            )
            shiftLeft(expectedLength)
        }
    }

    private fun hasIncompletePacket(): Boolean {
        if (size < MIN_PACKET_SIZE) return false
        if (findHeader(buffer, size) != 0) return false

        val expectedLength = decodeLength(buffer)
        return expectedLength >= MIN_PACKET_SIZE && size < expectedLength
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

    private fun resolveBoundaryCandidate(
        chunk: ByteArray,
        results: MutableList<PacketAssemblyResult>,
    ): ByteArray {
        if (pendingBoundaryCandidate.isNotEmpty()) {
            pendingBoundaryCandidate += chunk
            return resolvePendingBoundaryCandidate(results)
        }

        if (!hasIncompletePacket()) return chunk
        if (!startsWithHeaderPrefix(chunk)) return chunk

        pendingBoundaryCandidate = chunk.copyOf()
        return resolvePendingBoundaryCandidate(results)
    }

    private fun resolvePendingBoundaryCandidate(results: MutableList<PacketAssemblyResult>): ByteArray {
        val candidate = pendingBoundaryCandidate
        if (!startsWithHeaderPrefix(candidate)) {
            pendingBoundaryCandidate = ByteArray(0)
            return candidate
        }

        return when (classifyOverlapCandidate(candidate)) {
            OverlapCandidateState.INCOMPLETE -> ByteArray(0)
            OverlapCandidateState.INVALID -> {
                pendingBoundaryCandidate = ByteArray(0)
                candidate
            }

            OverlapCandidateState.CONFIRMED -> {
                pendingBoundaryCandidate = ByteArray(0)
                size = 0
                results += PacketAssemblyResult.Rejected(PacketAssemblyFailureReason.OVERLAPPING_PACKET_START)
                candidate
            }
        }
    }

    private fun classifyOverlapCandidate(chunk: ByteArray): OverlapCandidateState {
        if (chunk.size < MIN_PACKET_SIZE) return OverlapCandidateState.INCOMPLETE
        if (!hasHeaderAt(chunk, 0)) return OverlapCandidateState.INVALID

        val declaredLength = decodeLength(chunk)
        if (declaredLength < MIN_PACKET_SIZE) return OverlapCandidateState.INVALID

        val measurementCount = chunk[MEASUREMENT_COUNT_OFFSET].toInt() and 0xFF
        if (measurementCount !in 1..MAX_MEASUREMENT_COUNT) return OverlapCandidateState.INVALID
        if (chunk.size < declaredLength) return OverlapCandidateState.INCOMPLETE

        val currentPacketCounter = decodeCounter(buffer)
        val currentPacketTimer = decodeTimer(buffer)
        val packetBytes = chunk.copyOfRange(0, declaredLength)
        val parseResult = sensorBlockParser.parseResult(packetBytes)
        val candidateCounter = decodeCounter(packetBytes)
        val candidateTimer = decodeTimer(packetBytes)
        return if (
            parseResult.isComplete &&
            parseResult.parsedBlockCount == measurementCount &&
            candidateCounter > currentPacketCounter &&
            candidateTimer >= currentPacketTimer
        ) {
            OverlapCandidateState.CONFIRMED
        } else {
            OverlapCandidateState.INVALID
        }
    }

    private fun startsWithHeaderPrefix(chunk: ByteArray): Boolean {
        val prefixSize = minOf(chunk.size, HEADER_SIZE)
        if (prefixSize == 0) return false

        for (index in 0 until prefixSize) {
            if (chunk[index] != START_MARKER[index]) {
                return false
            }
        }

        return true
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

    private fun decodeTimer(packetBytes: ByteArray): Long {
        val t0 = packetBytes[11].toLong() and 0xFF
        val t1 = packetBytes[12].toLong() and 0xFF
        val t2 = packetBytes[13].toLong() and 0xFF
        val t3 = packetBytes[14].toLong() and 0xFF
        return t0 or (t1 shl 8) or (t2 shl 16) or (t3 shl 24)
    }

    private fun findHeader(packetBuffer: ByteArray, packetSize: Int): Int {
        for (index in 0..packetSize - HEADER_SIZE) {
            if (hasHeaderAt(packetBuffer, index)) {
                return index
            }
        }
        return -1
    }

    private fun hasHeaderAt(packetBuffer: ByteArray, index: Int): Boolean {
        return packetBuffer[index] == 0x33.toByte() &&
            packetBuffer[index + 1] == 0x99.toByte() &&
            packetBuffer[index + 2] == 0xAA.toByte() &&
            packetBuffer[index + 3] == 0x55.toByte()
    }

    private companion object {
        val START_MARKER = byteArrayOf(
            0x33,
            0x99.toByte(),
            0xAA.toByte(),
            0x55,
        )
        const val DEFAULT_CAPACITY = 512 * 1024
        const val HEADER_SIZE = 4
        const val MIN_PACKET_SIZE = 16
        const val MEASUREMENT_COUNT_OFFSET = 6
        const val MAX_MEASUREMENT_COUNT = 4
    }

    private enum class OverlapCandidateState {
        INCOMPLETE,
        INVALID,
        CONFIRMED,
    }
}
