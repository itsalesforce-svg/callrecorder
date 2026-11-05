package com.example.callrecorder.ui.screens

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.callrecorder.data.RecordingStorage
import com.example.callrecorder.ui.viewmodel.SettingsViewModel
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import java.util.Collections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshCallRecordingIssue() }
    val context = LocalContext.current
    val storagePath = remember(context) { RecordingStorage.directory(context).absolutePath }
    var accountEmailDraft by remember(state.googleAccountName) {
        mutableStateOf(state.googleAccountName.orEmpty())
    }
    var showManualAccountEntry by remember { mutableStateOf(false) }
    var accountPickerMessage by remember { mutableStateOf<String?>(null) }

    val accountPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !accountName.isNullOrBlank()) {
            accountEmailDraft = accountName
            viewModel.setGoogleAccountName(accountName)
            accountPickerMessage = null
        }
    }

    fun launchAccountPicker() {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singletonList(DriveScopes.DRIVE_FILE)
            )
            accountPicker.launch(credential.newChooseAccountIntent())
        } catch (error: Exception) {
            accountPickerMessage = "Couldn't open the account chooser. Enter an account already added to this device below."
            showManualAccountEntry = true
        }
    }

    val accountPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchAccountPicker() else {
            accountPickerMessage = "Account access was not granted. Enter a Google account already on this device below."
            showManualAccountEntry = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Recording Preferences", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PreferenceSwitch(
                        title = "Automatic Call Recording",
                        description = "Start a recording when a call connects (device support varies).",
                        checked = state.autoRecord,
                        onCheckedChange = viewModel::setAutoRecord
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Call audio source", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${state.audioSource} · Android and carrier restrictions may limit call audio capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.callRecordingIssue?.let { issue ->
                        Spacer(Modifier.height(8.dp))
                        Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Recording Storage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Phone calls and meetings are saved in this folder", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(storagePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is app-specific storage. Android may hide Android/data folders in some file managers; uninstalling the app can remove these files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Google Drive Sync", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google account", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.googleAccountName ?: "No account selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.googleAccountName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
                                launchAccountPicker()
                            } else {
                                accountPermission.launch(Manifest.permission.GET_ACCOUNTS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.googleAccountName == null) "Choose Google account" else "Change Google account")
                    }
                    TextButton(onClick = { showManualAccountEntry = !showManualAccountEntry }) {
                        Text(if (showManualAccountEntry) "Hide manual entry" else "Enter account email manually")
                    }
                    if (showManualAccountEntry) {
                        OutlinedTextField(
                            value = accountEmailDraft,
                            onValueChange = { accountEmailDraft = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Google account email") },
                            placeholder = { Text("name@gmail.com") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val email = accountEmailDraft.trim()
                                if (email.contains('@')) {
                                    viewModel.setGoogleAccountName(email)
                                    accountPickerMessage = "Account saved. It must also be present on this device."
                                } else {
                                    accountPickerMessage = "Enter a valid Google account email."
                                }
                            },
                            enabled = accountEmailDraft.contains('@'),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save account")
                        }
                    }
                    accountPickerMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    PreferenceSwitch(
                        title = "Automatic sync",
                        description = "Upload new recordings and retry pending uploads in the background.",
                        checked = state.autoSyncEnabled,
                        onCheckedChange = viewModel::setAutoSyncEnabled
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    PreferenceSwitch(
                        title = "Sync over Wi-Fi only",
                        description = "When off, sync can use any available network.",
                        checked = state.syncWifiOnly,
                        onCheckedChange = viewModel::setSyncWifiOnly
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.syncNow() },
                        enabled = !state.googleAccountName.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sync now")
                    }
                    state.syncMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
