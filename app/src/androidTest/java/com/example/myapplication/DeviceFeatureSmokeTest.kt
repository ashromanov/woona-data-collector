package com.example.myapplication

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionListener
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.feature.device.DeviceUiStateHolder
import com.example.myapplication.feature.device.PacketCaptureController
import com.example.myapplication.feature.device.PacketSubmitResult
import com.example.myapplication.storage.FileShareIntentFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceFeatureSmokeTest {
    @Test
    fun fakeDrivenSmokePath_coversScanConnectCaptureAndShare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packetFile = File(context.filesDir, "smoke-share.bin").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val packetCaptureController = FakePacketCaptureController(packetFile)
        val fileShareIntentFactory = FakeFileShareIntentFactory()
        lateinit var bleSessionController: FakeBleSessionController

        val controller = createDeviceFeatureController(
            context = context,
            filesDir = context.filesDir,
            fileProviderAuthority = "${context.packageName}.provider",
            serviceUuid = UUID.randomUUID(),
            characteristicUuid = UUID.randomUUID(),
            descriptorUuid = UUID.randomUUID(),
            packetCaptureControllerFactory = { _: DeviceUiStateHolder, _ ->
                packetCaptureController
            },
            bleSessionControllerFactory = { listener ->
                FakeBleSessionController(listener).also {
                    bleSessionController = it
                }
            },
            fileShareIntentFactory = fileShareIntentFactory,
        )

        var permissionsRequested = false
        controller.onStartScanRequested(
            hasPermissions = { false },
            requestPermissions = { permissionsRequested = true },
        )

        assertTrue(permissionsRequested)

        controller.onPermissionsResult(allGranted = true)
        assertEquals(1, bleSessionController.startScanningCalls)

        bleSessionController.listener.onStateChanged(BleSessionState.SCANNING)
        bleSessionController.listener.onDeviceFound(
            BleDevice(
                name = "Demo",
                address = "AA:BB:CC:DD:EE:FF",
            ),
        )
        instrumentation.waitForIdleSync()

        assertTrue(controller.uiState.isScanning)
        assertEquals(1, controller.uiState.foundDevices.size)
        assertEquals("AA:BB:CC:DD:EE:FF", controller.uiState.foundDevices.single().address)

        controller.onConnectRequested("AA:BB:CC:DD:EE:FF")
        assertEquals(1, bleSessionController.stopScanningCalls)
        assertEquals("AA:BB:CC:DD:EE:FF", bleSessionController.connectedAddress)
        assertTrue(packetCaptureController.stopCaptureCalled)

        bleSessionController.listener.onDiagnosticMessage("BLE MTU changed: mtu=247 status=0")
        bleSessionController.listener.onStateChanged(BleSessionState.CONNECTED)
        bleSessionController.listener.onCaptureReady()
        bleSessionController.listener.onPacketReceived(byteArrayOf(0x10, 0x20, 0x30))
        instrumentation.waitForIdleSync()

        assertTrue(controller.uiState.isConnected)
        assertTrue(packetCaptureController.resetCalled)
        assertEquals(1, packetCaptureController.recordedDiagnostics.size)
        assertEquals(1, packetCaptureController.submittedFragments.size)
        assertArrayEquals(
            byteArrayOf(0x10, 0x20, 0x30),
            packetCaptureController.submittedFragments.single(),
        )

        val shareIntent = controller.createPacketShareIntent(context)
        val sharedFile = fileShareIntentFactory.sharedFile

        assertNotNull(shareIntent)
        assertTrue(packetCaptureController.flushCalled)
        assertNotNull(sharedFile)
        assertTrue(sharedFile!!.exists())
        assertTrue(sharedFile.absolutePath != packetFile.absolutePath)
        assertTrue(sharedFile.name.contains("_snapshot_packet"))
        assertArrayEquals(packetFile.readBytes(), sharedFile.readBytes())

        controller.close()
        assertTrue(bleSessionController.closeCalled)
        assertTrue(packetCaptureController.closeCalled)
    }
}

private class FakeBleSessionController(
    val listener: BleSessionListener,
) : BleSessionController {
    var startScanningCalls = 0
    var stopScanningCalls = 0
    var connectedAddress: String? = null
    var closeCalled = false
    private var transportProfile = BleTransportProfile.DEFAULT

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
        closeCalled = true
    }
}

private class FakePacketCaptureController(
    private val packetFile: File,
) : PacketCaptureController {
    val submittedFragments = mutableListOf<ByteArray>()
    var resetCalled = false
    var stopCaptureCalled = false
    var flushCalled = false
    var closeCalled = false
    val recordedDiagnostics = mutableListOf<String>()

    override fun submit(packetFragment: ByteArray): PacketSubmitResult {
        submittedFragments += packetFragment
        return PacketSubmitResult.ACCEPTED
    }

    override fun recordDiagnosticEvent(
        type: com.example.myapplication.feature.device.PacketDiagnosticType,
        message: String,
    ) {
        recordedDiagnostics += message
    }

    override fun stopCapture() {
        stopCaptureCalled = true
    }

    override fun updateSelection(sensorType: Int, channel: Int) = Unit

    override fun resetSession() {
        resetCalled = true
    }

    override fun flush() {
        flushCalled = true
    }

    override fun currentFile(): File = packetFile

    override fun close() {
        closeCalled = true
    }
}

private class FakeFileShareIntentFactory : FileShareIntentFactory {
    var sharedFile: File? = null

    override fun createChooserIntent(context: Context, file: File): Intent {
        sharedFile = file
        return Intent("test-share")
    }
}
