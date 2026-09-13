import Foundation

struct ChartPoint: Equatable, Sendable {
    let timeMillis: UInt64
    let value: Float
    let startsNewSegment: Bool

    init(timeMillis: UInt64, value: Float, startsNewSegment: Bool = false) {
        self.timeMillis = timeMillis
        self.value = value
        self.startsNewSegment = startsNewSegment
    }
}

struct ChartStreamKey: Hashable, Sendable {
    let sensorType: Int
    let channel: Int
}

enum PacketDiagnosticType: String, Equatable, Sendable {
    case info = "INFO"
    case accepted = "ACCEPTED"
    case gap = "GAP"
    case rejected = "REJECTED"
}

struct PacketDiagnosticEvent: Equatable, Sendable {
    let id: UInt64
    let type: PacketDiagnosticType
    let message: String
}

struct PacketProcessingUpdate: Equatable, Sendable {
    let packetsReceived: UInt64
    let packetsLost: UInt64
    let packetsRejected: UInt64
    let timerRegressionRejects: UInt64
    let fragmentsReceived: UInt64
    let rawBytesReceived: UInt64
    let chartSamplesByStream: [ChartStreamKey: [ChartPoint]]
    let lastPacketIssue: String?
    let rejectionBreakdown: String?
    let diagnosticEvents: [PacketDiagnosticEvent]

    init(
        packetsReceived: UInt64,
        packetsLost: UInt64,
        packetsRejected: UInt64,
        timerRegressionRejects: UInt64,
        fragmentsReceived: UInt64 = 0,
        rawBytesReceived: UInt64 = 0,
        chartSamplesByStream: [ChartStreamKey: [ChartPoint]] = [:],
        lastPacketIssue: String? = nil,
        rejectionBreakdown: String? = nil,
        diagnosticEvents: [PacketDiagnosticEvent] = []
    ) {
        self.packetsReceived = packetsReceived
        self.packetsLost = packetsLost
        self.packetsRejected = packetsRejected
        self.timerRegressionRejects = timerRegressionRejects
        self.fragmentsReceived = fragmentsReceived
        self.rawBytesReceived = rawBytesReceived
        self.chartSamplesByStream = chartSamplesByStream
        self.lastPacketIssue = lastPacketIssue
        self.rejectionBreakdown = rejectionBreakdown
        self.diagnosticEvents = diagnosticEvents
    }
}

enum PacketSubmitResult: Equatable, Sendable {
    case accepted
    case rejected
    case overflow
}
