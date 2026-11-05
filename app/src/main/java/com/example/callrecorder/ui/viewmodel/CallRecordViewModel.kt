package com.example.callrecorder.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import com.example.callrecorder.service.CallRecordService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callrecorder.data.local.AppDatabase
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.data.repository.CallRecordRepository
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallRecordViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CallRecordRepository
    val records: StateFlow<List<CallRecordEntity>>

    private val _currentlyPlayingId = MutableStateFlow<Long?>(null)
    val currentlyPlayingId: StateFlow<Long?> = _currentlyPlayingId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    private val _voiceRecordingStartedAt = MutableStateFlow<Long?>(null)
    val voiceRecordingStartedAt: StateFlow<Long?> = _voiceRecordingStartedAt.asStateFlow()

    init {
        val dao = AppDatabase.getDatabase(application).callRecordDao()
        repository = CallRecordRepository(dao)
        records = repository.allRecords.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun playAudio(record: CallRecordEntity) {
        if (_currentlyPlayingId.value == record.id) {
            stopAudio()
            return
        }

        stopAudio()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(record.filePath)
                prepare()
                start()
                setOnCompletionListener {
                    _currentlyPlayingId.value = null
                }
            }
            _currentlyPlayingId.value = record.id
        } catch (e: Exception) {
            e.printStackTrace()
            _currentlyPlayingId.value = null
        }
    }

    fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _currentlyPlayingId.value = null
    }

    fun deleteRecord(record: CallRecordEntity) {
        viewModelScope.launch {
            if (_currentlyPlayingId.value == record.id) {
                stopAudio()
            }
            repository.deleteRecord(record)
        }
    }

    fun startVoiceRecording(title: String, category: String) {
        val intent = Intent(getApplication(), CallRecordService::class.java).apply {
            action = CallRecordService.ACTION_START_VOICE_RECORDING
            putExtra(CallRecordService.EXTRA_RECORDING_TITLE, title)
            putExtra(CallRecordService.EXTRA_RECORDING_CATEGORY, category)
        }
        try {
            val application = getApplication<Application>()
            ContextCompat.startForegroundService(application, intent)
            _voiceRecordingStartedAt.value = System.currentTimeMillis()
        } catch (e: Exception) {
            e.printStackTrace()
            _voiceRecordingStartedAt.value = null
        }
    }

    fun stopVoiceRecording() {
        val intent = Intent(getApplication(), CallRecordService::class.java).apply {
            action = CallRecordService.ACTION_STOP_RECORDING
        }
        getApplication<Application>().startService(intent)
        _voiceRecordingStartedAt.value = null
    }

    fun triggerCloudSync() {
        val context = getApplication<Application>()
        val account = context.getSharedPreferences("call_recorder_prefs", android.content.Context.MODE_PRIVATE)
            .getString("google_account_name", null)
        if (account.isNullOrBlank()) return
        val requireWifi = context.getSharedPreferences("call_recorder_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("sync_wifi_only", true)
        SyncScheduler.scheduleImmediateSync(context, requireWifi = requireWifi)
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
