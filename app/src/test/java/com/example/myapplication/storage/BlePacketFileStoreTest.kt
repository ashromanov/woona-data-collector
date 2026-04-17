package com.example.myapplication.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class BlePacketFileStoreTest {
    @Test
    fun open_createsFileInConfiguredDirectory() {
        val directory = Files.createTempDirectory("ble-store-test").toFile()
        val store = BlePacketFileStore(
            directory = directory,
            timestampProvider = { 1234L },
        )

        val file = store.open()

        assertEquals(directory.absolutePath, file.parentFile?.absolutePath)
        assertEquals("ble_dump_1234.bin", file.name)
        assertTrue(file.exists())
        store.close()
    }

    @Test
    fun append_persistsBytesToCurrentFile() {
        val directory = Files.createTempDirectory("ble-store-write").toFile()
        val store = BlePacketFileStore(
            directory = directory,
            timestampProvider = { 55L },
        )

        store.open()
        store.append(byteArrayOf(0x01, 0x02, 0x03))
        store.append(byteArrayOf(0x04, 0x05))
        store.flush()
        store.close()

        val bytes = requireNotNull(store.currentFile()).readBytes()
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05), bytes)
    }

    @Test
    fun append_opensFileLazily() {
        val directory = Files.createTempDirectory("ble-store-lazy").toFile()
        val store = BlePacketFileStore(
            directory = directory,
            timestampProvider = { 77L },
        )

        store.append(byteArrayOf(0x0A))
        store.flush()
        store.close()

        val file = requireNotNull(store.currentFile())
        assertEquals("ble_dump_77.bin", file.name)
        assertArrayEquals(byteArrayOf(0x0A), file.readBytes())
    }

    @Test
    fun append_afterCloseFailsInsteadOfReopeningAnotherFile() {
        val directory = Files.createTempDirectory("ble-store-close").toFile()
        val store = BlePacketFileStore(
            directory = directory,
            timestampProvider = { 88L },
        )

        store.open()
        store.close()

        assertThrows(IllegalStateException::class.java) {
            store.append(byteArrayOf(0x0B))
        }
    }

    @Test
    fun resetSession_rotatesToANewFileForNextAppend() {
        val directory = Files.createTempDirectory("ble-store-reset").toFile()
        var timestamp = 10L
        val store = BlePacketFileStore(
            directory = directory,
            timestampProvider = { timestamp },
        )

        store.append(byteArrayOf(0x01))
        store.flush()
        val firstFile = requireNotNull(store.currentFile())

        timestamp = 20L
        store.resetSession()
        store.append(byteArrayOf(0x02))
        store.flush()
        val secondFile = requireNotNull(store.currentFile())

        assertEquals("ble_dump_10.bin", firstFile.name)
        assertEquals("ble_dump_20.bin", secondFile.name)
        assertArrayEquals(byteArrayOf(0x01), firstFile.readBytes())
        assertArrayEquals(byteArrayOf(0x02), secondFile.readBytes())
        store.close()
    }
}
