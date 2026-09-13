import Foundation

struct SensorBlock: Equatable {
    let sensorType: Int
    let channelSamples: [[Float]]
}

struct SensorBlockParseResult: Equatable {
    let blocks: [SensorBlock]
    let parsedBlockCount: Int
    let consumedBytes: Int
    let isComplete: Bool
}

struct SensorBlockParser {
    func parse(_ packetBytes: [UInt8]) -> [SensorBlock] {
        parseResult(packetBytes).blocks
    }

    func parseResult(_ packetBytes: [UInt8]) -> SensorBlockParseResult {
        guard packetBytes.count >= Self.headerSize else {
            return SensorBlockParseResult(
                blocks: [],
                parsedBlockCount: 0,
                consumedBytes: 0,
                isComplete: false
            )
        }

        let measurementCount = Int(packetBytes[6])
        var blockOffset = Self.headerSize
        var blocks: [SensorBlock] = []
        var parsedBlockCount = 0

        for _ in 0..<measurementCount {
            guard blockOffset + Self.blockHeaderSize <= packetBytes.count else {
                return SensorBlockParseResult(
                    blocks: blocks,
                    parsedBlockCount: parsedBlockCount,
                    consumedBytes: blockOffset,
                    isComplete: false
                )
            }

            let sensorType = Int(packetBytes[blockOffset])
            let channelCount = Int(packetBytes[blockOffset + 1])
            let samplesPerChannel = Int(packetBytes[blockOffset + 2]) |
                (Int(packetBytes[blockOffset + 3]) << 8)
            let payloadSize = channelCount * samplesPerChannel * Self.bytesPerSample
            let nextBlockOffset = blockOffset + Self.blockHeaderSize + payloadSize

            guard nextBlockOffset <= packetBytes.count else {
                return SensorBlockParseResult(
                    blocks: blocks,
                    parsedBlockCount: parsedBlockCount,
                    consumedBytes: blockOffset,
                    isComplete: false
                )
            }

            var channels = Array(repeating: [Float](), count: channelCount)
            var sampleOffset = blockOffset + Self.blockHeaderSize

            for channelIndex in 0..<channelCount {
                for _ in 0..<samplesPerChannel {
                    let raw = UInt16(packetBytes[sampleOffset]) |
                        (UInt16(packetBytes[sampleOffset + 1]) << 8)
                    channels[channelIndex].append(Float(Int16(bitPattern: raw)))
                    sampleOffset += Self.bytesPerSample
                }
            }

            blocks.append(SensorBlock(sensorType: sensorType, channelSamples: channels))
            blockOffset = nextBlockOffset
            parsedBlockCount += 1
        }

        return SensorBlockParseResult(
            blocks: blocks,
            parsedBlockCount: parsedBlockCount,
            consumedBytes: blockOffset,
            isComplete: blockOffset == packetBytes.count
        )
    }

    func extractChannelSamples(
        packetBytes: [UInt8],
        sensorType: Int,
        channel: Int
    ) -> [Float] {
        extractChannelSamples(sensorBlocks: parse(packetBytes), sensorType: sensorType, channel: channel)
    }

    func extractChannelSamples(
        sensorBlocks: [SensorBlock],
        sensorType: Int,
        channel: Int
    ) -> [Float] {
        guard channel > 0 else { return [] }

        return sensorBlocks.flatMap { block -> [Float] in
            guard block.sensorType == sensorType, channel <= block.channelSamples.count else {
                return []
            }
            return block.channelSamples[channel - 1]
        }
    }

    private static let headerSize = 16
    private static let blockHeaderSize = 6
    private static let bytesPerSample = 2
}
