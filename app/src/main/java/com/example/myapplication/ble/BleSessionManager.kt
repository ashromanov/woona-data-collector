package com.example.myapplication.ble

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

interface BleSessionListener {
    fun onDeviceFound(device: BleDevice)
    fun onPacketReceived(packetFragment: ByteArray)
    fun onCaptureReady()
    fun onStateChanged(state: BleSessionState)
    fun onError(message: String, throwable: Throwable? = null)
}

interface BleSessionController {
    fun currentState(): BleSessionState
    fun startScanning()
    fun stopScanning()
    fun connect(address: String)
    fun disconnect()
    fun close()
}

class BleSessionManager(
    private val context: Context,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID,
    private val descriptorUuid: UUID,
    private val listener: BleSessionListener,
) : BleSessionController {
    private val scanHandler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val discoveredAddresses = linkedSetOf<String>()
    private val stopScanRunnable = Runnable { stopScanning() }

    private var bluetoothGatt: BluetoothGatt? = null
    private var pendingNotificationDescriptorUuid: UUID? = null
    private var sessionState = BleSessionState.IDLE

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError("Missing BLUETOOTH_CONNECT permission during scan result handling")
                return
            }

            val address = result.device.address
            if (!discoveredAddresses.add(address)) return

            listener.onDeviceFound(
                BleDevice(
                    name = result.device.name ?: "Unknown",
                    address = address,
                ),
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    clearGattReference(gatt)
                    transition(BleSessionEvent.Failure)
                    listener.onError("GATT connection error: $status")
                    safeCloseGatt(gatt)
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    transition(BleSessionEvent.Connected)
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        listener.onError("Missing BLUETOOTH_CONNECT permission before MTU request")
                        clearGattReference(gatt)
                        safeCloseGatt(gatt)
                        transition(BleSessionEvent.Failure)
                        return
                    }
                    gatt.requestMtu(512)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    transition(BleSessionEvent.Disconnected)
                    safeCloseGatt(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError("Missing BLUETOOTH_CONNECT permission after MTU change")
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError("Missing BLUETOOTH_CONNECT permission during service discovery")
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError("Service discovery failed: $status")
                safeCloseGatt(gatt)
                return
            }

            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            val descriptor = characteristic?.getDescriptor(descriptorUuid)

            if (service == null || characteristic == null || descriptor == null) {
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError("Required BLE service, characteristic, or descriptor missing")
                safeCloseGatt(gatt)
                return
            }

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                pendingNotificationDescriptorUuid = null
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError("Failed to register local BLE notification callback")
                safeCloseGatt(gatt)
                return
            }

            pendingNotificationDescriptorUuid = descriptor.uuid
            val writeResult = gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            )

            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                pendingNotificationDescriptorUuid = null
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError("Failed to enable BLE notifications: $writeResult")
                safeCloseGatt(gatt)
                return
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (bluetoothGatt !== gatt) return

            val pendingDescriptorUuid = pendingNotificationDescriptorUuid
            if (pendingDescriptorUuid == null || descriptor.uuid != pendingDescriptorUuid) {
                return
            }
            pendingNotificationDescriptorUuid = null

            if (!hasConnectPermission()) {
                listener.onError("Missing BLUETOOTH_CONNECT permission during notification setup")
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError("BLE descriptor write failed: $status")
                safeCloseGatt(gatt)
                return
            }

            listener.onCaptureReady()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onPacketReceived(value.clone())
        }
    }

    override fun currentState(): BleSessionState = sessionState

    override fun startScanning() {
        if (!hasScanPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError("Missing BLUETOOTH_SCAN permission")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            transition(BleSessionEvent.Failure)
            listener.onError("BLE scanner unavailable")
            return
        }

        try {
            discoveredAddresses.clear()
            scanner.startScan(scanCallback)
            scanHandler.removeCallbacks(stopScanRunnable)
            scanHandler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
            transition(BleSessionEvent.ScanStarted)
        } catch (exception: SecurityException) {
            transition(BleSessionEvent.Failure)
            listener.onError("Failed to start BLE scan", exception)
        }
    }

    override fun stopScanning() {
        scanHandler.removeCallbacks(stopScanRunnable)
        if (!hasScanPermission()) {
            transition(BleSessionEvent.ScanStopped)
            listener.onError("Missing BLUETOOTH_SCAN permission while stopping scan")
            return
        }

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            listener.onError("Failed to stop BLE scan", exception)
        }
        transition(BleSessionEvent.ScanStopped)
    }

    override fun connect(address: String) {
        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError("Missing BLUETOOTH_CONNECT permission")
            return
        }

        forceCloseCurrentGatt()

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            transition(BleSessionEvent.Failure)
            listener.onError("BLE device not found for address: $address")
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            transition(BleSessionEvent.ConnectRequested)
        } catch (exception: SecurityException) {
            transition(BleSessionEvent.Failure)
            listener.onError("Failed to connect to BLE device", exception)
        }
    }

    override fun disconnect() {
        val gatt = bluetoothGatt ?: return
        if (!hasConnectPermission()) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError("Missing BLUETOOTH_CONNECT permission while disconnecting")
            return
        }

        try {
            gatt.disconnect()
        } catch (exception: SecurityException) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError("Failed to disconnect BLE device", exception)
        }
    }

    override fun close() {
        stopScanning()
        forceCloseCurrentGatt()
    }

    private fun transition(event: BleSessionEvent) {
        sessionState = BleSessionStateMachine.transition(sessionState, event)
        listener.onStateChanged(sessionState)
    }

    private fun clearGattReference(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
            pendingNotificationDescriptorUuid = null
        }
    }

    private fun forceCloseCurrentGatt() {
        val gatt = bluetoothGatt ?: return
        clearGattReference(gatt)
        safeCloseGatt(gatt)
        transition(BleSessionEvent.Disconnected)
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (exception: SecurityException) {
            Log.w("BLE_SESSION", "Failed to close GATT after permission loss", exception)
        } catch (exception: Exception) {
            Log.w("BLE_SESSION", "Failed to close GATT", exception)
        }
    }

    private fun hasScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}
