package com.example.callrecorder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.callrecorder.ui.screens.CallRecordScreen
import com.example.callrecorder.ui.screens.SettingsScreen
import com.example.callrecorder.ui.screens.VoiceRecorderScreen
import com.example.callrecorder.ui.permissions.PermissionWithSettingsCheck
import com.example.callrecorder.ui.viewmodel.CallRecordViewModel
import com.example.callrecorder.ui.viewmodel.SettingsViewModel
import com.example.callrecorder.ui.viewmodel.VoiceRecorderViewModel

@Composable
fun AppNavigationGraph(
    navController: NavHostController,
    callRecordViewModel: CallRecordViewModel,
    settingsViewModel: SettingsViewModel,
    voiceRecorderViewModel: VoiceRecorderViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.CallRecords.route
    ) {
        composable(Screen.CallRecords.route) {
            CallRecordScreen(
                viewModel = callRecordViewModel,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                onNavigateToVoiceRecorder = { navController.navigate(Screen.VoiceRecorder.route) }
            )
        }

        composable(Screen.VoiceRecorder.route) {
            VoiceRecorderScreen(
                recorderViewModel = voiceRecorderViewModel,
                callRecordViewModel = callRecordViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Permissions.route) {
            PermissionWithSettingsCheck(
                onAllPermissionsGranted = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
