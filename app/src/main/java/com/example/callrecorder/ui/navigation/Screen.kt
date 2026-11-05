package com.example.callrecorder.ui.navigation

sealed class Screen(val route: String) {
    object CallRecords : Screen("call_records")
    object VoiceRecorder : Screen("voice_recorder")
    object Settings : Screen("settings")
    object Permissions : Screen("permissions")
}
