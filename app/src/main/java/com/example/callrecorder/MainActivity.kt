package com.example.callrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.ui.navigation.AppNavigationGraph
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import com.example.callrecorder.ui.viewmodel.SettingsViewModel
import com.example.callrecorder.ui.viewmodel.VoiceRecorderViewModel

class MainActivity : ComponentActivity() {

    private val callRecordViewModel: CallRecordViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val voiceRecorderViewModel: VoiceRecorderViewModel by viewModels()

    /**
     * Asks for the runtime permissions on first launch.
     *
     * This is the actual fix for "the Google account exists but the app says null": GET_ACCOUNTS is
     * declared in the manifest but is a *dangerous* permission, so unless the user is prompted the
     * system never grants it, AccountManager returns an empty list and the Drive client resolves the
     * saved address to null. Nothing on screen told the user that, so the app blamed the account.
     */
    private val requestStartupPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // Re-run the Drive diagnostics with whatever the user allowed.
            settingsViewModel.refresh()
            if (grants[Manifest.permission.GET_ACCOUNTS] == true && !AppPrefs.accountName(this).isNullOrBlank()) {
                settingsViewModel.runConnectionTest()
            }
        }

    /** Permissions that must be requested at runtime on the current API level. */
    private fun missingRuntimePermissions(): Array<String> {
        val wanted = buildList<String> {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.GET_ACCOUNTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return wanted
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()
    }

    private fun askForPermissionsOnce() {
        if (AppPrefs.startupPermissionsAsked(this)) return
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            AppPrefs.setStartupPermissionsAsked(this, true)
            return
        }
        AppPrefs.setStartupPermissionsAsked(this, true)
        requestStartupPermissions.launch(missing)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app is not launched from a Compose context yet, so no LaunchedEffect is involved:
        // asking here keeps it a single, well-defined prompt on first launch.
        askForPermissionsOnce()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigationGraph(
                        navController = navController,
                        callRecordViewModel = callRecordViewModel,
                        settingsViewModel = settingsViewModel,
                        voiceRecorderViewModel = voiceRecorderViewModel
                    )
                }
            }
        }
    }
}
