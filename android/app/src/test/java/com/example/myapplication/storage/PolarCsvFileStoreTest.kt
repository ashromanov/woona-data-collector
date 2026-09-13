package com.example.myapplication.storage

import com.example.myapplication.protocol.PolarAccelerationFrame
import com.example.myapplication.protocol.PolarAccelerationSample
import com.example.myapplication.protocol.PolarEcgFrame
import com.example.myapplication.protocol.PolarHeartRateFrame
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarCsvFileStoreTest {
    @Test
    fun writesAllPolarStreamsIntoSessionDirectory() {
        val directory = File(System.getProperty("java.io.tmpdir"), "polar-store-${System.nanoTime()}")
        val store = PolarCsvFileStore()

        try {
            store.useSessionDirectory(directory)
            assertTrue(store.start())
            store.append(PolarHeartRateFrame(72, listOf(800)), 1_000L)
            store.append(PolarEcgFrame(2UL, listOf(1_000, -1_000)), 1_000L)
            store.append(PolarAccelerationFrame(3UL, listOf(PolarAccelerationSample(1, -2, 3))), 1_000L)
            store.close()

            assertEquals(listOf("1970-01-01T00:00:01Z,72,800"), File(directory, "polar_hr.csv").readLines().drop(1))
            assertEquals(2, File(directory, "polar_ecg.csv").readLines().drop(1).size)
            assertEquals("1970-01-01T00:00:01Z,3,0,1,-2,3", File(directory, "polar_acc.csv").readLines().last())
        } finally {
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }
}
