package com.example.callrecorder.ui.viewmodel

import android.app.Application
import android.media.MediaPlayer
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

    fun triggerCloudSync() {
        SyncScheduler.scheduleImmediateSync(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
