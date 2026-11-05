package com.example.callrecorder.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.callrecorder.data.VoiceCategory
import com.example.callrecorder.service.VoiceRecorderService
import com.example.callrecorder.service.VoiceRecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls the manual recorder. It only sends start/stop intents to [VoiceRecorderService] and mirrors
 * the state that service publishes, so the screen keeps working when it is recreated (rotation, or
 * coming back after the screen was off for an hour).
 */
class VoiceRecorderViewModel(application: Application) : AndroidViewModel(application) {

    val recorder: StateFlow<VoiceRecorderState.Snapshot> = VoiceRecorderState.state

    private val _selectedCategory = MutableStateFlow(VoiceCategory.MEETING)
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    fun selectCategory(category: String) {
        if (recorder.value.recording) return
        _selectedCategory.value = category
    }

    fun start() {
        VoiceRecorderService.start(getApplication(), _selectedCategory.value)
    }

    fun stop() {
        VoiceRecorderService.stop(getApplication())
    }

    fun toggle() {
        if (recorder.value.recording) stop() else start()
    }

    fun clearMessage() {
        VoiceRecorderState.clearMessage()
    }
}
