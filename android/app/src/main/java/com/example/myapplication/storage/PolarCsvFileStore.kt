package com.example.myapplication.storage

import com.example.myapplication.protocol.PolarAccelerationFrame
import com.example.myapplication.protocol.PolarEcgFrame
import com.example.myapplication.protocol.PolarHeartRateFrame
import java.io.BufferedWriter
import java.io.File
import java.time.Instant

class PolarCsvFileStore : AutoCloseable {
    private var directory: File? = null
    private var hrWriter: BufferedWriter? = null
    private var ecgWriter: BufferedWriter? = null
    private var accWriter: BufferedWriter? = null

    @Synchronized
    fun useSessionDirectory(directory: File) {
        close()
        this.directory = directory
    }

    @Synchronized
    fun start(): Boolean {
        val target = directory ?: return false
        if (hrWriter != null) return true
        target.mkdirs()
        hrWriter = File(target, POLAR_HR_FILE).bufferedWriter().also {
            it.appendLine("host_utc,heart_rate_bpm,rr_millis")
        }
        ecgWriter = File(target, POLAR_ECG_FILE).bufferedWriter().also {
            it.appendLine("host_utc,polar_timestamp_ns,sample_index,ecg_mv")
        }
        accWriter = File(target, POLAR_ACC_FILE).bufferedWriter().also {
            it.appendLine("host_utc,polar_timestamp_ns,sample_index,x_mg,y_mg,z_mg")
        }
        return true
    }

    @Synchronized
    fun append(frame: PolarHeartRateFrame, nowMillis: Long) {
        val timestamp = Instant.ofEpochMilli(nowMillis)
        if (frame.rrMillis.isEmpty()) {
            hrWriter?.appendLine("$timestamp,${frame.beatsPerMinute},")
        } else {
            frame.rrMillis.forEach { rr -> hrWriter?.appendLine("$timestamp,${frame.beatsPerMinute},$rr") }
        }
    }

    @Synchronized
    fun append(frame: PolarEcgFrame, nowMillis: Long) {
        val timestamp = Instant.ofEpochMilli(nowMillis)
        frame.samplesMicrovolts.forEachIndexed { index, sample ->
            ecgWriter?.appendLine("$timestamp,${frame.timestampNanos},$index,${sample / 1_000.0}")
        }
    }

    @Synchronized
    fun append(frame: PolarAccelerationFrame, nowMillis: Long) {
        val timestamp = Instant.ofEpochMilli(nowMillis)
        frame.samples.forEachIndexed { index, sample ->
            accWriter?.appendLine(
                "$timestamp,${frame.timestampNanos},$index,${sample.xMilliG},${sample.yMilliG},${sample.zMilliG}",
            )
        }
    }

    @Synchronized
    fun flush() {
        listOf(hrWriter, ecgWriter, accWriter).forEach { it?.flush() }
    }

    @Synchronized
    override fun close() {
        listOf(hrWriter, ecgWriter, accWriter).forEach { writer ->
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
        }
        hrWriter = null
        ecgWriter = null
        accWriter = null
    }

    companion object {
        const val POLAR_HR_FILE = "polar_hr.csv"
        const val POLAR_ECG_FILE = "polar_ecg.csv"
        const val POLAR_ACC_FILE = "polar_acc.csv"
        val FILE_NAMES = listOf(POLAR_HR_FILE, POLAR_ECG_FILE, POLAR_ACC_FILE)
    }
}
