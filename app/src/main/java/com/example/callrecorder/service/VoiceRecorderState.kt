package com.example.callrecorder.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Recording state shared between [VoiceRecorderService] and the UI.
 *
 * The service owns the MediaRecorder, but the screen has to draw the timer, the selected category and
 * any error. A process-wide holder is enough because both live in the same process, and it keeps the
 * service free of any UI dependency.
 */
object VoiceRecorderState {

    data class Snapshot(
        val recording: Boolean = false,
        val startedAt: Long = 0L,
        val category: String = "",
        /** Id of the row written when the last recording was saved, or null. */
        val lastSavedId: Long? = null,
        /** Human readable result of the last attempt: an error, or "Saved ...". */
        val message: String? = null
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun onStarted(startedAt: Long, category: String) {
        _state.update {
            it.copy(recording = true, startedAt = startedAt, category = category, lastSavedId = null, message = null)
        }
    }

    fun onSaved(id: Long, message: String) {
        _state.update { it.copy(recording = false, startedAt = 0L, lastSavedId = id, message = message) }
    }

    fun onFailed(message: String) {
        _state.update { it.copy(recording = false, startedAt = 0L, message = message) }
    }

    fun onStopped() {
        _state.update { it.copy(recording = false, startedAt = 0L) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
