package com.example.myapplication.storage

import com.example.myapplication.protocol.PacketValidationResult
import com.example.myapplication.protocol.PacketValidator
import com.example.myapplication.protocol.RecordedPacketFileParser
import com.example.myapplication.protocol.SensorBlock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.math.BigDecimal

class BleSessionCsvExporter(
    private val packetFileParser: RecordedPacketFileParser = RecordedPacketFileParser(),
    private val packetValidator: PacketValidator = PacketValidator(),
) {
    fun export(
        packetFile: File,
        sessionStartMillis: Long,
        targetFile: File,
    ): File {
        val rows = mutableListOf<CsvRow>()
        val columns = linkedSetOf<CsvColumnKey>()
        val packetBytes = packetFile.readBytes()
        val packets = packetFileParser.splitIntoPackets(packetBytes)
        var previousDeviceTimeMillis: Long? = null
        var firstSampleDeviceTimeMillis: Long? = null

        packets.forEachIndexed { packetIndex, bytes ->
            when (val validation = packetValidator.validate(bytes)) {
                is PacketValidationResult.Accepted -> {
                    buildRows(
                        sensorBlocks = validation.packet.sensorBlocks,
                        packetDeviceTimeMillis = validation.packet.timerMillis,
                        previousDeviceTimeMillis = previousDeviceTimeMillis,
                    ).forEach { row ->
                        columns += row.values.keys
                        rows += row
                        if (firstSampleDeviceTimeMillis == null) {
                            firstSampleDeviceTimeMillis = row.sampleDeviceTimeMillis
                        }
                    }
                    previousDeviceTimeMillis = validation.packet.timerMillis
                }

                is PacketValidationResult.Rejected -> {
                    throw IllegalArgumentException(
                        "Packet dump contains an invalid packet at index $packetIndex: ${validation.reason}",
                    )
                }
            }
        }

        val orderedColumns = columns.sortedWith(
            compareBy<CsvColumnKey> { it.sensorType }
                .thenBy { it.channel },
        )
        targetFile.parentFile?.mkdirs()
        BufferedWriter(FileWriter(targetFile, false), BUFFER_SIZE_BYTES).use { writer ->
            writeHeader(writer, orderedColumns)
            rows.forEachIndexed { rowIndex, row ->
                writer.append((sessionStartMillis + rowIndex).toString())
                writer.append(CSV_SEPARATOR)
                writer.append(row.packetDeviceTimeMillis.toString())
                writer.append(CSV_SEPARATOR)
                writer.append(row.sampleDeviceTimeMillis.toString())
                writer.append(CSV_SEPARATOR)
                writer.append(
                    (row.sampleDeviceTimeMillis - (firstSampleDeviceTimeMillis ?: row.sampleDeviceTimeMillis)).toString(),
                )
                orderedColumns.forEach { column ->
                    writer.append(CSV_SEPARATOR)
                    row.values[column]?.let { value ->
                        writer.append(formatNumericValue(value))
                    }
                }
                writer.append('\n')
            }
        }
        return targetFile
    }

    private fun writeHeader(
        writer: BufferedWriter,
        columns: List<CsvColumnKey>,
    ) {
        writer.append(COLUMN_TIME_MILLIS)
        writer.append(CSV_SEPARATOR)
        writer.append(COLUMN_PACKET_DEVICE_TIME_MILLIS)
        writer.append(CSV_SEPARATOR)
        writer.append(COLUMN_SAMPLE_DEVICE_TIME_MILLIS)
        writer.append(CSV_SEPARATOR)
        writer.append(COLUMN_SAMPLE_DEVICE_TIME_NORMALIZED_MILLIS)
        columns.forEach { column ->
            writer.append(CSV_SEPARATOR)
            writer.append(column.headerName())
        }
        writer.append('\n')
    }

    private fun buildRows(
        sensorBlocks: List<SensorBlock>,
        packetDeviceTimeMillis: Long,
        previousDeviceTimeMillis: Long?,
    ): List<CsvRow> {
        val samplesByStream = linkedMapOf<CsvColumnKey, MutableList<Float>>()
        sensorBlocks.forEach { sensorBlock ->
            sensorBlock.channelSamples.forEachIndexed { channelIndex, samples ->
                if (samples.isEmpty()) return@forEachIndexed

                val column = CsvColumnKey(
                    sensorType = sensorBlock.sensorType,
                    channel = channelIndex + 1,
                )
                samplesByStream.getOrPut(column) { mutableListOf() }.addAll(samples)
            }
        }

        val rowCount = samplesByStream.values.maxOfOrNull(List<Float>::size) ?: 0
        val sampleDeviceTimes = buildSampleDeviceTimes(
            rowCount = rowCount,
            currentDeviceTimeMillis = packetDeviceTimeMillis,
            previousDeviceTimeMillis = previousDeviceTimeMillis,
        )
        return List(rowCount) { sampleIndex ->
            CsvRow(
                packetDeviceTimeMillis = packetDeviceTimeMillis,
                sampleDeviceTimeMillis = sampleDeviceTimes[sampleIndex],
                values = buildMap {
                    samplesByStream.forEach { (column, values) ->
                        values.getOrNull(sampleIndex)?.let { put(column, it) }
                    }
                },
            )
        }
    }

    private fun buildSampleDeviceTimes(
        rowCount: Int,
        currentDeviceTimeMillis: Long,
        previousDeviceTimeMillis: Long?,
    ): List<Long> {
        if (rowCount == 0) return emptyList()
        val intervalMillis = when {
            previousDeviceTimeMillis == null -> null
            currentDeviceTimeMillis <= previousDeviceTimeMillis -> null
            else -> currentDeviceTimeMillis - previousDeviceTimeMillis
        }

        return List(rowCount) { index ->
            if (rowCount == 1) {
                currentDeviceTimeMillis
            } else if (intervalMillis == null) {
                currentDeviceTimeMillis
            } else {
                requireNotNull(previousDeviceTimeMillis) + (intervalMillis * (index + 1) / rowCount)
            }
        }
    }

    private fun formatNumericValue(value: Float): String {
        return BigDecimal.valueOf(value.toDouble())
            .stripTrailingZeros()
            .toPlainString()
    }

    private data class CsvColumnKey(
        val sensorType: Int,
        val channel: Int,
    ) {
        fun headerName(): String = "${sensorNamePrefix(sensorType)}_sensor_${sensorType}_ch_$channel"
    }

    private data class CsvRow(
        val packetDeviceTimeMillis: Long,
        val sampleDeviceTimeMillis: Long,
        val values: Map<CsvColumnKey, Float>,
    )

    private companion object {
        const val BUFFER_SIZE_BYTES = 65_536
        const val COLUMN_TIME_MILLIS = "time_millis"
        const val COLUMN_PACKET_DEVICE_TIME_MILLIS = "packet_device_time_millis"
        const val COLUMN_SAMPLE_DEVICE_TIME_MILLIS = "sample_device_time_millis"
        const val COLUMN_SAMPLE_DEVICE_TIME_NORMALIZED_MILLIS = "sample_device_time_normalized_millis"
        const val CSV_SEPARATOR = ','

        fun sensorNamePrefix(sensorType: Int): String {
            return when (sensorType) {
                1 -> "t"
                2 -> "axl"
                3 -> "gir"
                4 -> "mic"
                else -> "unknown"
            }
        }
    }
}
