package com.example.myapplication.feature.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.appStringResource
import com.example.myapplication.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceOverviewScreen(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            item {
                ErrorCard(message = uiState.errorMessage)
            }
        }

        if (shouldShowSessionSummary(uiState)) {
            item {
                SessionSummaryCard(uiState = uiState, compact = false)
            }
        }

        if (!uiState.showCaptureUi && !uiState.isReplayRunning) {
            item {
                DeviceDiscoverySection(
                    uiState = uiState,
                    onStartScan = onStartScan,
                    onConnect = onConnect,
                    showReplayAction = showReplayAction,
                    onReplayRequest = onReplayRequest,
                )
            }
        } else {
            item {
                DisconnectCard(onDisconnect = onDisconnect)
            }
        }

        item {
            EventLogSection(uiState = uiState)
        }
    }
}

@Composable
fun DeviceChartsScreen(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            ErrorCard(message = uiState.errorMessage)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            ChartTab(
                uiState = uiState,
                onSensorSelect = onSensorSelect,
                onChannelSelect = onChannelSelect,
                onChartWindowSelect = onChartWindowSelect,
                onFollowLiveChange = onFollowLiveChange,
                onChartPanLeft = onChartPanLeft,
                onChartPanRight = onChartPanRight,
                onChartZoomIn = onChartZoomIn,
                onChartZoomOut = onChartZoomOut,
                onChartZoomReset = onChartZoomReset,
                onChartPanGesture = onChartPanGesture,
                onChartZoomGesture = onChartZoomGesture,
            )
        }
    }
}

