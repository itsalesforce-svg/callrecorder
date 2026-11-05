package com.example.callrecorder.data

import android.content.Context
import java.io.File

/** Shared app-private location for call and microphone recordings. */
object RecordingStorage {
    const val DIRECTORY_NAME = "CallRecordings"

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY_NAME)
}
