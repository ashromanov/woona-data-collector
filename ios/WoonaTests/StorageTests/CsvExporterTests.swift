import XCTest
@testable import Woona

final class CsvExporterTests: XCTestCase {
    private let exporter = CsvExporter(timestampFormatter: { millis in "time-\(millis)" })

    func testExportWritesTimeColumnAndOneColumnPerObservedChannel() throws {
        let directory = try makeTemporaryDirectory()
        let packetFile = directory.appendingPathComponent("capture.bin")
        let targetFile = directory.appendingPathComponent("capture.csv")
        let first = makeTestPacket(
            counter: 9,
            timerMillis: 50,
            measurementCount: 1,
            blocks: [
                makeSensorBlock(sensorType: 4, channelSamples: [[50, 60]])
            ]
        )
        let second = makeTestPacket(
            counter: 10,
            timerMillis: 60,
            measurementCount: 1,
            blocks: [
                makeSensorBlock(sensorType: 2, channelSamples: [[10, 20], [30, 40]])
            ]
        )
        try Data(first + second).write(to: packetFile)

        try exporter.export(packetFile: packetFile, sessionStartMillis: 1_000, targetFile: targetFile)

        XCTAssertEqual(
            try readLines(targetFile),
            [
                "estimated_time,device_timer_millis,sample_timer_millis,axl_sensor_2_ch_1,axl_sensor_2_ch_2,mic_sensor_4_ch_1",
                "time-1000,50,50,,,50",
                "time-1001,50,51,,,60",
                "time-1010,60,60,10,30,",
                "time-1011,60,61,20,40,"
            ]
        )
    }

    func testExportKeepsSampleTimerMonotonicAcrossPackets() throws {
        let directory = try makeTemporaryDirectory()
        let packetFile = directory.appendingPathComponent("capture-overlap.bin")
        let targetFile = directory.appendingPathComponent("capture-overlap.csv")
        let first = makeTestPacket(
            counter: 9,
            timerMillis: 50,
            measurementCount: 1,
            blocks: [
                makeSensorBlock(sensorType: 2, channelSamples: [[10, 20, 30, 40]])
            ]
        )
        let second = makeTestPacket(
            counter: 10,
            timerMillis: 52,
            measurementCount: 1,
            blocks: [
                makeSensorBlock(sensorType: 2, channelSamples: [[50, 60]])
            ]
        )
        try Data(first + second).write(to: packetFile)

        try exporter.export(packetFile: packetFile, sessionStartMillis: 1_000, targetFile: targetFile)

        XCTAssertEqual(
            try readLines(targetFile),
            [
                "estimated_time,device_timer_millis,sample_timer_millis,axl_sensor_2_ch_1",
                "time-1000,50,50,10",
                "time-1001,50,51,20",
                "time-1002,50,52,30",
                "time-1003,50,53,40",
                "time-1004,52,54,50",
                "time-1005,52,55,60"
            ]
        )
    }

    func testExportUsesUnknownSensorPrefixAndSortsColumnsBySensorThenChannel() throws {
        let directory = try makeTemporaryDirectory()
        let packetFile = directory.appendingPathComponent("capture-unknown.bin")
        let targetFile = directory.appendingPathComponent("capture-unknown.csv")
        let packet = makeTestPacket(
            counter: 1,
            timerMillis: 10,
            measurementCount: 2,
            blocks: [
                makeSensorBlock(sensorType: 99, channelSamples: [[1]]),
                makeSensorBlock(sensorType: 1, channelSamples: [[2], [3]])
            ]
        )
        try Data(packet).write(to: packetFile)

        try exporter.export(packetFile: packetFile, sessionStartMillis: 1_000, targetFile: targetFile)

        XCTAssertEqual(
            try readLines(targetFile).first,
            "estimated_time,device_timer_millis,sample_timer_millis,t_sensor_1_ch_1,t_sensor_1_ch_2,unknown_sensor_99_ch_1"
        )
    }

    func testExportRejectsInvalidCompiledDump() throws {
        let directory = try makeTemporaryDirectory()
        let packetFile = directory.appendingPathComponent("invalid.bin")
        let targetFile = directory.appendingPathComponent("invalid.csv")
        let invalidPacket = makeTestPacket(counter: 1, timerMillis: 10, measurementCount: 0)
        try Data(invalidPacket).write(to: packetFile)

        XCTAssertThrowsError(
            try exporter.export(packetFile: packetFile, sessionStartMillis: 1_000, targetFile: targetFile)
        ) { error in
            XCTAssertEqual(error as? CsvExporterError, .invalidPacket(.invalidMeasurementCount))
        }
    }

    func testExportOverwritesExistingTargetFile() throws {
        let directory = try makeTemporaryDirectory()
        let packetFile = directory.appendingPathComponent("capture.bin")
        let targetFile = directory.appendingPathComponent("capture.csv")
        let packet = makeTestPacket(
            counter: 1,
            timerMillis: 10,
            measurementCount: 1,
            blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[42]])]
        )
        try Data(packet).write(to: packetFile)
        try Data("stale-content-that-must-disappear\n".utf8).write(to: targetFile)

        try exporter.export(packetFile: packetFile, sessionStartMillis: 1_000, targetFile: targetFile)

        XCTAssertEqual(
            try readLines(targetFile),
            [
                "estimated_time,device_timer_millis,sample_timer_millis,axl_sensor_2_ch_1",
                "time-1000,10,10,42"
            ]
        )
    }

    private func readLines(_ file: URL) throws -> [String] {
        Array(String(decoding: try Data(contentsOf: file), as: UTF8.self)
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
            .dropLast())
    }
}
