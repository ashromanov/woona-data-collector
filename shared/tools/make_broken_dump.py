#!/usr/bin/env python3

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


HEADER = bytes([0x33, 0x99, 0xAA, 0x55])
MIN_PACKET_SIZE = 16
MEASUREMENT_COUNT_OFFSET = 6
COUNTER_OFFSET = 7
TIMER_OFFSET = 11


@dataclass(frozen=True)
class PacketInfo:
    index: int
    offset: int
    length: int


def parse_packets(data: bytes) -> list[PacketInfo]:
    packets: list[PacketInfo] = []
    offset = 0
    index = 0

    while offset < len(data):
        if offset + MIN_PACKET_SIZE > len(data):
            raise ValueError(f"Truncated packet header at offset {offset}")
        if data[offset : offset + 4] != HEADER:
            raise ValueError(f"Invalid packet start marker at offset {offset}")

        length = data[offset + 4] | (data[offset + 5] << 8)
        if length < MIN_PACKET_SIZE:
            raise ValueError(f"Invalid packet length {length} at offset {offset}")
        if offset + length > len(data):
            raise ValueError(f"Truncated packet body at offset {offset}: expected {length} bytes")

        packets.append(PacketInfo(index=index, offset=offset, length=length))
        offset += length
        index += 1

    return packets


def read_u32_le(buffer: bytearray, offset: int) -> int:
    return int.from_bytes(buffer[offset : offset + 4], "little")


def write_u32_le(buffer: bytearray, offset: int, value: int) -> None:
    buffer[offset : offset + 4] = value.to_bytes(4, "little", signed=False)


def mutate_packet_measurement_count(
    data: bytearray,
    packet: PacketInfo,
    measurement_count: int,
) -> tuple[int, int]:
    field_offset = packet.offset + MEASUREMENT_COUNT_OFFSET
    original = data[field_offset]
    data[field_offset] = measurement_count & 0xFF
    return original, data[field_offset]


def mutate_packet_timer(
    data: bytearray,
    packet: PacketInfo,
    timer: int,
) -> tuple[int, int]:
    field_offset = packet.offset + TIMER_OFFSET
    original = read_u32_le(data, field_offset)
    write_u32_le(data, field_offset, timer)
    return original, read_u32_le(data, field_offset)


def mutate_packet_counter(
    data: bytearray,
    packet: PacketInfo,
    counter: int,
) -> tuple[int, int]:
    field_offset = packet.offset + COUNTER_OFFSET
    original = read_u32_le(data, field_offset)
    write_u32_le(data, field_offset, counter)
    return original, read_u32_le(data, field_offset)


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a replayable BLE dump with targeted bad packets.")
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument(
        "--bad-packets",
        type=int,
        default=4,
        help="How many packets to mutate while keeping the dump structurally replayable.",
    )
    parser.add_argument(
        "--front-window",
        type=int,
        default=0,
        help="If > 0, concentrate mutations within the first N packets so issues appear early in replay.",
    )
    args = parser.parse_args()

    data = bytearray(args.source.read_bytes())
    packets = parse_packets(data)
    if len(packets) < 20:
        raise ValueError("Dump is too small to safely spread out mutations")
    if args.bad_packets < 1:
        raise ValueError("--bad-packets must be at least 1")

    mutation_window = len(packets)
    if args.front_window > 0:
        mutation_window = min(max(args.front_window, 8), len(packets))

    first_mutation_index = 4 if mutation_window > 8 else 0
    available_span = mutation_window - first_mutation_index
    if available_span <= 0:
        raise ValueError("front window is too small to place mutations safely")

    target_mutations = min(args.bad_packets, available_span)
    selected_indexes: list[int] = []
    for step in range(target_mutations):
        if target_mutations == 1:
            relative_index = available_span // 2
        else:
            relative_index = round(step * (available_span - 1) / (target_mutations - 1))
        packet_index = first_mutation_index + relative_index
        if not selected_indexes or packet_index != selected_indexes[-1]:
            selected_indexes.append(packet_index)

    if len(selected_indexes) < target_mutations:
        selected_set = set(selected_indexes)
        for packet_index in range(first_mutation_index, mutation_window):
            if packet_index not in selected_set:
                selected_indexes.append(packet_index)
                selected_set.add(packet_index)
            if len(selected_indexes) == target_mutations:
                break

    mutations: list[str] = []
    invalid_zero_count = 0
    invalid_five_count = 0
    timer_regression_count = 0
    counter_jump_count = 0

    for mutation_number, packet_index in enumerate(selected_indexes):
        packet = packets[packet_index]
        mutation_kind = mutation_number % 4

        if mutation_kind == 0:
            old, new = mutate_packet_measurement_count(data, packet, measurement_count=0)
            invalid_zero_count += 1
            mutations.append(
                f"- Packet {packet.index} at byte {packet.offset}: measurementCount {old} -> {new} "
                f"(invalid measurement count rejection)."
            )
        elif mutation_kind == 1:
            old, new = mutate_packet_measurement_count(data, packet, measurement_count=5)
            invalid_five_count += 1
            mutations.append(
                f"- Packet {packet.index} at byte {packet.offset}: measurementCount {old} -> {new} "
                f"(invalid measurement count rejection)."
            )
        elif mutation_kind == 2:
            previous_packet = packets[packet.index - 1]
            previous_timer = read_u32_le(data, previous_packet.offset + TIMER_OFFSET)
            old, new = mutate_packet_timer(
                data,
                packet,
                timer=max(0, previous_timer - 50_000 - (mutation_number * 25)),
            )
            timer_regression_count += 1
            mutations.append(
                f"- Packet {packet.index} at byte {packet.offset}: timerMillis {old} -> {new} "
                f"(timer regression versus packet {previous_packet.index})."
            )
        else:
            old_counter = read_u32_le(data, packet.offset + COUNTER_OFFSET)
            old, new = mutate_packet_counter(
                data,
                packet,
                counter=old_counter + 25 + (mutation_number % 7),
            )
            counter_jump_count += 1
            mutations.append(
                f"- Packet {packet.index} at byte {packet.offset}: counter {old} -> {new} "
                f"(forward jump to create visible packet-loss stats)."
            )

    args.output.write_bytes(data)

    notes = "\n".join(
        [
            f"# Broken Dump Notes",
            "",
            f"Source: `{args.source}`",
            f"Output: `{args.output}`",
            f"Packets: {len(packets)}",
            f"Bytes: {len(data)}",
            f"Mutated packets: {len(selected_indexes)}",
            f"Mutation window: first {mutation_window} packets" if args.front_window > 0 else "Mutation window: full dump",
            (
                "Summary: "
                f"{invalid_zero_count} measurementCount=0, "
                f"{invalid_five_count} measurementCount=5, "
                f"{timer_regression_count} timer regressions, "
                f"{counter_jump_count} counter jumps"
            ),
            "",
            "Applied mutations:",
            *mutations,
            "",
            "Design goal:",
            "- Keep the file structurally valid for `RecordedPacketFileParser` so upload/replay starts.",
            "- Trigger diagnostics inside the replay/capture pipeline instead of failing the file immediately.",
        ]
    )
    args.notes.write_text(notes + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
