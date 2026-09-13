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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.localization.AppTextResolver
import java.util.Locale
import java.util.UUID

interface BleSessionListener {
    fun onDeviceFound(device: BleDevice)
    fun onPacketReceived(packetFragment: ByteArray)
    fun onPacketReceived(
        packetFragment: ByteArray,
        receivedAtWallClockMillis: Long,
        receivedAtMonotonicNs: Long,
    ) = onPacketReceived(packetFragment)
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
    private val appTextResolver: AppTextResolver,
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
    private var transportProfile = BleTransportProfile.COMPATIBILITY
    @Volatile
    private var activeTransportProfile: BleTransportProfile? = null
    private var negotiatedMtu: Int? = null
    private var negotiatedTxPhy: Int? = null
    private var negotiatedRxPhy: Int? = null
    private var negotiatedConnectionIntervalUnits: Int? = null
    private var negotiatedConnectionLatency: Int? = null
    private var negotiatedSupervisionTimeoutUnits: Int? = null
    private var notificationCount = 0L
    private var notificationGapSampleCount = 0L
    private var notificationGapTotalMillis = 0L
    private var notificationGapMaxMillis = 0L
    private var longNotificationGapCount = 0L
    private var lastNotificationElapsedRealtimeMs: Long? = null
    private var lastLongGapLoggedElapsedRealtimeMs = 0L
    private val transportSummaryRunnable = object : Runnable {
        override fun run() {
            if (sessionState != BleSessionState.CONNECTED || bluetoothGatt == null) return
            emitDiagnostic(
                message = buildTransportSummaryMessage(),
                level = DiagnosticLevel.INFO,
            )
            scanHandler.postDelayed(this, TRANSPORT_SUMMARY_INTERVAL_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_scan_result))
                return
            }

            val address = result.device.address
            if (!discoveredAddresses.add(address)) return

