package com.example.myapplication

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.AppShellEntryPoint
import com.example.myapplication.DeviceFeatureModuleEntryPoint
import com.example.myapplication.ble.BleTransportProfilePreferences
import com.example.myapplication.feature.device.DeviceFeatureController
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLocalizationEntryPoint
import com.example.myapplication.localization.AppLanguagePreferences
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.ui.theme.AppThemeMode
import com.example.myapplication.ui.theme.AppThemeEntryPoint
import com.example.myapplication.ui.theme.AppThemePreferences
import com.example.myapplication.ui.theme.applyAppThemeMode
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val appLanguagePreferences by lazy {
        AppLanguagePreferences(applicationContext)
    }
    private val appThemePreferences by lazy {
        AppThemePreferences(applicationContext)
    }
    private val bleTransportProfilePreferences by lazy {
        BleTransportProfilePreferences(applicationContext)
    }
    private var selectedLanguage by mutableStateOf(AppLanguage.ENGLISH)
    private var selectedThemeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private val appTextResolver by lazy {
        AppTextResolver(applicationContext) { selectedLanguage }
    }
    private val backgroundExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "main-activity-io").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        deviceFeatureController.onPermissionsResult(allGranted)
        if (!allGranted) {
            Toast.makeText(
                this,
                appTextResolver.getString(R.string.permissions_required),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val replayFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        backgroundExecutor.execute {
            try {
                val fileBytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                }
                runOnUiThread {
                    if (fileBytes == null || fileBytes.isEmpty()) {
                        Toast.makeText(
                            this,
                            appTextResolver.getString(R.string.unable_read_bin_file),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@runOnUiThread
                    }

                    deviceFeatureController.startReplay(fileBytes)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        appTextResolver.getString(R.string.unable_load_bin_file),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private val deviceFeatureController: DeviceFeatureController by lazy {
        DeviceFeatureModuleEntryPoint.create(
            context = this,
            filesDir = filesDir,
            fileProviderAuthority = "com.example.myapplication.provider",
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            initialTransportProfile = bleTransportProfilePreferences.selectedTransportProfile(),
            appTextResolver = appTextResolver,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedLanguage = appLanguagePreferences.selectedLanguage()
        selectedThemeMode = appThemePreferences.selectedThemeMode()
        applyThemeMode(selectedThemeMode)

        super.onCreate(savedInstanceState)

        deviceFeatureController
        enableEdgeToEdge()
        setContent {
            AppLocalizationEntryPoint.Provide(
                language = selectedLanguage,
                textResolver = appTextResolver,
            ) {
                val uiState = deviceFeatureController.uiState
                AppThemeEntryPoint.Render(themeMode = selectedThemeMode) {
                    AppShellEntryPoint.Render(
                        uiState = uiState,
                        selectedLanguage = selectedLanguage,
                        selectedThemeMode = selectedThemeMode,
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
                            bleTransportProfilePreferences.setSelectedTransportProfile(profile)
                            deviceFeatureController.onTransportProfileSelected(profile)
                        },
                        onLanguageSelect = { language ->
                            appLanguagePreferences.setSelectedLanguage(language)
                            selectedLanguage = language
                        },
                        onThemeModeSelect = { themeMode ->
                            appThemePreferences.setSelectedThemeMode(themeMode)
                            selectedThemeMode = themeMode
                            applyThemeMode(themeMode)
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
                        canShareAllFiles = deviceFeatureController.canShareAllFiles(),
                        canSharePacketFile = deviceFeatureController.canSharePacketFile(),
                        canShareCsvFile = deviceFeatureController.canShareCsvFile(),
                        canShareRawFile = deviceFeatureController.canShareRawFile(),
                        canShareLogFile = deviceFeatureController.canShareLogFile(),
                        onShareAllFiles = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createAllFilesShareIntent(this)
                            }
                        },
                        onSharePacketFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createPacketShareIntent(this)
                            }
                        },
                        onShareCsvFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createCsvShareIntent(this)
                            }
                        },
                        onShareRawFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createRawShareIntent(this)
                            }
                        },
                        onShareLogFile = {
                            prepareShareIntentAsync {
                                deviceFeatureController.createLogShareIntent(this)
                            }
                        },
                        showReplayAction = true,
                        onReplayRequest = { replayFilePickerLauncher.launch("*/*") },
                    )
                }
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
        backgroundExecutor.shutdownNow()

        super.onDestroy()
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun applyThemeMode(themeMode: AppThemeMode) {
        applyAppThemeMode(this, themeMode)
    }

    private fun prepareShareIntentAsync(
        createIntent: () -> android.content.Intent?,
    ) {
        backgroundExecutor.execute {
            val intent = createIntent() ?: return@execute
            runOnUiThread {
                startActivity(intent)
            }
        }
    }
}
