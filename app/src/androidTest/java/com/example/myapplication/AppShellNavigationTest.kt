package com.example.myapplication

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.feature.device.DeviceUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overview_isDefaultDestination() {
        setShellContent()

        composeRule.onNodeWithText("Overview").assertExists()
        composeRule.onNodeWithText("BLE devices").assertIsDisplayed()
        navTab("Overview").assertIsSelected()
        navTab("Charts").assertIsNotSelected()
        navTab("Settings").assertIsNotSelected()
    }

    @Test
    fun bottomNavigation_switchesBetweenDestinations() {
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
        )

        navTab("Charts").performClick()
        navTab("Charts").assertIsSelected()
        navTab("Overview").assertIsNotSelected()
        composeRule.onNodeWithText("Y Reset").assertIsDisplayed()

        navTab("Settings").performClick()
        navTab("Settings").assertIsSelected()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertDoesNotExist()

        navTab("Overview").performClick()
        navTab("Overview").assertIsSelected()
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
    }

    @Test
    fun exportButton_isHiddenOnSettings_andDisabledWithoutFiles() {
        setShellContent()

        composeRule.onNodeWithText("Export").assertExists().assertIsNotEnabled()

        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithText("Export").assertDoesNotExist()
    }

    @Test
    fun exportSheet_showsAvailableActions_andDisablesUnavailableOnes() {
        setShellContent(
            canSharePacketFile = true,
            canShareRawFile = false,
            canShareLogFile = true,
        )

        composeRule.onNodeWithText("Export").assertIsEnabled().performClick()

        composeRule.onNodeWithText("Compiled binary").assertIsEnabled()
        composeRule.onNodeWithText("Raw data stream").assertIsNotEnabled()
        composeRule.onNodeWithText("Session log").assertIsEnabled()
    }

    @Test
    fun exportWorksFromCharts_andDismissesSheetAfterSelection() {
        var packetExportClicks = 0
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
            canSharePacketFile = true,
            onSharePacketFile = { packetExportClicks++ },
        )

        navTab("Charts").performClick()
        composeRule.onNodeWithText("Export").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Compiled binary").assertIsEnabled().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Compiled binary").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, packetExportClicks)
        }
    }

    @Test
    fun chartsDestination_remainsAccessible_withEmptyState() {
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
        )

        navTab("Charts").performClick()

        composeRule.onNodeWithText("Нет данных для выбранного датчика/канала").assertIsDisplayed()
        composeRule.onNodeWithText("Y Reset").assertIsDisplayed()
    }

    private fun setShellContent(
        uiState: DeviceUiState = DeviceUiState(),
        canSharePacketFile: Boolean = false,
        canShareRawFile: Boolean = false,
        canShareLogFile: Boolean = false,
        onSharePacketFile: () -> Unit = {},
        onShareRawFile: () -> Unit = {},
        onShareLogFile: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceAppShell(
                        uiState = uiState,
                        onStartScan = {},
                        onConnect = {},
                        onTransportProfileSelect = {},
                        onDisconnect = {},
                        onSensorSelect = {},
                        onChannelSelect = {},
                        onChartWindowSelect = {},
                        onFollowLiveChange = {},
                        onChartPanLeft = {},
                        onChartPanRight = {},
                        onChartZoomIn = {},
                        onChartZoomOut = {},
                        onChartZoomReset = {},
                        onChartPanGesture = { _ -> },
                        onChartZoomGesture = { _, _ -> },
                        canSharePacketFile = canSharePacketFile,
                        canShareRawFile = canShareRawFile,
                        canShareLogFile = canShareLogFile,
                        onSharePacketFile = onSharePacketFile,
                        onShareRawFile = onShareRawFile,
                        onShareLogFile = onShareLogFile,
                        showReplayAction = true,
                        onReplayRequest = {},
                    )
                }
            }
        }
    }

    private fun navTab(label: String) = composeRule.onNode(
        hasText(label) and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Tab,
        ),
    )
}
