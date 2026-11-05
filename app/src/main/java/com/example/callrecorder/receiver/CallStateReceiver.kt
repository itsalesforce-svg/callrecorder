package com.example.callrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
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
                // Call answered or outgoing call connected
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_START_RECORDING
                    putExtra(CallRecordService.EXTRA_PHONE_NUMBER, phoneNumber)
                }
                context.startForegroundService(serviceIntent)
            } else if (stateStr == TelephonyManager.EXTRA_STATE_IDLE) {
                // Call disconnected or idle
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_STOP_RECORDING
                }
                context.startService(serviceIntent)
            }
        }
    }
}
