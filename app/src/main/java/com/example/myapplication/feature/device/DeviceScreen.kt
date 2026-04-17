package com.example.myapplication.feature.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    uiState: DeviceUiState,
    onSensorSelect: (Int) -> Unit,
    onChannelSelect: (Int) -> Unit,
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
            ) {
                Text(
                    text = uiState.errorMessage,
                    color = Color(0xFFB00020),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (!uiState.showCaptureUi) {
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

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
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
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ПРИНЯТО ПАКЕТОВ", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "${uiState.packetsReceived}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFF2E7D32),
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ПОТЕРИ (GAP)", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "${uiState.packetsLost}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (uiState.packetsLost > 0) Color.Red else Color.Gray,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ОТКЛОНЕНО", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "${uiState.packetsRejected}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (uiState.packetsRejected > 0) Color(0xFFB00020) else Color.Gray,
                            )
                        }
                    }

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

            if (uiState.diagnosticEvents.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
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
                                .height(140.dp),
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

            Text("Выбор датчика (Case #2):", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
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
            Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                for (channel in 1..maxChannels) {
                    FilterChip(
                        selected = uiState.selectedChannel == channel,
                        onClick = { onChannelSelect(channel) },
                        label = { Text("Ch $channel") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (uiState.points.isNotEmpty()) {
                    DeviceChart(uiState.points)
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("График отключен", color = Color.Gray)
                        Text(
                            "Прием полных пакетов (14КБ) в файл...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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
private fun DeviceChart(points: List<Float>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(8.dp),
    ) {
        val width = size.width
        val height = size.height
        val maxPoint = 32768f
        val minPoint = -32768f
        val range = maxPoint - minPoint
        val stepX = width / (points.size.coerceAtLeast(2) - 1)

        val path = androidx.compose.ui.graphics.Path().apply {
            points.forEachIndexed { index, value ->
                val x = index * stepX
                val y = height - ((value - minPoint) / range * height)
                if (index == 0) {
                    moveTo(x, y)
                } else {
                    lineTo(x, y)
                }
            }
        }

        drawPath(
            path = path,
            color = Color.Blue,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
        )
    }
}
