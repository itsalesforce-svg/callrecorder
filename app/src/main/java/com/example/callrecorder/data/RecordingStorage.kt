package com.example.callrecorder.data

import android.content.Context
import com.example.callrecorder.auth.AppPrefs
import java.io.File

/**
 * Where recordings live on the phone. [CallRecordService] writes here, the Settings screen shows
 * it, and the player/preview resolves paths from it - previously the directory was hard-coded in
 * the service while Settings referenced a class that did not exist in the repo (hence the
 * "Unresolved reference: RecordingStorage" build failure).
 */
object RecordingStorage {

    const val DIR_NAME = "CallRecordings"

    /** Primary location: app-scoped external storage, no runtime permission needed. */
    fun directory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun fileFor(context: Context, phoneNumber: String, timestamp: Long): File {
        val safe = phoneNumber.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "unknown" }
        return File(directory(context), "CALL_${sanitizeForName(safe)}_$timestamp.m4a")
    }

    private fun sanitizeForName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_+-]"), "_").take(32)

    /** Total bytes currently held locally (used to explain the queue on the Settings screen). */
    fun usedBytes(context: Context): Long =
        directory(context).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun pendingUploads(context: Context): Int = directory(context)
        .listFiles()
        ?.count { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
        ?: 0

    /** Records why the last attempt failed, so the Settings screen can explain it. */
    fun reportRecordingIssue(context: Context, issue: String?) =
        AppPrefs.setLastRecordingIssue(context, issue)

    fun lastRecordingIssue(context: Context): String? = AppPrefs.lastRecordingIssue(context)
}
