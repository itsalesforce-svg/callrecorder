package com.example.callrecorder.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callrecorder.data.VoiceCategory
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import com.example.callrecorder.ui.viewmodel.VoiceRecorderViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The manual recorder screen: meetings, podcasts and voice notes.
 *
 * These are ordinary rows in the same library as call recordings, so they appear on the home screen,
 * play with the same player, delete the same way and upload to the same Drive folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderScreen(
    recorderViewModel: VoiceRecorderViewModel,
    callRecordViewModel: CallRecordViewModel,
    onBack: () -> Unit
) {
    val state by recorderViewModel.recorder.collectAsState()
    val selectedCategory by recorderViewModel.selectedCategory.collectAsState()
    val allRecords by callRecordViewModel.records.collectAsState()
    val playingId by callRecordViewModel.currentlyPlayingId.collectAsState()

    val voiceRecords = remember(allRecords) { allRecords.filter { VoiceCategory.isVoice(it.callType) } }

    // Tick only while recording, so an idle screen costs nothing.
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.recording, state.startedAt) {
        if (!state.recording) {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - state.startedAt) / 1000L).coerceAtLeast(0L)
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Recorder", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (state.recording) formatClock(elapsedSeconds) else "Ready",
                    fontSize = if (state.recording) 44.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.recording) {
                        "Recording ${VoiceCategory.label(state.category)}"
                    } else {
                        "Pick what you are recording, then tap the button"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceCategory.all.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category && !state.recording,
                            onClick = { recorderViewModel.selectCategory(category) },
                            enabled = !state.recording,
                            label = { Text(VoiceCategory.label(category)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (category) {
                                        VoiceCategory.MEETING -> Icons.Default.Groups
                                        VoiceCategory.PODCAST -> Icons.Default.Podcasts
                                        else -> Icons.Default.EditNote
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                FilledIconButton(
                    onClick = { recorderViewModel.toggle() },
                    shape = CircleShape,
                    modifier = Modifier.size(88.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (state.recording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (state.recording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (state.recording) "Stop recording" else "Start recording",
                        modifier = Modifier.size(40.dp)
                    )
                }

                state.message?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            IconButton(
                                onClick = { recorderViewModel.clearMessage() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = if (voiceRecords.isEmpty()) {
                    "No voice recordings yet"
                } else {
                    "Voice recordings (${voiceRecords.size})"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            if (voiceRecords.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = "Meetings, podcasts and voice notes recorded here are saved to the same " +
                            "library as call recordings and sync to Google Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(voiceRecords, key = { it.id }) { record ->
                        VoiceRecordingItem(
                            record = record,
                            isPlaying = playingId == record.id,
                            onPlayToggle = { callRecordViewModel.playAudio(record) },
                            onDelete = { callRecordViewModel.deleteRecord(record) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRecordingItem(
    record: CallRecordEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (record.callType) {
                    VoiceCategory.MEETING -> Icons.Default.Groups
                    VoiceCategory.PODCAST -> Icons.Default.Podcasts
                    else -> Icons.Default.EditNote
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = VoiceCategory.label(record.callType),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$dateStr · ${formatClock(record.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (record.isSynced) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (record.isSynced) Color(0xFF2E7D32) else Color(0xFFF57C00)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (record.isSynced) "Synced to Drive" else "Local only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onPlayToggle) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
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

/** mm:ss, or h:mm:ss for a long meeting. */
private fun formatClock(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
