package com.example.myapplication.ble

object BleSessionStateMachine {
    fun transition(
        currentState: BleSessionState,
        event: BleSessionEvent,
    ): BleSessionState {
        return when (event) {
            BleSessionEvent.ScanStarted -> BleSessionState.SCANNING
            BleSessionEvent.ScanStopped -> {
                if (currentState == BleSessionState.CONNECTED) {
                    BleSessionState.CONNECTED
                } else {
                    BleSessionState.IDLE
                }
            }

            BleSessionEvent.ConnectRequested -> BleSessionState.CONNECTING
            BleSessionEvent.Connected -> BleSessionState.CONNECTED
            BleSessionEvent.Disconnected -> BleSessionState.DISCONNECTED
            BleSessionEvent.Failure -> BleSessionState.FAILED
        }
    }
}
