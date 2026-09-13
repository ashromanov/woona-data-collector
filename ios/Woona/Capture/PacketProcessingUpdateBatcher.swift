import Foundation

@MainActor
final class PacketProcessingUpdateBatcher {
    private let dispatchIntervalNanos: UInt64
    private let dispatch: @MainActor (PacketProcessingUpdate) -> Void

    private var pendingUpdate: PendingPacketProcessingUpdate?
    private var scheduledTask: Task<Void, Never>?
    private var generation: UInt64 = 0

    init(
        dispatchIntervalMillis: UInt64 = 100,
        dispatch: @escaping @MainActor (PacketProcessingUpdate) -> Void
    ) {
        self.dispatchIntervalNanos = dispatchIntervalMillis * 1_000_000
        self.dispatch = dispatch
    }

    func submit(_ update: PacketProcessingUpdate) {
        if dispatchIntervalNanos == 0 {
            dispatch(update)
            return
        }

        var pending = pendingUpdate ?? PendingPacketProcessingUpdate()
        pending.merge(update)
        pendingUpdate = pending

        guard scheduledTask == nil else { return }

        let targetGeneration = generation
        let delay = dispatchIntervalNanos
        scheduledTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: delay)
            } catch {
                return
            }
            self?.flushScheduled(generation: targetGeneration)
        }
    }

    func flushNow() {
        generation += 1
        scheduledTask?.cancel()
        scheduledTask = nil

        guard let update = pendingUpdate?.toUpdate() else { return }
        pendingUpdate = nil
        dispatch(update)
    }

    func clearPending() {
        generation += 1
        scheduledTask?.cancel()
        scheduledTask = nil
        pendingUpdate = nil
    }

    private func flushScheduled(generation targetGeneration: UInt64) {
        guard targetGeneration == generation else { return }
        scheduledTask = nil
        guard let update = pendingUpdate?.toUpdate() else { return }
        pendingUpdate = nil
        dispatch(update)
    }
}

private struct PendingPacketProcessingUpdate {
    private var packetsReceived: UInt64 = 0
    private var packetsLost: UInt64 = 0
    private var packetsRejected: UInt64 = 0
    private var timerRegressionRejects: UInt64 = 0
    private var fragmentsReceived: UInt64 = 0
    private var rawBytesReceived: UInt64 = 0
    private var chartSamplesByStream: [ChartStreamKey: [ChartPoint]] = [:]
    private var lastPacketIssue: String?
    private var rejectionBreakdown: String?
    private var diagnosticEvents: [PacketDiagnosticEvent] = []

    mutating func merge(_ update: PacketProcessingUpdate) {
        packetsReceived = update.packetsReceived
        packetsLost = update.packetsLost
        packetsRejected = update.packetsRejected
        timerRegressionRejects = update.timerRegressionRejects
        fragmentsReceived = update.fragmentsReceived
        rawBytesReceived = update.rawBytesReceived

        for (key, points) in update.chartSamplesByStream where !points.isEmpty {
            chartSamplesByStream[key, default: []].append(contentsOf: points)
        }

        if let issue = update.lastPacketIssue {
            lastPacketIssue = issue
        }
        if let breakdown = update.rejectionBreakdown {
            rejectionBreakdown = breakdown
        }
        diagnosticEvents.append(contentsOf: update.diagnosticEvents)
    }

    func toUpdate() -> PacketProcessingUpdate {
        PacketProcessingUpdate(
            packetsReceived: packetsReceived,
            packetsLost: packetsLost,
            packetsRejected: packetsRejected,
            timerRegressionRejects: timerRegressionRejects,
            fragmentsReceived: fragmentsReceived,
            rawBytesReceived: rawBytesReceived,
            chartSamplesByStream: chartSamplesByStream,
            lastPacketIssue: lastPacketIssue,
            rejectionBreakdown: rejectionBreakdown,
            diagnosticEvents: diagnosticEvents
        )
    }
}
