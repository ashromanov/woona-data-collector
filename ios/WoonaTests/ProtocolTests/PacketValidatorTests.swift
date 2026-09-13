import XCTest
@testable import Woona

final class PacketValidatorTests: XCTestCase {
    private let validator = PacketValidator()

    func testValidateAcceptsPacketWithKnownTopLevelFields() {
        let result = validator.validate(
            makeTestPacket(
                counter: 7,
                timerMillis: 99,
                measurementCount: 1,
                blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]
            )
        )

        guard case let .accepted(packet) = result else {
            return XCTFail("Expected packet acceptance")
        }

        XCTAssertEqual(packet.counter, 7)
        XCTAssertEqual(packet.timerMillis, 99)
        XCTAssertEqual(packet.measurementCount, 1)
    }

    func testValidateAcceptsPacketWithSmallSensorBlockTrailer() {
        let packet = appendPacketTrailer(
            [0x10, 0x20],
            to: makeTestPacket(
                counter: 7,
                timerMillis: 99,
                measurementCount: 1,
                blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]
            )
        )

        guard case let .accepted(validatedPacket) = validator.validate(packet) else {
            return XCTFail("Expected packet acceptance")
        }

        XCTAssertEqual(validatedPacket.counter, 7)
    }

    func testValidateRejectsPacketShorterThanMinimumSize() {
        let result = validator.validate([0x33, 0x99, 0xAA, 0x55])

        XCTAssertEqual(result, .rejected(.invalidLength))
    }

    func testValidateRejectsPacketWithoutStartMarkerAtOffsetZero() {
        var packet = makeTestPacket(counter: 1, measurementCount: 1)
        packet[0] = 0x00

        XCTAssertEqual(validator.validate(packet), .rejected(.invalidStart))
    }

    func testValidateRejectsPacketWithLengthMismatch() {
        let packet = Array(
            makeTestPacket(
                counter: 1,
                timerMillis: 10,
                measurementCount: 1,
                blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]
            )[0..<17]
        )

        XCTAssertEqual(validator.validate(packet), .rejected(.invalidLength))
    }

    func testValidateRejectsPacketLongerThanMaximum() {
        var packet = makeTestPacket(
            counter: 1,
            timerMillis: 10,
            measurementCount: 1,
            blocks: [makeSensorBlock(sensorType: 2, channelSamples: [[10, 20]])]
        )
        let oversizedLength = PacketValidator.maximumPacketSize + 1
        packet.append(contentsOf: Array(repeating: 0, count: oversizedLength - packet.count))
        packet[4] = UInt8(oversizedLength & 0xFF)
        packet[5] = UInt8((oversizedLength >> 8) & 0xFF)

        XCTAssertEqual(validator.validate(packet), .rejected(.invalidLength))
    }

    func testValidateRejectsMeasurementCountsOutsideOneThroughFour() {
        XCTAssertEqual(
            validator.validate(makeTestPacket(counter: 7, timerMillis: 99, measurementCount: 0)),
            .rejected(.invalidMeasurementCount)
        )
        XCTAssertEqual(
            validator.validate(makeTestPacket(counter: 7, timerMillis: 99, measurementCount: 5)),
            .rejected(.invalidMeasurementCount)
        )
    }

    func testValidateRejectsIncompleteSensorBlockLayout() {
        let result = validator.validate(makeTestPacket(counter: 7, timerMillis: 99, measurementCount: 1))

        XCTAssertEqual(result, .rejected(.invalidSensorBlocks))
    }

    func testValidateRejectsZeroChannelSensorBlock() {
        let packet = makeTestPacket(
            counter: 7,
            timerMillis: 99,
            measurementCount: 1,
            payloadBytes: [0x02, 0x00, 0x01, 0x00, 0x00, 0x00]
        )

        XCTAssertEqual(validator.validate(packet), .rejected(.invalidSensorBlocks))
    }
}
