package com.example.myapplication.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.myapplication.protocol.parsePolarAcceleration
import com.example.myapplication.protocol.parsePolarEcg
import com.example.myapplication.protocol.parsePolarHeartRate
import com.example.myapplication.protocol.PolarAccelerationFrame
import com.example.myapplication.protocol.PolarEcgFrame
import com.example.myapplication.protocol.PolarHeartRateFrame
import java.util.UUID

enum class PolarConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    READY,
    RECORDING,
    LOST,
    FAILED,
}

data class PolarStatus(
    val state: PolarConnectionState = PolarConnectionState.IDLE,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val heartRateBpm: Int? = null,
    val ecgFrames: Long = 0L,
    val accFrames: Long = 0L,
    val lastDataAtMillis: Long? = null,
    val message: String? = null,
)

class PolarSessionManager(
    context: Context,
    private val onDeviceFound: (BleDevice) -> Unit,
    private val onStatus: (PolarStatus) -> Unit,
    private val onHeartRate: (PolarHeartRateFrame, Long) -> Unit,
    private val onEcg: (PolarEcgFrame, Long) -> Unit,
    private val onAcceleration: (PolarAccelerationFrame, Long) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter by lazy {
        appContext.getSystemService(BluetoothManager::class.java).adapter
    }
    private val foundAddresses = linkedSetOf<String>()
    private val stopScanRunnable = Runnable { stopScanning() }
    private var gatt: BluetoothGatt? = null
    private var setupStep = SetupStep.NONE
    private var status = PolarStatus()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            val name = result.device.name ?: return
            if (!name.startsWith("Polar", ignoreCase = true) || !foundAddresses.add(result.device.address)) return
            onDeviceFound(BleDevice(name, result.device.address))
        }

        override fun onScanFailed(errorCode: Int) {
            fail("Polar scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, result: Int, newState: Int) {
            if (this@PolarSessionManager.gatt !== gatt) {
                runCatching { gatt.close() }
                return
            }
            when {
                result != BluetoothGatt.GATT_SUCCESS -> fail("Polar GATT error: $result", closeGatt = gatt)
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        fail("Bluetooth permission is required", closeGatt = gatt)
                        return
                    }
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.discoverServices()) fail("Polar service discovery did not start", closeGatt = gatt)
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    clearGatt(gatt)
                    updateStatus(PolarConnectionState.LOST, message = "Polar disconnected")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, result: Int) {
            if (result != BluetoothGatt.GATT_SUCCESS || characteristic(gatt, HR_SERVICE, HR_CHARACTERISTIC) == null ||
                characteristic(gatt, PMD_SERVICE, PMD_DATA) == null ||
                characteristic(gatt, PMD_SERVICE, PMD_CONTROL) == null
            ) {
                fail("Polar HR/PMD services are unavailable", closeGatt = gatt)
                return
            }
            setupStep = SetupStep.HR_NOTIFY
            enableNotifications(gatt, HR_SERVICE, HR_CHARACTERISTIC)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) {
            if (result != BluetoothGatt.GATT_SUCCESS) {
                fail("Polar notification setup failed: $result", closeGatt = gatt)
                return
            }
            when (setupStep) {
                SetupStep.HR_NOTIFY -> {
                    setupStep = SetupStep.PMD_DATA_NOTIFY
                    enableNotifications(gatt, PMD_SERVICE, PMD_DATA)
                }
                SetupStep.PMD_DATA_NOTIFY -> {
                    setupStep = SetupStep.PMD_CONTROL_NOTIFY
                    enableNotifications(gatt, PMD_SERVICE, PMD_CONTROL)
                }
                SetupStep.PMD_CONTROL_NOTIFY -> readBatteryOrStartEcg(gatt)
                else -> Unit
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
            handleBatteryRead(gatt, characteristic.uuid, characteristic.value, result)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            result: Int,
        ) {
            handleBatteryRead(gatt, characteristic.uuid, value, result)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
            if (result != BluetoothGatt.GATT_SUCCESS) {
                fail("Polar PMD command failed: $result", closeGatt = gatt)
                return
            }
            when (setupStep) {
                SetupStep.START_ECG -> {
                    setupStep = SetupStep.START_ACC
                    handler.postDelayed(
                        {
                            if (this@PolarSessionManager.gatt === gatt && setupStep == SetupStep.START_ACC) {
                                writeControl(gatt, START_ACC)
                            }
                        },
                        600L,
                    )
                }
                SetupStep.START_ACC -> {
                    setupStep = SetupStep.NONE
                    updateStatus(PolarConnectionState.READY, message = null)
                }
                else -> Unit
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            fail("Bluetooth permission is required")
            return
        }
        stopScanning()
        foundAddresses.clear()
        status = PolarStatus(state = PolarConnectionState.SCANNING)
        onStatus(status)
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback) ?: fail("Bluetooth scanner is unavailable")
        handler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MILLIS)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        handler.removeCallbacks(stopScanRunnable)
        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            runCatching { bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        if (status.state == PolarConnectionState.SCANNING) updateStatus(PolarConnectionState.IDLE, message = null)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            fail("Bluetooth permission is required")
            return
        }
        stopScanning()
        disconnect()
        status = PolarStatus(state = PolarConnectionState.CONNECTING, deviceName = name ?: "Polar H10")
        onStatus(status)
        runCatching {
            gatt = bluetoothAdapter.getRemoteDevice(address)
                .connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure { fail("Unable to connect Polar", it) }
    }

    fun setRecordingActive(active: Boolean) {
        if (active && status.state == PolarConnectionState.READY) {
            updateStatus(PolarConnectionState.RECORDING, message = null)
        } else if (!active && status.state == PolarConnectionState.RECORDING) {
            updateStatus(PolarConnectionState.READY, message = null)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanning()
        val current = gatt
        gatt = null
        if (current != null) runCatching { current.disconnect(); current.close() }
        setupStep = SetupStep.NONE
        status = PolarStatus()
        onStatus(status)
    }

    override fun close() = disconnect()

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, serviceUuid: UUID, characteristicUuid: UUID) {
        val characteristic = characteristic(gatt, serviceUuid, characteristicUuid)
            ?: return fail("Polar characteristic is unavailable", closeGatt = gatt)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG)
            ?: return fail("Polar notification descriptor is unavailable", closeGatt = gatt)
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail("Polar notification callback could not be enabled", closeGatt = gatt)
            return
        }
        @Suppress("DEPRECATION")
        descriptor.value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        @Suppress("DEPRECATION")
        if (!gatt.writeDescriptor(descriptor)) fail("Polar notification setup did not start", closeGatt = gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryOrStartEcg(gatt: BluetoothGatt) {
        val battery = characteristic(gatt, BATTERY_SERVICE, BATTERY_CHARACTERISTIC)
        setupStep = SetupStep.BATTERY_READ
        if (battery == null || !gatt.readCharacteristic(battery)) startEcg(gatt)
    }

    private fun handleBatteryRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, result: Int) {
        if (setupStep != SetupStep.BATTERY_READ || uuid != BATTERY_CHARACTERISTIC) return
        if (result == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
            status = status.copy(batteryPercent = value[0].toInt() and 0xFF)
            onStatus(status)
        }
        startEcg(gatt)
    }

    private fun startEcg(gatt: BluetoothGatt) {
        setupStep = SetupStep.START_ECG
        writeControl(gatt, START_ECG)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeControl(gatt: BluetoothGatt, value: ByteArray) {
        val control = characteristic(gatt, PMD_SERVICE, PMD_CONTROL)
            ?: return fail("Polar PMD control is unavailable", closeGatt = gatt)
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.value = value
        if (!gatt.writeCharacteristic(control)) fail("Polar PMD command did not start", closeGatt = gatt)
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            HR_CHARACTERISTIC -> recordHeartRate(value)
            PMD_DATA -> when (value.firstOrNull()?.toInt()) {
                0 -> recordEcg(value)
                2 -> recordAcc(value)
            }
        }
    }

    private fun recordHeartRate(value: ByteArray) {
        val frame = parsePolarHeartRate(value) ?: return
        val now = System.currentTimeMillis()
        onHeartRate(frame, now)
        status = status.copy(heartRateBpm = frame.beatsPerMinute, lastDataAtMillis = now)
        onStatus(status)
    }

    private fun recordEcg(value: ByteArray) {
        val frame = parsePolarEcg(value) ?: return
        val now = System.currentTimeMillis()
        onEcg(frame, now)
        status = status.copy(ecgFrames = status.ecgFrames + 1, lastDataAtMillis = now)
        onStatus(status)
    }

    private fun recordAcc(value: ByteArray) {
        val frame = parsePolarAcceleration(value) ?: return
        val now = System.currentTimeMillis()
        onAcceleration(frame, now)
        status = status.copy(accFrames = status.accFrames + 1, lastDataAtMillis = now)
        onStatus(status)
    }

    private fun updateStatus(
        state: PolarConnectionState,
        deviceName: String? = status.deviceName,
        message: String? = status.message,
    ) {
        status = status.copy(state = state, deviceName = deviceName, message = message)
        onStatus(status)
    }

    private fun fail(message: String, throwable: Throwable? = null, closeGatt: BluetoothGatt? = null) {
        closeGatt?.let(::clearGatt)
        updateStatus(PolarConnectionState.FAILED, message = message)
        onError(message, throwable)
    }

    @SuppressLint("MissingPermission")
    private fun clearGatt(target: BluetoothGatt) {
        if (gatt === target) gatt = null
        runCatching { target.close() }
        setupStep = SetupStep.NONE
    }

    private fun characteristic(gatt: BluetoothGatt, service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt.getService(service)?.getCharacteristic(characteristic)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private enum class SetupStep {
        NONE,
        HR_NOTIFY,
        PMD_DATA_NOTIFY,
        PMD_CONTROL_NOTIFY,
        BATTERY_READ,
        START_ECG,
        START_ACC,
    }

    companion object {
        private const val SCAN_TIMEOUT_MILLIS = 10_000L
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val PMD_SERVICE = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
        private val PMD_CONTROL = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
        private val PMD_DATA = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")
        private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_CHARACTERISTIC = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHARACTERISTIC = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val START_ECG = byteArrayOf(2, 0, 0, 1, 0x82.toByte(), 0, 1, 1, 0x0e, 0)
        private val START_ACC = byteArrayOf(2, 2, 0, 1, 0xc8.toByte(), 0, 1, 1, 0x10, 0, 2, 1, 8, 0)
    }
}
