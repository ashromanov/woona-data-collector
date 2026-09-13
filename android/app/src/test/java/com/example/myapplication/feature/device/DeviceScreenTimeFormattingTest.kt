package com.example.myapplication.feature.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceScreenTimeFormattingTest {
    @Test
    fun relativeTimeParts_truncatesToWholeSecondsForChartLabels() {
        assertEquals(
            RelativeTimeParts(minutes = 0L, seconds = 0L, showMinutes = false),
            relativeTimeParts(999L),
        )
        assertEquals(
            RelativeTimeParts(minutes = 0L, seconds = 1L, showMinutes = false),
            relativeTimeParts(1_777L),
        )
        assertEquals(
            RelativeTimeParts(minutes = 0L, seconds = 3L, showMinutes = false),
            relativeTimeParts(3_556L),
        )
        assertEquals(
            RelativeTimeParts(minutes = 1L, seconds = 1L, showMinutes = true),
            relativeTimeParts(61_999L),
        )
    }

    @Test
    fun buildXAxisTickModels_preservesTickPositionsAndRelativeOffsets() {
        assertEquals(
            listOf(
                TimeAxisTickModel(timeMillis = 952_989L, relativeMillis = 0L),
                TimeAxisTickModel(timeMillis = 954_766L, relativeMillis = 1_777L),
                TimeAxisTickModel(timeMillis = 956_544L, relativeMillis = 3_555L),
                TimeAxisTickModel(timeMillis = 958_322L, relativeMillis = 5_333L),
                TimeAxisTickModel(timeMillis = 960_100L, relativeMillis = 7_111L),
            ),
            buildXAxisTickModels(
                viewportStart = 952_989L,
                viewportEnd = 960_100L,
                sessionStart = 952_989L,
            ),
        )
    }
}
