package com.example.myapplication

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ble.BleTransportProfile
import com.example.myapplication.feature.device.ChartWindowPreset
import com.example.myapplication.feature.device.DeviceScreenEntryPoint
import com.example.myapplication.feature.device.DeviceUiState
import com.example.myapplication.localization.AppLanguage
import com.example.myapplication.localization.appStringResource
import com.example.myapplication.ui.theme.AppThemeMode

private enum class AppDestination(
    val titleRes: Int,
    val navLabelRes: Int,
    val icon: ImageVector,
    val supportsExport: Boolean,
) {
    OVERVIEW(
        titleRes = R.string.nav_overview,
        navLabelRes = R.string.nav_overview,
        icon = Icons.Rounded.Home,
        supportsExport = true,
    ),
    CHARTS(
        titleRes = R.string.nav_charts,
        navLabelRes = R.string.nav_charts,
        icon = Icons.Rounded.ShowChart,
        supportsExport = true,
    ),
    SETTINGS(
        titleRes = R.string.nav_settings,
        navLabelRes = R.string.nav_settings,
        icon = Icons.Rounded.Settings,
        supportsExport = false,
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAppShell(
    uiState: DeviceUiState,
    selectedLanguage: AppLanguage,
    selectedThemeMode: AppThemeMode,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onThemeModeSelect: (AppThemeMode) -> Unit,
    onDisconnect: () -> Unit,
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
    canSharePacketFile: Boolean,
    canShareCsvFile: Boolean,
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onSharePacketFile: () -> Unit,
    onShareCsvFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    var selectedDestinationName by rememberSaveable {
        mutableStateOf(AppDestination.OVERVIEW.name)
    }
    var isExportSheetVisible by rememberSaveable { mutableStateOf(false) }
    val selectedDestination = AppDestination.valueOf(selectedDestinationName)
    val canExportAnyFile = canSharePacketFile || canShareCsvFile || canShareRawFile || canShareLogFile

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = appStringResource(selectedDestination.titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (selectedDestination.supportsExport) {
                        TextButton(
                            onClick = { isExportSheetVisible = true },
                            enabled = canExportAnyFile,
                        ) {
                            Text(appStringResource(R.string.action_export))
                        }
                    }
                    ConnectionStatusBadge(uiState = uiState)
                },
            )
        },
        bottomBar = {
            AppBottomNavigation(
                selectedDestination = selectedDestination,
                onDestinationSelected = { selectedDestinationName = it.name },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedDestination) {
                AppDestination.OVERVIEW -> DeviceScreenEntryPoint.Overview(
                    uiState = uiState,
                    onStartScan = onStartScan,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    showReplayAction = showReplayAction,
                    onReplayRequest = onReplayRequest,
                )

                AppDestination.CHARTS -> DeviceScreenEntryPoint.Charts(
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

                AppDestination.SETTINGS -> DeviceScreenEntryPoint.Settings(
                    uiState = uiState,
                    selectedLanguage = selectedLanguage,
                    selectedThemeMode = selectedThemeMode,
                    onTransportProfileSelect = onTransportProfileSelect,
                    onLanguageSelect = onLanguageSelect,
                    onThemeModeSelect = onThemeModeSelect,
                )
            }
        }
    }

    if (isExportSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isExportSheetVisible = false },
        ) {
            ExportSheet(
                canSharePacketFile = canSharePacketFile,
                canShareCsvFile = canShareCsvFile,
                canShareRawFile = canShareRawFile,
                canShareLogFile = canShareLogFile,
                onSharePacketFile = {
                    isExportSheetVisible = false
                    onSharePacketFile()
                },
                onShareCsvFile = {
                    isExportSheetVisible = false
                    onShareCsvFile()
                },
                onShareRawFile = {
                    isExportSheetVisible = false
                    onShareRawFile()
                },
                onShareLogFile = {
                    isExportSheetVisible = false
                    onShareLogFile()
                },
            )
        }
    }
}

