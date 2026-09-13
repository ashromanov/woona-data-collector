package com.example.myapplication.protocol

data class SensorBlock(
    val sensorType: Int,
    val channelSamples: List<List<Float>>,
)

data class SensorBlockParseResult(
    val blocks: List<SensorBlock>,
    val parsedBlockCount: Int,
    val consumedBytes: Int,
    val isComplete: Boolean,
)

class SensorBlockParser {
    fun parse(packetBytes: ByteArray): List<SensorBlock> {
        return parseResult(packetBytes).blocks
    }

    fun parseResult(packetBytes: ByteArray): SensorBlockParseResult {
        if (packetBytes.size < HEADER_SIZE) {
            return SensorBlockParseResult(
                blocks = emptyList(),
                parsedBlockCount = 0,
                consumedBytes = 0,
                isComplete = false,
            )
        }

        val measurementCount = packetBytes[6].toInt() and 0xFF
        var blockOffset = HEADER_SIZE
        val blocks = mutableListOf<SensorBlock>()
        var parsedBlockCount = 0

        repeat(measurementCount) {
            if (blockOffset + BLOCK_HEADER_SIZE > packetBytes.size) {
                return SensorBlockParseResult(
                    blocks = blocks.toList(),
                    parsedBlockCount = parsedBlockCount,
                    consumedBytes = blockOffset,
                    isComplete = false,
                )
            }

            val blockSensorType = packetBytes[blockOffset].toInt() and 0xFF
            val channelCount = packetBytes[blockOffset + 1].toInt() and 0xFF
            val samplesPerChannel = (packetBytes[blockOffset + 2].toInt() and 0xFF) or
                ((packetBytes[blockOffset + 3].toInt() and 0xFF) shl 8)

            val blockPayloadSize = channelCount * samplesPerChannel * BYTES_PER_SAMPLE
            val nextBlockOffset = blockOffset + BLOCK_HEADER_SIZE + blockPayloadSize
            if (nextBlockOffset > packetBytes.size) {
                return SensorBlockParseResult(
                    blocks = blocks.toList(),
                    parsedBlockCount = parsedBlockCount,
                    consumedBytes = blockOffset,
                    isComplete = false,
                )
            }

            val channels = MutableList(channelCount) { mutableListOf<Float>() }
            var sampleOffset = blockOffset + BLOCK_HEADER_SIZE

            for (channelIndex in 0 until channelCount) {
                repeat(samplesPerChannel) {
                    val raw = (packetBytes[sampleOffset].toInt() and 0xFF) or
                        ((packetBytes[sampleOffset + 1].toInt() and 0xFF) shl 8)
                    channels[channelIndex] += raw.toShort().toFloat()
                    sampleOffset += BYTES_PER_SAMPLE
                }
            }

            blocks += SensorBlock(
                sensorType = blockSensorType,
                channelSamples = channels.map { it.toList() },
            )
            blockOffset = nextBlockOffset
            parsedBlockCount++
        }

        return SensorBlockParseResult(
            blocks = blocks.toList(),
            parsedBlockCount = parsedBlockCount,
            consumedBytes = blockOffset,
            isComplete = blockOffset == packetBytes.size,
        )
    }

    fun extractChannelSamples(
        packetBytes: ByteArray,
        sensorType: Int,
        channel: Int,
    ): List<Float> = extractChannelSamples(
        sensorBlocks = parse(packetBytes),
        sensorType = sensorType,
        channel = channel,
    )

    fun extractChannelSamples(
        sensorBlocks: List<SensorBlock>,
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

    private companion object {
        const val HEADER_SIZE = 16
        const val BLOCK_HEADER_SIZE = 6
        const val BYTES_PER_SAMPLE = 2
    }
}
