package com.example.callrecorder

import android.app.Application
import com.example.callrecorder.auth.AppPrefs
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.worker.SyncScheduler

/**
 * Application entry point. Its only job today is to install the diagnostic logger before anything
 * else can fail, so crashes and permission problems are captured even when nobody opened a screen.
 */
class CallRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CallRecorderLog.init(this)
        CallRecorderLog.d("APP", "Application.onCreate")
        // Touch the preference file once so the one-time migration from the old file runs here
        // rather than in the middle of a sync.
        AppPrefs.prefs(this)

        // Nothing scheduled periodic sync before: recordings were only uploaded by the manual
        // buttons, so "Automatic sync" could be on with nothing happening in the background.
        runCatching {
            if (AppPrefs.autoSync(this)) SyncScheduler.schedulePeriodicSync(this)
        }.onFailure { CallRecorderLog.e("APP", "could not schedule periodic sync", it) }
    }
}
