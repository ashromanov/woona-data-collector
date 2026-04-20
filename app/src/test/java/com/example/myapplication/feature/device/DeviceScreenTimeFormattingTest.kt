package com.example.myapplication.feature.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceScreenTimeFormattingTest {
    @Test
    fun formatRelativeRawTimer_clampsNegativeValuesAndKeepsRawUnits() {
        assertEquals("0", formatRelativeRawTimer(-1L))
        assertEquals("1777", formatRelativeRawTimer(1_777L))
        assertEquals("3556", formatRelativeRawTimer(3_556L))
    }

    @Test
    fun buildXAxisTicks_usesRawRelativeTimerLabels() {
        assertEquals(
            listOf(
                TimeAxisTick(timeMillis = 952_989L, label = "0"),
                TimeAxisTick(timeMillis = 954_766L, label = "1777"),
                TimeAxisTick(timeMillis = 956_544L, label = "3555"),
                TimeAxisTick(timeMillis = 958_322L, label = "5333"),
                TimeAxisTick(timeMillis = 960_100L, label = "7111"),
            ),
            buildXAxisTicks(
                viewportStart = 952_989L,
                viewportEnd = 960_100L,
                sessionStart = 952_989L,
            ),
        )
    }
}
