package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionListener
import com.example.myapplication.ble.BleSessionManager
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.feature.device.DebugPacketReplayController
import com.example.myapplication.feature.device.DeviceUiStateHolder
import com.example.myapplication.feature.device.PacketCaptureController
import com.example.myapplication.feature.device.PacketCaptureProcessor
import com.example.myapplication.feature.device.PacketReplayController
import com.example.myapplication.feature.device.PacketSubmitResult
import com.example.myapplication.storage.BleFileShareIntentFactory
import com.example.myapplication.storage.BleDiagnosticLogFileStore
import com.example.myapplication.storage.FileShareIntentFactory
import com.example.myapplication.storage.BlePacketFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import java.io.File
import java.util.UUID

fun createDeviceFeatureController(
    context: Context,
    filesDir: File,
    fileProviderAuthority: String,
    serviceUuid: UUID,
    characteristicUuid: UUID,
    descriptorUuid: UUID,
    packetCaptureControllerFactory: ((DeviceUiStateHolder, Handler) -> PacketCaptureController)? = null,
    bleSessionControllerFactory: ((BleSessionListener) -> BleSessionController)? = null,
    fileShareIntentFactory: FileShareIntentFactory? = null,
): DeviceFeatureController {
    val uiStateHolder = DeviceUiStateHolder()
    val mainHandler = Handler(Looper.getMainLooper())
    val packetCaptureController: PacketCaptureController =
        packetCaptureControllerFactory?.invoke(uiStateHolder, mainHandler) ?: PacketCaptureProcessor(
            packetFileStore = BlePacketFileStore(directory = filesDir),
            rawFragmentFileStore = BleRawFragmentFileStore(directory = filesDir),
            diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = filesDir),
            onPacketProcessed = { update ->
                mainHandler.post {
                    uiStateHolder.applyPacketUpdate(update)
                }
            },
            onError = { message, throwable ->
                mainHandler.post {
                    uiStateHolder.showError(message)
                }
                Log.e("BLE_PROCESSOR", message, throwable)
            },
        )
    packetCaptureController.updateSelection(
        sensorType = uiStateHolder.uiState.selectedSensorType,
        channel = uiStateHolder.uiState.selectedChannel,
    )

    var bleSessionController: BleSessionController? = null
    var deviceFeatureController: DeviceFeatureController? = null
    val submitPacketFragment: (ByteArray) -> PacketSubmitResult = { packetFragment ->
        when (val submitResult = packetCaptureController.submit(packetFragment)) {
            PacketSubmitResult.ACCEPTED -> submitResult
            PacketSubmitResult.REJECTED -> {
                Log.d("BLE_QUEUE", "Dropping packet fragment after capture shutdown")
                submitResult
            }

            PacketSubmitResult.OVERFLOW -> {
                val message = "Packet queue overflow. Capture stopped."
                packetCaptureController.stopCapture()
                mainHandler.post {
                    uiStateHolder.showError(message)
                }
                bleSessionController?.close()
                Log.e("BLE_QUEUE", message)
                submitResult
            }
        }
    }
    val listener = object : BleSessionListener {
            override fun onDeviceFound(device: BleDevice) {
                mainHandler.post {
                    uiStateHolder.addFoundDevice(device)
                }
            }

            override fun onPacketReceived(packetFragment: ByteArray) {
                submitPacketFragment(packetFragment)
            }

            override fun onDiagnosticMessage(message: String) {
                mainHandler.post {
                    deviceFeatureController?.onTransportDiagnostic(message)
                }
            }

            override fun onCaptureReady() {
                mainHandler.post {
                    deviceFeatureController?.onCaptureReady()
                }
            }

            override fun onStateChanged(state: BleSessionState) {
                mainHandler.post {
                    uiStateHolder.onSessionStateChanged(state)
                }
            }

            override fun onError(message: String, throwable: Throwable?) {
                mainHandler.post {
                    uiStateHolder.showError(message)
                }
                Log.e("BLE_SESSION", message, throwable)
            }
        }
    bleSessionController = bleSessionControllerFactory?.invoke(listener) ?: BleSessionManager(
        context = context,
        serviceUuid = serviceUuid,
        characteristicUuid = characteristicUuid,
        descriptorUuid = descriptorUuid,
        listener = listener,
    )
    uiStateHolder.onTransportProfileSelected(requireNotNull(bleSessionController).currentTransportProfile())
    val packetReplayController: PacketReplayController = DebugPacketReplayController(
        submitFragment = submitPacketFragment,
        onReplayStarted = {
            mainHandler.post {
                uiStateHolder.startReplaySession()
            }
        },
        onReplayCompleted = {
            mainHandler.post {
                deviceFeatureController?.onReplayCompleted()
            }
        },
        onReplayStopped = {
            mainHandler.post {
                deviceFeatureController?.onReplayStopped()
            }
        },
        onError = { message, throwable ->
            mainHandler.post {
                uiStateHolder.showError(message)
            }
            Log.e("BLE_REPLAY", message, throwable)
        },
    )

    deviceFeatureController = DeviceFeatureController(
        bleSessionController = requireNotNull(bleSessionController),
        packetCaptureController = packetCaptureController,
        fileShareIntentFactory = fileShareIntentFactory
            ?: BleFileShareIntentFactory(authority = fileProviderAuthority),
        packetReplayController = packetReplayController,
        uiStateHolder = uiStateHolder,
    )
    return requireNotNull(deviceFeatureController)
}