@Composable
fun DeviceSettingsScreen(
    uiState: DeviceUiState,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onThemeModeSelect: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            BleTransportProfileCard(
                selectedProfile = uiState.transportProfile,
                onTransportProfileSelect = onTransportProfileSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            ThemeSettingsCard(
                selectedThemeMode = selectedThemeMode,
                onThemeModeSelect = onThemeModeSelect,
            )
        }

        item {
            LanguageSettingsCard(
                selectedLanguage = selectedLanguage,
                onLanguageSelect = onLanguageSelect,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onTabSelect: (DeviceCaptureTab) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            ErrorCard(message = uiState.errorMessage)
        }

        if (!uiState.showCaptureUi) {
            ScanSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                uiState = uiState,
                onStartScan = onStartScan,
                onConnect = onConnect,
                onTransportProfileSelect = onTransportProfileSelect,
                showReplayAction = showReplayAction,
                onReplayRequest = onReplayRequest,
            )
        } else {
            CaptureTabs(
                selectedTab = uiState.selectedTab,
                onTabSelect = onTabSelect,
            )

            Spacer(Modifier.height(8.dp))

            when (uiState.selectedTab) {
                DeviceCaptureTab.OVERVIEW -> OverviewTab(
                    uiState = uiState,
                    onTransportProfileSelect = onTransportProfileSelect,
                    onDisconnect = onDisconnect,
                    onSharePacketFile = onSharePacketFile,
                    onShareRawFile = onShareRawFile,
                    onShareLogFile = onShareLogFile,
                )

                DeviceCaptureTab.CHART -> ChartTab(
                    uiState = uiState,
                    onSensorSelect = onSensorSelect,
                    onChannelSelect = onChannelSelect,
                    onChartWindowSelect = onChartWindowSelect,
                    onFollowLiveChange = onFollowLiveChange,
                    onChartPanLeft = onChartPanLeft,
                    onChartPanRight = onChartPanRight,
                    onChartZoomIn = onChartZoomIn,
                    onChartZoomOut = onChartZoomOut,
                    onChartZoomReset = onChartZoomReset,
                    onChartPanGesture = onChartPanGesture,
                    onChartZoomGesture = onChartZoomGesture,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun DeviceDiscoverySection(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    val scanButtonLabel = if (uiState.isScanning) {
        appStringResource(R.string.action_scanning)
    } else {
        appStringResource(R.string.action_start_scan)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.ble_devices_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = appStringResource(R.string.ble_devices_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStartScan,
                enabled = !uiState.isScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(scanButtonLabel)
            }
            if (showReplayAction) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onReplayRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(appStringResource(R.string.action_replay_bin_debug))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (uiState.foundDevices.isEmpty()) {
                Text(
                    text = if (uiState.isScanning) {
                        appStringResource(R.string.ble_devices_scanning_empty)
                    } else {
                        appStringResource(R.string.ble_devices_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingTextColor(),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.foundDevices.forEach { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConnect(device.address) },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = supportingTextColor(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectCard(onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.connection_title), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(appStringResource(R.string.action_disconnect))
            }
        }
    }
}

@Composable
private fun EventLogSection(uiState: DeviceUiState) {
    if (uiState.diagnosticEvents.isNotEmpty()) {
        DiagnosticEventsCard(uiState = uiState)
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.latest_events_title), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = appStringResource(R.string.latest_events_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
        }
    }
}

@Composable
private fun ThemeSettingsCard(
    selectedThemeMode: AppThemeMode,
    onThemeModeSelect: (AppThemeMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(appStringResource(R.string.settings_theme_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppThemeMode.entries.forEachIndexed { index, themeMode ->
                    SegmentedButton(
                        selected = selectedThemeMode == themeMode,
                        onClick = { onThemeModeSelect(themeMode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AppThemeMode.entries.size,
                        ),
                        label = {
                            Text(appStringResource(themeMode.labelRes))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun appCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

@Composable
private fun supportingTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun LanguageSettingsCard(
    selectedLanguage: AppLanguage,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(appStringResource(R.string.settings_language_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageOptionRow(
                        language = language,
                        selected = language == selectedLanguage,
                        onSelect = { onLanguageSelect(language) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Text(
            text = languageDisplayName(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun shouldShowSessionSummary(uiState: DeviceUiState): Boolean {
    return uiState.showCaptureUi ||
        uiState.isReplayRunning ||
        uiState.packetsReceived > 0L ||
        uiState.packetsLost > 0L ||
        uiState.packetsRejected > 0L ||
        uiState.fragmentsReceived > 0L
}

@Composable
private fun ScanSection(
    modifier: Modifier,
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    val scanButtonLabel = if (uiState.isScanning) {
        appStringResource(R.string.action_scanning)
    } else {
        appStringResource(R.string.action_start_scan)
    }
    Column(modifier = modifier) {
        Text(appStringResource(R.string.ble_devices_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        BleTransportProfileCard(
            selectedProfile = uiState.transportProfile,
            onTransportProfileSelect = onTransportProfileSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onStartScan,
            enabled = !uiState.isScanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(scanButtonLabel)
        }
        if (showReplayAction) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onReplayRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(appStringResource(R.string.action_replay_bin_debug))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
            items(uiState.foundDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onConnect(device.address) },
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = supportingTextColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureTabs(
    selectedTab: DeviceCaptureTab,
    onTabSelect: (DeviceCaptureTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = DeviceCaptureTab.entries.indexOf(selectedTab),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DeviceCaptureTab.entries.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onTabSelect(tab) },
                text = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun OverviewTab(
    uiState: DeviceUiState,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SessionSummaryCard(uiState = uiState, compact = false)
        }

        item {
            BleTransportProfileCard(
                selectedProfile = uiState.transportProfile,
                onTransportProfileSelect = onTransportProfileSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (uiState.diagnosticEvents.isNotEmpty()) {
            item {
                DiagnosticEventsCard(uiState = uiState)
            }
        }

        item {
            ActionButtons(
                onDisconnect = onDisconnect,
                onSharePacketFile = onSharePacketFile,
                onShareRawFile = onShareRawFile,
                onShareLogFile = onShareLogFile,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BleTransportProfileCard(
    selectedProfile: BleTransportProfile,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(appStringResource(R.string.ble_transport_profile_title), style = MaterialTheme.typography.labelMedium)
            Text(
                text = appStringResource(R.string.ble_transport_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BleTransportProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = selectedProfile == profile,
                        onClick = { onTransportProfileSelect(profile) },
                        label = {
                            Text(
                                text = appStringResource(profile.titleRes),
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = appStringResource(selectedProfile.shortDescriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                selectedProfile.detailedDescriptionResIds.forEach { lineResId ->
                    Text(
                        text = "• ${appStringResource(lineResId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingTextColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartTab(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
    onChartPanGesture: (Float) -> Unit,
    onChartZoomGesture: (Float, Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChartStatusLine(uiState = uiState)
        SensorSelector(
            uiState = uiState,
            onSensorSelect = onSensorSelect,
            onChannelSelect = onChannelSelect,
        )
        ChartControls(
            chart = uiState.chart,
            onChartWindowSelect = onChartWindowSelect,
            onFollowLiveChange = onFollowLiveChange,
            onChartPanLeft = onChartPanLeft,
            onChartPanRight = onChartPanRight,
            onChartZoomIn = onChartZoomIn,
            onChartZoomOut = onChartZoomOut,
            onChartZoomReset = onChartZoomReset,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (uiState.chart.points.isNotEmpty()) {
                DeviceChart(
                    chart = uiState.chart,
                    onPanGesture = onChartPanGesture,
                    onZoomGesture = onChartZoomGesture,
                )
            } else {
                EmptyChartState(chart = uiState.chart)
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    uiState: DeviceUiState,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isReplayRunning) {
                Text(
                    appStringResource(R.string.debug_replay),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                SummaryMetric(
                    label = appStringResource(R.string.summary_packets_received),
                    value = "${uiState.packetsReceived}",
                    color = MaterialTheme.colorScheme.primary,
                )
                SummaryMetric(
                    label = appStringResource(R.string.summary_gap),
                    value = "${uiState.packetsLost}",
                    color = if (uiState.packetsLost > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
                SummaryMetric(
                    label = appStringResource(R.string.summary_rejected),
                    value = "${uiState.packetsRejected}",
                    color = if (uiState.packetsRejected > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
            }

            if (compact) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = appStringResource(
                        R.string.summary_fragments_compact,
                        uiState.fragmentsReceived,
                        uiState.rawBytesReceived,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = supportingTextColor(),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = appStringResource(
                        R.string.summary_fragments_full,
                        uiState.fragmentsReceived,
                        uiState.rawBytesReceived,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingTextColor(),
                )
                Spacer(Modifier.height(8.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_last_issue),
                    value = uiState.lastPacketIssue ?: appStringResource(R.string.summary_none),
                    valueColor = if (uiState.lastPacketIssue.isNullOrBlank()) supportingTextColor() else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_timer_regressions),
                    value = "${uiState.timerRegressionRejects}",
                    valueColor = if (uiState.timerRegressionRejects > 0) MaterialTheme.colorScheme.error else supportingTextColor(),
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = appStringResource(R.string.summary_rejection_breakdown),
                    value = uiState.rejectionBreakdown ?: appStringResource(R.string.summary_none),
                    valueColor = if (uiState.rejectionBreakdown.isNullOrBlank()) {
                        supportingTextColor()
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = color,
        )
    }
}

@Composable
private fun DiagnosticEventsCard(uiState: DeviceUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(appStringResource(R.string.latest_events_title), style = MaterialTheme.typography.labelMedium)
            Text(
                appStringResource(R.string.latest_events_order),
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor(),
            )
            Spacer(Modifier.height(8.dp))
            val logState = rememberLazyListState()
            LaunchedEffect(uiState.diagnosticEvents.firstOrNull()?.id) {
                if (uiState.diagnosticEvents.isNotEmpty()) {
                    logState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                state = logState,
            ) {
                items(
                    items = uiState.diagnosticEvents,
                    key = { it.id },
                ) { event ->
                    Text(
                        text = event.message,
                        color = diagnosticColor(event.type),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorSelector(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(appStringResource(R.string.chart_sensor_selection), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val sensors = listOf("T" to 1, "AXL" to 2, "GIR" to 3, "MIC" to 4)
                sensors.forEach { (name, type) ->
                    FilterChip(
                        selected = uiState.selectedSensorType == type,
                        onClick = { onSensorSelect(type) },
                        label = { Text(name) },
                    )
                }
            }

            val maxChannels = if (uiState.selectedSensorType == 2 || uiState.selectedSensorType == 3) 3 else 1
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (channel in 1..maxChannels) {
                    FilterChip(
                        selected = uiState.selectedChannel == channel,
                        onClick = { onChannelSelect(channel) },
                        label = { Text(appStringResource(R.string.chart_channel, channel)) },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartControls(
    chart: ChartUiState,
    onChartWindowSelect: (ChartWindowPreset) -> Unit,
    onFollowLiveChange: (Boolean) -> Unit,
    onChartPanLeft: () -> Unit,
    onChartPanRight: () -> Unit,
    onChartZoomIn: () -> Unit,
    onChartZoomOut: () -> Unit,
    onChartZoomReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartWindowPreset.entries.forEach { preset ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = chart.windowPreset == preset,
                        onClick = { onChartWindowSelect(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = chart.isFollowingLive,
                    onClick = { onFollowLiveChange(!chart.isFollowingLive) },
                    label = {
                        Text(
                            if (chart.isFollowingLive) {
                                appStringResource(R.string.chart_follow_live)
                            } else {
                                appStringResource(R.string.chart_follow_history)
                            },
                        )
                    },
                )
                IconButton(
                    onClick = onChartPanLeft,
                    enabled = chart.canPanLeft,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("←", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartPanRight,
                    enabled = chart.canPanRight,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("→", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomOut,
                    enabled = chart.canZoomOut,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("−", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomIn,
                    enabled = chart.canZoomIn,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text("+", textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onChartZoomReset,
                ) {
                    Text(appStringResource(R.string.chart_y_reset))
                }
            }
        }
    }
}

@Composable
private fun ChartStatusLine(uiState: DeviceUiState) {
    val connectionLabel = when {
        uiState.isReplayRunning -> appStringResource(R.string.status_replay)
        uiState.isConnected -> appStringResource(R.string.status_connected)
        uiState.isScanning -> appStringResource(R.string.status_scanning)
        else -> appStringResource(R.string.status_idle)
    }
    Text(
        text = appStringResource(
            R.string.chart_status_line,
            connectionLabel,
            uiState.packetsReceived,
            uiState.packetsLost,
            uiState.packetsRejected,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = supportingTextColor(),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EmptyChartState(chart: ChartUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(appStringResource(R.string.chart_empty_state), color = supportingTextColor())
        Text(
            appStringResource(R.string.chart_empty_state_window, chart.windowPreset.label),
            style = MaterialTheme.typography.bodySmall,
            color = supportingTextColor(),
        )
    }
}

@Composable
private fun ActionButtons(
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_disconnect))
            }
            Button(
                onClick = onSharePacketFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_file))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onShareRawFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_raw))
            }
            OutlinedButton(
                onClick = onShareLogFile,
                modifier = Modifier.weight(1f),
            ) {
                Text(appStringResource(R.string.action_send_log))
            }
        }
    }
}

@Composable
private fun diagnosticColor(type: PacketDiagnosticType): Color {
    return when (type) {
        PacketDiagnosticType.INFO -> MaterialTheme.colorScheme.primary
        PacketDiagnosticType.ACCEPTED -> MaterialTheme.colorScheme.tertiary
        PacketDiagnosticType.GAP -> MaterialTheme.colorScheme.secondary
        PacketDiagnosticType.REJECTED -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = supportingTextColor(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
        )
    }
}

@Composable
private fun DeviceChart(
    chart: ChartUiState,
    onPanGesture: (Float) -> Unit,
    onZoomGesture: (Float, Float) -> Unit,
) {
    val viewportStart = chart.viewportStartMillis ?: return
    val viewportEnd = chart.viewportEndMillis ?: return
    val sessionStart = chart.sessionStartMillis ?: viewportStart
    val colorScheme = MaterialTheme.colorScheme
    val xTicks = buildXAxisTicks(viewportStart, viewportEnd, sessionStart)
    val yTicks = remember(chart.yAxisCenter, chart.yAxisAbsRange) {
        buildYAxisTicks(chart.yAxisCenter, chart.yAxisAbsRange)
    }
    val yAxisWidth = 56.dp
    val xAxisHeight = 26.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .padding(top = 2.dp),
    ) {
        Text(
            text = buildChartSummary(chart, sessionStart, viewportStart, viewportEnd),
            style = MaterialTheme.typography.labelSmall,
            color = supportingTextColor(),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .width(yAxisWidth)
                    .fillMaxSize()
                    .padding(top = 8.dp, end = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(bottom = xAxisHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    yTicks.forEach { tick ->
                        Text(
                            text = tick.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = supportingTextColor(),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(chart.viewportStartMillis, chart.viewportEndMillis, chart.yAxisAbsRange) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    val anchorFractionY = if (size.height > 0f) {
                                        (centroid.y / size.height).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    onZoomGesture(zoom, anchorFractionY)
                                }
                                if (pan.x != 0f && size.width > 0f) {
                                    onPanGesture(-(pan.x / size.width))
                                }
                            }
                        }
                        .padding(top = 8.dp, end = 4.dp),
                ) {
                    val width = size.width
                    val height = size.height
                    val minPoint = chart.yAxisCenter - chart.yAxisAbsRange
                    val maxPoint = chart.yAxisCenter + chart.yAxisAbsRange
                    val range = (maxPoint - minPoint).coerceAtLeast(1f)
                    val timeRange = (viewportEnd - viewportStart).coerceAtLeast(1L).toFloat()

                    yTicks.forEach { tick ->
                        val y = height - ((tick.value - minPoint) / range * height)
                        drawLine(
                            color = if (tick.value == 0f) {
                                colorScheme.outline
                            } else {
                                colorScheme.outlineVariant
                            },
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = if (tick.value == 0f) 2f else 1f,
                        )
                    }

                    xTicks.forEach { tick ->
                        val x = ((tick.timeMillis - viewportStart) / timeRange) * width
                        drawLine(
                            color = colorScheme.outlineVariant,
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 1f,
                        )
                    }

                    val path = Path().apply {
                        chart.points.forEachIndexed { index, point ->
                            val x = ((point.timeMillis - viewportStart) / timeRange) * width
                            val clampedValue = point.value.coerceIn(minPoint, maxPoint)
                            val y = height - ((clampedValue - minPoint) / range * height)
                            if (index == 0 || point.startsNewSegment) {
                                moveTo(x, y)
                            } else {
                                lineTo(x, y)
                            }
                        }
                    }

                    drawPath(
                        path = path,
                        color = colorScheme.primary,
                        style = Stroke(width = 3f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(xAxisHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    xTicks.forEach { tick ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = when (tick) {
                                xTicks.first() -> Alignment.CenterStart
                                xTicks.last() -> Alignment.CenterEnd
                                else -> Alignment.Center
                            },
                        ) {
                            Text(
                                text = tick.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = supportingTextColor(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildYAxisTicks(
    axisCenter: Float,
    axisAbsRange: Float,
): List<AxisTick> {
    val halfRange = axisAbsRange / 2f
    return listOf(
        AxisTick(label = formatAxisValue(axisCenter + axisAbsRange), value = axisCenter + axisAbsRange),
        AxisTick(label = formatAxisValue(axisCenter + halfRange), value = axisCenter + halfRange),
        AxisTick(label = formatAxisValue(axisCenter), value = axisCenter),
        AxisTick(label = formatAxisValue(axisCenter - halfRange), value = axisCenter - halfRange),
        AxisTick(label = formatAxisValue(axisCenter - axisAbsRange), value = axisCenter - axisAbsRange),
    )
}

private fun formatAxisValue(value: Float): String {
    return value.toInt().toString()
}

@Composable
private fun buildChartSummary(
    chart: ChartUiState,
    sessionStart: Long,
    viewportStart: Long,
    viewportEnd: Long,
): String {
    val modeLabel = if (chart.isFollowingLive) {
        appStringResource(R.string.chart_follow_live)
    } else {
        appStringResource(R.string.chart_follow_history)
    }
    val yZoom = (32_768f / chart.yAxisAbsRange).toInt().coerceAtLeast(1)
    return appStringResource(
        R.string.chart_summary,
        modeLabel,
        chart.windowPreset.label,
        yZoom,
        formatRelativeMillis(viewportStart - sessionStart),
        formatRelativeMillis(viewportEnd - sessionStart),
    )
}

@Composable
private fun formatRelativeMillis(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        appStringResource(R.string.relative_time_minutes_seconds, minutes, seconds)
    } else {
        appStringResource(R.string.relative_time_seconds, seconds)
    }
}

@Composable
private fun buildXAxisTicks(
    viewportStart: Long,
    viewportEnd: Long,
    sessionStart: Long,
): List<TimeAxisTick> {
    val stepCount = 4
    val duration = (viewportEnd - viewportStart).coerceAtLeast(1L)
    return (0..stepCount).map { index ->
        val timeMillis = viewportStart + (duration * index / stepCount)
        TimeAxisTick(
            timeMillis = timeMillis,
            label = formatRelativeMillis(timeMillis - sessionStart),
        )
    }
}

@Composable
private fun languageDisplayName(language: AppLanguage): String {
    return appStringResource(language.displayNameRes)
}

object DeviceScreenEntryPoint {
    @Composable
    fun Overview(
        uiState: DeviceUiState,
        onStartScan: () -> Unit,
        onConnect: (String) -> Unit,
        onDisconnect: () -> Unit,
        showReplayAction: Boolean,
        onReplayRequest: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        DeviceOverviewScreen(
            uiState = uiState,
            onStartScan = onStartScan,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            showReplayAction = showReplayAction,
            onReplayRequest = onReplayRequest,
            modifier = modifier,
        )
    }

    @Composable
    fun Charts(
        uiState: DeviceUiState,
        onSensorSelect: (Int) -> Unit,
        onChannelSelect: (Int) -> Unit,
        onChartWindowSelect: (ChartWindowPreset) -> Unit,
        onFollowLiveChange: (Boolean) -> Unit,
        onChartPanLeft: () -> Unit,
        onChartPanRight: () -> Unit,
        onChartZoomIn: () -> Unit,
        onChartZoomOut: () -> Unit,
        onChartZoomReset: () -> Unit,
        onChartPanGesture: (Float) -> Unit,
        onChartZoomGesture: (Float, Float) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        DeviceChartsScreen(
            uiState = uiState,
            onSensorSelect = onSensorSelect,
            onChannelSelect = onChannelSelect,
            onChartWindowSelect = onChartWindowSelect,
            onFollowLiveChange = onFollowLiveChange,
            onChartPanLeft = onChartPanLeft,
            onChartPanRight = onChartPanRight,
            onChartZoomIn = onChartZoomIn,
            onChartZoomOut = onChartZoomOut,
            onChartZoomReset = onChartZoomReset,
            onChartPanGesture = onChartPanGesture,
            onChartZoomGesture = onChartZoomGesture,
            modifier = modifier,
        )
    }

    @Composable
    fun Settings(
        uiState: DeviceUiState,
        selectedLanguage: AppLanguage,
        selectedThemeMode: AppThemeMode,
        onTransportProfileSelect: (BleTransportProfile) -> Unit,
        onLanguageSelect: (AppLanguage) -> Unit,
        onThemeModeSelect: (AppThemeMode) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        DeviceSettingsScreen(
            uiState = uiState,
            selectedLanguage = selectedLanguage,
            selectedThemeMode = selectedThemeMode,
            onTransportProfileSelect = onTransportProfileSelect,
            onLanguageSelect = onLanguageSelect,
            onThemeModeSelect = onThemeModeSelect,
            modifier = modifier,
        )
    }
}

private data class AxisTick(
    val label: String,
    val value: Float,
)

private data class TimeAxisTick(
    val timeMillis: Long,
    val label: String,
)
