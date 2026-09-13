package com.example.myapplication

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.DogProfile
import com.example.myapplication.data.DogQuestionnaire
import com.example.myapplication.ble.PolarConnectionState
import com.example.myapplication.ble.PolarStatus
import com.example.myapplication.feature.device.VideoCaptureState
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.profile.ProfilesOverview
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualCaptureScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recordingCaptureCard_fitsViewport() {
        capture(VideoCaptureState.RECORDING, "visual-capture-recording.png")
        composeRule.onNodeWithText("Sensor and video are recording").assertIsDisplayed()
        composeRule.onNodeWithText("Stop session").assertIsDisplayed()
        composeRule.onNodeWithText("ECG 12", substring = true).assertIsDisplayed()
    }

    private fun capture(state: VideoCaptureState, fileName: String) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ProfilesOverview(
                            profiles = listOf(profile),
                            selectedProfileId = profile.id,
                            recordings = emptyList(),
                            language = AppLanguage.ENGLISH,
                            onSelectProfile = {},
                            onCreateProfile = {},
                            onEditProfile = {},
                            onShareRecording = {},
                            videoState = state,
                            videoOffsetMillis = 12.345,
                            onVideoAction = {},
                            polarStatus = PolarStatus(
                                state = PolarConnectionState.RECORDING,
                                heartRateBpm = 72,
                                ecgFrames = 12,
                                accFrames = 10,
                            ),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("Synchronized session").performScrollTo()
        composeRule.waitForIdle()
        val directory = checkNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        )
        val screenshot = File(directory, fileName)
        screenshot.outputStream().use { output ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            ))
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cp ${screenshot.absolutePath} /sdcard/Download/$fileName",
            ),
        ).use { it.readBytes() }
    }

    private companion object {
        val profile = DogProfile(
            id = "visual-dog",
            profileVersionId = "visual-profile",
            numberOrName = "Visual Rex",
            questionnaire = DogQuestionnaire(numberOrName = "Visual Rex"),
            validationState = "complete",
            createdAtUtc = "2026-07-30T20:00:00Z",
            updatedAtUtc = "2026-07-30T20:00:00Z",
        )
    }
}
