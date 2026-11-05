package com.example.callrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.example.callrecorder.debug.CallRecorderLog
import com.example.callrecorder.service.CallRecordService

/**
 * Listens for telephony state changes to start/stop the foreground recording service.
 *
 * Every branch is logged. Previously a failure inside a broadcast receiver was invisible: the
 * process simply stopped and nothing appeared in the app, which is why "no recordings" looked like a
 * feature regression rather than a crash.
 *
 * `EXTRA_INCOMING_NUMBER` is also read via the deprecated `getSerializableExtra` fallback because it
 * is delivered as a String on some OEM builds and the typed getter returns null there.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = resolveNumber(intent)
        CallRecorderLog.d("CALLS", "PHONE_STATE=$stateStr number=$phoneNumber SDK=${Build.VERSION.SDK_INT}")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered or outgoing call connected.
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_START_RECORDING
                    putExtra(CallRecordService.EXTRA_PHONE_NUMBER, phoneNumber)
                }
                try {
                    context.startForegroundService(serviceIntent)
                    CallRecorderLog.d("CALLS", "startForegroundService(CallRecordService) requested")
                } catch (t: Throwable) {
                    // ForegroundServiceStartNotAllowedException on Android 12+ is the usual reason a
                    // call ends up with no recording at all.
                    CallRecorderLog.e("CALLS", "could not start the recording service", t)
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_STOP_RECORDING
                }
                try {
                    context.startService(serviceIntent)
                    CallRecorderLog.d("CALLS", "stop requested")
                } catch (t: Throwable) {
                    CallRecorderLog.e("CALLS", "could not deliver the stop request", t)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNumber(intent: Intent): String {
        val typed = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!typed.isNullOrBlank()) return typed
        val legacy = runCatching { intent.extras?.get(TelephonyManager.EXTRA_INCOMING_NUMBER) }.getOrNull()
        return legacy?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown"
    }
}
