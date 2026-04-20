package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.R
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionListener
import com.example.myapplication.ble.BleSessionManager
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.feature.device.DebugPacketReplayController
import com.example.myapplication.feature.device.DeviceUiStateHolder
import com.example.myapplication.feature.device.BatchingPacketCaptureController
import com.example.myapplication.feature.device.PacketCaptureController
import com.example.myapplication.feature.device.PacketCaptureProcessor
import com.example.myapplication.feature.device.PacketProcessingUpdateBatcher
import com.example.myapplication.feature.device.PacketReplayController
import com.example.myapplication.feature.device.PacketSubmitResult
import com.example.myapplication.storage.BleFileShareIntentFactory
import com.example.myapplication.storage.BleDiagnosticLogFileStore
import com.example.myapplication.storage.FileShareIntentFactory
import com.example.myapplication.storage.BlePacketFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppTextResolver
import java.io.File
import java.util.UUID

fun createDeviceFeatureController(
    context: Context,
    filesDir: File,
    fileProviderAuthority: String,
    serviceUuid: UUID,
    characteristicUuid: UUID,
    descriptorUuid: UUID,
    initialTransportProfile: BleTransportProfile? = null,
    packetCaptureControllerFactory: ((DeviceUiStateHolder, Handler) -> PacketCaptureController)? = null,
    bleSessionControllerFactory: ((BleSessionListener) -> BleSessionController)? = null,
    fileShareIntentFactory: FileShareIntentFactory? = null,
    appTextResolver: AppTextResolver = AppTextResolver(context.applicationContext) {
        AppLanguage.defaultFrom()
    },
): DeviceFeatureController {
    val uiStateHolder = DeviceUiStateHolder()
    val mainHandler = Handler(Looper.getMainLooper())
    var updateBatcher: PacketProcessingUpdateBatcher? = null
    updateBatcher = PacketProcessingUpdateBatcher(
        dispatchIntervalMillis = UI_PACKET_UPDATE_INTERVAL_MS,
        schedule = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancel = { runnable -> mainHandler.removeCallbacks(runnable) },
        dispatch = { generation, update ->
            mainHandler.post {
                if (updateBatcher?.isGenerationCurrent(generation) == true) {
                    uiStateHolder.applyPacketUpdate(update)
                }
            }
        },
    )
    val packetCaptureController: PacketCaptureController =
        packetCaptureControllerFactory?.invoke(uiStateHolder, mainHandler) ?: BatchingPacketCaptureController(
            delegate = PacketCaptureProcessor(
                packetFileStore = BlePacketFileStore(directory = filesDir),
                rawFragmentFileStore = BleRawFragmentFileStore(directory = filesDir),
                diagnosticLogFileStore = BleDiagnosticLogFileStore(directory = filesDir),
                appTextResolver = appTextResolver,
                onPacketProcessed = updateBatcher::submit,
                onError = { message, throwable ->
                    updateBatcher.clearPending()
                    mainHandler.post {
                        uiStateHolder.showError(message)
                    }
                    Log.e("BLE_PROCESSOR", message, throwable)
                },
            ),
            batcher = updateBatcher,
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
                val message = appTextResolver.getString(R.string.packet_queue_overflow_stopped)
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
                    deviceFeatureController?.onSessionError(message) ?: uiStateHolder.showError(message)
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
        appTextResolver = appTextResolver,
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
        appTextResolver = appTextResolver,
    )

    deviceFeatureController = DeviceFeatureController(
        bleSessionController = requireNotNull(bleSessionController),
        packetCaptureController = packetCaptureController,
        fileShareIntentFactory = fileShareIntentFactory
            ?: BleFileShareIntentFactory(
                authority = fileProviderAuthority,
                appTextResolver = appTextResolver,
            ),
        appTextResolver = appTextResolver,
        packetReplayController = packetReplayController,
        uiStateHolder = uiStateHolder,
        runOnUiThread = { action ->
            mainHandler.post {
                action()
            }
        },
    )

    initialTransportProfile?.let { profile ->
        deviceFeatureController.onTransportProfileSelected(profile)
    }
    return requireNotNull(deviceFeatureController)
}

object DeviceFeatureModuleEntryPoint {
    fun create(
        context: Context,
        filesDir: File,
        fileProviderAuthority: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        initialTransportProfile: BleTransportProfile? = null,
        packetCaptureControllerFactory: ((DeviceUiStateHolder, Handler) -> PacketCaptureController)? = null,
        bleSessionControllerFactory: ((BleSessionListener) -> BleSessionController)? = null,
        fileShareIntentFactory: FileShareIntentFactory? = null,
        appTextResolver: AppTextResolver = AppTextResolver(context.applicationContext) {
            AppLanguage.defaultFrom()
        },
    ): DeviceFeatureController {
        return createDeviceFeatureController(
            context = context,
            filesDir = filesDir,
            fileProviderAuthority = fileProviderAuthority,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            initialTransportProfile = initialTransportProfile,
            packetCaptureControllerFactory = packetCaptureControllerFactory,
            bleSessionControllerFactory = bleSessionControllerFactory,
            fileShareIntentFactory = fileShareIntentFactory,
            appTextResolver = appTextResolver,
        )
    }
}

private const val UI_PACKET_UPDATE_INTERVAL_MS = 100L
