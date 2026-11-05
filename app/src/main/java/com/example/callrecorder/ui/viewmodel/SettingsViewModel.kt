package com.example.callrecorder.ui.viewmodel

import android.accounts.AccountManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callrecorder.auth.GoogleAuthManager
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val autoRecord: Boolean = true,
    val audioSource: String = "VOICE_COMMUNICATION",
    val syncWifiOnly: Boolean = true,
    val autoSyncEnabled: Boolean = false,
    val googleAccountName: String? = null,
    val syncMessage: String? = null,
    val callRecordingIssue: String? = null,
    val isDriveTestRunning: Boolean = false,
    val driveConnected: Boolean? = null,
    val driveConnectionMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("call_recorder_settings", Context.MODE_PRIVATE)
    private val accountPrefs = application.getSharedPreferences("call_recorder_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(loadSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        updatePeriodicSync()
    }

    private fun loadSettings(): SettingsUiState {
        return SettingsUiState(
            autoRecord = prefs.getBoolean("auto_record", true),
            audioSource = prefs.getString("audio_source", "VOICE_COMMUNICATION") ?: "VOICE_COMMUNICATION",
            syncWifiOnly = prefs.getBoolean("sync_wifi_only", true),
            autoSyncEnabled = prefs.getBoolean("auto_sync_enabled", false),
            googleAccountName = accountPrefs.getString("google_account_name", null),
            callRecordingIssue = prefs.getString("last_call_recording_issue", null)
        )
    }

    fun setAutoRecord(enabled: Boolean) {
        prefs.edit().putBoolean("auto_record", enabled).apply()
        _uiState.value = _uiState.value.copy(autoRecord = enabled)
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        prefs.edit().putBoolean("sync_wifi_only", wifiOnly).apply()
        _uiState.value = _uiState.value.copy(syncWifiOnly = wifiOnly)
        updatePeriodicSync()
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        _uiState.value = _uiState.value.copy(
            autoSyncEnabled = enabled,
            syncMessage = if (enabled && _uiState.value.googleAccountName.isNullOrBlank()) {
                "Choose a Google account to start automatic sync."
            } else null
        )
        updatePeriodicSync()
    }

    fun setGoogleAccountName(accountName: String) {
        accountPrefs.edit().putString("google_account_name", accountName).apply()
        _uiState.value = _uiState.value.copy(
            googleAccountName = accountName,
            syncMessage = "Google account saved. Test Drive access before enabling sync.",
            driveConnected = null,
            driveConnectionMessage = null
        )
        updatePeriodicSync()
    }

    fun testDriveConnection() {
        val email = _uiState.value.googleAccountName
        if (email.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                driveConnected = false,
                driveConnectionMessage = "Save a Google account first."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isDriveTestRunning = true,
            driveConnected = null,
            driveConnectionMessage = "Checking Google Drive access…"
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    val deviceAccounts = AccountManager.get(application).getAccountsByType("com.google")
                    if (deviceAccounts.none { it.name.equals(email.trim(), ignoreCase = true) }) {
                        error("${email.trim()} is not available as a Google account on this phone. Add it under Android Settings > Accounts, then try again.")
                    }
                    val drive = GoogleAuthManager(application).getGoogleDriveService()
                        ?: error("No Google account is configured.")
                    drive.about().get().setFields("user(emailAddress)").execute()
                        .user?.emailAddress ?: email
                }
            }
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(
                    isDriveTestRunning = false,
                    driveConnected = true,
                    driveConnectionMessage = "Connected to Google Drive as ${result.getOrNull()}."
                )
            } else {
                _uiState.value.copy(
                    isDriveTestRunning = false,
                    driveConnected = false,
                    driveConnectionMessage = result.exceptionOrNull()?.localizedMessage
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "Drive connection failed: $it" }
                        ?: "Drive connection failed. Check the account, network, and Google Drive authorization."
                )
            }
        }
    }

    fun syncNow(): Boolean {
        if (_uiState.value.googleAccountName.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(syncMessage = "Select a Google account before syncing.")
            return false
        }
        SyncScheduler.scheduleImmediateSync(
            getApplication(),
            requireWifi = _uiState.value.syncWifiOnly
        )
        _uiState.value = _uiState.value.copy(syncMessage = "Sync queued. It will run when network conditions allow.")
        return true
    }

    private fun updatePeriodicSync() {
        val state = _uiState.value
        if (state.autoSyncEnabled && !state.googleAccountName.isNullOrBlank()) {
            SyncScheduler.schedulePeriodicSync(
                getApplication(),
                intervalHours = 4,
                requireWifi = state.syncWifiOnly
            )
        } else {
            SyncScheduler.cancelPeriodicSync(getApplication())
        }
    }

    fun refreshCallRecordingIssue() {
        _uiState.value = _uiState.value.copy(
            callRecordingIssue = prefs.getString("last_call_recording_issue", null)
        )
    }

    fun setAudioSource(source: String) {
        prefs.edit().putString("audio_source", source).apply()
        _uiState.value = _uiState.value.copy(audioSource = source)
    }
}
