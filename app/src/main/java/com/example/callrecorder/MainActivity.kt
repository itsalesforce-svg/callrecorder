package com.example.callrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.callrecorder.ui.navigation.AppNavigationGraph
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import com.example.callrecorder.ui.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val callRecordViewModel: CallRecordViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val runtimePermissions = remember {
                buildList {
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.GET_ACCOUNTS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        add(Manifest.permission.READ_PHONE_NUMBERS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            }
            var showPermissionIntro by rememberSaveable { mutableStateOf(false) }
            var permissionNotice by rememberSaveable { mutableStateOf<String?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val denied = result.filterValues { !it }.keys
                if (denied.isNotEmpty()) {
                    permissionNotice = "Some permissions were not granted. Call and voice recording features may be limited; review Permissions from the shield icon."
                }
            }

            LaunchedEffect(Unit) {
                val preferences = getSharedPreferences("call_recorder_settings", MODE_PRIVATE)
                if (!preferences.getBoolean("first_permission_prompt_shown", false)) {
                    val missing = runtimePermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) showPermissionIntro = true
                    preferences.edit().putBoolean("first_permission_prompt_shown", true).apply()
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigationGraph(
                        navController = navController,
                        callRecordViewModel = callRecordViewModel,
                        settingsViewModel = settingsViewModel
                    )

                    if (showPermissionIntro) {
                        AlertDialog(
                            onDismissRequest = { showPermissionIntro = false },
                            title = { Text("Set up recording permissions") },
                            text = {
                                Text("Allow microphone, phone-state, account, and notification permissions so recording and optional Google Drive sync can work. Android will ask for these now; you can change them later in system settings.")
                            },
                            confirmButton = {
                                Button(onClick = {
                                    showPermissionIntro = false
                                    permissionLauncher.launch(runtimePermissions)
                                }) { Text("Grant permissions") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPermissionIntro = false }) { Text("Not now") }
                            }
                        )
                    }
                    permissionNotice?.let { notice ->
                        AlertDialog(
                            onDismissRequest = { permissionNotice = null },
                            title = { Text("Some permissions are missing") },
                            text = { Text(notice) },
                            confirmButton = {
                                TextButton(onClick = { permissionNotice = null }) { Text("OK") }
                            }
                        )
                    }
                }
            }
        }
    }
}
