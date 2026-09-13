import XCTest
@testable import Woona

final class SensorBlockParserTests: XCTestCase {
    private let parser = SensorBlockParser()

    func testExtractChannelSamplesReturnsRequestedChannelForMatchingSensorType() {
        let packet = makeTestPacket(
            counter: 1,
            measurementCount: 1,
            blocks: [
                makeSensorBlock(
                    sensorType: 2,
                    channelSamples: [
                        [10, 20],
                        [30, 40],
                        [50, 60]
                    ]
                )
            ]
        )

        XCTAssertEqual(parser.extractChannelSamples(packetBytes: packet, sensorType: 2, channel: 2), [30, 40])
    }

    func testExtractChannelSamplesIgnoresNonMatchingSensorBlocks() {
        let packet = makeTestPacket(
            counter: 1,
            measurementCount: 2,
            blocks: [
                makeSensorBlock(sensorType: 1, channelSamples: [[1, 2]]),
                makeSensorBlock(sensorType: 2, channelSamples: [[3, 4]])
            ]
        )

        XCTAssertEqual(parser.extractChannelSamples(packetBytes: packet, sensorType: 2, channel: 1), [3, 4])
    }

    func testExtractChannelSamplesReturnsEmptyListForOutOfRangeChannel() {
        let packet = makeTestPacket(
            counter: 1,
            measurementCount: 1,
            blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]
        )

        XCTAssertEqual(parser.extractChannelSamples(packetBytes: packet, sensorType: 2, channel: 2), [])
    }

    func testParseResultReportsIncompleteBlockHeader() {
        let packet = makeTestPacket(
            counter: 1,
            measurementCount: 1,
            payloadBytes: [0x02, 0x01]
        )

        let result = parser.parseResult(packet)

        XCTAssertEqual(result.parsedBlockCount, 0)
        XCTAssertEqual(result.consumedBytes, 16)
        XCTAssertFalse(result.isComplete)
    }
}
