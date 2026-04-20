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
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLocalizationProvider
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.ui.theme.AppThemeMode
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
            canShareCsvFile = true,
            canShareRawFile = false,
            canShareLogFile = true,
        )

        composeRule.onNodeWithText("Export").assertIsEnabled().performClick()

        composeRule.onNodeWithText("Compiled binary").assertIsEnabled()
        composeRule.onNodeWithText("Channel CSV").assertIsEnabled()
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

        composeRule.onNodeWithText("No data for the selected sensor/channel").assertIsDisplayed()
        composeRule.onNodeWithText("Y Reset").assertIsDisplayed()
    }

    @Test
    fun settingsThemeControl_emitsSelectionChanges() {
        var selectedThemeMode = AppThemeMode.SYSTEM
        setShellContent(
            onThemeModeSelect = { themeMode -> selectedThemeMode = themeMode },
        )

        navTab("Settings").performClick()
        composeRule.onNodeWithText("Dark").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(AppThemeMode.DARK, selectedThemeMode)
        }
    }

    private fun setShellContent(
        uiState: DeviceUiState = DeviceUiState(),
        canSharePacketFile: Boolean = false,
        canShareCsvFile: Boolean = false,
        canShareRawFile: Boolean = false,
        canShareLogFile: Boolean = false,
        selectedThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
        onSharePacketFile: () -> Unit = {},
        onShareCsvFile: () -> Unit = {},
        onShareRawFile: () -> Unit = {},
        onShareLogFile: () -> Unit = {},
        onThemeModeSelect: (AppThemeMode) -> Unit = {},
    ) {
        composeRule.setContent {
            AppLocalizationProvider(
                language = AppLanguage.ENGLISH,
                textResolver = AppTextResolver(composeRule.activity) { AppLanguage.ENGLISH },
            ) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        DeviceAppShell(
                            uiState = uiState,
                            selectedLanguage = AppLanguage.ENGLISH,
                            selectedThemeMode = selectedThemeMode,
                            onStartScan = {},
                            onConnect = {},
                            onTransportProfileSelect = {},
                            onLanguageSelect = {},
                            onThemeModeSelect = onThemeModeSelect,
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
                            canShareCsvFile = canShareCsvFile,
                            canShareRawFile = canShareRawFile,
                            canShareLogFile = canShareLogFile,
                            onSharePacketFile = onSharePacketFile,
                            onShareCsvFile = onShareCsvFile,
                            onShareRawFile = onShareRawFile,
                            onShareLogFile = onShareLogFile,
                            showReplayAction = true,
                            onReplayRequest = {},
                        )
                    }
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
