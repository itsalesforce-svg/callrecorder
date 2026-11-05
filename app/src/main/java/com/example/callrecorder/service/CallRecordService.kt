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
import com.example.callrecorder.data.RecordingStorage
import com.example.callrecorder.data.local.AppDatabase
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecordService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var startTimestamp = 0L
    private var currentPhoneNumber = "Unknown"
    private var currentCallType = "OUTGOING"
    private var outputFilePath = ""
    private var isVoiceRecording = false
    private var voiceTitle = "Voice recording"
    private var voiceCategory = "Meeting"

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START_RECORDING = "com.example.callrecorder.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.callrecorder.ACTION_STOP_RECORDING"
        const val ACTION_STOP_CALL_RECORDING = "com.example.callrecorder.ACTION_STOP_CALL_RECORDING"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val ACTION_START_VOICE_RECORDING = "com.example.callrecorder.ACTION_START_VOICE_RECORDING"
        const val EXTRA_RECORDING_TITLE = "extra_recording_title"
        const val EXTRA_RECORDING_CATEGORY = "extra_recording_category"
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
                val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "OUTGOING"
                startCallRecording(phone, callType)
            }
            ACTION_START_VOICE_RECORDING -> {
                voiceTitle = intent.getStringExtra(EXTRA_RECORDING_TITLE)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Voice recording"
                voiceCategory = intent.getStringExtra(EXTRA_RECORDING_CATEGORY) ?: "Meeting"
                startVoiceRecording()
            }
            ACTION_STOP_RECORDING -> {
                stopCallRecording()
            }
            ACTION_STOP_CALL_RECORDING -> {
                if (!isVoiceRecording) stopCallRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCallRecording(phoneNumber: String, callType: String) {
        if (isRecording) return
        currentPhoneNumber = phoneNumber
        currentCallType = callType
        isVoiceRecording = false
        startRecording()
    }

    private fun startVoiceRecording() {
        if (isRecording) return
        currentPhoneNumber = ""
        isVoiceRecording = true
        startRecording()
    }

    private fun startRecording() {
        if (isRecording) return
        startTimestamp = System.currentTimeMillis()

        val notificationText = if (isVoiceRecording) {
            "Recording $voiceCategory: $voiceTitle"
        } else {
            "Recording call: $currentPhoneNumber"
        }
        try {
            val notification = buildForegroundNotification(notificationText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val storageDir = RecordingStorage.directory(this).apply {
                if (!exists() && !mkdirs()) error("Could not create recordings folder: $absolutePath")
            }
            val timeStampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isVoiceRecording) "VOICE" else "CALL"
            val safeName = (if (isVoiceRecording) voiceTitle else currentPhoneNumber)
                .replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val audioFile = File(storageDir, "${prefix}_${safeName}_$timeStampStr.m4a")
            outputFilePath = audioFile.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(
                    if (isVoiceRecording) MediaRecorder.AudioSource.MIC
                    else MediaRecorder.AudioSource.VOICE_COMMUNICATION
                )
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFilePath)
                prepare()
                start()
            }

            isRecording = true
            if (!isVoiceRecording) {
                getSharedPreferences("call_recorder_settings", MODE_PRIVATE)
                    .edit().remove("last_call_recording_issue").apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isVoiceRecording) {
                getSharedPreferences("call_recorder_settings", MODE_PRIVATE).edit()
                    .putString(
                        "last_call_recording_issue",
                        "Could not start call recording: ${e.message ?: "microphone service unavailable"}. Android or the phone carrier may block call audio capture."
                    ).apply()
            }
            mediaRecorder?.release()
            mediaRecorder = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCallRecording() {
        if (!isRecording) {
            stopSelf()
            return
        }

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val durationSeconds = (System.currentTimeMillis() - startTimestamp) / 1000

            // Save call record into Room Database
            serviceScope.launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val entity = CallRecordEntity(
                    contactName = if (isVoiceRecording) voiceTitle else resolveContactName(currentPhoneNumber),
                    phoneNumber = if (isVoiceRecording) voiceCategory else currentPhoneNumber,
                    callType = if (isVoiceRecording) "VOICE" else currentCallType,
                    timestamp = startTimestamp,
                    durationSeconds = durationSeconds,
                    filePath = outputFilePath,
                    isSynced = false,
                    recordingType = if (isVoiceRecording) "VOICE" else "CALL"
                )
                db.callRecordDao().insertRecord(entity)

                // Upload new recordings automatically only when the user enabled it and selected an account.
                val syncPreferences = getSharedPreferences("call_recorder_settings", MODE_PRIVATE)
                val driveAccount = getSharedPreferences("call_recorder_prefs", MODE_PRIVATE)
                    .getString("google_account_name", null)
                if (syncPreferences.getBoolean("auto_sync_enabled", false) && !driveAccount.isNullOrBlank()) {
                    SyncScheduler.scheduleImmediateSync(
                        applicationContext,
                        requireWifi = syncPreferences.getBoolean("sync_wifi_only", true)
                    )
                }
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
            .setContentTitle(if (isVoiceRecording) "Voice Recorder Active" else "Call Recorder Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
