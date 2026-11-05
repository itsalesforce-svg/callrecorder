package com.example.callrecorder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.callrecorder.MainActivity
import com.example.callrecorder.data.RecordingStorage
import com.example.callrecorder.data.VoiceCategory
import com.example.callrecorder.data.local.AppDatabase
import com.example.callrecorder.data.local.CallRecordEntity
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records meetings, podcasts and voice notes on demand.
 *
 * This is the manual recorder: nothing about it depends on the phone app, the telephony stack or the
 * call-state broadcast, which is why it works while call capture may not. It is a foreground service
 * so a long meeting keeps recording with the screen off.
 *
 * Every stage is logged, and the result (saved row, or the exact reason it failed) is published to
 * [VoiceRecorderState] so the screen can show it instead of failing silently.
 */
class VoiceRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.callrecorder.VOICE_START"
        const val ACTION_STOP = "com.example.callrecorder.VOICE_STOP"
        const val EXTRA_CATEGORY = "extra_voice_category"

        private const val CHANNEL_ID = "voice_recording_channel"
        private const val NOTIFICATION_ID = 2001

        /** Warm-up before checking that audio is really being captured. */
        private const val WARM_UP_MS = 1500L

        fun start(context: Context, category: String) {
            val intent = Intent(context, VoiceRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CATEGORY, category)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceRecorderService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var category: String = VoiceCategory.MEETING

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CallRecorderLog.d("VOICE", "service created (API ${Build.VERSION.SDK_INT})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallRecorderLog.d("VOICE", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                category = intent.getStringExtra(EXTRA_CATEGORY) ?: VoiceCategory.MEETING
                begin()
            }
            ACTION_STOP -> finish(save = true)
            else -> finish(save = true)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (recorder != null) {
            CallRecorderLog.d("VOICE", "already recording, ignoring start")
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val message = "Microphone permission is missing, so nothing could be recorded."
            VoiceRecorderState.onFailed(message)
            CallRecorderLog.w("VOICE", message)
            stopSelf()
            return
        }

        startedAt = System.currentTimeMillis()
        val file = RecordingStorage.fileForVoice(applicationContext, category, startedAt)
        outputFile = file

        try {
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                setOnErrorListener { _, what, extra ->
                    CallRecorderLog.e("VOICE", "MediaRecorder error what=$what extra=$extra")
                }
                prepare()
                start()
            }
        } catch (t: Throwable) {
            val message = "The recorder could not start on this device " +
                "(${t.javaClass.simpleName}: ${t.message})."
            VoiceRecorderState.onFailed(message)
            CallRecorderLog.e("VOICE", message, t)
            releaseRecorder()
            runCatching { file.delete() }
            stopSelf()
            return
        }

        startForegroundCompat(buildNotification())
        VoiceRecorderState.onStarted(startedAt, category)
        CallRecorderLog.d("VOICE", "recording ${VoiceCategory.label(category)} -> ${file.name}")

        // Prove that sound is actually being captured: a recorder that starts and hears nothing is the
        // single most confusing failure, and for a meeting it would waste the whole session.
        scope.launch {
            try {
                Thread.sleep(WARM_UP_MS)
                if (recorder == null) return@launch
                val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                CallRecorderLog.d("VOICE", "peak amplitude after warm-up: $peak")
                if (peak == 0) {
                    CallRecorderLog.w(
                        "VOICE",
                        "no sound detected yet - continuing, the user may simply be silent"
                    )
                }
            } catch (t: Throwable) {
                CallRecorderLog.e("VOICE", "amplitude probe failed", t)
            }
        }
    }

    private fun finish(save: Boolean) {
        val file = outputFile
        val recorderRef = recorder
        if (recorderRef == null || file == null) {
            VoiceRecorderState.onStopped()
            stopForegroundCompat()
            stopSelf()
            return
        }

        val durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        var stopError: Throwable? = try {
            recorderRef.stop()
            null
        } catch (t: Throwable) {
            // stop() throws when the recording was too short to contain a single frame.
            CallRecorderLog.e("VOICE", "stop() failed - recording too short?", t)
            t
        }
        releaseRecorder()

        val length = file.length()
        CallRecorderLog.d(
            "VOICE",
            "stopped: file=${file.name} exists=${file.exists()} bytes=$length duration=${durationSeconds}s"
        )

        if (!save || stopError != null || length == 0L) {
            runCatching { file.delete() }
            val message = "Nothing was saved: the recording was too short (${durationSeconds}s). " +
                "Record for at least two seconds."
            VoiceRecorderState.onFailed(message)
            stopForegroundCompat()
            stopSelf()
            return
        }

        val typedCategory = category
        val duration = durationSeconds
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).callRecordDao()
                val id = dao.insertRecord(
                    CallRecordEntity(
                        contactName = VoiceCategory.label(typedCategory),
                        // Blank on purpose: this is not a phone number. The list and the Drive file
                        // name both fall back to the label when the field is empty.
                        phoneNumber = "",
                        callType = typedCategory,
                        timestamp = startedAt,
                        durationSeconds = duration,
                        filePath = file.absolutePath,
                        isSynced = false
                    )
                )
                CallRecorderLog.d("VOICE", "saved record #$id (${file.name})")
                VoiceRecorderState.onSaved(
                    id,
                    "${VoiceCategory.label(typedCategory)} saved (${formatDuration(duration)})."
                )
                SyncScheduler.scheduleImmediateSync(applicationContext)
            } catch (t: Throwable) {
                CallRecorderLog.e("VOICE", "could not save the recording to the library", t)
                VoiceRecorderState.onFailed(
                    "The audio file was written but the library entry failed " +
                        "(${t.javaClass.simpleName}). The file is at ${file.absolutePath}"
                )
            } finally {
                // only now is it safe to die: the row is written
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes > 0) "${minutes}m ${rest}s" else "${rest}s"
    }

    private fun embedStopAction() = PendingIntent.getService(
        this,
        0,
        Intent(this, VoiceRecorderService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording ${VoiceCategory.label(category)}")
            .setContentText("Tap stop when you are done")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", embedStopAction())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
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

    private fun releaseRecorder() {
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while a meeting, podcast or voice note is being recorded" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        CallRecorderLog.d("VOICE", "onDestroy (recorder=${recorder != null})")
        releaseRecorder()
        scope.cancel()
        VoiceRecorderState.onStopped()
    }
}
