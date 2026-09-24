package com.example.myapplication.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRotationTest {
    @Test
    fun physicalOrientationControlsRecordedVideoEvenWhenDisplayStaysPortrait() {
        assertEquals(90, videoRotationDegrees(90, false, 0))
        assertEquals(0, videoRotationDegrees(90, false, 90))
        assertEquals(180, videoRotationDegrees(90, false, 270))
        assertEquals(0, videoRotationDegrees(270, true, 90))
    }
}
