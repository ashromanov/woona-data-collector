import Foundation

enum RawFragmentFileParserError: Error, Equatable, LocalizedError {
    case invalidMagicHeader
    case truncatedRecordHeader(offset: Int)
    case invalidFragmentLength(length: Int32, offset: Int)
    case truncatedFragmentBody(offset: Int, expectedLength: Int)

    var errorDescription: String? {
        switch self {
        case .invalidMagicHeader:
            "Raw fragment file has an invalid magic header"
        case let .truncatedRecordHeader(offset):
            "Truncated raw fragment record header at offset \(offset)"
        case let .invalidFragmentLength(length, offset):
            "Invalid raw fragment length \(length) at offset \(offset)"
        case let .truncatedFragmentBody(offset, expectedLength):
            "Truncated raw fragment body at offset \(offset): expected \(expectedLength) bytes"
        }
    }
}

struct RawFragmentRecord: Equatable {
    let sequence: Int64
    let receivedAtMillis: Int64
    let receivedAtMonotonicNs: Int64?
    let bytes: [UInt8]

    init(sequence: Int64, receivedAtMillis: Int64, receivedAtMonotonicNs: Int64? = nil, bytes: [UInt8]) {
        self.sequence = sequence
        self.receivedAtMillis = receivedAtMillis
        self.receivedAtMonotonicNs = receivedAtMonotonicNs
        self.bytes = bytes
    }
}

struct RawFragmentFileParser {
    func hasMagicHeader(_ fileBytes: [UInt8]) -> Bool {
        fileBytes.count >= RawFragmentFileStore.magicHeader.count
            && (fileBytes[0..<RawFragmentFileStore.magicHeader.count].elementsEqual(RawFragmentFileStore.magicHeader)
                || fileBytes[0..<RawFragmentFileStore.legacyMagicHeader.count].elementsEqual(RawFragmentFileStore.legacyMagicHeader))
    }

    func splitIntoFragments(_ fileBytes: [UInt8]) throws -> [RawFragmentRecord] {
        var fragments: [RawFragmentRecord] = []
        try forEachFragment(fileBytes) { fragment in
            fragments.append(fragment)
        }
        return fragments
    }

    func forEachFragment(
        _ fileBytes: [UInt8],
        onFragment: (RawFragmentRecord) throws -> Void
    ) throws {
        guard hasMagicHeader(fileBytes) else {
            throw RawFragmentFileParserError.invalidMagicHeader
        }

        let isV2 = fileBytes[0..<RawFragmentFileStore.magicHeader.count].elementsEqual(RawFragmentFileStore.magicHeader)
        let headerSize = isV2 ? Self.v2RecordHeaderSize : Self.v1RecordHeaderSize
        var offset = RawFragmentFileStore.magicHeader.count
        while offset < fileBytes.count {
            guard offset + headerSize <= fileBytes.count else {
                throw RawFragmentFileParserError.truncatedRecordHeader(offset: offset)
            }

            let sequence = Self.decodeBigEndianInt64(fileBytes, at: offset)
            let receivedAtMillis = Self.decodeBigEndianInt64(fileBytes, at: offset + 8)
            let receivedAtMonotonicNs = isV2 ? Self.decodeBigEndianInt64(fileBytes, at: offset + 16) : nil
            let fragmentLength = Self.decodeBigEndianInt32(fileBytes, at: offset + (isV2 ? 24 : 16))
            guard fragmentLength > 0 else {
                throw RawFragmentFileParserError.invalidFragmentLength(length: fragmentLength, offset: offset)
            }

            let bodyOffset = offset + headerSize
            let endOffset = bodyOffset + Int(fragmentLength)
            guard endOffset <= fileBytes.count else {
                throw RawFragmentFileParserError.truncatedFragmentBody(
                    offset: offset,
                    expectedLength: Int(fragmentLength)
                )
            }

            try onFragment(
                RawFragmentRecord(
                    sequence: sequence,
                    receivedAtMillis: receivedAtMillis,
                    receivedAtMonotonicNs: receivedAtMonotonicNs,
                    bytes: Array(fileBytes[bodyOffset..<endOffset])
                )
            )
            offset = endOffset
        }
    }

    private static func decodeBigEndianInt64(_ bytes: [UInt8], at offset: Int) -> Int64 {
        var value: UInt64 = 0
        for index in offset..<(offset + 8) {
            value = (value << 8) | UInt64(bytes[index])
        }
        return Int64(bitPattern: value)
    }

    private static func decodeBigEndianInt32(_ bytes: [UInt8], at offset: Int) -> Int32 {
        var value: UInt32 = 0
        for index in offset..<(offset + 4) {
            value = (value << 8) | UInt32(bytes[index])
        }
        return Int32(bitPattern: value)
    }

    private static let v1RecordHeaderSize = 20
    private static let v2RecordHeaderSize = 28
}
