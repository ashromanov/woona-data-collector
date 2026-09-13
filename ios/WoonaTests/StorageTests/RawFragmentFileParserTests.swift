import XCTest
@testable import Woona

final class RawFragmentFileParserTests: XCTestCase {
    private let parser = RawFragmentFileParser()

    func testSplitIntoFragmentsReturnsStoredRecords() throws {
        let first: [UInt8] = [0x01, 0x02]
        let second: [UInt8] = [0x33, 0x99, 0xAA, 0x55]
        let fileBytes = RawFragmentFileStore.magicHeader
            + makeRawFragmentRecord(sequence: 10, receivedAtMillis: 1_000, bytes: first)
            + makeRawFragmentRecord(sequence: 11, receivedAtMillis: 1_015, bytes: second)

        let fragments = try parser.splitIntoFragments(fileBytes)

        XCTAssertEqual(
            fragments,
            [
                RawFragmentRecord(sequence: 10, receivedAtMillis: 1_000, receivedAtMonotonicNs: 0, bytes: first),
                RawFragmentRecord(sequence: 11, receivedAtMillis: 1_015, receivedAtMonotonicNs: 0, bytes: second)
            ]
        )
    }

    func testHasMagicHeaderIdentifiesRawFragmentFiles() {
        XCTAssertTrue(parser.hasMagicHeader(RawFragmentFileStore.magicHeader))
        XCTAssertTrue(parser.hasMagicHeader(RawFragmentFileStore.legacyMagicHeader))
        XCTAssertFalse(parser.hasMagicHeader([0x33, 0x99, 0xAA, 0x55]))
    }

    func testSplitIntoFragmentsRejectsInvalidMagicHeader() {
        XCTAssertThrowsError(try parser.splitIntoFragments([0x33, 0x99, 0xAA, 0x55])) { error in
            XCTAssertEqual(error as? RawFragmentFileParserError, .invalidMagicHeader)
        }
    }

    func testSplitIntoFragmentsRejectsTruncatedRecordHeader() {
        let fileBytes = RawFragmentFileStore.magicHeader + [0x00, 0x01]

        XCTAssertThrowsError(try parser.splitIntoFragments(fileBytes)) { error in
            XCTAssertEqual(
                error as? RawFragmentFileParserError,
                .truncatedRecordHeader(offset: RawFragmentFileStore.magicHeader.count)
            )
        }
    }

    func testSplitIntoFragmentsRejectsInvalidFragmentLength() {
        let fileBytes = RawFragmentFileStore.magicHeader
            + makeRawFragmentRecordHeader(sequence: 1, receivedAtMillis: 2, length: 0)

        XCTAssertThrowsError(try parser.splitIntoFragments(fileBytes)) { error in
            XCTAssertEqual(
                error as? RawFragmentFileParserError,
                .invalidFragmentLength(length: 0, offset: RawFragmentFileStore.magicHeader.count)
            )
        }
    }

    func testSplitIntoFragmentsRejectsTruncatedFragmentBody() {
        let fileBytes = RawFragmentFileStore.magicHeader
            + makeRawFragmentRecordHeader(sequence: 1, receivedAtMillis: 2, length: 3)
            + [0x01, 0x02]

        XCTAssertThrowsError(try parser.splitIntoFragments(fileBytes)) { error in
            XCTAssertEqual(
                error as? RawFragmentFileParserError,
                .truncatedFragmentBody(offset: RawFragmentFileStore.magicHeader.count, expectedLength: 3)
            )
        }
    }
}

private func makeRawFragmentRecord(sequence: Int64, receivedAtMillis: Int64, bytes: [UInt8]) -> [UInt8] {
    makeRawFragmentRecordHeader(sequence: sequence, receivedAtMillis: receivedAtMillis, length: Int32(bytes.count)) + bytes
}

private func makeRawFragmentRecordHeader(sequence: Int64, receivedAtMillis: Int64, length: Int32) -> [UInt8] {
    var bytes: [UInt8] = []
    bytes.appendBigEndianInt64(sequence)
    bytes.appendBigEndianInt64(receivedAtMillis)
    bytes.appendBigEndianInt64(0)
    bytes.appendBigEndianInt32(length)
    return bytes
}
