package com.example.callrecorder.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Permission checklist. Previously this screen only *listed* permissions (all with a green tick,
 * granted or not) and pushed the user to the system app-info page - which is how the missing
 * runtime `GET_ACCOUNTS` permission stayed invisible: without it the Drive client cannot resolve any
 * Google account and every sync fails with "account is null".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionWithSettingsCheck(
    onAllPermissionsGranted: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.READ_PHONE_STATE to "Detects call state (OFFHOOK / IDLE).")
            add(Manifest.permission.RECORD_AUDIO to "Needed by the foreground recording service.")
            add(Manifest.permission.READ_CALL_LOG to "Resolves the name and number for each record.")
            // Dangerous since Android 6.0: without it AccountManager returns an empty account list
            // and GoogleAccountCredential resolves the saved address to null.
            add(Manifest.permission.GET_ACCOUNTS to "Lets the app see the Google account used for Drive sync.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS to "Shows the recording notification.")
            }
        }
    }

    var refreshKey by remember { mutableStateOf(0) }
    val granted = remember(refreshKey) {
        requiredPermissions.associate { (permission, _) ->
            permission to ContextCompat.checkSelfPermission(
                context, permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    val missing = requiredPermissions.filter { granted[it.first] != true }.map { it.first }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refreshKey++
        if (result.values.all { it }) onAllPermissionsGranted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "Required Privileges for Android 14 / 15",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Grant everything below from inside the app - the system dialog is the only reliable " +
                    "way to obtain the runtime permissions on recent Android versions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            requiredPermissions.forEach { (permission, reason) ->
                val isGranted = granted[permission] == true
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isGranted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                permission.substringAfterLast('.'),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isGranted) {
                                Text(
                                    "not granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { requestLauncher.launch(missing.toTypedArray()) },
                enabled = missing.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (missing.isEmpty()) "All permissions granted" else "Grant ${missing.size} permission(s)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open App System Settings")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "FOREGROUND_SERVICE_MICROPHONE is a normal permission and is granted at install; " +
                        "it only has to be declared, which the manifest does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Special-access fallback for the one permission OEM ROMs like to keep revoked.
            if (granted[Manifest.permission.GET_ACCOUNTS] == false) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "If the dialog never appears, your ROM hides 'Find accounts on the device'. Open the " +
                        "app's permission page above and enable it manually, otherwise Drive sync cannot " +
                        "see any account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
