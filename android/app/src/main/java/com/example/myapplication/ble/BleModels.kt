package com.example.myapplication.ble

data class BleDevice(
    val name: String,
    val address: String,
)

enum class BleSessionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

sealed interface BleSessionEvent {
    data object ScanStarted : BleSessionEvent
    data object ScanStopped : BleSessionEvent
    data object ConnectRequested : BleSessionEvent
    data object Connected : BleSessionEvent
    data object Disconnected : BleSessionEvent
    data object Failure : BleSessionEvent
}
