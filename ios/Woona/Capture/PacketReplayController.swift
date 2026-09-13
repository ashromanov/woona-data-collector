import Foundation

enum PacketReplayMessage {
    static let emptyFile = "Replay file is empty"
    static let invalidFile = "Replay file is not a valid replay file"
    static let interrupted = "Replay interrupted"
    static let failed = "Replay failed"
}

enum PacketReplayRuntimeError: Error, Equatable, LocalizedError {
    case fragmentRejected
    case queueOverflow

    var errorDescription: String? {
        switch self {
        case .fragmentRejected:
            "Replay fragment was rejected by capture pipeline"
        case .queueOverflow:
            "Replay overflowed the capture queue"
        }
    }
}

actor PacketReplayController {
    private let submitFragment: @Sendable ([UInt8]) async -> PacketSubmitResult
    private let onReplayStarted: @Sendable () async -> Void
    private let onReplayCompleted: @Sendable () async -> Void
    private let onReplayStopped: @Sendable () async -> Void
    private let onError: @Sendable (String, Error?) async -> Void
    private let packetFileParser: RecordedPacketFileParser
    private let rawFragmentFileParser: RawFragmentFileParser
    private let chunkSize: Int
    private let chunkDelayMillis: UInt64
    private let packetIntervalMillis: UInt64
    private let maximumRawFragmentDelayMillis: UInt64

    private var replayTask: Task<Void, Never>?

    init(
        submitFragment: @escaping @Sendable ([UInt8]) async -> PacketSubmitResult,
        onReplayStarted: @escaping @Sendable () async -> Void,
        onReplayCompleted: @escaping @Sendable () async -> Void,
        onReplayStopped: @escaping @Sendable () async -> Void,
        onError: @escaping @Sendable (String, Error?) async -> Void,
        packetFileParser: RecordedPacketFileParser = RecordedPacketFileParser(),
        rawFragmentFileParser: RawFragmentFileParser = RawFragmentFileParser(),
        chunkSize: Int = 247,
        chunkDelayMillis: UInt64 = 15,
        packetIntervalMillis: UInt64 = 1_000,
        maximumRawFragmentDelayMillis: UInt64? = nil
    ) {
        self.submitFragment = submitFragment
        self.onReplayStarted = onReplayStarted
        self.onReplayCompleted = onReplayCompleted
        self.onReplayStopped = onReplayStopped
        self.onError = onError
        self.packetFileParser = packetFileParser
        self.rawFragmentFileParser = rawFragmentFileParser
        self.chunkSize = chunkSize
        self.chunkDelayMillis = chunkDelayMillis
        self.packetIntervalMillis = packetIntervalMillis
        self.maximumRawFragmentDelayMillis = maximumRawFragmentDelayMillis ?? packetIntervalMillis
    }

    func startReplay(_ fileBytes: [UInt8]) async {
        guard !fileBytes.isEmpty else {
            await onError(PacketReplayMessage.emptyFile, nil)
            await onReplayStopped()
            return
        }

        await stop()

        let replaySource: ReplaySource
        do {
            replaySource = try parseReplaySource(fileBytes)
        } catch {
            await onError(PacketReplayMessage.invalidFile, error)
            await onReplayStopped()
            return
        }
        guard !replaySource.isEmpty else {
            await onError(PacketReplayMessage.emptyFile, nil)
            await onReplayStopped()
            return
        }

        let submitFragment = submitFragment
        let onReplayStarted = onReplayStarted
        let onReplayCompleted = onReplayCompleted
        let onReplayStopped = onReplayStopped
        let onError = onError
        let chunkSize = chunkSize
        let chunkDelayMillis = chunkDelayMillis
        let packetIntervalMillis = packetIntervalMillis
        let maximumRawFragmentDelayMillis = maximumRawFragmentDelayMillis

        replayTask = Task {
            await onReplayStarted()

            do {
                switch replaySource {
                case .packetDump(let packets):
                    try await Self.replayPackets(
                        packets,
                        chunkSize: chunkSize,
                        chunkDelayMillis: chunkDelayMillis,
                        packetIntervalMillis: packetIntervalMillis,
                        submitFragment: submitFragment
                    )
                case .rawFragments(let fragments):
                    try await Self.replayRawFragments(
                        fragments,
                        maximumFragmentDelayMillis: maximumRawFragmentDelayMillis,
                        submitFragment: submitFragment
                    )
                }

                if Task.isCancelled {
                    await onReplayStopped()
                } else {
                    await onReplayCompleted()
                }
            } catch is CancellationError {
                await onReplayStopped()
            } catch {
                await onError(PacketReplayMessage.failed, error)
                await onReplayStopped()
            }
        }
    }

    private func parseReplaySource(_ fileBytes: [UInt8]) throws -> ReplaySource {
        if rawFragmentFileParser.hasMagicHeader(fileBytes) {
            return .rawFragments(try rawFragmentFileParser.splitIntoFragments(fileBytes))
        }

        return .packetDump(try packetFileParser.splitIntoPackets(fileBytes))
    }

    func stop() async {
        guard let task = replayTask else { return }
        replayTask = nil
        task.cancel()
        await task.value
    }

    func close() async {
        await stop()
    }

    private static func replayPackets(
        _ packets: [[UInt8]],
        chunkSize: Int,
        chunkDelayMillis: UInt64,
        packetIntervalMillis: UInt64,
        submitFragment: @Sendable ([UInt8]) async -> PacketSubmitResult
    ) async throws {
        let resolvedChunkSize = max(1, chunkSize)

        for packet in packets {
            try Task.checkCancellation()
            let packetStartNanos = DispatchTime.now().uptimeNanoseconds

            var offset = 0
            while offset < packet.count {
                try Task.checkCancellation()
                let endOffset = min(offset + resolvedChunkSize, packet.count)
                let result = await submitFragment(Array(packet[offset..<endOffset]))
                try Task.checkCancellation()

                switch result {
                case .accepted:
                    break
                case .rejected:
                    throw PacketReplayRuntimeError.fragmentRejected
                case .overflow:
                    throw PacketReplayRuntimeError.queueOverflow
                }

                offset = endOffset
                if offset < packet.count {
                    try await sleep(milliseconds: chunkDelayMillis)
                }
            }

            let elapsedMillis = (DispatchTime.now().uptimeNanoseconds - packetStartNanos) / 1_000_000
            if packetIntervalMillis > elapsedMillis {
                try await sleep(milliseconds: packetIntervalMillis - elapsedMillis)
            }
        }
    }

    private static func replayRawFragments(
        _ fragments: [RawFragmentRecord],
        maximumFragmentDelayMillis: UInt64,
        submitFragment: @Sendable ([UInt8]) async -> PacketSubmitResult
    ) async throws {
        for (index, fragment) in fragments.enumerated() {
            try Task.checkCancellation()
            let result = await submitFragment(fragment.bytes)
            try Task.checkCancellation()

            switch result {
            case .accepted:
                break
            case .rejected:
                throw PacketReplayRuntimeError.fragmentRejected
            case .overflow:
                throw PacketReplayRuntimeError.queueOverflow
            }

            if index < fragments.count - 1 {
                let delayMillis = rawReplayDelayMillis(
                    from: fragment,
                    to: fragments[index + 1],
                    maximumDelayMillis: maximumFragmentDelayMillis
                )
                try await sleep(milliseconds: delayMillis)
            }
        }
    }

    private static func rawReplayDelayMillis(
        from current: RawFragmentRecord,
        to next: RawFragmentRecord,
        maximumDelayMillis: UInt64
    ) -> UInt64 {
        guard maximumDelayMillis > 0 else { return 0 }
        guard next.receivedAtMillis > current.receivedAtMillis else { return 0 }

        return min(UInt64(next.receivedAtMillis - current.receivedAtMillis), maximumDelayMillis)
    }

    private static func sleep(milliseconds: UInt64) async throws {
        guard milliseconds > 0 else { return }
        try await Task.sleep(nanoseconds: milliseconds * 1_000_000)
    }

    private enum ReplaySource {
        case packetDump([[UInt8]])
        case rawFragments([RawFragmentRecord])

        var isEmpty: Bool {
            switch self {
            case .packetDump(let packets):
                packets.isEmpty
            case .rawFragments(let fragments):
                fragments.isEmpty
            }
        }
    }
}
