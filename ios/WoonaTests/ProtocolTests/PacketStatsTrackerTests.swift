import XCTest
@testable import Woona

final class PacketStatsTrackerTests: XCTestCase {
    func testRecordCountsReceivedPackets() {
        let tracker = PacketStatsTracker()

        _ = tracker.record(counter: 10)
        let result = tracker.record(counter: 11)

        XCTAssertEqual(result.snapshot.packetsReceived, 2)
        XCTAssertEqual(result.snapshot.packetsLost, 0)
        XCTAssertEqual(result.gapCount, 0)
    }

    func testRecordCountsCounterGapsAsLostPackets() {
        let tracker = PacketStatsTracker()

        _ = tracker.record(counter: 100)
        let result = tracker.record(counter: 103)

        XCTAssertEqual(result.snapshot.packetsReceived, 2)
        XCTAssertEqual(result.snapshot.packetsLost, 2)
        XCTAssertEqual(result.gapCount, 2)
        XCTAssertEqual(result.expectedCounter, 101)
        XCTAssertEqual(result.actualCounter, 103)
    }

    func testRecordIgnoresBackwardsCounterForLossAccounting() {
        let tracker = PacketStatsTracker()

        _ = tracker.record(counter: 10)
        let result = tracker.record(counter: 9)

        XCTAssertEqual(result.snapshot.packetsReceived, 2)
        XCTAssertEqual(result.snapshot.packetsLost, 0)
        XCTAssertEqual(result.gapCount, 0)
    }

    func testResetClearsCountersForNewSession() {
        let tracker = PacketStatsTracker()

        _ = tracker.record(counter: 5)
        _ = tracker.record(counter: 8)
        tracker.reset()
        let result = tracker.record(counter: 100)

        XCTAssertEqual(result.snapshot.packetsReceived, 1)
        XCTAssertEqual(result.snapshot.packetsLost, 0)
        XCTAssertEqual(result.gapCount, 0)
    }

    func testRecordKeepsUnsignedCounterDomainWithoutWrappingExpectedCounter() {
        let tracker = PacketStatsTracker()

        _ = tracker.record(counter: UInt64(UInt32.max))
        let result = tracker.record(counter: 0)

        XCTAssertEqual(result.snapshot.packetsReceived, 2)
        XCTAssertEqual(result.snapshot.packetsLost, 0)
        XCTAssertEqual(result.gapCount, 0)
        XCTAssertEqual(result.expectedCounter, UInt64(UInt32.max) + 1)
        XCTAssertEqual(result.actualCounter, 0)
    }
}
