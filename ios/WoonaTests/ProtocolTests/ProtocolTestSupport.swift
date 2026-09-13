import Foundation

func makeTestPacket(
    counter: UInt32,
    timerMillis: UInt32? = nil,
    payloadSize: Int = 0,
    measurementCount: UInt8 = 0,
    blocks: [[UInt8]] = [],
    payloadBytes: [UInt8]? = nil
) -> [UInt8] {
    let blockPayload = blocks.flatMap { $0 }
    let resolvedPayload: [UInt8]
    if let payloadBytes {
        resolvedPayload = payloadBytes
    } else if !blocks.isEmpty {
        resolvedPayload = blockPayload
    } else {
        resolvedPayload = (0..<payloadSize).map { UInt8(truncatingIfNeeded: $0 + 16) }
    }

    let length = 16 + resolvedPayload.count
    let timer = timerMillis ?? counter
    var packet = Array(repeating: UInt8(0), count: length)
    packet[0] = 0x33
    packet[1] = 0x99
    packet[2] = 0xAA
    packet[3] = 0x55
    packet[4] = UInt8(length & 0xFF)
    packet[5] = UInt8((length >> 8) & 0xFF)
    packet[6] = measurementCount
    writeLittleEndian(counter, to: &packet, offset: 7)
    writeLittleEndian(timer, to: &packet, offset: 11)

    if !resolvedPayload.isEmpty {
        packet.replaceSubrange(16..<length, with: resolvedPayload)
    }

    return packet
}

func makeSensorBlock(sensorType: UInt8, channelSamples: [[Int16]]) -> [UInt8] {
    let channelCount = channelSamples.count
    let samplesPerChannel = channelSamples.first?.count ?? 0
    let payloadSize = channelCount * samplesPerChannel * 2
    var block = Array(repeating: UInt8(0), count: 6 + payloadSize)
    block[0] = sensorType
    block[1] = UInt8(channelCount)
    block[2] = UInt8(samplesPerChannel & 0xFF)
    block[3] = UInt8((samplesPerChannel >> 8) & 0xFF)

    var offset = 6
    for channel in channelSamples {
        for sample in channel {
            let raw = UInt16(bitPattern: sample)
            block[offset] = UInt8(raw & 0xFF)
            block[offset + 1] = UInt8((raw >> 8) & 0xFF)
            offset += 2
        }
    }

    return block
}

func appendPacketTrailer(_ trailer: [UInt8], to packet: [UInt8]) -> [UInt8] {
    var packetWithTrailer = packet + trailer
    let length = packetWithTrailer.count
    packetWithTrailer[4] = UInt8(length & 0xFF)
    packetWithTrailer[5] = UInt8((length >> 8) & 0xFF)
    return packetWithTrailer
}

private func writeLittleEndian(_ value: UInt32, to bytes: inout [UInt8], offset: Int) {
    bytes[offset] = UInt8(value & 0xFF)
    bytes[offset + 1] = UInt8((value >> 8) & 0xFF)
    bytes[offset + 2] = UInt8((value >> 16) & 0xFF)
    bytes[offset + 3] = UInt8((value >> 24) & 0xFF)
}
