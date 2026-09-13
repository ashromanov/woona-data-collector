import XCTest
@testable import Woona

final class RawFragmentFileStoreTests: XCTestCase {
    func testAppendFragmentWritesMagicHeaderAndBigEndianEntry() throws {
        let directory = try makeTemporaryDirectory()
        let store = RawFragmentFileStore(directory: directory, timestampProvider: { 11 })

        try store.appendFragment(
            sequence: 0x01_02_03_04_05_06_07_08,
            receivedAtMillis: 0x11_12_13_14_15_16_17_18,
            fragmentBytes: [0xAA, 0xBB, 0xCC]
        )
        try store.flush()
        try store.close()

        let file = try XCTUnwrap(store.currentFile())
        XCTAssertEqual(file.lastPathComponent, "ble_raw_11.binlog")
        XCTAssertEqual(
            [UInt8](try Data(contentsOf: file)),
            [
                0x42, 0x4C, 0x45, 0x52, 0x41, 0x57, 0x32, 0x00,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x03,
                0xAA, 0xBB, 0xCC
            ]
        )
    }

    func testEmptyFragmentDoesNotCreateFile() throws {
        let directory = try makeTemporaryDirectory()
        let store = RawFragmentFileStore(directory: directory, timestampProvider: { 22 })

        try store.appendFragment(sequence: 1, receivedAtMillis: 2, fragmentBytes: [])

        XCTAssertNil(store.currentFile())
    }

    func testResetSessionRotatesToANewRawFile() throws {
        let directory = try makeTemporaryDirectory()
        var timestamp: Int64 = 10
        let store = RawFragmentFileStore(directory: directory, timestampProvider: { timestamp })

        try store.appendFragment(sequence: 1, receivedAtMillis: 2, fragmentBytes: [0x01])
        try store.flush()
        let firstFile = try XCTUnwrap(store.currentFile())

        timestamp = 20
        try store.resetSession()
        try store.appendFragment(sequence: 3, receivedAtMillis: 4, fragmentBytes: [0x02])
        try store.flush()
        let secondFile = try XCTUnwrap(store.currentFile())

        XCTAssertEqual(firstFile.lastPathComponent, "ble_raw_10.binlog")
        XCTAssertEqual(secondFile.lastPathComponent, "ble_raw_20.binlog")
        XCTAssertEqual(Array([UInt8](try Data(contentsOf: firstFile)).prefix(8)), RawFragmentFileStore.magicHeader)
        XCTAssertEqual(Array([UInt8](try Data(contentsOf: secondFile)).prefix(8)), RawFragmentFileStore.magicHeader)
        try store.close()
    }

    func testResetSessionWithSameTimestampAppendsFreshMagicHeader() throws {
        let directory = try makeTemporaryDirectory()
        let store = RawFragmentFileStore(directory: directory, timestampProvider: { 10 })

        try store.appendFragment(sequence: 1, receivedAtMillis: 2, fragmentBytes: [0x01])
        try store.flush()
        let file = try XCTUnwrap(store.currentFile())

        try store.resetSession()
        try store.appendFragment(sequence: 3, receivedAtMillis: 4, fragmentBytes: [0x02])
        try store.flush()
        try store.close()

        let bytes = [UInt8](try Data(contentsOf: file))
        XCTAssertEqual(Array(bytes.prefix(8)), RawFragmentFileStore.magicHeader)
        XCTAssertEqual(
            bytes.windows(ofCount: RawFragmentFileStore.magicHeader.count)
                .filter { Array($0) == RawFragmentFileStore.magicHeader }
                .count,
            2
        )
    }
}

private extension Array {
    func windows(ofCount count: Int) -> [ArraySlice<Element>] {
        guard count > 0, self.count >= count else { return [] }
        return (0...(self.count - count)).map { self[$0..<($0 + count)] }
    }
}
