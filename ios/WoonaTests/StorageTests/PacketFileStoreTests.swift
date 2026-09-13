import XCTest
@testable import Woona

final class PacketFileStoreTests: XCTestCase {
    func testOpenCreatesFileInConfiguredDirectory() throws {
        let directory = try makeTemporaryDirectory()
        let store = PacketFileStore(directory: directory, timestampProvider: { 1_234 })

        let file = try store.open()

        XCTAssertEqual(file.deletingLastPathComponent(), directory)
        XCTAssertEqual(file.lastPathComponent, "ble_dump_1234.bin")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        try store.close()
    }

    func testAppendPersistsAcceptedPacketBytesExactlyToCurrentFile() throws {
        let directory = try makeTemporaryDirectory()
        let store = PacketFileStore(directory: directory, timestampProvider: { 55 })
        let first = makeTestPacket(counter: 1, measurementCount: 1)
        let second = makeTestPacket(counter: 2, payloadSize: 2, measurementCount: 1)

        try store.append(first)
        try store.append(second)
        try store.flush()
        try store.close()

        let file = try XCTUnwrap(store.currentFile())
        XCTAssertEqual([UInt8](try Data(contentsOf: file)), first + second)
    }

    func testAppendOpensFileLazily() throws {
        let directory = try makeTemporaryDirectory()
        let store = PacketFileStore(directory: directory, timestampProvider: { 77 })

        try store.append([0x0A])
        try store.flush()
        try store.close()

        let file = try XCTUnwrap(store.currentFile())
        XCTAssertEqual(file.lastPathComponent, "ble_dump_77.bin")
        XCTAssertEqual([UInt8](try Data(contentsOf: file)), [0x0A])
    }

    func testAppendAfterCloseFailsInsteadOfReopeningAnotherFile() throws {
        let directory = try makeTemporaryDirectory()
        let store = PacketFileStore(directory: directory, timestampProvider: { 88 })

        _ = try store.open()
        try store.close()

        XCTAssertThrowsError(try store.append([0x0B])) { error in
            XCTAssertEqual(error as? PacketFileStoreError, .closed)
        }
    }

    func testResetSessionRotatesToANewFileForNextAppend() throws {
        let directory = try makeTemporaryDirectory()
        var timestamp: Int64 = 10
        let store = PacketFileStore(directory: directory, timestampProvider: { timestamp })

        try store.append([0x01])
        try store.flush()
        let firstFile = try XCTUnwrap(store.currentFile())

        timestamp = 20
        try store.resetSession()
        try store.append([0x02])
        try store.flush()
        let secondFile = try XCTUnwrap(store.currentFile())

        XCTAssertEqual(firstFile.lastPathComponent, "ble_dump_10.bin")
        XCTAssertEqual(secondFile.lastPathComponent, "ble_dump_20.bin")
        XCTAssertEqual([UInt8](try Data(contentsOf: firstFile)), [0x01])
        XCTAssertEqual([UInt8](try Data(contentsOf: secondFile)), [0x02])
        try store.close()
    }
}

func makeTemporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("woona-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}
