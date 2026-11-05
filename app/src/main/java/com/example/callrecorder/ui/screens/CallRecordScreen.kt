package com.example.callrecorder.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RecordingTab { CALLS, VOICE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRecordScreen(
    viewModel: CallRecordViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsState()
    val playingId by viewModel.currentlyPlayingId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recordingStartedAt by viewModel.voiceRecordingStartedAt.collectAsState()
    var selectedTab by remember { mutableStateOf(RecordingTab.CALLS) }
    var showNewRecordingDialog by remember { mutableStateOf(false) }
    var pendingPermissionRequest by remember { mutableStateOf(false) }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingPermissionRequest) showNewRecordingDialog = true
        pendingPermissionRequest = false
    }

    val filteredRecords = remember(records, searchQuery, selectedTab) {
        records.filter { record ->
            val matchesTab = if (selectedTab == RecordingTab.VOICE) {
                record.recordingType == "VOICE"
            } else {
                record.recordingType != "VOICE"
            }
            val matchesSearch = searchQuery.isBlank() ||
                record.contactName.contains(searchQuery, ignoreCase = true) ||
                record.phoneNumber.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Recorder", fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedTab == RecordingTab.CALLS) "Your call recordings" else "Meetings, podcasts & more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerCloudSync() }) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Sync to Google Drive")
                    }
                    IconButton(onClick = onNavigateToPermissions) {
                        Icon(Icons.Default.Security, contentDescription = "Permissions")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == RecordingTab.CALLS,
                    onClick = {
                        selectedTab = RecordingTab.CALLS
                        viewModel.updateSearchQuery("")
                    },
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text("Calls") }
                )
                NavigationBarItem(
                    selected = selectedTab == RecordingTab.VOICE,
                    onClick = {
                        selectedTab = RecordingTab.VOICE
                        viewModel.updateSearchQuery("")
                    },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("Voice recorder") }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (selectedTab == RecordingTab.VOICE) {
                VoiceRecorderPanel(
                    recordingStartedAt = recordingStartedAt,
                    onStart = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            showNewRecordingDialog = true
                        } else {
                            pendingPermissionRequest = true
                            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = { viewModel.stopVoiceRecording() }
                )
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Search calls by name or number") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            if (filteredRecords.isEmpty()) {
                EmptyRecordsState(isVoice = selectedTab == RecordingTab.VOICE)
            } else {
                Text(
                    text = if (selectedTab == RecordingTab.VOICE) "VOICE RECORDINGS" else "RECENT CALLS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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

    if (showNewRecordingDialog) {
        NewVoiceRecordingDialog(
            onDismiss = { showNewRecordingDialog = false },
            onStart = { title, category ->
                showNewRecordingDialog = false
                viewModel.startVoiceRecording(title, category)
            }
        )
    }
}

@Composable
private fun VoiceRecorderPanel(
    recordingStartedAt: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingStartedAt) {
        while (recordingStartedAt != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    val isRecording = recordingStartedAt != null
    val elapsedSeconds = if (recordingStartedAt == null) 0L else ((now - recordingStartedAt) / 1000).coerceAtLeast(0)
    val timer = "%02d:%02d:%02d".format(elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF231D35), Color(0xFF4C315A), Color(0xFF9D315B))
                    )
                )
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isRecording) "RECORDING IN PROGRESS" else "VOICE RECORDER",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFC2D1),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (isRecording) "Take your time." else "Capture the conversation.",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isRecording) "Keep this screen open while you record." else "For meetings, podcast interviews and ideas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = .76f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = .13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            if (isRecording) {
                Text(timer, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Recording audio from your microphone", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .75f))
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF8F274F)),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop and save recording", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Ready when you are", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Saved privately on this device", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .72f))
                    }
                    FilledIconButton(
                        onClick = onStart,
                        modifier = Modifier.size(62.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFF486C), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Start voice recording", modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewVoiceRecordingDialog(onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Meeting") }
    val categories = listOf("Meeting", "Podcast", "Interview", "Other")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New voice recording", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Choose a type and give this recording a name.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    label = { Text("Recording name") },
                    placeholder = { Text("e.g. Weekly team sync") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("TYPE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(option) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(title.ifBlank { category }, category) }) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start recording")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyRecordsState(isVoice: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isVoice) Icons.Default.MicNone else Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (isVoice) "No voice recordings yet" else "No call recordings yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (isVoice) "Start a recording above for a meeting, podcast or interview." else "Your call recordings will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CallRecordItem(record: CallRecordEntity, isPlaying: Boolean, onPlayToggle: () -> Unit, onDelete: () -> Unit) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }
    val isVoice = record.recordingType == "VOICE"
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(
                        if (isVoice) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isVoice) Icons.Default.Mic else Icons.Default.Call,
                        contentDescription = null,
                        tint = if (isVoice) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.contactName.ifEmpty { if (isVoice) "Voice recording" else "Unknown caller" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isVoice) "${record.phoneNumber} · Voice recording" else record.phoneNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPlayToggle) {
                    Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, contentDescription = if (isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isVoice) Icons.Default.Schedule else Icons.Default.CallReceived, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(5.dp))
                Text("$dateStr  ·  ${formatDuration(record.durationSeconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                Icon(if (record.isSynced) Icons.Default.CloudDone else Icons.Default.CloudQueue, contentDescription = if (record.isSynced) "Synced" else "Local only", modifier = Modifier.size(15.dp), tint = if (record.isSynced) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                Text(if (record.isSynced) "Synced" else "On device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}
