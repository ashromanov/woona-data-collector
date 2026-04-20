package com.example.myapplication

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        deviceFeatureController.onPermissionsResult(allGranted)
        if (!allGranted) {
            Toast.makeText(this, "Нужны разрешения!", Toast.LENGTH_SHORT).show()
        }
    }

    private val replayFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        try {
            val fileBytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
            if (fileBytes == null || fileBytes.isEmpty()) {
                Toast.makeText(this, "Не удалось прочитать BIN файл", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            deviceFeatureController.startReplay(fileBytes)
        } catch (exception: Exception) {
            Toast.makeText(this, "Не удалось загрузить BIN файл", Toast.LENGTH_SHORT).show()
        }
    }

    private val deviceFeatureController: DeviceFeatureController by lazy {
        createDeviceFeatureController(
            context = this,
            filesDir = filesDir,
            fileProviderAuthority = "com.example.myapplication.provider",
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceFeatureController
        enableEdgeToEdge()
        setContent {
            val uiState = deviceFeatureController.uiState
            MyApplicationTheme {
                DeviceAppShell(
                    uiState = uiState,
                    onStartScan = {
                        deviceFeatureController.onStartScanRequested(
                            hasPermissions = ::hasBlePermissions,
                            requestPermissions = {
                                requestPermissionLauncher.launch(blePermissions)
                            },
                        )
                    },
                    onConnect = { mac -> deviceFeatureController.onConnectRequested(mac) },
                    onTransportProfileSelect = { profile ->
                        deviceFeatureController.onTransportProfileSelected(profile)
                    },
                    onDisconnect = { deviceFeatureController.onDisconnectRequested() },
                    onSensorSelect = { type -> deviceFeatureController.onSensorSelected(type) },
                    onChannelSelect = { channel -> deviceFeatureController.onChannelSelected(channel) },
                    onChartWindowSelect = { windowPreset ->
                        deviceFeatureController.onChartWindowSelected(windowPreset)
                    },
                    onFollowLiveChange = { enabled ->
                        deviceFeatureController.onFollowLiveChanged(enabled)
                    },
                    onChartPanLeft = { deviceFeatureController.onChartPanLeftRequested() },
                    onChartPanRight = { deviceFeatureController.onChartPanRightRequested() },
                    onChartZoomIn = { deviceFeatureController.onChartZoomInRequested() },
                    onChartZoomOut = { deviceFeatureController.onChartZoomOutRequested() },
                    onChartZoomReset = { deviceFeatureController.onChartZoomResetRequested() },
                    onChartPanGesture = { deltaFraction ->
                        deviceFeatureController.onChartPanned(deltaFraction)
                    },
                    onChartZoomGesture = { scaleFactor, anchorFractionY ->
                        deviceFeatureController.onChartZoomChanged(scaleFactor, anchorFractionY)
                    },
                    canSharePacketFile = deviceFeatureController.canSharePacketFile(),
                    canShareRawFile = deviceFeatureController.canShareRawFile(),
                    canShareLogFile = deviceFeatureController.canShareLogFile(),
                    onSharePacketFile = {
                        deviceFeatureController.createPacketShareIntent(this)?.let(::startActivity)
                    },
                    onShareRawFile = {
                        deviceFeatureController.createRawShareIntent(this)?.let(::startActivity)
                    },
                    onShareLogFile = {
                        deviceFeatureController.createLogShareIntent(this)?.let(::startActivity)
                    },
                    showReplayAction = true,
                    onReplayRequest = { replayFilePickerLauncher.launch("*/*") },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        deviceFeatureController.onPause()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            deviceFeatureController.close()
        }

        super.onDestroy()
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
