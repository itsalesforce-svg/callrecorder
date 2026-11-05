package com.example.callrecorder.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory + on-disk diagnostic log, readable from the phone.
 *
 * Why this exists: every serious bug so far (missing GET_ACCOUNTS, split preference files, the
 * `UnregisteredOnApiConsole` token failure) had to be guessed from a screenshot or from logcat that
 * the user cannot easily reach. This keeps the last [MAX_ENTRIES] lines in memory, mirrors them to
 * `filesDir/callrecorder-debug.log`, and the Settings screen can share that file (or copy it) in one
 * tap.
 *
 * Nothing secret is logged: OAuth tokens are recorded as a length only, never the value.
 */
object CallRecorderLog {

    private const val TAG = "CallRecorder"
    private const val MAX_ENTRIES = 500
    private const val FILE_NAME = "callrecorder-debug.log"
    private const val MAX_FILE_BYTES = 512 * 1024

    private val entries = ArrayDeque<String>()
    private val lock = Any()

    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    @Volatile
    private var context: Context? = null

    @Volatile
    private var installedCrashHandler = false

    /** Safe to call more than once; the first call wins. */
    fun init(appContext: Context) {
        context = appContext.applicationContext
        if (!installedCrashHandler) {
            installedCrashHandler = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("CRASH", "Uncaught exception on ${thread.name}", throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }
        d("LOG", "Logger initialised (build ${Build2.versionLine(context ?: appContext)} )")
    }

    fun d(tag: String, message: String) = append("D", tag, message, null)

    fun w(tag: String, message: String) = append("W", tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append("E", tag, message, throwable)

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val time = stamp.get()?.format(Date()) ?: ""
        val line = "$time $level/$tag: $message"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            throwable?.let { t ->
                entries.addLast("    ! ${t.javaClass.name}: ${t.message}")
                t.stackTrace.take(10).forEach { frame -> entries.addLast("    at $frame") }
            }
            writeLineLocked(line)
        }
        // Also to logcat so `adb logcat -s CallRecorder` works when a cable is available.
        runCatching {
            when (level) {
                "E" -> Log.e(TAG, "$tag: $message", throwable)
                "W" -> Log.w(TAG, "$tag: $message")
                else -> Log.d(TAG, "$tag: $message")
            }
        }
    }

    private fun logFile(): File? {
        val ctx = context ?: return null
        return runCatching { File(ctx.filesDir, FILE_NAME) }.getOrNull()
    }

    private fun writeLineLocked(line: String) {
        val file = logFile() ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) file.delete()
            file.appendText(line + "\n")
        }
    }

    /** Everything the log holds, plus a header describing the build and device. */
    fun snapshot(context: Context): String = buildString {
        appendLine("=== Call Recorder debug log ===")
        appendLine("generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine(Build2.describe(context))
        appendLine("signing SHA-1: ${AppSigning.sha1(context)}")
        appendLine("signing SHA-256: ${AppSigning.sha256(context)}")
        appendLine("---- end of header ----")
        synchronized(lock) {
            entries.forEach { appendLine(it) }
        }
    }

    /** Writes [snapshot] to the private file and returns it, for sharing. */
    fun exportToFile(context: Context): File {
        val target = File(context.filesDir, "callrecorder-debug-share.txt")
        runCatching { target.writeText(snapshot(context)) }
        return target
    }

    fun clear(context: Context) {
        synchronized(lock) {
            entries.clear()
            runCatching { logFile()?.delete() }
        }
        d("LOG", "Log cleared by user")
    }

    /** True when there is anything worth sharing. */
    fun hasEntries(): Boolean = synchronized(lock) { entries.isNotEmpty() }
}

/** Build/device description, kept out of [CallRecorderLog] so it stays readable. */
internal object Build2 {

    fun versionLine(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrDefault("unknown")

    fun describe(context: Context): String = buildString {
        appendLine("package: ${context.packageName}")
        appendLine("version: ${versionLine(context)}")
        appendLine("running on: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        appendLine("build type: ${android.os.Build.TYPE} / tags: ${android.os.Build.TAGS}")
    }.trimEnd()
}
