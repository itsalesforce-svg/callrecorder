package com.example.callrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.callrecorder.MainActivity
import com.example.callrecorder.R
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.data.RecordingStorage
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.data.local.AppDatabase
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class CallRecordService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var startTimestamp = 0L
    private var currentPhoneNumber = "Unknown"
    private var outputFilePath = ""

    /**
     * Set when the call ends or the service dies. The start loop sleeps for a warm-up window and then
     * may try other audio sources; without this it would happily open a fresh recorder for a call that
     * is already over.
     */
    @Volatile
    private var recordingStopped = false

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START_RECORDING = "com.example.callrecorder.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.callrecorder.ACTION_STOP_RECORDING"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CallRecorderLog.d("SERVICE", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallRecorderLog.d("SERVICE", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Unknown"
                startCallRecording(phone)
            }
            ACTION_STOP_RECORDING -> {
                stopCallRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCallRecording(phoneNumber: String) {
        if (isRecording) {
            CallRecorderLog.d("SERVICE", "already recording, ignoring start for $phoneNumber")
            return
        }
        if (!AppPrefs.autoRecord(applicationContext)) {
            // The "Automatic Call Recording" switch on the Settings screen must actually gate work.
            CallRecorderLog.w("SERVICE", "automatic recording is disabled in Settings - not recording $phoneNumber")
            RecordingStorage.reportRecordingIssue(
                applicationContext,
                "Automatic recording is switched OFF in Settings, so this call was not recorded."
            )
            stopSelf()
            return
        }
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        CallRecorderLog.d("SERVICE", "start requested for $phoneNumber, RECORD_AUDIO granted=$mic")
        if (!mic) {
            RecordingStorage.reportRecordingIssue(
                applicationContext,
                "Microphone permission is missing, so the call could not be recorded. Open the " +
                    "Permissions screen and grant the microphone."
            )
            stopSelf()
            return
        }
        recordingStopped = false
        currentPhoneNumber = phoneNumber
        startTimestamp = System.currentTimeMillis()

        val notification = buildForegroundNotification("Recording call: $phoneNumber")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Starting the recorder and verifying that it captures sound must not block the main thread
        // (a silence probe sleeps for over a second), so it runs on the service's IO scope.
        serviceScope.launch { startRecorderWithFallback(phoneNumber) }
    }

    /**
     * Tries audio sources until one both *starts* and *produces sound*.
     *
     * The second condition is what was missing. On Android 9 a third-party app can start
     * `VOICE_COMMUNICATION` successfully and still record pure silence: `prepare()`/`start()` never
     * throw, so the old fallback never ran, and the result was a valid MP4 with no audible audio -
     * indistinguishable from "the recorder is gone". `getMaxAmplitude()` is the only on-device signal
     * that separates "recording" from "recording silence", so it is checked after a short warm-up.
     */
    private fun startRecorderWithFallback(phoneNumber: String) {
        val preferred = audioSourceFromString(AppPrefs.audioSource(applicationContext))
        val candidates = (listOf(preferred) + listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_CALL
        )).distinct()

        var lastError: Throwable? = null
        val silentSources = mutableListOf<String>()
        for (source in candidates) {
            if (recordingStopped) {
                CallRecorderLog.d("SERVICE", "call ended during source selection - stopping at $source")
                return
            }
            try {
                startRecorderWith(phoneNumber, source)
                isRecording = true
                outputFilePath.let { CallRecorderLog.d("SERVICE", "source=$source started -> $it") }

                // Warm-up: encoders emit nothing for the first few hundred milliseconds.
                Thread.sleep(1200)
                if (recordingStopped) {
                    runCatching { mediaRecorder?.stop() }
                    runCatching { mediaRecorder?.release() }
                    mediaRecorder = null
                    isRecording = false
                    CallRecorderLog.d("SERVICE", "call ended during warm-up - recorder released")
                    return
                }
                val peak = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                CallRecorderLog.d("SERVICE", "source=$source peak amplitude after warm-up: $peak")
                if (peak > 0) {
                    AppPrefs.setLastRecordingIssue(applicationContext, null)
                    AppPrefs.setAudioSource(applicationContext, audioSourceName(source))
                    CallRecorderLog.d("SERVICE", "CONFIRMED audio capture with source=$source (peak=$peak)")
                    return
                }

                // Started but deaf. Keep the bytes only if every other source fails too.
                releaseRecorder()
                isRecording = false
                val stale = File(outputFilePath)
                CallRecorderLog.w(
                    "SERVICE",
                    "source=$source captured SILENCE (peak=0, ${stale.length()}B) - trying the next source"
                )
                silentSources += audioSourceName(source)
                stale.delete()
            } catch (t: Throwable) {
                lastError = t
                CallRecorderLog.e("SERVICE", "recorder failed with source=$source", t)
                releaseRecorder()
                isRecording = false
            }
        }

        val detail = when {
            silentSources.isNotEmpty() && lastError == null ->
                "Every audio source started but captured silence (${silentSources.joinToString()}). " +
                    "This Android build (API ${Build.VERSION.SDK_INT}) does not allow third-party apps " +
                    "to record call audio. Use the speakerphone setting, or record only your side."
            else ->
                "Recorder could not start (${lastError?.javaClass?.simpleName}: ${lastError?.message}). " +
                    "Close other apps that use the microphone and check the Microphone permission."
        }
        RecordingStorage.reportRecordingIssue(applicationContext, detail)
        CallRecorderLog.e("SERVICE", detail, lastError)
        stopSelf()
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> source.toString()
    }

    @Suppress("DEPRECATION")
    private fun startRecorderWith(phoneNumber: String, audioSource: Int) {
        val audioFile = RecordingStorage.fileFor(applicationContext, phoneNumber, startTimestamp)
        outputFilePath = audioFile.absolutePath

        mediaRecorder = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else MediaRecorder()
            ).apply {
            setAudioSource(audioSource)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFilePath)
            prepare()
            start()
        }
    }

    private fun audioSourceFromString(name: String): Int = when (name.trim().uppercase(Locale.US)) {
        "MIC" -> MediaRecorder.AudioSource.MIC
        "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
        "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "VOICE_CALL" -> MediaRecorder.AudioSource.VOICE_CALL
        else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    private fun releaseRecorder() {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
    }

    private fun stopCallRecording() {
        recordingStopped = true
        if (!isRecording) {
            stopSelf()
            return
        }

        try {
            releaseRecorder()
            isRecording = false

            val produced = File(outputFilePath)
            CallRecorderLog.d(
                "SERVICE",
                "stopping: file=${produced.name} exists=${produced.exists()} bytes=${produced.length()}"
            )
            if (!produced.exists() || produced.length() == 0L) {
                RecordingStorage.reportRecordingIssue(
                    applicationContext,
                    "The recording finished but the file is empty. This Android version does not let " +
                        "third-party apps capture call audio - switch the audio source to MIC in " +
                        "Settings (only your side of the conversation will be audible)."
                )
                produced.delete()
                return
            }

            val durationSeconds = (System.currentTimeMillis() - startTimestamp) / 1000

            // Save call record into Room Database
            serviceScope.launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val entity = CallRecordEntity(
                    contactName = resolveContactName(currentPhoneNumber),
                    phoneNumber = currentPhoneNumber,
                    callType = "INCOMING", // Resolved by call log or receiver state
                    timestamp = startTimestamp,
                    durationSeconds = durationSeconds,
                    filePath = outputFilePath,
                    isSynced = false
                )
                val rowId = db.callRecordDao().insertRecord(entity)
                CallRecorderLog.d("SERVICE", "saved record #$rowId duration=${durationSeconds}s path=$outputFilePath")

                // Schedule background cloud sync via WorkManager
                SyncScheduler.scheduleImmediateSync(applicationContext)
            }
        } catch (e: Exception) {
            CallRecorderLog.e("SERVICE", "stopCallRecording failed", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun resolveContactName(phone: String): String {
        return if (phone.length > 3) "Contact ($phone)" else "Unknown Contact"
    }

    private fun buildForegroundNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Recorder Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing notification when a phone call is recorded"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recordingStopped = true
        serviceScope.cancel()
        CallRecorderLog.d("SERVICE", "onDestroy (isRecording=$isRecording)")
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
