package com.example.myapplication.feature.device

import com.example.myapplication.ble.BleDevice
import com.example.myapplication.ble.BleSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiStateHolderTest {
    @Test
    fun addFoundDevice_ignoresDuplicateAddresses() {
        val holder = DeviceUiStateHolder()

        holder.addFoundDevice(BleDevice(name = "One", address = "AA:BB"))
        holder.addFoundDevice(BleDevice(name = "Two", address = "AA:BB"))

        assertEquals(1, holder.uiState.foundDevices.size)
        assertEquals("One", holder.uiState.foundDevices.single().name)
    }

    @Test
    fun onSensorTypeSelected_resetsChannelAndChartPoints() {
        val holder = DeviceUiStateHolder()

        holder.onChannelSelected(3)
        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 1,
                packetsLost = 0,
                packetsRejected = 0,
                timerRegressionRejects = 0,
                chartSamples = listOf(1f, 2f, 3f),
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(
                        id = 1L,
                        type = PacketDiagnosticType.ACCEPTED,
                        message = "Accepted packet counter=1",
                    ),
                ),
            ),
        )

        holder.onSensorTypeSelected(4)

        assertEquals(4, holder.uiState.selectedSensorType)
        assertEquals(1, holder.uiState.selectedChannel)
        assertEquals(emptyList<Float>(), holder.uiState.points)
    }

    @Test
    fun onSessionStateChanged_disconnectedClearsCaptureState() {
        val holder = DeviceUiStateHolder()

        holder.applyPacketUpdate(
            PacketProcessingUpdate(
                packetsReceived = 5,
                packetsLost = 2,
                packetsRejected = 1,
                timerRegressionRejects = 1,
                chartSamples = listOf(1f, 2f),
                diagnosticEvents = listOf(
                    PacketDiagnosticEvent(
                        id = 2L,
                        type = PacketDiagnosticType.REJECTED,
                        message = "Rejected packet reason=test",
                    ),
                ),
            ),
        )
        holder.showError("boom")

        holder.onSessionStateChanged(BleSessionState.CONNECTED)
        holder.onSessionStateChanged(BleSessionState.DISCONNECTED)

        assertEquals(0L, holder.uiState.packetsReceived)
        assertEquals(0L, holder.uiState.packetsLost)
        assertEquals(0L, holder.uiState.packetsRejected)
        assertEquals(0L, holder.uiState.timerRegressionRejects)
        assertEquals(emptyList<Float>(), holder.uiState.points)
        assertTrue(holder.uiState.diagnosticEvents.isEmpty())
        assertNull(holder.uiState.errorMessage)
    }

    @Test
    fun onSessionStateChanged_ignoresBleTransitionsWhileReplayIsRunning() {
        val holder = DeviceUiStateHolder()

        holder.startReplaySession()
        holder.onSessionStateChanged(BleSessionState.DISCONNECTED)

        assertTrue(holder.uiState.showCaptureUi)
        assertTrue(holder.uiState.isReplayRunning)
    }
}
