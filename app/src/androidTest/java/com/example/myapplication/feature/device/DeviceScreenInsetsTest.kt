package com.example.myapplication.feature.device

import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class DeviceScreenInsetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
    }

    @Test
    fun scanScreen_titleStaysBelowTopSystemInsets() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceScreen(
                        uiState = DeviceUiState(),
                        onSensorSelect = {},
                        onChannelSelect = {},
                        onTabSelect = {},
                        onChartWindowSelect = {},
                        onFollowLiveChange = {},
                        onChartPanLeft = {},
                        onChartPanRight = {},
                        onChartZoomIn = {},
                        onChartZoomOut = {},
                        onChartZoomReset = {},
                        onChartPanGesture = { _ -> },
                        onChartZoomGesture = { _, _ -> },
                        onStartScan = {},
                        onConnect = {},
                        onDisconnect = {},
                        onSharePacketFile = {},
                        onShareRawFile = {},
                        onShareLogFile = {},
                        showReplayAction = true,
                        onReplayRequest = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()

        val titleBounds = composeRule
            .onNodeWithText("📡 Поиск BLE-оборудования")
            .assertExists()
            .fetchSemanticsNode()
            .boundsInRoot
        val topInset = topSafeInsetPx()

        assertTrue(
            "Expected title top ${titleBounds.top} to be below top inset $topInset",
            titleBounds.top >= topInset,
        )
    }

    @Test
    fun overviewScreen_actionsStayAboveBottomSystemInsets() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceScreen(
                        uiState = DeviceUiState(showCaptureUi = true),
                        onSensorSelect = {},
                        onChannelSelect = {},
                        onTabSelect = {},
                        onChartWindowSelect = {},
                        onFollowLiveChange = {},
                        onChartPanLeft = {},
                        onChartPanRight = {},
                        onChartZoomIn = {},
                        onChartZoomOut = {},
                        onChartZoomReset = {},
                        onChartPanGesture = { _ -> },
                        onChartZoomGesture = { _, _ -> },
                        onStartScan = {},
                        onConnect = {},
                        onDisconnect = {},
                        onSharePacketFile = {},
                        onShareRawFile = {},
                        onShareLogFile = {},
                        showReplayAction = true,
                        onReplayRequest = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("SEND LOG").assertExists().performScrollTo()

        val sendLogBounds = composeRule
            .onNodeWithText("SEND LOG")
            .fetchSemanticsNode()
            .boundsInRoot
        val rootHeight = composeRule.activity.findViewById<View>(android.R.id.content).height.toFloat()
        val bottomInset = bottomSystemInsetPx()

        assertTrue(
            "Expected button bottom ${sendLogBounds.bottom} to be above bottom-safe bound ${rootHeight - bottomInset}",
            sendLogBounds.bottom <= rootHeight - bottomInset,
        )
    }

    @Test
    fun chartScreen_controlsStayWithinViewportWidth() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceScreen(
                        uiState = DeviceUiState(
                            showCaptureUi = true,
                            selectedTab = DeviceCaptureTab.CHART,
                            chart = ChartUiState(
                                canPanLeft = true,
                                canPanRight = true,
                                canZoomIn = true,
                                canZoomOut = true,
                            ),
                        ),
                        onSensorSelect = {},
                        onChannelSelect = {},
                        onTabSelect = {},
                        onChartWindowSelect = {},
                        onFollowLiveChange = {},
                        onChartPanLeft = {},
                        onChartPanRight = {},
                        onChartZoomIn = {},
                        onChartZoomOut = {},
                        onChartZoomReset = {},
                        onChartPanGesture = { _ -> },
                        onChartZoomGesture = { _, _ -> },
                        onStartScan = {},
                        onConnect = {},
                        onDisconnect = {},
                        onSharePacketFile = {},
                        onShareRawFile = {},
                        onShareLogFile = {},
                        showReplayAction = true,
                        onReplayRequest = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()

        val rootWidth = composeRule.activity.findViewById<View>(android.R.id.content).width.toFloat()
        val presetBounds = composeRule.onNodeWithText("15m").assertExists().fetchSemanticsNode().boundsInRoot
        val resetBounds = composeRule.onNodeWithText("Y Reset").assertExists().fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Expected preset chip right edge ${presetBounds.right} to stay within root width $rootWidth",
            presetBounds.right <= rootWidth,
        )
        assertTrue(
            "Expected reset button right edge ${resetBounds.right} to stay within root width $rootWidth",
            resetBounds.right <= rootWidth,
        )
    }

    private fun topSafeInsetPx(): Int {
        val insets = requireWindowInsets()
        val systemBarTop = insets.getInsets(WindowInsets.Type.systemBars()).top
        val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
        return max(systemBarTop, cutoutTop)
    }

    private fun bottomSystemInsetPx(): Int {
        return requireWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom
    }

    private fun requireWindowInsets(): WindowInsets {
        return composeRule.runOnIdle {
            composeRule.activity.window.decorView.rootWindowInsets
                ?: error("Root window insets were not available")
        }
    }
}
