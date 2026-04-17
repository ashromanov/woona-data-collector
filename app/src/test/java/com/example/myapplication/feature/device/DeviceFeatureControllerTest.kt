package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.storage.FileShareIntentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceFeatureControllerTest {
    @Test
    fun onPermissionsResult_withDeniedPermissionsShowsError() {
        val controller = createController()

        controller.onPermissionsResult(allGranted = false)

        assertEquals(DeviceFeatureController.PERMISSION_REQUIRED_MESSAGE, controller.uiState.errorMessage)
    }

    @Test
    fun onConnectRequested_resetsCaptureAndStartsConnection() {
        val ble = FakeBleSessionController()
        val packetCapture = FakePacketCaptureController()
        val controller = createController(
            bleSessionController = ble,
            packetCaptureController = packetCapture,
        )

        controller.onConnectRequested("AA:BB")

        assertTrue(!packetCapture.resetCalled)
        assertEquals("AA:BB", ble.connectedAddress)
        assertEquals(1, ble.stopScanningCalls)
    }

    @Test
    fun onCaptureReady_resetsCaptureSession() {
        val packetCapture = FakePacketCaptureController()
        val controller = createController(packetCaptureController = packetCapture)

        controller.onCaptureReady()

        assertTrue(packetCapture.resetCalled)
    }

    @Test
    fun onSensorSelected_updatesCaptureSelection() {
        val packetCapture = FakePacketCaptureController()
        val controller = createController(packetCaptureController = packetCapture)

        controller.onSensorSelected(4)

        assertEquals(4, controller.uiState.selectedSensorType)
        assertEquals(1, controller.uiState.selectedChannel)
        assertEquals(4, packetCapture.selectedSensorType)
        assertEquals(1, packetCapture.selectedChannel)
    }

    @Test
    fun startReplay_resetsCaptureAndShowsReplayUi() {
        val ble = FakeBleSessionController()
        val packetCapture = FakePacketCaptureController()
        val replayController = FakePacketReplayController()
        val controller = createController(
            bleSessionController = ble,
            packetCaptureController = packetCapture,
            packetReplayController = replayController,
        )

        controller.startReplay(byteArrayOf(0x01, 0x02))

        assertTrue(packetCapture.resetCalled)
        assertTrue(replayController.started)
        assertTrue(replayController.stopped)
        assertEquals(1, ble.closeCalls)
        assertTrue(controller.uiState.showCaptureUi)
        assertTrue(controller.uiState.isReplayRunning)
    }

    @Test
    fun createPacketShareIntent_sharesPacketFile() {
        val packetFile = File("/tmp/share-packet.bin")
        packetFile.writeText("packet")
        val shareFactory = FakeFileShareIntentFactory()
        val controller = DeviceFeatureController(
            bleSessionController = FakeBleSessionController(),
            packetCaptureController = FakePacketCaptureController(
                currentFile = packetFile,
                currentPacketFile = packetFile,
            ),
            fileShareIntentFactory = shareFactory,
        )

        val intent = controller.createPacketShareIntent(ContextWrapper(null))

        assertNotNull(intent)
        assertEquals(packetFile, shareFactory.sharedFile)

        packetFile.delete()
    }

    @Test
    fun createRawShareIntent_sharesRawFile() {
        val rawFile = File("/tmp/share-raw.binlog")
        rawFile.writeText("raw")
        val shareFactory = FakeFileShareIntentFactory()
        val controller = DeviceFeatureController(
            bleSessionController = FakeBleSessionController(),
            packetCaptureController = FakePacketCaptureController(
                currentRawFile = rawFile,
            ),
            fileShareIntentFactory = shareFactory,
        )

        val intent = controller.createRawShareIntent(ContextWrapper(null))

        assertNotNull(intent)
        assertEquals(rawFile, shareFactory.sharedFile)

        rawFile.delete()
    }

    @Test
    fun createLogShareIntent_sharesLogFile() {
        val logFile = File("/tmp/share-log.log")
        logFile.writeText("log")
        val shareFactory = FakeFileShareIntentFactory()
        val controller = DeviceFeatureController(
            bleSessionController = FakeBleSessionController(),
            packetCaptureController = FakePacketCaptureController(
                currentLogFile = logFile,
            ),
            fileShareIntentFactory = shareFactory,
        )

        val intent = controller.createLogShareIntent(ContextWrapper(null))

        assertNotNull(intent)
        assertEquals(logFile, shareFactory.sharedFile)

        logFile.delete()
    }

    private fun createController(
        bleSessionController: FakeBleSessionController = FakeBleSessionController(),
        packetCaptureController: FakePacketCaptureController = FakePacketCaptureController(),
        packetReplayController: FakePacketReplayController? = null,
    ): DeviceFeatureController {
        return DeviceFeatureController(
            bleSessionController = bleSessionController,
            packetCaptureController = packetCaptureController,
            fileShareIntentFactory = FakeFileShareIntentFactory(),
            packetReplayController = packetReplayController,
        )
    }
}

private class FakeBleSessionController : BleSessionController {
    var stopScanningCalls = 0
    var connectedAddress: String? = null
    var closeCalls = 0

    override fun currentState(): BleSessionState = BleSessionState.IDLE

    override fun startScanning() = Unit

    override fun stopScanning() {
        stopScanningCalls++
    }

    override fun connect(address: String) {
        connectedAddress = address
    }

    override fun disconnect() = Unit

    override fun close() {
        closeCalls++
    }
}

private class FakePacketCaptureController(
    private val submitResult: PacketSubmitResult = PacketSubmitResult.ACCEPTED,
    private val currentFile: File? = File("/tmp/non-existent-share.bin"),
    private val currentPacketFile: File? = currentFile,
    private val currentRawFile: File? = null,
    private val currentLogFile: File? = null,
) : PacketCaptureController {
    var resetCalled = false
    var selectedSensorType: Int? = null
    var selectedChannel: Int? = null
    var flushCalled = false
    var closeCalled = false

    override fun submit(packetFragment: ByteArray): PacketSubmitResult = submitResult

    override fun stopCapture() = Unit

    override fun updateSelection(sensorType: Int, channel: Int) {
        selectedSensorType = sensorType
        selectedChannel = channel
    }

    override fun resetSession() {
        resetCalled = true
    }

    override fun flush() {
        flushCalled = true
    }

    override fun currentFile(): File? = currentFile

    override fun currentPacketFile(): File? = currentPacketFile

    override fun currentRawFile(): File? = currentRawFile

    override fun currentLogFile(): File? = currentLogFile

    override fun close() {
        closeCalled = true
    }
}

private class FakePacketReplayController : PacketReplayController {
    var started = false
    var stopped = false

    override fun startReplay(fileBytes: ByteArray) {
        started = fileBytes.isNotEmpty()
    }

    override fun stop() {
        stopped = true
    }

    override fun close() = Unit
}

private class FakeFileShareIntentFactory : FileShareIntentFactory {
    var sharedFile: File? = null

    override fun createChooserIntent(context: Context, file: File): Intent {
        sharedFile = file
        return Intent("test")
    }
}
