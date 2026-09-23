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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.feature.device.ChartPoint
import com.example.myapplication.feature.device.ChartUiState
import com.example.myapplication.feature.device.DeviceUiState
import com.example.myapplication.feature.device.ExportPhase
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.AppLocalizationProvider
import com.example.myapplication.localization.AppTextResolver
import com.example.myapplication.profile.DogQuestionnaireDialog
import com.example.myapplication.profile.SessionQuestionnaireDialog
import com.example.myapplication.sync.ServerSyncUiState
import com.example.myapplication.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val CHART_FULLSCREEN_DIALOG_TEST_TAG = "chart_fullscreen_dialog"

@RunWith(AndroidJUnit4::class)
class AppShellNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overview_isDefaultDestination() {
        setShellContent()

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
        composeRule.onNodeWithText("Y Reset").scrollToIfNeeded().assertIsDisplayed()

        navTab("Settings").performClick()
        navTab("Settings").assertIsSelected()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(1)
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
            canShareAllFiles = true,
            canSharePacketFile = true,
            canShareCsvFile = true,
            canShareRawFile = false,
            canShareLogFile = true,
        )

        composeRule.onNodeWithText("Export").assertIsEnabled().performClick()

        composeRule.onNodeWithText("Session ZIP").assertIsEnabled()
        composeRule.onNodeWithText("Compiled binary").assertIsEnabled()
        composeRule.onNodeWithText("Channel CSV").assertIsEnabled()
        composeRule.onNodeWithText("Raw data stream").assertIsNotEnabled()
        composeRule.onNodeWithText("Session log").assertIsEnabled()
    }

    @Test
    fun exportWorksFromCharts_andDismissesSheetAfterSelection() {
        var exportAllClicks = 0
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
            canShareAllFiles = true,
            onShareAllFiles = { exportAllClicks++ },
        )

        navTab("Charts").performClick()
        composeRule.onNodeWithText("Export").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Session ZIP").assertIsEnabled().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Session ZIP").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, exportAllClicks)
        }
    }

    @Test
    fun exportProgress_overlayIsVisibleAndDisablesExportButton() {
        setShellContent(
            uiState = DeviceUiState(
                showCaptureUi = true,
                exportPhase = ExportPhase.GENERATING_CSV,
            ),
            canShareAllFiles = true,
        )

        composeRule.onNodeWithText("Export").assertExists().assertIsNotEnabled()
        composeRule.onNodeWithText("Preparing export").assertIsDisplayed()
        composeRule.onNodeWithText("Generating CSV").assertIsDisplayed()
    }

    @Test
    fun chartsDestination_remainsAccessible_withEmptyState() {
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
        )

        navTab("Charts").performClick()

        composeRule.onNodeWithText("Y Reset").scrollToIfNeeded().assertIsDisplayed()
        composeRule
            .onNodeWithText("No data for the selected sensor/channel")
            .scrollToIfNeeded()
            .assertIsDisplayed()
    }

    @Test
    fun chartsFullscreen_opensAndClosesOverlay() {
        setShellContent(
            uiState = DeviceUiState(
                showCaptureUi = true,
                chart = ChartUiState(
                    points = listOf(
                        ChartPoint(timeMillis = 0L, value = 10f),
                        ChartPoint(timeMillis = 1_000L, value = 20f),
                    ),
                    viewportStartMillis = 0L,
                    viewportEndMillis = 1_000L,
                    sessionStartMillis = 0L,
                    latestPointMillis = 1_000L,
                ),
            ),
        )

        navTab("Charts").performClick()
        composeRule.onNodeWithContentDescription("Open fullscreen chart").performClick()

        composeRule.onNodeWithText("Fullscreen chart").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close fullscreen chart").performClick()

        composeRule.onNodeWithText("Fullscreen chart").assertDoesNotExist()
    }

    @Test
    fun chartsFullscreen_supportsEmptyChartState() {
        setShellContent(
            uiState = DeviceUiState(showCaptureUi = true),
        )

        navTab("Charts").performClick()
        composeRule.onNodeWithContentDescription("Open fullscreen chart").performClick()

        composeRule.onNodeWithText("Fullscreen chart").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag(CHART_FULLSCREEN_DIALOG_TEST_TAG) and
                hasAnyDescendant(hasText("No data for the selected sensor/channel")),
            useUnmergedTree = true,
        )
            .assertExists()
    }

    @Test
    fun settingsThemeControl_emitsSelectionChanges() {
        var selectedThemeMode = AppThemeMode.SYSTEM
        setShellContent(
            onThemeModeSelect = { themeMode -> selectedThemeMode = themeMode },
        )

        navTab("Settings").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("Dark").performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(AppThemeMode.DARK, selectedThemeMode)
        }
    }

    @Test
    fun serverConfiguration_isAvailableFromSettings() {
        setShellContent()
        navTab("Settings").performClick()

        composeRule.onNode(hasScrollAction()).performScrollToIndex(3)
        composeRule.onNodeWithText("Server storage").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").assertIsEnabled()
    }

    @Test
    fun requiredDogProfile_cannotBeSkipped() {
        var savedProfiles = 0
        composeRule.setContent {
            MaterialTheme {
                DogQuestionnaireDialog(
                    initial = null,
                    required = true,
                    language = AppLanguage.ENGLISH,
                    onDismiss = {},
                    onSave = { savedProfiles++ },
                )
            }
        }

        composeRule.onNodeWithText("Dog questionnaire").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText("Validate and save").performClick()
        composeRule.runOnIdle {
            assertEquals(0, savedProfiles)
        }
        composeRule.onNodeWithText("Answered: 0 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("2 fields need attention").assertIsDisplayed()
        composeRule.onNodeWithText("Номер/ID животного *").assertIsDisplayed()
        composeRule.onNodeWithText("Dog questionnaire").assertIsDisplayed()
    }

    @Test
    fun sheetSession_supportsMultipleRecordingTypes() {
        var saved: com.example.myapplication.data.SessionQuestionnaire? = null
        composeRule.setContent {
            MaterialTheme {
                SessionQuestionnaireDialog(
                    language = AppLanguage.ENGLISH,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithText("Номер сессии *").performTextInput("1")
        composeRule.onNodeWithText("Аллюр/движение").performScrollTo().performClick()
        composeRule.onNodeWithText("Активность").performScrollTo().performClick()
        composeRule.onNodeWithText("Validate and save").performClick()
        composeRule.runOnIdle { assertEquals(listOf("Аллюр/движение", "Активность"), saved?.plannedActivities) }
    }

    @Test
    fun sheetSession_preservesMultipleSurfacesAcrossLocationChange() {
        var saved: com.example.myapplication.data.SessionQuestionnaire? = null
        composeRule.setContent {
            MaterialTheme {
                SessionQuestionnaireDialog(
                    language = AppLanguage.ENGLISH,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithText("Номер сессии *").performTextInput("2")
        composeRule.onNodeWithText("Аллюр/движение").performScrollTo().performClick()
        composeRule.onNodeWithText("На улице").performScrollTo().performClick()
        composeRule.onNodeWithText("Асфальт").performScrollTo().performClick()
        composeRule.onNodeWithText("Трава").performScrollTo().performClick()
        composeRule.onNodeWithText("В помещении").performScrollTo().performClick()
        composeRule.onNodeWithText("Validate and save").performClick()
        composeRule.runOnIdle { assertEquals(listOf("Асфальт", "Трава"), saved?.surfaces) }
    }

    @Test
    fun sessionDraft_survivesSavedStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                SessionQuestionnaireDialog(
                    language = AppLanguage.ENGLISH,
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
        composeRule.onNodeWithText("Номер сессии *").performTextInput("Draft session")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Draft session").assertIsDisplayed()
    }

    private fun setShellContent(
        uiState: DeviceUiState = DeviceUiState(),
        canShareAllFiles: Boolean = false,
        canSharePacketFile: Boolean = false,
        canShareCsvFile: Boolean = false,
        canShareRawFile: Boolean = false,
        canShareLogFile: Boolean = false,
        selectedThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
        serverSyncState: ServerSyncUiState = ServerSyncUiState(),
        onShareAllFiles: () -> Unit = {},
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
                            serverSyncState = serverSyncState,
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
                            canShareAllFiles = canShareAllFiles,
                            canSharePacketFile = canSharePacketFile,
                            canShareCsvFile = canShareCsvFile,
                            canShareRawFile = canShareRawFile,
                            canShareLogFile = canShareLogFile,
                            onShareAllFiles = onShareAllFiles,
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

private fun SemanticsNodeInteraction.scrollToIfNeeded(): SemanticsNodeInteraction = apply {
    try {
        assertIsDisplayed()
    } catch (_: AssertionError) {
        performScrollTo()
    }
}
