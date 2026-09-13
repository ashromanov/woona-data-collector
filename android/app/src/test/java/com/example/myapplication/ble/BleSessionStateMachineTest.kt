package com.example.myapplication.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleSessionStateMachineTest {
    @Test
    fun scanStarted_movesToScanning() {
        val state = BleSessionStateMachine.transition(
            BleSessionState.IDLE,
            BleSessionEvent.ScanStarted,
        )

        assertEquals(BleSessionState.SCANNING, state)
    }

    @Test
    fun connectRequested_movesToConnecting() {
        val state = BleSessionStateMachine.transition(
            BleSessionState.SCANNING,
            BleSessionEvent.ConnectRequested,
        )

        assertEquals(BleSessionState.CONNECTING, state)
    }

    @Test
    fun connected_movesToConnected() {
        val state = BleSessionStateMachine.transition(
            BleSessionState.CONNECTING,
            BleSessionEvent.Connected,
        )

        assertEquals(BleSessionState.CONNECTED, state)
    }

    @Test
    fun disconnected_movesToDisconnected() {
        val state = BleSessionStateMachine.transition(
            BleSessionState.CONNECTED,
            BleSessionEvent.Disconnected,
        )

        assertEquals(BleSessionState.DISCONNECTED, state)
    }

    @Test
    fun failure_movesToFailed() {
        val state = BleSessionStateMachine.transition(
            BleSessionState.CONNECTING,
            BleSessionEvent.Failure,
        )

        assertEquals(BleSessionState.FAILED, state)
    }
}
