import XCTest
@testable import Woona

final class PacketTimelineFileStoreTests: XCTestCase {
    func testWritesAndroidCompatibleBigEndianTimeline() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = PacketTimelineFileStore(directory: directory)
        try store.append(sequence: 1, counter: 2, deviceTimerMillis: 3, hostWallClockMillis: 4, hostMonotonicNs: 5, packetBytes: 6)
        try store.close()
        let file = try XCTUnwrap(store.currentFile())
        let bytes = try Data(contentsOf: file)
        XCTAssertEqual(Array(bytes.prefix(8)), Array("BLETIME1".utf8))
        XCTAssertEqual(bytes.count, 8 + 8 * 5 + 4)
    }
}
