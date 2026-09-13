import Foundation

struct PacketStatsSnapshot: Equatable {
    let packetsReceived: UInt64
    let packetsLost: UInt64
}

struct PacketRecordResult: Equatable {
    let snapshot: PacketStatsSnapshot
    let gapCount: UInt64
    let expectedCounter: UInt64?
    let actualCounter: UInt64?
}

final class PacketStatsTracker {
    private var lastCounter: UInt64?
    private var packetsReceived: UInt64 = 0
    private var packetsLost: UInt64 = 0

    func reset() {
        lastCounter = nil
        packetsReceived = 0
        packetsLost = 0
    }

    func record(counter: UInt64) -> PacketRecordResult {
        var gapCount: UInt64 = 0
        let expectedCounter = lastCounter.map { $0 + 1 }

        if let expectedCounter, counter > expectedCounter {
            gapCount = counter - expectedCounter
            packetsLost += gapCount
        }

        packetsReceived += 1
        lastCounter = counter

        return PacketRecordResult(
            snapshot: snapshot(),
            gapCount: gapCount,
            expectedCounter: expectedCounter,
            actualCounter: counter
        )
    }

    func snapshot() -> PacketStatsSnapshot {
        PacketStatsSnapshot(packetsReceived: packetsReceived, packetsLost: packetsLost)
    }
}
