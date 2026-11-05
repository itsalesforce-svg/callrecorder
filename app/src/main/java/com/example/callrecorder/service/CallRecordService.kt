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

    /**
     * False while a pre-opened capture is waiting for the call to connect. If the call is never
     * answered the file is thrown away, so a ringing phone does not leave recordings behind.
     */
    @Volatile
    private var callConnected = false

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START_RECORDING = "com.example.callrecorder.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.callrecorder.ACTION_STOP_RECORDING"
        /** Opens the microphone while the phone is still ringing (see AppPrefs.recordFromRing). */
        const val ACTION_PRIME_RECORDING = "com.example.callrecorder.ACTION_PRIME_RECORDING"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val CHANNEL_ID = "call_recording_channel"
        private const val NOTIFICATION_ID = 1001

        /** Warm-up before the amplitude check; encoders emit nothing at first. */
        private const val WARM_UP_MS = 1200L

        /** Attempts per audio source. The audio HAL is briefly busy when a call connects. */
        private const val START_ATTEMPTS = 3

        private const val RETRY_DELAY_MS = 400L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CallRecorderLog.d("SERVICE", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallRecorderLog.d("SERVICE", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_PRIME_RECORDING -> {
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Unknown"
                primeForCall(phone)
            }
            ACTION_START_RECORDING -> {
                val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Unknown"
                onCallConnected(phone)
            }
            ACTION_STOP_RECORDING -> {
                stopCallRecording()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Called on RINGING / outgoing dial: start capturing before the telephony stack grabs the mic.
     * Waiting for OFFHOOK is what produced `RuntimeException: start failed` on this OPPO/ColorOS
     * build - the microphone was already in use by the call by the time the recorder tried to open it.
     */
    private fun primeForCall(phoneNumber: String) {
        if (isRecording) {
            CallRecorderLog.d("SERVICE", "pre-open ignored, already capturing for $currentPhoneNumber")
            return
        }
        CallRecorderLog.d("SERVICE", "pre-open for $phoneNumber (waiting for the call to connect)")
        startCallRecording(phoneNumber, connected = false)
    }

    /**
     * Called on OFFHOOK. If a pre-opened capture is running it is kept - restarting the recorder here
     * would hit exactly the failure this exists to avoid - and the call is simply marked connected.
     */
    private fun onCallConnected(phoneNumber: String) {
        if (isRecording && !callConnected) {
            callConnected = true
            startTimestamp = System.currentTimeMillis()
            CallRecorderLog.d(
                "SERVICE",
                "call connected - keeping the pre-opened capture for $phoneNumber (${outputFilePath})"
            )
            AppPrefs.setLastRecordingIssue(applicationContext, null)
            return
        }
        startCallRecording(phoneNumber, connected = true)
    }

    private fun startCallRecording(phoneNumber: String, connected: Boolean) {
        if (isRecording) {
            CallRecorderLog.d("SERVICE", "already recording, ignoring start for $phoneNumber")
            return
        }

        // This service is launched with startForegroundService from the call receiver, so the
        // notification has to exist before any early return - otherwise the platform kills the app
        // ("did not then call Service.startForeground()"), which used to look like the app vanishing
        // mid-call.
        promoteToForeground("Recording call: $phoneNumber")

        if (!AppPrefs.autoRecord(applicationContext)) {
            // The "Automatic Call Recording" switch on the Settings screen must actually gate work.
            CallRecorderLog.w("SERVICE", "automatic recording is disabled in Settings - not recording $phoneNumber")
            RecordingStorage.reportRecordingIssue(
                applicationContext,
                "Automatic recording is switched OFF in Settings, so this call was not recorded."
            )
            stopForegroundCompat()
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
            stopForegroundCompat()
            stopSelf()
            return
        }
        recordingStopped = false
        callConnected = connected
        currentPhoneNumber = phoneNumber
        startTimestamp = System.currentTimeMillis()

        promoteToForeground("Recording call: $phoneNumber")

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
        val failedSources = mutableListOf<String>()

        for (source in candidates) {
            if (recordingStopped) {
                CallRecorderLog.d("SERVICE", "call ended during source selection - stopping at $source")
                return
            }
            // The audio HAL is often busy for a few hundred milliseconds around the moment a call
            // connects, which surfaces as a one-off `start failed`. Retrying costs nothing and turns a
            // lost call into a recorded one.
            var attempt = 0
            while (attempt < START_ATTEMPTS) {
                if (recordingStopped) return
                try {
                    startRecorderWith(phoneNumber, source)
                    isRecording = true
                    CallRecorderLog.d(
                        "SERVICE",
                        "source=${audioSourceName(source)} attempt=${attempt + 1} started -> $outputFilePath"
                    )

                    // Warm-up: encoders emit nothing for the first few hundred milliseconds.
                    Thread.sleep(WARM_UP_MS)
                    if (recordingStopped) {
                        runCatching { mediaRecorder?.stop() }
                        runCatching { mediaRecorder?.release() }
                        mediaRecorder = null
                        isRecording = false
                        CallRecorderLog.d("SERVICE", "call ended during warm-up - recorder released")
                        return
                    }
                    val peak = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    CallRecorderLog.d(
                        "SERVICE",
                        "source=${audioSourceName(source)} peak amplitude after warm-up: $peak"
                    )
                    if (peak > 0) {
                        AppPrefs.setLastRecordingIssue(applicationContext, null)
                        AppPrefs.setAudioSource(applicationContext, audioSourceName(source))
                        CallRecorderLog.d(
                            "SERVICE",
                            "CONFIRMED audio capture with source=$source (peak=$peak)"
                        )
                        return
                    }

                    // Started but deaf: no point retrying the same source.
                    releaseRecorder()
                    isRecording = false
                    val stale = File(outputFilePath)
                    CallRecorderLog.w(
                        "SERVICE",
                        "source=${audioSourceName(source)} captured SILENCE " +
                            "(peak=0, ${stale.length()}B) - trying the next source"
                    )
                    silentSources += audioSourceName(source)
                    stale.delete()
                    break
                } catch (t: Throwable) {
                    lastError = t
                    CallRecorderLog.e(
                        "SERVICE",
                        "source=${audioSourceName(source)} attempt=${attempt + 1} failed: $t",
                        t
                    )
                    releaseRecorder()
                    isRecording = false
                    attempt++
                    if (attempt < START_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                    } else {
                        failedSources += "${audioSourceName(source)}: ${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            }
        }

        val detail = when {
            silentSources.isNotEmpty() ->
                "Every audio source started but captured silence (${silentSources.joinToString()}). " +
                    "This Android build (API ${Build.VERSION.SDK_INT}) does not allow third-party apps " +
                    "to record call audio. Speakerphone may still work for the other side."
            else ->
                "Recorder could not start (${lastError?.javaClass?.simpleName}: ${lastError?.message}). " +
                    "Tried ${failedSources.joinToString()}. On ColorOS/MIUI/EMUI the call app can hold " +
                    "the microphone for the whole call, in which case no third-party app can record it."
        }
        RecordingStorage.reportRecordingIssue(applicationContext, detail)
        CallRecorderLog.e("SERVICE", detail, lastError)
        stopForegroundCompat()
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
            // MediaRecorder reports some failures only through this callback; without it the reason
            // for a silent or truncated recording is lost.
            setOnErrorListener { _, what, extra ->
                CallRecorderLog.e(
                    "SERVICE",
                    "MediaRecorder error what=$what extra=$extra (source=$audioSource)"
                )
            }
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

            if (!callConnected) {
                // Pre-opened for a call that never connected (unanswered or rejected): throw it away.
                CallRecorderLog.d(
                    "SERVICE",
                    "call never connected - discarding pre-opened capture (${produced.length()}B)"
                )
                runCatching { produced.delete() }
                RecordingStorage.reportRecordingIssue(applicationContext, null)
                return
            }
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

    private fun promoteToForeground(content: String) {
        val notification = buildForegroundNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
