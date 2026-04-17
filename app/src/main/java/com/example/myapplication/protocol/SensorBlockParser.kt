package com.example.myapplication.protocol

data class SensorBlock(
    val sensorType: Int,
    val channelSamples: List<List<Float>>,
)

class SensorBlockParser {
    fun parse(packetBytes: ByteArray): List<SensorBlock> {
        if (packetBytes.size < HEADER_SIZE) return emptyList()

        val measurementCount = packetBytes[6].toInt() and 0xFF
        var blockOffset = HEADER_SIZE
        val blocks = mutableListOf<SensorBlock>()

        repeat(measurementCount) {
            if (blockOffset + BLOCK_HEADER_SIZE > packetBytes.size) return blocks

            val blockSensorType = packetBytes[blockOffset].toInt() and 0xFF
            val channelCount = packetBytes[blockOffset + 1].toInt() and 0xFF
            val samplesPerChannel = (packetBytes[blockOffset + 2].toInt() and 0xFF) or
                ((packetBytes[blockOffset + 3].toInt() and 0xFF) shl 8)

            val blockPayloadSize = channelCount * samplesPerChannel * BYTES_PER_SAMPLE
            val nextBlockOffset = blockOffset + BLOCK_HEADER_SIZE + blockPayloadSize
            if (nextBlockOffset > packetBytes.size) return blocks

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
        }

        return blocks
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
