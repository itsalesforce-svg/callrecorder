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
import com.example.callrecorder.data.local.AppDatabase
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class CallRecordService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var startTimestamp = 0L
    private var currentPhoneNumber = "Unknown"
    private var outputFilePath = ""

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        if (isRecording) return
        if (!AppPrefs.autoRecord(applicationContext)) {
            // The "Automatic Call Recording" switch on the Settings screen must actually gate work.
            stopSelf()
            return
        }
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

        // Android 10+ blocks third-party apps from capturing the call stream for most devices, so a
        // hard-coded VOICE_COMMUNICATION silently produced nothing. Try the configured source, then
        // fall back to the microphone, and tell the user in Settings if both fail.
        val preferred = audioSourceFromString(AppPrefs.audioSource(applicationContext))
        var lastError: Throwable? = null
        for (source in listOf(preferred, MediaRecorder.AudioSource.MIC).distinct()) {
            try {
                startRecorderWith(phoneNumber, source)
                isRecording = true
                AppPrefs.setLastRecordingIssue(applicationContext, null)
                return
            } catch (t: Throwable) {
                lastError = t
                releaseRecorder()
            }
        }
        RecordingStorage.reportRecordingIssue(
            applicationContext,
            "Recorder could not start (${lastError?.javaClass?.simpleName}: ${lastError?.message}). " +
                "Pick another audio source in Settings, close other mic-using apps, and check the " +
                "Microphone permission."
        )
        stopSelf()
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
        if (!isRecording) {
            stopSelf()
            return
        }

        try {
            releaseRecorder()
            isRecording = false

            val produced = File(outputFilePath)
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
                db.callRecordDao().insertRecord(entity)

                // Schedule background cloud sync via WorkManager
                SyncScheduler.scheduleImmediateSync(applicationContext)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
