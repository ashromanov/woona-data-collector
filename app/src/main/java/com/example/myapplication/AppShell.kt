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
import com.example.myapplication.feature.device.DeviceChartsScreen
import com.example.myapplication.feature.device.DeviceOverviewScreen
import com.example.myapplication.feature.device.DeviceSettingsScreen
import com.example.myapplication.feature.device.DeviceUiState

private enum class AppDestination(
    val title: String,
    val navLabel: String,
    val icon: ImageVector,
    val supportsExport: Boolean,
) {
    OVERVIEW(
        title = "Overview",
        navLabel = "Overview",
        icon = Icons.Rounded.Home,
        supportsExport = true,
    ),
    CHARTS(
        title = "Charts",
        navLabel = "Charts",
        icon = Icons.Rounded.ShowChart,
        supportsExport = true,
    ),
    SETTINGS(
        title = "Settings",
        navLabel = "Settings",
        icon = Icons.Rounded.Settings,
        supportsExport = false,
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAppShell(
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onTransportProfileSelect: (BleTransportProfile) -> Unit,
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
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onSharePacketFile: () -> Unit,
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
    val canExportAnyFile = canSharePacketFile || canShareRawFile || canShareLogFile

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedDestination.title,
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
                            Text("Export")
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
                AppDestination.OVERVIEW -> DeviceOverviewScreen(
                    uiState = uiState,
                    onStartScan = onStartScan,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    showReplayAction = showReplayAction,
                    onReplayRequest = onReplayRequest,
                )

                AppDestination.CHARTS -> DeviceChartsScreen(
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

                AppDestination.SETTINGS -> DeviceSettingsScreen(
                    uiState = uiState,
                    onTransportProfileSelect = onTransportProfileSelect,
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
                canShareRawFile = canShareRawFile,
                canShareLogFile = canShareLogFile,
                onSharePacketFile = {
                    isExportSheetVisible = false
                    onSharePacketFile()
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
    val (label, containerColor, contentColor) = when {
        uiState.isReplayRunning -> Triple("Replay", Color(0xFFE3F2FD), Color(0xFF1565C0))
        uiState.isConnected -> Triple("Connected", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        uiState.isScanning -> Triple("Scanning", Color(0xFFFFF3E0), Color(0xFFEF6C00))
        else -> Triple("Idle", Color(0xFFF1F3F5), Color(0xFF455A64))
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
        color = Color(0xFFF4EFE8),
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
    val containerColor = if (selected) Color(0xFFE4DDD1) else Color.Transparent
    val titleColor = if (selected) Color(0xFF223133) else Color(0xFF5C676A)

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
                    text = destination.navLabel,
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
    canShareRawFile: Boolean,
    canShareLogFile: Boolean,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Export session files",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Choose which session artifact to share.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        ExportActionButton(
            title = "Compiled binary",
            description = "Validated session packets saved as the compiled output file.",
            enabled = canSharePacketFile,
            onClick = onSharePacketFile,
        )
        ExportActionButton(
            title = "Raw data stream",
            description = "Incoming BLE fragments before parsing and validation.",
            enabled = canShareRawFile,
            onClick = onShareRawFile,
        )
        ExportActionButton(
            title = "Session log",
            description = "Connection events, warnings, and packet diagnostics for the session.",
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
                    color = Color.Gray,
                )
            }
        }
    }
}