            listener.onDeviceFound(
                BleDevice(
                    name = result.device.name ?: appTextResolver.getString(R.string.ble_unknown_device),
                    address = address,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanHandler.removeCallbacks(stopScanRunnable)
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_scan_failed, describeScanFailure(errorCode)))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    stopTransportSummaryLoop()
                    clearGattReference(gatt)
                    transition(BleSessionEvent.Failure)
                    listener.onError(appTextResolver.getString(R.string.ble_gatt_connection_error, status))
                    safeCloseGatt(gatt)
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    transition(BleSessionEvent.Connected)
                    resetTransportDiagnostics()
                    val activeProfile = activeTransportProfile ?: transportProfile
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        stopTransportSummaryLoop()
                        listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_before_mtu))
                        clearGattReference(gatt)
                        safeCloseGatt(gatt)
                        transition(BleSessionEvent.Failure)
                        return
                    }

                    emitDiagnostic(
                        message = appTextResolver.getString(
                            R.string.ble_transport_profile_message,
                            appTextResolver.getString(activeProfile.titleRes),
                            appTextResolver.getString(activeProfile.shortDescriptionRes),
                        ),
                        level = DiagnosticLevel.INFO,
                    )
                    emitDiagnostic(
                        message = appTextResolver.getString(R.string.ble_best_effort_connection_logging),
                        level = DiagnosticLevel.INFO,
                    )

                    if (activeProfile.requestsHighConnectionPriority) {
                        val priorityRequested = gatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                        )
                        if (priorityRequested) {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_requested_high_priority),
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_failed_high_priority),
                                level = DiagnosticLevel.WARNING,
                            )
                        }
                    } else {
                        emitDiagnostic(
                            message = appTextResolver.getString(R.string.ble_skipped_priority_profile),
                            level = DiagnosticLevel.INFO,
                        )
                    }

                    val requestedMtu = activeProfile.requestedMtu
                    if (requestedMtu != null) {
                        val mtuRequested = gatt.requestMtu(requestedMtu)
                        if (mtuRequested) {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_requested_mtu, requestedMtu),
                                level = DiagnosticLevel.INFO,
                            )
                        } else {
                            emitDiagnostic(
                                message = appTextResolver.getString(R.string.ble_failed_mtu_continue, requestedMtu),
                                level = DiagnosticLevel.WARNING,
                            )
                            requestPreferredPhy(gatt, activeProfile)
                            gatt.discoverServices()
                        }
                    } else {
                        emitDiagnostic(
                            message = appTextResolver.getString(R.string.ble_skipped_explicit_mtu),
                            level = DiagnosticLevel.INFO,
                        )
                        requestPreferredPhy(gatt, activeProfile)
                        gatt.discoverServices()
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    stopTransportSummaryLoop()
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
                stopTransportSummaryLoop()
                listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_after_mtu))
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            emitDiagnostic(
                message = appTextResolver.getString(R.string.ble_mtu_changed, mtu, status),
                level = DiagnosticLevel.INFO,
            )
            negotiatedMtu = mtu
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
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
                message = appTextResolver.getString(R.string.ble_phy_updated, txPhy, rxPhy, status),
                level = DiagnosticLevel.INFO,
            )
            negotiatedTxPhy = txPhy
            negotiatedRxPhy = rxPhy
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
                level = DiagnosticLevel.INFO,
            )
        }

        // This callback is hidden from the public Android SDK stubs, so this is a
        // best-effort runtime hook for debug builds rather than a guaranteed override.
        @Suppress("unused")
        fun onConnectionUpdated(
            gatt: BluetoothGatt,
            interval: Int,
            latency: Int,
            timeout: Int,
            status: Int,
        ) {
            if (bluetoothGatt !== gatt) return

            negotiatedConnectionIntervalUnits = interval
            negotiatedConnectionLatency = latency
            negotiatedSupervisionTimeoutUnits = timeout
            emitDiagnostic(
                message = appTextResolver.getString(
                    R.string.ble_connection_updated,
                    formatConnectionIntervalMillis(interval),
                    latency,
                    timeout * 10,
                    status,
                ),
                level = DiagnosticLevel.INFO,
            )
            emitDiagnostic(
                message = buildTransportSnapshotMessage(),
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
                stopTransportSummaryLoop()
                listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_service_discovery))
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopTransportSummaryLoop()
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_service_discovery_failed, status))
                safeCloseGatt(gatt)
                return
            }

            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            val descriptor = characteristic?.getDescriptor(descriptorUuid)

            if (service == null || characteristic == null || descriptor == null) {
                stopTransportSummaryLoop()
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_required_service_missing))
                safeCloseGatt(gatt)
                return
            }

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                pendingNotificationDescriptorUuid = null
                stopTransportSummaryLoop()
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_failed_register_notification_callback))
                safeCloseGatt(gatt)
                return
            }

            pendingNotificationDescriptorUuid = descriptor.uuid
            val writeResult = writeNotificationDescriptor(gatt, descriptor)

            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                pendingNotificationDescriptorUuid = null
                stopTransportSummaryLoop()
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_failed_enable_notifications, writeResult))
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
                stopTransportSummaryLoop()
                listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_notification_setup))
                clearGattReference(gatt)
                safeCloseGatt(gatt)
                transition(BleSessionEvent.Failure)
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopTransportSummaryLoop()
                clearGattReference(gatt)
                transition(BleSessionEvent.Failure)
                listener.onError(appTextResolver.getString(R.string.ble_descriptor_write_failed, status))
                safeCloseGatt(gatt)
                return
            }

            startTransportSummaryLoop()
            listener.onCaptureReady()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (bluetoothGatt !== gatt) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value?.clone() ?: return
            handleCharacteristicChanged(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (bluetoothGatt !== gatt) return
            handleCharacteristicChanged(value)
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
            listener.onError(appTextResolver.getString(R.string.ble_missing_scan_permission))
            return
        }

        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.bluetooth_adapter_unavailable))
            return
        }

        if (!adapter.isEnabled) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.bluetooth_off))
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_scanner_unavailable))
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
            listener.onError(appTextResolver.getString(R.string.ble_failed_start_scan), exception)
        }
    }

    override fun stopScanning() {
        scanHandler.removeCallbacks(stopScanRunnable)
        if (!hasScanPermission()) {
            transition(BleSessionEvent.ScanStopped)
            listener.onError(appTextResolver.getString(R.string.ble_missing_scan_permission_stop))
            return
        }

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            listener.onError(appTextResolver.getString(R.string.ble_failed_stop_scan), exception)
        }
        transition(BleSessionEvent.ScanStopped)
    }

    override fun connect(address: String) {
        if (!hasConnectPermission()) {
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
            return
        }

        forceCloseCurrentGatt()
        activeTransportProfile = transportProfile

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            activeTransportProfile = null
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_device_not_found, address))
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            transition(BleSessionEvent.ConnectRequested)
        } catch (exception: SecurityException) {
            activeTransportProfile = null
            transition(BleSessionEvent.Failure)
            listener.onError(appTextResolver.getString(R.string.ble_failed_connect_device), exception)
        }
    }

    override fun disconnect() {
        val gatt = bluetoothGatt ?: return
        if (!hasConnectPermission()) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission_disconnect))
            return
        }

        try {
            gatt.disconnect()
        } catch (exception: SecurityException) {
            clearGattReference(gatt)
            safeCloseGatt(gatt)
            transition(BleSessionEvent.Disconnected)
            listener.onError(appTextResolver.getString(R.string.ble_failed_disconnect_device), exception)
        }
    }

    override fun close() {
        stopTransportSummaryLoop()
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
            resetTransportDiagnostics()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPreferredPhy(
        gatt: BluetoothGatt,
        profile: BleTransportProfile,
    ) {
        when (profile.preferredPhy) {
            PreferredPhyMode.LE_2M -> {
                if (!hasConnectPermission()) {
                    listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission))
                    return
                }

                emitDiagnostic(
                    message = appTextResolver.getString(R.string.ble_requested_preferred_phy),
                    level = DiagnosticLevel.INFO,
                )
                try {
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                } catch (exception: SecurityException) {
                    listener.onError(appTextResolver.getString(R.string.ble_missing_connect_permission), exception)
                }
            }

            PreferredPhyMode.SYSTEM_DEFAULT -> {
                emitDiagnostic(
                    message = appTextResolver.getString(R.string.ble_skipped_preferred_phy),
                    level = DiagnosticLevel.INFO,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeNotificationDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
    ): Int {
        if (!hasConnectPermission()) {
            return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
            } else {
                if (!descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    BluetoothStatusCodes.ERROR_UNKNOWN
                } else if (gatt.writeDescriptor(descriptor)) {
                    BluetoothStatusCodes.SUCCESS
                } else {
                    BluetoothStatusCodes.ERROR_UNKNOWN
                }
            }
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
    }

    private fun handleCharacteristicChanged(value: ByteArray) {
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val previousNotificationElapsedRealtimeMs = lastNotificationElapsedRealtimeMs
        lastNotificationElapsedRealtimeMs = nowElapsedRealtimeMs
        notificationCount++
        if (previousNotificationElapsedRealtimeMs != null) {
            val gapMillis = (nowElapsedRealtimeMs - previousNotificationElapsedRealtimeMs).coerceAtLeast(0L)
            notificationGapSampleCount++
            notificationGapTotalMillis += gapMillis
            notificationGapMaxMillis = maxOf(notificationGapMaxMillis, gapMillis)
            val longGapThresholdMillis = longNotificationGapThresholdMillis()
            if (gapMillis >= longGapThresholdMillis) {
                longNotificationGapCount++
                if (nowElapsedRealtimeMs - lastLongGapLoggedElapsedRealtimeMs >= LONG_GAP_LOG_COOLDOWN_MS) {
                    lastLongGapLoggedElapsedRealtimeMs = nowElapsedRealtimeMs
                    emitDiagnostic(
                        message = appTextResolver.getString(
                            R.string.ble_notification_gap,
                            gapMillis,
                            longGapThresholdMillis,
                            notificationCount,
                        ),
                        level = DiagnosticLevel.WARNING,
                    )
                }
            }
        }
        listener.onPacketReceived(
            packetFragment = value.clone(),
            receivedAtWallClockMillis = System.currentTimeMillis(),
            receivedAtMonotonicNs = SystemClock.elapsedRealtimeNanos(),
        )
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

    private fun resetTransportDiagnostics() {
        negotiatedMtu = null
        negotiatedTxPhy = null
        negotiatedRxPhy = null
        negotiatedConnectionIntervalUnits = null
        negotiatedConnectionLatency = null
        negotiatedSupervisionTimeoutUnits = null
        notificationCount = 0L
        notificationGapSampleCount = 0L
        notificationGapTotalMillis = 0L
        notificationGapMaxMillis = 0L
        longNotificationGapCount = 0L
        lastNotificationElapsedRealtimeMs = null
        lastLongGapLoggedElapsedRealtimeMs = 0L
    }

    private fun startTransportSummaryLoop() {
        scanHandler.removeCallbacks(transportSummaryRunnable)
        scanHandler.postDelayed(transportSummaryRunnable, TRANSPORT_SUMMARY_INTERVAL_MS)
    }

    private fun stopTransportSummaryLoop() {
        scanHandler.removeCallbacks(transportSummaryRunnable)
    }

    private fun buildTransportSnapshotMessage(): String {
        val interval = negotiatedConnectionIntervalUnits?.let(::formatConnectionIntervalMillis)
            ?: appTextResolver.getString(R.string.value_not_available)
        val latency = negotiatedConnectionLatency?.toString() ?: appTextResolver.getString(R.string.value_not_available)
        val timeout = negotiatedSupervisionTimeoutUnits?.let { "${it * 10}" }
            ?: appTextResolver.getString(R.string.value_not_available)
        val mtu = negotiatedMtu?.toString() ?: "?"
        val phy = when {
            negotiatedTxPhy == null || negotiatedRxPhy == null -> "?"
            else -> "${describePhy(negotiatedTxPhy)} / ${describePhy(negotiatedRxPhy)}"
        }
        return appTextResolver.getString(
            R.string.ble_transport_snapshot,
            appTextResolver.getString((activeTransportProfile ?: transportProfile).titleRes),
            mtu,
            phy,
            interval,
            latency,
            timeout,
        )
    }

    private fun buildTransportSummaryMessage(): String {
        val averageGapMillis = if (notificationGapSampleCount == 0L) {
            appTextResolver.getString(R.string.value_not_available)
        } else {
            formatMillis(notificationGapTotalMillis.toDouble() / notificationGapSampleCount.toDouble())
        }
        val maxGapMillis = if (notificationGapSampleCount == 0L) {
            appTextResolver.getString(R.string.value_not_available)
        } else {
            "${notificationGapMaxMillis}"
        }
        return appTextResolver.getString(
            R.string.ble_transport_summary,
            buildTransportSnapshotMessage(),
            notificationCount,
            averageGapMillis,
            maxGapMillis,
            longNotificationGapCount,
        )
    }

    private fun longNotificationGapThresholdMillis(): Long {
        val negotiatedIntervalMs = negotiatedConnectionIntervalUnits?.let { it * 1.25 } ?: return DEFAULT_LONG_NOTIFICATION_GAP_MS
        return maxOf(DEFAULT_LONG_NOTIFICATION_GAP_MS, (negotiatedIntervalMs * 4).toLong())
    }

    private fun formatConnectionIntervalMillis(intervalUnits: Int): String {
        return formatMillis(intervalUnits * 1.25)
    }

    private fun formatMillis(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun describePhy(phy: Int?): String {
        return when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "CODED"
            null -> "?"
            else -> phy.toString()
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
            ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
                appTextResolver.getString(R.string.ble_scan_failure_already_started)
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                appTextResolver.getString(R.string.ble_scan_failure_app_registration)
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                appTextResolver.getString(R.string.ble_scan_failure_feature_unsupported)
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                appTextResolver.getString(R.string.ble_scan_failure_internal_error)
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                appTextResolver.getString(R.string.ble_scan_failure_out_of_hw)
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                appTextResolver.getString(R.string.ble_scan_failure_too_frequent)
            else -> appTextResolver.getString(R.string.ble_scan_failure_code, errorCode)
        }
    }

    private companion object {
        const val TAG = "BLE_SESSION"
        const val SCAN_TIMEOUT_MS = 10_000L
        const val TRANSPORT_SUMMARY_INTERVAL_MS = 5_000L
        const val DEFAULT_LONG_NOTIFICATION_GAP_MS = 100L
        const val LONG_GAP_LOG_COOLDOWN_MS = 5_000L
    }
}

private enum class DiagnosticLevel {
    INFO,
    WARNING,
}
