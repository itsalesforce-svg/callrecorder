package com.example.callrecorder.debug

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.auth.GoogleAccountRepository
import com.example.callrecorder.data.RecordingStorage
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Record a 2 second sample" self-test.
 *
 * The important property: every stage is logged *and* every failure is caught, so the result always
 * reaches the debug log instead of dying silently inside a broadcast receiver. It answers the
 * question "can this Android build capture audio at all, and with which source?" without waiting for
 * a real phone call - which is otherwise the only way to find out, and the reason recordings were
 * silently missing.
 */
object SelfTest {

    data class Outcome(val ok: Boolean, val message: String, val file: File?)

    /** Tries each audio source until one produces a non-empty file. */
    suspend fun recordSample(context: Context, seconds: Int = 2): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        CallRecorderLog.d("SELFTEST", "recordSample started (${seconds}s), API ${Build.VERSION.SDK_INT}")

        val micGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        CallRecorderLog.d("SELFTEST", "RECORD_AUDIO granted=$micGranted")
        if (!micGranted) {
            return@withContext Outcome(
                false,
                "Microphone permission is not granted - tap 'Grant permissions' first.",
                null
            )
        }

        val sources = linkedMapOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        var lastError: String? = null
        for ((name, source) in sources) {
            val target = File(RecordingStorage.directory(app), "SELFTEST_${System.currentTimeMillis()}_$name.m4a")
            var recorder: MediaRecorder? = null
            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app)
                else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                recorder.setAudioSource(source)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128000)
                recorder.setAudioSamplingRate(44100)
                recorder.setOutputFile(target.absolutePath)
                recorder.prepare()
                recorder.start()
                CallRecorderLog.d("SELFTEST", "recording with $name into ${target.name}")
                Thread.sleep(seconds * 1000L)
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                recorder = null

                val size = target.length()
                CallRecorderLog.d("SELFTEST", "$name produced $size bytes")
                if (size > 1000) {
                    AppPrefs.setAudioSource(app, name)
                    CallRecorderLog.d("SELFTEST", "SUCCESS with $name; saved as preferred audio source")
                    return@withContext Outcome(true, "Capture works with $name.", target)
                }
                lastError = "$name produced an empty file (${size}B) - the OS is blocking call/voice capture"
                target.delete()
            } catch (t: Throwable) {
                lastError = "$name failed: ${t.javaClass.simpleName}: ${t.message}"
                CallRecorderLog.e("SELFTEST", "source $name failed", t)
                runCatching { recorder?.release() }
                runCatching { target.delete() }
                // start()/prepare() failures are often "microphone in use": stop trying others.
                if (t is IllegalStateException || t is java.io.IOException) {
                    break
                }
            }
        }

        Outcome(false, lastError ?: "No audio source could start.", null)
    }

    /** Lists every account the app is allowed to see, and probes the OAuth scope string. */
    suspend fun diagnoseAccounts(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val repository = GoogleAccountRepository(app)
        val builder = StringBuilder()
        builder.appendLine("GET_ACCOUNTS granted: ${repository.hasAccountsPermission()}")
        builder.appendLine("google authenticator installed: ${repository.hasGoogleAuthenticator()}")
        builder.appendLine(
            "android 8+ visibility rules apply: ${repository.visibilityRulesApply(app)} " +
                "(targetSdk ${app.applicationInfo.targetSdkVersion})"
        )
        val all = runCatching {
            AccountManager.get(app).accounts.orEmpty().map { "${it.name} (${it.type})" }
        }.getOrElse { listOf("AccountManager threw: ${it.javaClass.simpleName}: ${it.message}") }
        builder.appendLine("accounts visible to this app: ${all.size}")
        all.forEach { builder.appendLine("  - $it") }
        builder.appendLine("saved address: ${AppPrefs.accountName(app) ?: "(none)"}")
        builder.appendLine("account was synthetic: ${AppPrefs.accountIsSynthetic(app)}")
        builder.appendLine("scope string: ${scopeString()}")
        CallRecorderLog.d("SELFTEST", builder.toString().trim())
        builder.toString()
    }

    /**
     * Explicitly requests a token for the saved address.
     *
     * `GoogleAccountCredential.getToken()` wraps the cause in an IOException, which is why the UI used
     * to show only a class name. Calling [GoogleAuthUtil] directly surfaces the real message -
     * `UnregisteredOnApiConsole`, `BadUsername`, `NetworkError` … - and it is logged verbatim.
     */
    suspend fun probeToken(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val saved = AppPrefs.accountName(app)
        if (saved.isNullOrBlank()) {
            return@withContext "No saved account address - nothing to probe."
        }
        val result = try {
            val token = GoogleAuthUtil.getToken(
                app,
                Account(saved, GoogleAccountRepository.GOOGLE_ACCOUNT_TYPE),
                scopeString()
            )
            // Never log the token itself.
            "TOKEN OK (${token.length} chars)"
        } catch (t: Throwable) {
            val cause = t.cause
            "TOKEN FAILED: ${t.javaClass.name}: ${t.message}" +
                (cause?.let { "\n  caused by ${it.javaClass.name}: ${it.message}" } ?: "") +
                (t.stackTrace.take(3).joinToString("\n  ") { "at $it" })
        }
        CallRecorderLog.d("SELFTEST", result.replace("\n", " | "))
        result
    }

    /** Exactly the string [com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential] builds. */
    fun scopeString(): String = "oauth2:https://www.googleapis.com/auth/drive.file"
}
