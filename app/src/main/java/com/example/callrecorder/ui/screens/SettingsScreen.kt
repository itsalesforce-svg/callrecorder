package com.example.callrecorder.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import com.example.callrecorder.auth.GoogleAccountRepository
import com.example.callrecorder.cloud.DriveConnectionTester
import com.example.callrecorder.data.RecordingStorage
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.ui.viewmodel.DriveLinkState
import com.example.callrecorder.ui.viewmodel.SettingsViewModel

private val AudioSources = listOf(
    "VOICE_COMMUNICATION",
    "MIC",
    "CAMCORDER",
    "VOICE_RECOGNITION",
    "VOICE_CALL"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.refreshCallRecordingIssue()
    }

    // 1) Runtime permission that makes AccountManager return the Google accounts at all.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refresh()
        if (granted) viewModel.runConnectionTest()
    }

    // 2) Play services account chooser. Picking this way also grants the app account visibility.
    val accountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onSystemChooserResult(result.data)
        } else {
            viewModel.onSystemChooserResult(null)
        }
    }

    // 3) Google's consent / re-login prompt (UserRecoverableAuthException.intent).
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onConsentScreenReturned()
    }

    LaunchedEffect(viewModel) {
        viewModel.driveAuthorizationRequests.collect { authorization ->
            runCatching { consentLauncher.launch(authorization) }
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
            // ------------------------------------------------------------------ Drive
            SectionHeader("Google Drive Sync")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DriveStatusRow(state.linkState, state.headline)

                    state.detail?.let { detail ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                detail,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (!state.accountsPermissionGranted) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Step 1 · let the app see the accounts on this phone",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.GET_ACCOUNTS) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Grant accounts permission")
                        }
                        TextButton(onClick = { viewModel.openAppPermissionSettings() }) {
                            Text("Open app permission settings")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Accounts the app can see: ${state.deviceAccounts.size} · all accounts on the " +
                            "device: ${state.totalAccounts}. Scope used: drive.file (only files this " +
                            "app creates).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Text("Google account", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.googleAccountName ?: "No account linked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.googleAccountName == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Choose the Google account already added to this phone (recommended) — picking " +
                            "it from the list is also what makes the account visible to the app. " +
                            "Typing the address works too, but it must match Settings > Accounts exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.setShowAccountPicker(true) }) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Choose from this device")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = viewModel.systemChooserIntent()
                                if (intent != null) {
                                    try {
                                        accountPickerLauncher.launch(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        viewModel.onSystemChooserResult(null)
                                    }
                                } else {
                                    viewModel.onSystemChooserResult(null)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("System picker")
                        }
                        if (state.googleAccountName != null) {
                            OutlinedButton(onClick = { viewModel.unlinkAccount() }) {
                                Text("Unlink")
                            }
                        }
                    }

                    if (state.needsConsoleSetup) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "This is not an account problem. Google Play services needs this app " +
                                    "registered in the Cloud project before it will issue a Drive token, and " +
                                    "no button on this phone can change that. The exact package name and " +
                                    "SHA-1 to paste into Cloud Console are at the bottom of this screen " +
                                    "under 'Debug & Diagnostics'. After registering them, tap " +
                                    "'Test Drive connection' again.",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.accountDraft,
                        onValueChange = viewModel::onAccountDraftChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Google account email") },
                        placeholder = { Text("name@gmail.com") },
                        singleLine = true,
                        isError = !state.draftLooksValid,
                        supportingText = {
                            Text(
                                if (state.draftLooksValid) "Must equal the account name in Settings > Accounts."
                                else "That is not an email address.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveDraftAccount() },
                        enabled = state.accountDraft.isNotBlank() && state.draftLooksValid,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Google account")
                    }

                    // If the phone is signed in with a different mailbox, the address above can
                    // never produce a token. Offer the account the app *can* see, one tap away.
                    if (state.linkState == DriveLinkState.FAILED || state.linkState == DriveLinkState.NO_ACCOUNT) {
                        val suggestion = state.deviceAccounts.firstOrNull { account ->
                            !account.name.equals(state.googleAccountName, ignoreCase = true)
                        }
                        if (suggestion != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { viewModel.linkDeviceAccount(suggestion.name) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Use ${suggestion.name} instead")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.runConnectionTest() },
                        enabled = !state.busy && !state.googleAccountName.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.busy) "Testing Drive access…" else "Test Drive connection")
                    }

                    state.consentIntent?.let { consent ->
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { runCatching { consentLauncher.launch(consent) } },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Grant access on Google's screen")
                        }
                    }

                    if (state.steps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("What was checked", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        state.steps.forEach { step -> TestStepRow(step) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------------ Sync
            SectionHeader("Background Sync")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PreferenceSwitch(
                        title = "Automatic sync",
                        description = "Upload new recordings and retry pending uploads in the background.",
                        checked = state.autoSync,
                        onCheckedChange = viewModel::setAutoSync
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
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    state.lastSyncSummary?.let { summary ->
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                "Last run: $summary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------------ Recording
            SectionHeader("Recording Preferences")
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
                        "Android blocks call-audio capture for third-party apps on most devices. " +
                            "If recordings come out silent, switch to MIC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioSources.forEach { source ->
                            if (source == state.audioSource) {
                                Button(onClick = { }) { Text(source) }
                            } else {
                                OutlinedButton(onClick = { viewModel.setAudioSource(source) }) {
                                    Text(source)
                                }
                            }
                        }
                    }
                    state.callRecordingIssue?.let { issue ->
                        Spacer(Modifier.height(8.dp))
                        Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------------ Storage
            SectionHeader("Recording Storage")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Phone calls and meetings are saved in this folder", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    val storagePath = remember(context) { RecordingStorage.directory(context).absolutePath }
                    SelectionContainer {
                        Text(storagePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is app-specific storage. Android may hide Android/data folders in some file " +
                            "managers; uninstalling the app can remove these files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    TextButton(onClick = { viewModel.openDeviceAccountSettings() }) {
                        Text("Add a Google account to this phone")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------------ Debug
            SectionHeader("Debug & Diagnostics")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "If Drive or recording still fails, run these checks and send the log. The log " +
                            "contains build, device, account visibility, the real Play services error and " +
                            "the signing fingerprint - never passwords or tokens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.runSelfTest(recordSample = true) },
                        enabled = !state.diagnosticsRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.diagnosticsRunning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.BugReport, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (state.diagnosticsRunning) "Running checks…"
                            else "Run self-test (accounts + token + 2s mic sample)"
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { shareLog(context, viewModel.debugFile()) }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share debug log")
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, viewModel.debugSnapshot())
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy log")
                        }
                        OutlinedButton(onClick = { viewModel.clearDebugLog() }) {
                            Text("Clear")
                        }
                    }

                    state.diagnosticsResult?.let { result ->
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(
                                result,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text("Signing identity (needed in Google Cloud Console)",
                        style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("package: ${context.packageName}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            "SHA-1: ${state.signingSha1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    SelectionContainer {
                        Text(
                            "SHA-256: ${state.signingSha256}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                "Package name: ${context.packageName}\nSHA-1: ${state.signingSha1}"
                            )
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy package + SHA-1")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cloud Console > APIs & Services > Credentials > Create credentials > OAuth client " +
                            "ID > Android needs exactly this package name and SHA-1. Each GitHub Actions " +
                            "build is signed with a new debug key, so a new download needs the new SHA-1 " +
                            "unless the workflow has the stable keystore set up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    if (state.showAccountPicker) {
        AccountPickerDialog(
            accounts = state.deviceAccounts,
            permissionGranted = state.accountsPermissionGranted,
            onSelect = { viewModel.linkDeviceAccount(it) },
            onDismiss = { viewModel.setShowAccountPicker(false) },
            onRequestPermission = {
                viewModel.setShowAccountPicker(false)
                permissionLauncher.launch(Manifest.permission.GET_ACCOUNTS)
            },
            onAddAccount = {
                viewModel.setShowAccountPicker(false)
                viewModel.openDeviceAccountSettings()
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DriveStatusRow(linkState: DriveLinkState, headline: String?) {
    val tint = when (linkState) {
        DriveLinkState.READY -> Color(0xFF2E7D32)
        DriveLinkState.FAILED -> MaterialTheme.colorScheme.error
        DriveLinkState.CONSENT_PENDING -> MaterialTheme.colorScheme.tertiary
        DriveLinkState.TESTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (linkState) {
        DriveLinkState.READY -> Icons.Default.CheckCircle
        DriveLinkState.FAILED -> Icons.Default.Error
        DriveLinkState.CONSENT_PENDING -> Icons.Default.Security
        DriveLinkState.TESTING -> Icons.Default.CloudSync
        else -> Icons.Default.HelpOutline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(
            headline ?: when (linkState) {
                DriveLinkState.NO_ACCOUNT -> "No Google account linked"
                else -> "Drive status unknown"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tint
        )
    }
}

@Composable
private fun TestStepRow(step: DriveConnectionTester.TestStep) {
    val tint = when (step.status) {
        DriveConnectionTester.StepStatus.PASS -> Color(0xFF2E7D32)
        DriveConnectionTester.StepStatus.FAIL -> MaterialTheme.colorScheme.error
        DriveConnectionTester.StepStatus.SKIP -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = when (step.status) {
                DriveConnectionTester.StepStatus.PASS -> Icons.Default.CheckCircle
                DriveConnectionTester.StepStatus.FAIL -> Icons.Default.Cancel
                DriveConnectionTester.StepStatus.SKIP -> Icons.Default.RemoveCircle
            },
            contentDescription = null,
            tint = tint
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(step.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Composable
private fun AccountPickerDialog(
    accounts: List<GoogleAccountRepository.VisibleAccount>,
    permissionGranted: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    onAddAccount: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google accounts on this phone") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (accounts.isEmpty()) {
                    Text(
                        if (!permissionGranted) {
                            "The app may not list accounts until you grant 'Find accounts on this " +
                                "device'. Without it the list is always empty - that is the source of " +
                                "the 'account is null' error."
                        } else {
                            "Permission is granted but no account of type com.google was returned. Add " +
                                "one under Settings > Accounts > Add account > Google, then reopen this list."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    accounts.forEach { account ->
                        TextButton(
                            onClick = { onSelect(account.name) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        account.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column {
                if (!permissionGranted) {
                    TextButton(onClick = onRequestPermission) { Text("Grant permission") }
                }
                TextButton(onClick = onAddAccount) { Text("Add account") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Hands the log file to any app that accepts text: Gmail, Keep, Drive, a chat app…
 * FileProvider is used because a raw `file://` URI throws FileUriExposedException on API 24+.
 */
private fun shareLog(context: Context, file: java.io.File) {
    val authority = "${context.packageName}.fileprovider"
    val uri = runCatching {
        FileProvider.getUriForFile(context, authority, file)
    }.getOrNull()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Call Recorder debug log")
        putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault("(log unreadable)"))
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share debug log")) }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("Call Recorder debug log", text))
}
