package com.example.myapplication.feature.device

import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import com.example.myapplication.ble.BleTransportProfile
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
    fun onStartScanRequested_withGrantedPermissionsStartsScanningImmediately() {
        val ble = FakeBleSessionController()
        val controller = createController(bleSessionController = ble)
        var permissionsRequested = false

        controller.onStartScanRequested(
            hasPermissions = { true },
            requestPermissions = { permissionsRequested = true },
        )

        assertEquals(1, ble.startScanningCalls)
        assertTrue(!permissionsRequested)
    }

    @Test
    fun onStartScanRequested_withoutPermissionsRequestsThemInsteadOfStartingScan() {
        val ble = FakeBleSessionController()
        val controller = createController(bleSessionController = ble)
        var permissionsRequested = false

        controller.onStartScanRequested(
            hasPermissions = { false },
            requestPermissions = { permissionsRequested = true },
        )

        assertEquals(0, ble.startScanningCalls)
        assertTrue(permissionsRequested)
    }

    @Test
    fun onConnectRequested_stopsCurrentCaptureAndStartsConnection() {
        val ble = FakeBleSessionController()
        val packetCapture = FakePacketCaptureController()
        val controller = createController(
            bleSessionController = ble,
            packetCaptureController = packetCapture,
        )

        controller.onConnectRequested("AA:BB")

        assertTrue(packetCapture.stopCaptureCalled)
        assertTrue(!packetCapture.resetCalled)
        assertEquals("AA:BB", ble.connectedAddress)
        assertEquals(1, ble.stopScanningCalls)
    }

    @Test
    fun onCaptureReady_resetsCaptureSessionAndFlushesBufferedTransportDiagnostics() {
        val packetCapture = FakePacketCaptureController()
        val controller = createController(packetCaptureController = packetCapture)

        controller.onConnectRequested("AA:BB")
        controller.onTransportDiagnostic("BLE MTU changed: mtu=247 status=0")
        controller.onTransportDiagnostic("BLE PHY updated: txPhy=2 rxPhy=2 status=0")
        controller.onCaptureReady()

        assertTrue(packetCapture.resetCalled)
        assertEquals(
            listOf(
                "BLE MTU changed: mtu=247 status=0",
                "BLE PHY updated: txPhy=2 rxPhy=2 status=0",
            ),
            packetCapture.recordedDiagnostics.map { it.message },
        )
    }

    @Test
    fun onTransportDiagnostic_recordsImmediatelyWhenSessionIsActive() {
        val packetCapture = FakePacketCaptureController()
        val controller = createController(packetCaptureController = packetCapture)

        controller.onTransportDiagnostic("BLE PHY updated: txPhy=2 rxPhy=2 status=0")

        assertEquals(1, packetCapture.recordedDiagnostics.size)
    }

    @Test
    fun onSessionError_flushesBufferedTransportDiagnosticsBeforeShowingError() {
        val packetCapture = FakePacketCaptureController()
        val controller = createController(packetCaptureController = packetCapture)

        controller.onConnectRequested("AA:BB")
        controller.onTransportDiagnostic("BLE MTU changed: mtu=247 status=0")
        controller.onTransportDiagnostic("BLE transport snapshot: profile=Default, mtu=247, phy=?, interval=n/a, latency=n/a, timeout=n/a")
        controller.onSessionError("Service discovery failed: 133")

        assertEquals(
            listOf(
                "BLE MTU changed: mtu=247 status=0",
                "BLE transport snapshot: profile=Default, mtu=247, phy=?, interval=n/a, latency=n/a, timeout=n/a",
            ),
            packetCapture.recordedDiagnostics.map { it.message },
        )
        assertEquals("Service discovery failed: 133", controller.uiState.errorMessage)
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
    fun onTransportProfileSelected_updatesUiAndBleSessionController() {
        val ble = FakeBleSessionController()
        val packetCapture = FakePacketCaptureController()
        val controller = createController(
            bleSessionController = ble,
            packetCaptureController = packetCapture,
        )

        controller.onTransportProfileSelected(BleTransportProfile.COMPATIBILITY)

        assertEquals(BleTransportProfile.COMPATIBILITY, controller.uiState.transportProfile)
        assertEquals(BleTransportProfile.COMPATIBILITY, ble.transportProfile)
        assertEquals(0, packetCapture.recordedDiagnostics.size)
    }

    @Test
    fun chartControls_updateUiViewportState() {
        val controller = createController()

        controller.onChartWindowSelected(ChartWindowPreset.FIVE_MINUTES)
        controller.onFollowLiveChanged(enabled = false)

        assertEquals(ChartWindowPreset.FIVE_MINUTES, controller.uiState.chart.windowPreset)
        assertTrue(!controller.uiState.chart.isFollowingLive)
    }

    @Test
    fun onTabSelected_updatesVisibleCaptureTabOnly() {
        val controller = createController()

        controller.onTabSelected(DeviceCaptureTab.CHART)

        assertEquals(DeviceCaptureTab.CHART, controller.uiState.selectedTab)
    }

    @Test
    fun chartZoomActions_updateViewportState() {
        val controller = createController()

        controller.onChartZoomChanged(scaleFactor = 0.5f)
        controller.onChartPanned(deltaFraction = 0.25f)
        controller.onChartZoomResetRequested()

        assertEquals(ChartWindowPreset.THIRTY_SECONDS, controller.uiState.chart.windowPreset)
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
        assertNotNull(shareFactory.sharedFile)
        assertEquals("packet", requireNotNull(shareFactory.sharedFile).readText())

        packetFile.delete()
        shareFactory.sharedFile?.delete()
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
        assertNotNull(shareFactory.sharedFile)
        assertEquals("raw", requireNotNull(shareFactory.sharedFile).readText())

        rawFile.delete()
        shareFactory.sharedFile?.delete()
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
        assertNotNull(shareFactory.sharedFile)
        assertEquals("log", requireNotNull(shareFactory.sharedFile).readText())

        logFile.delete()
        shareFactory.sharedFile?.delete()
    }

    @Test
    fun shareIntents_useSingleFrozenSnapshotAcrossFiles() {
        val packetFile = File("/tmp/share-snapshot-packet.bin").apply { writeText("packet-v1") }
        val rawFile = File("/tmp/share-snapshot-raw.binlog").apply { writeText("raw-v1") }
        val logFile = File("/tmp/share-snapshot-log.log").apply { writeText("log-v1") }
        val shareFactory = FakeFileShareIntentFactory()
        val controller = DeviceFeatureController(
            bleSessionController = FakeBleSessionController(),
            packetCaptureController = FakePacketCaptureController(
                currentFile = packetFile,
                currentPacketFile = packetFile,
                currentRawFile = rawFile,
                currentLogFile = logFile,
            ),
            fileShareIntentFactory = shareFactory,
        )

        controller.createPacketShareIntent(ContextWrapper(null))
        val packetSnapshot = requireNotNull(shareFactory.sharedFile)
        assertEquals("packet-v1", packetSnapshot.readText())

        packetFile.writeText("packet-v2")
        rawFile.writeText("raw-v2")
        logFile.writeText("log-v2")

        controller.createRawShareIntent(ContextWrapper(null))
        val rawSnapshot = requireNotNull(shareFactory.sharedFile)
        assertEquals("raw-v1", rawSnapshot.readText())

        controller.createLogShareIntent(ContextWrapper(null))
        val logSnapshot = requireNotNull(shareFactory.sharedFile)
        assertEquals("log-v1", logSnapshot.readText())

        packetFile.delete()
        rawFile.delete()
        logFile.delete()
        packetSnapshot.delete()
        rawSnapshot.delete()
        logSnapshot.delete()
    }

    @Test
    fun disconnectRequested_deletesExistingSnapshotFiles() {
        val packetFile = File("/tmp/share-cleanup-packet.bin").apply { writeText("packet-v1") }
        val shareFactory = FakeFileShareIntentFactory()
        val controller = DeviceFeatureController(
            bleSessionController = FakeBleSessionController(),
            packetCaptureController = FakePacketCaptureController(
                currentFile = packetFile,
                currentPacketFile = packetFile,
            ),
            fileShareIntentFactory = shareFactory,
        )

        controller.createPacketShareIntent(ContextWrapper(null))
        val packetSnapshot = requireNotNull(shareFactory.sharedFile)
        assertTrue(packetSnapshot.exists())

        controller.onDisconnectRequested()

        assertTrue(!packetSnapshot.exists())

        packetFile.delete()
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
    var startScanningCalls = 0
    var stopScanningCalls = 0
    var connectedAddress: String? = null
    var closeCalls = 0
    var transportProfile = BleTransportProfile.DEFAULT

    override fun currentState(): BleSessionState = BleSessionState.IDLE

    override fun currentTransportProfile(): BleTransportProfile = transportProfile

    override fun updateTransportProfile(profile: BleTransportProfile) {
        transportProfile = profile
    }

    override fun startScanning() {
        startScanningCalls++
    }

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
    var stopCaptureCalled = false
    var selectedSensorType: Int? = null
    var selectedChannel: Int? = null
    var flushCalled = false
    var closeCalled = false
    val recordedDiagnostics = mutableListOf<PacketDiagnosticEvent>()

    override fun submit(packetFragment: ByteArray): PacketSubmitResult = submitResult

    override fun recordDiagnosticEvent(type: PacketDiagnosticType, message: String) {
        recordedDiagnostics += PacketDiagnosticEvent(
            id = recordedDiagnostics.size.toLong(),
            type = type,
            message = message,
        )
    }

    override fun stopCapture() {
        stopCaptureCalled = true
    }

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
