import Foundation

enum RecordedPacketFileParserError: Error, Equatable, LocalizedError {
    case truncatedPacketHeader(offset: Int)
    case invalidPacketStartMarker(offset: Int)
    case invalidPacketLength(length: Int, offset: Int)
    case truncatedPacketBody(offset: Int, expectedLength: Int)

    var errorDescription: String? {
        switch self {
        case let .truncatedPacketHeader(offset):
            "Truncated packet header at offset \(offset)"
        case let .invalidPacketStartMarker(offset):
            "Invalid packet start marker at offset \(offset)"
        case let .invalidPacketLength(length, offset):
            "Invalid packet length \(length) at offset \(offset)"
        case let .truncatedPacketBody(offset, expectedLength):
            "Truncated packet body at offset \(offset): expected \(expectedLength) bytes"
        }
    }
}

struct RecordedPacketFileParser {
    func splitIntoPackets(_ fileBytes: [UInt8]) throws -> [[UInt8]] {
        var packets: [[UInt8]] = []
        try forEachPacket(fileBytes) { packetBytes in
            packets.append(packetBytes)
        }
        return packets
    }

    func forEachPacket(
        _ fileBytes: [UInt8],
        onPacket: ([UInt8]) throws -> Void
    ) throws {
        guard !fileBytes.isEmpty else { return }

        var offset = 0
        while offset < fileBytes.count {
            guard offset + PacketValidator.minimumPacketSize <= fileBytes.count else {
                throw RecordedPacketFileParserError.truncatedPacketHeader(offset: offset)
            }

            guard PacketValidator.hasStartMarker(fileBytes, at: offset) else {
                throw RecordedPacketFileParserError.invalidPacketStartMarker(offset: offset)
            }

            let packetLength = PacketValidator.decodeLength(fileBytes, at: offset)
            guard (PacketValidator.minimumPacketSize...PacketValidator.maximumPacketSize).contains(packetLength) else {
                throw RecordedPacketFileParserError.invalidPacketLength(length: packetLength, offset: offset)
            }

            let endOffset = offset + packetLength
            guard endOffset <= fileBytes.count else {
                throw RecordedPacketFileParserError.truncatedPacketBody(
                    offset: offset,
                    expectedLength: packetLength
                )
            }

            try onPacket(Array(fileBytes[offset..<endOffset]))
            offset = endOffset
        }
    }
}
