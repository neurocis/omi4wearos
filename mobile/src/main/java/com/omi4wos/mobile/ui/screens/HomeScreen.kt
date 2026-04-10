package com.omi4wos.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WatchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omi4wos.mobile.data.SyncSummary
import com.omi4wos.mobile.viewmodel.HomeViewModel
import com.omi4wos.shared.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "omi4wOS - cipioh version",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Watch connection card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.watchConnected)
                    Color(0xFF1B5E20).copy(alpha = 0.15f)
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (uiState.watchConnected) Icons.Default.Watch else Icons.Default.WatchOff,
                    contentDescription = null,
                    tint = if (uiState.watchConnected) Color(0xFF4CAF50) else Color(0xFF757575),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.watchConnected) "Watch Connected" else "Watch Disconnected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Always reserve space; color hides it when inactive so the card stays the same height
                    Text(
                        text = "Receiving audio…",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.watchConnected && uiState.isReceivingAudio)
                            Color(0xFF4CAF50) else Color.Transparent
                    )
                    if (uiState.watchBatteryLevel >= 0) {
                        Text(
                            text = "Battery: ${uiState.watchBatteryLevel}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (uiState.watchConnected) {
                    Button(
                        onClick = {
                            if (uiState.watchRecordingEnabled) viewModel.stopWatchRecording()
                            else viewModel.startWatchRecording()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.watchRecordingEnabled)
                                Color(0xFFB71C1C) else Color(0xFF1B5E20)
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.watchRecordingEnabled) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (uiState.watchRecordingEnabled) "Stop" else "Start")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stream mode card
        StreamModeCard(
            streamMode = uiState.streamMode,
            batchIntervalMinutes = uiState.batchIntervalMinutes,
            onModeSelected = { viewModel.setStreamMode(it) },
            onIntervalSelected = { viewModel.setBatchIntervalMinutes(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // [Retry icon] Upload Failures: N          [Force Sync] or [● Realtime]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.retryPendingUploads() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Retry failed uploads",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Upload Failures: ${uiState.uploadFailures}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.uploadFailures > 0) Color(0xFFFFA000)
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            if (uiState.streamMode == Constants.STREAM_MODE_REALTIME) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "Realtime Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                OutlinedButton(onClick = { viewModel.forceSyncWatch() }) {
                    Text("Force Sync")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.recentSyncs.isEmpty()) {
            Text(
                text = "No syncs yet. Uploads will appear here after the watch syncs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(uiState.recentSyncs) { sync ->
                    SyncCard(sync)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private val BATCH_INTERVAL_OPTIONS = listOf(5, 10, 15, 30, 60, 90, 120)

@Composable
private fun StreamModeCard(
    streamMode: String,
    batchIntervalMinutes: Int,
    onModeSelected: (String) -> Unit,
    onIntervalSelected: (Int) -> Unit
) {
    val realtimeSelected = streamMode == Constants.STREAM_MODE_REALTIME
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Sync Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onModeSelected(Constants.STREAM_MODE_REALTIME) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (realtimeSelected) Color(0xFFE53935)
                                         else MaterialTheme.colorScheme.surface,
                        contentColor = if (realtimeSelected) Color.White
                                       else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Realtime Stream", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onModeSelected(Constants.STREAM_MODE_BATCH) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!realtimeSelected) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.surface,
                        contentColor = if (!realtimeSelected) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Batch Stream", style = MaterialTheme.typography.bodySmall)
                }
                if (!realtimeSelected) {
                    Column {
                        Text(
                            text = "Interval (min)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(
                                onClick = { dropdownExpanded = true },
                                modifier = Modifier.width(90.dp)
                            ) {
                                Text(
                                    text = "$batchIntervalMinutes",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                BATCH_INTERVAL_OPTIONS.forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text("$minutes min") },
                                        onClick = {
                                            onIntervalSelected(minutes)
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(sync: SyncSummary) {
    val dateFmt = SimpleDateFormat("MM/dd/yy hh:mma", Locale.getDefault())
    val timeFmt = SimpleDateFormat("hh:mma", Locale.getDefault())

    val allUploaded = sync.failedCount == 0
    val statusText = if (allUploaded) "✓ Uploaded" else "⏳ ${sync.failedCount} failed"
    val statusColor = if (allUploaded) Color(0xFF4CAF50) else Color(0xFFFFA000)

    val batteryStr = if (sync.batteryLevel >= 0) "${sync.batteryLevel}%" else "?%"
    val sizeStr = formatSize(sync.totalBytes)
    val segStr = if (sync.segmentCount == 1) "1 segment" else "${sync.segmentCount} segments"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${timeFmt.format(Date(sync.syncTime))}  |  Watch Battery: $batteryStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Uploaded to Omi Cloud: $sizeStr  ($segStr)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Spanning ${dateFmt.format(Date(sync.earliestMs))} to ${dateFmt.format(Date(sync.latestMs))}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L      -> "$bytes B"
    bytes < 1_048_576L -> "${"%.1f".format(bytes / 1024.0)} KB"
    else               -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
}