@Composable
private fun ConnectionStatusBadge(uiState: DeviceUiState) {
    val colorScheme = MaterialTheme.colorScheme
    val (label, containerColor, contentColor) = when {
        uiState.isReplayRunning -> Triple(
            appStringResource(R.string.status_replay),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )

        uiState.isConnected -> Triple(
            appStringResource(R.string.status_connected),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )

        uiState.isScanning -> Triple(
            appStringResource(R.string.status_scanning),
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
        )

        else -> Triple(
            appStringResource(R.string.status_idle),
            colorScheme.surfaceVariant,
            colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AppBottomNavigation(
    selectedDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppDestination.entries.forEach { destination ->
                ShellNavItem(
                    destination = destination,
                    selected = selectedDestination == destination,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShellNavItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (selected) colorScheme.secondaryContainer else Color.Transparent
    val titleColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.Tab,
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = appStringResource(destination.navLabelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
            }
        }
    }
}

@Composable
private fun ExportSheet(
    canSharePacketFile: Boolean,
    canShareCsvFile: Boolean,
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onSharePacketFile: () -> Unit,
    onShareCsvFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = appStringResource(R.string.export_session_files),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = appStringResource(R.string.export_choose_artifact),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_compiled_binary),
            description = appStringResource(R.string.export_compiled_binary_desc),
            enabled = canSharePacketFile,
            onClick = onSharePacketFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_channel_csv),
            description = appStringResource(R.string.export_channel_csv_desc),
            enabled = canShareCsvFile,
            onClick = onShareCsvFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_raw_data_stream),
            description = appStringResource(R.string.export_raw_data_stream_desc),
            enabled = canShareRawFile,
            onClick = onShareRawFile,
        )
        ExportActionButton(
            title = appStringResource(R.string.export_session_log),
            description = appStringResource(R.string.export_session_log_desc),
            enabled = canShareLogFile,
            onClick = onShareLogFile,
        )
    }
}

@Composable
private fun ExportActionButton(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(8.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

object AppShellEntryPoint {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Render(
        uiState: DeviceUiState,
        selectedLanguage: AppLanguage,
        selectedThemeMode: AppThemeMode,
        onStartScan: () -> Unit,
        onConnect: (String) -> Unit,
        onTransportProfileSelect: (BleTransportProfile) -> Unit,
        onLanguageSelect: (AppLanguage) -> Unit,
        onThemeModeSelect: (AppThemeMode) -> Unit,
        onDisconnect: () -> Unit,
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
        canSharePacketFile: Boolean,
        canShareCsvFile: Boolean,
        canShareRawFile: Boolean,
        canShareLogFile: Boolean,
        onSharePacketFile: () -> Unit,
        onShareCsvFile: () -> Unit,
        onShareRawFile: () -> Unit,
        onShareLogFile: () -> Unit,
        showReplayAction: Boolean,
        onReplayRequest: () -> Unit,
    ) {
        DeviceAppShell(
            uiState = uiState,
            selectedLanguage = selectedLanguage,
            selectedThemeMode = selectedThemeMode,
            onStartScan = onStartScan,
            onConnect = onConnect,
            onTransportProfileSelect = onTransportProfileSelect,
            onLanguageSelect = onLanguageSelect,
            onThemeModeSelect = onThemeModeSelect,
            onDisconnect = onDisconnect,
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
            canSharePacketFile = canSharePacketFile,
            canShareCsvFile = canShareCsvFile,
            canShareRawFile = canShareRawFile,
            canShareLogFile = canShareLogFile,
            onSharePacketFile = onSharePacketFile,
            onShareCsvFile = onShareCsvFile,
            onShareRawFile = onShareRawFile,
            onShareLogFile = onShareLogFile,
            showReplayAction = showReplayAction,
            onReplayRequest = onReplayRequest,
        )
    }
}
