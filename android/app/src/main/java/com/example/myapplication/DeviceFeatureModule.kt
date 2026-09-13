package com.example.myapplication

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.example.myapplication.R
import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionController
import com.example.myapplication.ble.BleSessionListener
import com.example.myapplication.ble.BleSessionManager
import com.example.myapplication.ble.BleSessionState
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.ble.PolarSessionManager
import com.example.myapplication.data.WoonaDatabase
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
import com.example.myapplication.storage.BlePacketTimelineFileStore
import com.example.myapplication.storage.BleRawFragmentFileStore
import com.example.myapplication.storage.PolarCsvFileStore
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.protocol.ConnectionQuality
import com.example.myapplication.video.AndroidVideoRecorder
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
    woonaDatabase: WoonaDatabase? = null,
    snapshotDirectory: File? = null,
    onRecordingChanged: () -> Unit = {},
    appTextResolver: AppTextResolver = AppTextResolver(context.applicationContext) {
        AppLanguage.defaultFrom()
    },
): DeviceFeatureController {
    val uiStateHolder = DeviceUiStateHolder()
    val mainHandler = Handler(Looper.getMainLooper())
    var deviceFeatureController: DeviceFeatureController? = null
    var updateBatcher: PacketProcessingUpdateBatcher? = null
    updateBatcher = PacketProcessingUpdateBatcher(
        dispatchIntervalMillis = UI_PACKET_UPDATE_INTERVAL_MS,
        schedule = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancel = { runnable -> mainHandler.removeCallbacks(runnable) },
        dispatch = { generation, update ->
            mainHandler.post {
                if (updateBatcher?.isGenerationCurrent(generation) == true) {
                    deviceFeatureController?.onPacketUpdate(update) ?: uiStateHolder.applyPacketUpdate(update)
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
                packetTimelineFileStore = BlePacketTimelineFileStore(directory = filesDir),
                appTextResolver = appTextResolver,
                onPacketProcessed = updateBatcher::submit,
                onAcceptedPacketTimestamp = { wallClockMillis, monotonicNs, deviceTimerMillis ->
                    deviceFeatureController?.onSensorFragmentReceived(
                        receivedAtMillis = wallClockMillis,
                        receivedAtMonotonicNs = monotonicNs,
                        deviceTimerMillis = deviceTimerMillis,
                    )
                },
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
    val submitPacketFragment: (ByteArray, Long, Long) -> PacketSubmitResult = {
            packetFragment,
            receivedAtMillis,
            receivedAtMonotonicNs,
        ->
        when (
            val submitResult = packetCaptureController.submit(
                packetFragment,
                receivedAtMillis,
                receivedAtMonotonicNs,
            )
        ) {
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
                submitPacketFragment(
                    packetFragment,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtimeNanos(),
                )
            }

            override fun onPacketReceived(
                packetFragment: ByteArray,
                receivedAtWallClockMillis: Long,
                receivedAtMonotonicNs: Long,
            ) {
                submitPacketFragment(packetFragment, receivedAtWallClockMillis, receivedAtMonotonicNs)
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
                    deviceFeatureController?.onBleStateChanged(state)
                        ?: uiStateHolder.onSessionStateChanged(state)
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
        submitFragment = { bytes ->
            submitPacketFragment(
                bytes,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
            )
        },
        onReplayPreparing = {
            deviceFeatureController?.onReplayPreparing()
        },
        onReplayStarted = {
            deviceFeatureController?.onReplayStarted()
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
                deviceFeatureController?.onReplayError(message) ?: uiStateHolder.showError(message)
            }
            Log.e("BLE_REPLAY", message, throwable)
        },
        appTextResolver = appTextResolver,
        callbackDispatcher = { callback -> mainHandler.post(callback) },
        replayTempFileFactory = {
            File.createTempFile("debug-replay-", ".bin", context.cacheDir)
        },
    )

    val polarFileStore = PolarCsvFileStore()
    val polarSessionManager = PolarSessionManager(
        context = context,
        onDeviceFound = { device -> mainHandler.post { uiStateHolder.addFoundPolarDevice(device) } },
        onStatus = { status ->
            mainHandler.post {
                deviceFeatureController?.onPolarStatusChanged(status) ?: uiStateHolder.updatePolarStatus(status)
            }
        },
        onHeartRate = { frame, now -> deviceFeatureController?.onPolarHeartRate(frame, now) },
        onEcg = { frame, now -> deviceFeatureController?.onPolarEcg(frame, now) },
        onAcceleration = { frame, now -> deviceFeatureController?.onPolarAcceleration(frame, now) },
        onError = { message, throwable ->
            mainHandler.post { uiStateHolder.showError(message) }
            Log.e("POLAR_SESSION", message, throwable)
        },
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
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                mainHandler.post(action)
            }
        },
        woonaDatabase = woonaDatabase,
        snapshotDirectory = snapshotDirectory,
        onRecordingChanged = onRecordingChanged,
        videoRecorder = AndroidVideoRecorder(context),
        polarSessionManager = polarSessionManager,
        polarFileStore = polarFileStore,
        schedule = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancel = mainHandler::removeCallbacks,
        onConnectionAlarm = { quality -> playConnectionAlarm(context, mainHandler, quality) },
    )

    initialTransportProfile?.let { profile ->
        deviceFeatureController.onTransportProfileSelected(profile)
    }
    return requireNotNull(deviceFeatureController)
}

private fun playConnectionAlarm(
    context: Context,
    handler: Handler,
    quality: ConnectionQuality,
) {
    val vibration = when (quality) {
        ConnectionQuality.LOST -> longArrayOf(0L, 350L, 180L, 350L)
        ConnectionQuality.WEAK -> longArrayOf(0L, 120L, 80L, 120L)
        else -> longArrayOf(0L, 80L)
    }
    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        ?.vibrate(VibrationEffect.createWaveform(vibration, -1))

    val durationMillis = if (quality == ConnectionQuality.LOST) 700 else 250
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, if (quality == ConnectionQuality.LOST) 80 else 55)
        tone.startTone(
            if (quality == ConnectionQuality.GOOD) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP2,
            durationMillis,
        )
        handler.postDelayed(tone::release, durationMillis + 100L)
    }
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
        woonaDatabase: WoonaDatabase? = null,
        snapshotDirectory: File? = null,
        onRecordingChanged: () -> Unit = {},
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
            woonaDatabase = woonaDatabase,
            snapshotDirectory = snapshotDirectory,
            onRecordingChanged = onRecordingChanged,
            appTextResolver = appTextResolver,
        )
    }
}

private const val UI_PACKET_UPDATE_INTERVAL_MS = 100L
