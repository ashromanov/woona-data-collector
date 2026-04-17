package com.example.myapplication.feature.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState

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
            .padding(16.dp),
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
                showReplayAction = showReplayAction,
                onReplayRequest = onReplayRequest,
            )
        } else {
            CaptureTabs(
                selectedTab = uiState.selectedTab,
                onTabSelect = onTabSelect,
            )

            Spacer(Modifier.height(12.dp))

            when (uiState.selectedTab) {
                DeviceCaptureTab.OVERVIEW -> OverviewTab(
                    uiState = uiState,
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
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Text(
            text = message,
            color = Color(0xFFB00020),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ScanSection(
    modifier: Modifier,
    uiState: DeviceUiState,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    showReplayAction: Boolean,
    onReplayRequest: () -> Unit,
) {
    Column(modifier = modifier) {
        Text("📡 Поиск BLE-оборудования", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStartScan,
            enabled = !uiState.isScanning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isScanning) "ИДЕТ СКАНИРОВАНИЕ..." else "НАЧАТЬ ПОИСК")
        }
        if (showReplayAction) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReplayRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("REPLAY BIN (DEBUG)")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(uiState.foundDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onConnect(device.address) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
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
    onDisconnect: () -> Unit,
    onSharePacketFile: () -> Unit,
    onShareRawFile: () -> Unit,
    onShareLogFile: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SessionSummaryCard(uiState = uiState, compact = false)
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isReplayRunning) {
                Text("DEBUG REPLAY", color = Color(0xFF1565C0))
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                SummaryMetric(
                    label = "ПРИНЯТО ПАКЕТОВ",
                    value = "${uiState.packetsReceived}",
                    color = Color(0xFF2E7D32),
                )
                SummaryMetric(
                    label = "ПОТЕРИ (GAP)",
                    value = "${uiState.packetsLost}",
                    color = if (uiState.packetsLost > 0) Color.Red else Color.Gray,
                )
                SummaryMetric(
                    label = "ОТКЛОНЕНО",
                    value = "${uiState.packetsRejected}",
                    color = if (uiState.packetsRejected > 0) Color(0xFFB00020) else Color.Gray,
                )
            }

            if (compact) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Fragments ${uiState.fragmentsReceived} | Raw ${uiState.rawBytesReceived}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF455A64),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Fragments: ${uiState.fragmentsReceived} | Raw bytes: ${uiState.rawBytesReceived}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF455A64),
                )
                Spacer(Modifier.height(8.dp))
                StatusLine(
                    label = "Last issue",
                    value = uiState.lastPacketIssue ?: "none",
                    valueColor = if (uiState.lastPacketIssue.isNullOrBlank()) Color.Gray else Color(0xFFB00020),
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = "Timer regressions",
                    value = "${uiState.timerRegressionRejects}",
                    valueColor = if (uiState.timerRegressionRejects > 0) Color(0xFFB00020) else Color.Gray,
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    label = "Rejection breakdown",
                    value = uiState.rejectionBreakdown ?: "none",
                    valueColor = if (uiState.rejectionBreakdown.isNullOrBlank()) Color.Gray else Color(0xFF6D4C41),
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text("Последние события", style = MaterialTheme.typography.labelMedium)
            Text(
                "Новые сверху, старые доступны прокруткой вниз",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Выбор датчика", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (channel in 1..maxChannels) {
                    FilterChip(
                        selected = uiState.selectedChannel == channel,
                        onClick = { onChannelSelect(channel) },
                        label = { Text("Ch $channel") },
                        modifier = Modifier.padding(horizontal = 4.dp),
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartWindowPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = chart.windowPreset == preset,
                        onClick = { onChartWindowSelect(preset) },
                        label = { Text(preset.label) },
                    )
                }
                FilterChip(
                    selected = chart.isFollowingLive,
                    onClick = { onFollowLiveChange(!chart.isFollowingLive) },
                    label = { Text(if (chart.isFollowingLive) "Live" else "History") },
                )
                IconButton(
                    onClick = onChartPanLeft,
                    enabled = chart.canPanLeft,
                ) {
                    Text("←", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartPanRight,
                    enabled = chart.canPanRight,
                ) {
                    Text("→", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomOut,
                    enabled = chart.canZoomOut,
                ) {
                    Text("−", textAlign = TextAlign.Center)
                }
                IconButton(
                    onClick = onChartZoomIn,
                    enabled = chart.canZoomIn,
                ) {
                    Text("+", textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onChartZoomReset,
                ) {
                    Text("Y Reset")
                }
            }
        }
    }
}

@Composable
private fun ChartStatusLine(uiState: DeviceUiState) {
    val connectionLabel = when {
        uiState.isReplayRunning -> "Replay"
        uiState.isConnected -> "Connected"
        uiState.isScanning -> "Scanning"
        else -> "Idle"
    }
    Text(
        text = "$connectionLabel | packets ${uiState.packetsReceived} | gap ${uiState.packetsLost} | rejected ${uiState.packetsRejected}",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF455A64),
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
        Text("Нет данных для выбранного датчика/канала", color = Color.Gray)
        Text(
            "История сессии сохраняется, окно: ${chart.windowPreset.label}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) {
                Text("ОТКЛЮЧИТЬ")
            }
            Button(
                onClick = onSharePacketFile,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
            ) {
                Text("ОТПРАВИТЬ ФАЙЛ")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onShareRawFile,
                modifier = Modifier.weight(1f),
            ) {
                Text("SEND RAW")
            }
            OutlinedButton(
                onClick = onShareLogFile,
                modifier = Modifier.weight(1f),
            ) {
                Text("SEND LOG")
            }
        }
    }
}

private fun diagnosticColor(type: PacketDiagnosticType): Color {
    return when (type) {
        PacketDiagnosticType.ACCEPTED -> Color(0xFF2E7D32)
        PacketDiagnosticType.GAP -> Color(0xFFEF6C00)
        PacketDiagnosticType.REJECTED -> Color(0xFFB00020)
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
            color = Color(0xFF455A64),
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
            color = Color(0xFF455A64),
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
                            color = Color.Gray,
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
                            color = if (tick.value == 0f) Color(0xFFD0D7DE) else Color(0xFFECEFF1),
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = if (tick.value == 0f) 2f else 1f,
                        )
                    }

                    xTicks.forEach { tick ->
                        val x = ((tick.timeMillis - viewportStart) / timeRange) * width
                        drawLine(
                            color = Color(0xFFF0F2F5),
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
                        color = Color(0xFF1565C0),
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
                                color = Color.Gray,
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

private fun buildChartSummary(
    chart: ChartUiState,
    sessionStart: Long,
    viewportStart: Long,
    viewportEnd: Long,
): String {
    val modeLabel = if (chart.isFollowingLive) "Live" else "History"
    val yZoom = (32_768f / chart.yAxisAbsRange).toInt().coerceAtLeast(1)
    return "$modeLabel | ${chart.windowPreset.label} | Y x$yZoom | ${formatRelativeMillis(viewportStart - sessionStart)} - ${formatRelativeMillis(viewportEnd - sessionStart)}"
}

private fun formatRelativeMillis(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}

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

private data class AxisTick(
    val label: String,
    val value: Float,
)

private data class TimeAxisTick(
    val timeMillis: Long,
    val label: String,
)
