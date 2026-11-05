package com.example.callrecorder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRecordScreen(
    viewModel: CallRecordViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToVoiceRecorder: () -> Unit
) {
    val records by viewModel.records.collectAsState()
    val playingId by viewModel.currentlyPlayingId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val filteredRecords = remember(records, searchQuery) {
        if (searchQuery.isBlank()) records
        else records.filter {
            it.contactName.contains(searchQuery, ignoreCase = true) ||
            it.phoneNumber.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Recorder", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToVoiceRecorder) {
                        Icon(Icons.Default.Mic, contentDescription = "Record a meeting, podcast or voice note")
                    }
                    IconButton(onClick = { viewModel.triggerCloudSync() }) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Sync to Google Drive")
                    }
                    IconButton(onClick = onNavigateToPermissions) {
                        Icon(Icons.Default.Security, contentDescription = "Permissions")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Search records by name or number...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhoneCallback,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No recordings yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Calls are captured automatically. For a meeting, podcast or voice " +
                                "note, tap the microphone above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredRecords, key = { it.id }) { record ->
                        CallRecordItem(
                            record = record,
                            isPlaying = playingId == record.id,
                            onPlayToggle = { viewModel.playAudio(record) },
                            onDelete = { viewModel.deleteRecord(record) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CallRecordItem(
    record: CallRecordEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isVoiceRecording = com.example.callrecorder.data.VoiceCategory.isVoice(record.callType)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.contactName.ifEmpty { "Unknown Caller" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        // A voice recording has no phone number; show what it is instead of a blank line.
                        text = if (isVoiceRecording) {
                            "Voice recording"
                        } else {
                            record.phoneNumber
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Cloud sync indicator
                AssistChip(
                    onClick = {},
                    label = { Text(if (record.isSynced) "Synced" else "Local Only") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (record.isSynced) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = if (record.isSynced) Color(0xFF2E7D32) else Color(0xFFF57C00),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (record.callType) {
                            "INCOMING" -> Icons.Default.CallReceived
                            "OUTGOING" -> Icons.Default.CallMade
                            com.example.callrecorder.data.VoiceCategory.MEETING -> Icons.Default.Groups
                            com.example.callrecorder.data.VoiceCategory.PODCAST -> Icons.Default.Podcasts
                            com.example.callrecorder.data.VoiceCategory.NOTE -> Icons.Default.EditNote
                            else -> Icons.Default.CallMade
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$dateStr (${record.durationSeconds}s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row {
                    IconButton(onClick = onPlayToggle) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
