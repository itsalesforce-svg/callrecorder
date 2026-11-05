package com.example.callrecorder.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val autoRecord: Boolean = true,
    val audioSource: String = "VOICE_COMMUNICATION",
    val syncWifiOnly: Boolean = true,
    val googleAccountName: String? = "itsalesforce@gmail.com",
    val syncIntervalHours: Long = 4
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("call_recorder_settings", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(loadSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadSettings(): SettingsUiState {
        return SettingsUiState(
            autoRecord = prefs.getBoolean("auto_record", true),
            audioSource = prefs.getString("audio_source", "VOICE_COMMUNICATION") ?: "VOICE_COMMUNICATION",
            syncWifiOnly = prefs.getBoolean("sync_wifi_only", true),
            googleAccountName = prefs.getString("google_account", "itsalesforce@gmail.com"),
            syncIntervalHours = prefs.getLong("sync_interval_hours", 4L)
        )
    }

    fun setAutoRecord(enabled: Boolean) {
        prefs.edit().putBoolean("auto_record", enabled).apply()
        _uiState.value = _uiState.value.copy(autoRecord = enabled)
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        prefs.edit().putBoolean("sync_wifi_only", wifiOnly).apply()
        _uiState.value = _uiState.value.copy(syncWifiOnly = wifiOnly)
    }

    fun setAudioSource(source: String) {
        prefs.edit().putString("audio_source", source).apply()
        _uiState.value = _uiState.value.copy(audioSource = source)
    }
}
