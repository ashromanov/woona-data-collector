import XCTest
@testable import Woona

final class DiagnosticLogFileStoreTests: XCTestCase {
    func testAppendEventWritesTabSeparatedRows() throws {
        let directory = try makeTemporaryDirectory()
        let store = DiagnosticLogFileStore(directory: directory, timestampProvider: { 111 })

        try store.appendEvent(eventId: 7, type: "info", message: "connected")
        try store.appendEvent(eventId: 8, type: "warn", message: "packet rejected")
        try store.flush()
        try store.close()

        let file = try XCTUnwrap(store.currentFile())
        XCTAssertEqual(file.lastPathComponent, "ble_log_111.log")
        XCTAssertEqual(
            String(decoding: try Data(contentsOf: file), as: UTF8.self),
            "111\t7\tinfo\tconnected\n111\t8\twarn\tpacket rejected\n"
        )
    }

    func testResetSessionStartsFreshDiagnosticLog() throws {
        let directory = try makeTemporaryDirectory()
        var timestamp: Int64 = 10
        let store = DiagnosticLogFileStore(directory: directory, timestampProvider: { timestamp })

        try store.appendEvent(eventId: 1, type: "info", message: "first")
        try store.flush()
        let firstFile = try XCTUnwrap(store.currentFile())

        timestamp = 20
        try store.resetSession()
        try store.appendEvent(eventId: 2, type: "info", message: "second")
        try store.flush()
        let secondFile = try XCTUnwrap(store.currentFile())

        XCTAssertEqual(firstFile.lastPathComponent, "ble_log_10.log")
        XCTAssertEqual(secondFile.lastPathComponent, "ble_log_20.log")
        XCTAssertEqual(String(decoding: try Data(contentsOf: firstFile), as: UTF8.self), "10\t1\tinfo\tfirst\n")
        XCTAssertEqual(String(decoding: try Data(contentsOf: secondFile), as: UTF8.self), "20\t2\tinfo\tsecond\n")
        try store.close()
    }
}
