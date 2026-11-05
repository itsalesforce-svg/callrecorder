package com.example.callrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.callrecorder.service.CallRecordService

/**
 * Listens for telephony state changes to start/stop the foreground recording service.
 * Implements logic specified in Android Call Recorder Clone Developer Specification.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"

            if (stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                val preferences = context.getSharedPreferences("call_recorder_settings", Context.MODE_PRIVATE)
                if (!preferences.getBoolean("auto_record", true)) return

                // Calls are recorded only after they connect. Some Android builds prohibit starting
                // a microphone foreground service from this background broadcast; preserve that
                // failure for the Settings screen instead of crashing the receiver.
                val callType = preferences.getString("pending_call_type", "OUTGOING") ?: "OUTGOING"
                preferences.edit().remove("pending_call_type").apply()
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_START_RECORDING
                    putExtra(CallRecordService.EXTRA_PHONE_NUMBER, phoneNumber)
                    putExtra(CallRecordService.EXTRA_CALL_TYPE, callType)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (error: Exception) {
                    Log.e("CallStateReceiver", "Unable to start call recording service", error)
                    preferences.edit().putString(
                        "last_call_recording_issue",
                        "Android blocked automatic call recording from the background. Check microphone and phone permissions; some devices do not allow third-party call capture."
                    ).apply()
                }
            } else if (stateStr == TelephonyManager.EXTRA_STATE_RINGING) {
                context.getSharedPreferences("call_recorder_settings", Context.MODE_PRIVATE)
                    .edit().putString("pending_call_type", "INCOMING").apply()
            } else if (stateStr == TelephonyManager.EXTRA_STATE_IDLE) {
                context.getSharedPreferences("call_recorder_settings", Context.MODE_PRIVATE)
                    .edit().remove("pending_call_type").apply()
                // Call disconnected or idle
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_STOP_CALL_RECORDING
                }
                try {
                    context.startService(serviceIntent)
                } catch (error: Exception) {
                    Log.w("CallStateReceiver", "Could not deliver call-ended event to recorder", error)
                }
            }
        }
    }
}
