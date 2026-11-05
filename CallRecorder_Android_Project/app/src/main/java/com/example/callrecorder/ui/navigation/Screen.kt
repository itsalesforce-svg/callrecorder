package com.example.callrecorder.ui.navigation

sealed class Screen(val route: String) {
    object CallRecords : Screen("call_records")
    object Settings : Screen("settings")
    object Permissions : Screen("permissions")
}
