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
    fun onDiagnosticMessage(message: String)
    fun onCaptureReady()
    fun onStateChanged(state: BleSessionState)
    fun onError(message: String, throwable: Throwable? = null)
}

interface BleSessionController {
    fun currentState(): BleSessionState
    fun currentTransportProfile(): BleTransportProfile
    fun updateTransportProfile(profile: BleTransportProfile)
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
    @Volatile
    private var transportProfile = BleTransportProfile.DEFAULT
    @Volatile
    private var activeTransportProfile: BleTransportProfile? = null

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

        override fun onScanFailed(errorCode: Int) {
            scanHandler.removeCallbacks(stopScanRunnable)
            transition(BleSessionEvent.Failure)
            listener.onError("BLE scan failed: ${describeScanFailure(errorCode)}")
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
                    val activeProfile = activeTransportProfile ?: transportProfile
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

                    emitDiagnostic(
                        message = "BLE transport profile: ${activeProfile.title} (${activeProfile.shortDescription})",
                        level = DiagnosticLevel.INFO,
                    )

                    if (activeProfile.requestsHighConnectionPriority) {
                        val priorityRequested = gatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                        )
                        if (priorityRequested) {
                            emitDiagnostic(
                                message = "Requested HIGH BLE connection priority",
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = "Failed to request high BLE connection priority",
                                level = DiagnosticLevel.WARNING,
                            )
                        }
                    } else {
                        emitDiagnostic(
                            message = "Skipped BLE connection priority request due to selected profile",
                            level = DiagnosticLevel.INFO,
                        )
                    }

                    val requestedMtu = activeProfile.requestedMtu
                    if (requestedMtu != null) {
                        val mtuRequested = gatt.requestMtu(requestedMtu)
                        if (mtuRequested) {
                            emitDiagnostic(
                                message = "Requested BLE MTU $requestedMtu",
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = "Failed to request MTU $requestedMtu; continuing with current MTU",
                                level = DiagnosticLevel.WARNING,
                            )
                            requestPreferredPhy(gatt, activeProfile)
                            gatt.discoverServices()
                        }
                    } else {
                        emitDiagnostic(
                            message = "Skipped explicit MTU request due to selected profile",
                            level = DiagnosticLevel.INFO,
                        )
                        requestPreferredPhy(gatt, activeProfile)
                        gatt.discoverServices()
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    clearGattReference(gatt)
                    transition(BleSessionEvent.Disconnected)
                    safeCloseGatt(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (bluetoothGatt !== gatt) return

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

            emitDiagnostic(
                message = "BLE MTU changed: mtu=$mtu status=$status",
                level = DiagnosticLevel.INFO,
            )
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestPreferredPhy(gatt, activeTransportProfile ?: transportProfile)
            }
            gatt.discoverServices()
        }

        override fun onPhyUpdate(
            gatt: BluetoothGatt,
            txPhy: Int,
            rxPhy: Int,
            status: Int,
        ) {
            if (bluetoothGatt !== gatt) return

            emitDiagnostic(
                message = "BLE PHY updated: txPhy=$txPhy rxPhy=$rxPhy status=$status",
                level = DiagnosticLevel.INFO,
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt !== gatt) return

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

    override fun currentTransportProfile(): BleTransportProfile = transportProfile

    override fun updateTransportProfile(profile: BleTransportProfile) {
        transportProfile = profile
    }

    override fun startScanning() {
        if (!hasScanPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError("Missing BLUETOOTH_SCAN permission")
            return
        }

        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError("Missing BLUETOOTH_CONNECT permission")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            transition(BleSessionEvent.Failure)
            listener.onError("Bluetooth adapter unavailable")
            return
        }

        if (!adapter.isEnabled) {
            transition(BleSessionEvent.Failure)
            listener.onError("Bluetooth is turned off")
            return
        }

        val scanner = adapter.bluetoothLeScanner
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
        activeTransportProfile = transportProfile

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            activeTransportProfile = null
            transition(BleSessionEvent.Failure)
            listener.onError("BLE device not found for address: $address")
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            transition(BleSessionEvent.ConnectRequested)
        } catch (exception: SecurityException) {
            activeTransportProfile = null
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
            activeTransportProfile = null
        }
    }

    private fun requestPreferredPhy(
        gatt: BluetoothGatt,
        profile: BleTransportProfile,
    ) {
        when (profile.preferredPhy) {
            PreferredPhyMode.LE_2M -> {
                emitDiagnostic(
                    message = "Requested BLE preferred PHY: LE 2M",
                    level = DiagnosticLevel.INFO,
                )
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }

            PreferredPhyMode.SYSTEM_DEFAULT -> {
                emitDiagnostic(
                    message = "Skipped BLE preferred PHY request due to selected profile",
                    level = DiagnosticLevel.INFO,
                )
            }
        }
    }

    private fun emitDiagnostic(
        message: String,
        level: DiagnosticLevel,
    ) {
        when (level) {
            DiagnosticLevel.INFO -> Log.i(TAG, message)
            DiagnosticLevel.WARNING -> Log.w(TAG, message)
        }
        listener.onDiagnosticMessage(message)
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
            Log.w(TAG, "Failed to close GATT after permission loss", exception)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to close GATT", exception)
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

    private fun describeScanFailure(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "application registration failed"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware resources"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently"
            else -> "code=$errorCode"
        }
    }

    private companion object {
        const val TAG = "BLE_SESSION"
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}

private enum class DiagnosticLevel {
    INFO,
    WARNING,
}
