package com.smartdialerpro.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                "ACTION_END_CALL" -> CallManager.endCall()
                "ACTION_TOGGLE_MUTE" -> CallManager.toggleMute()
                "ACTION_TOGGLE_SPEAKER" -> CallManager.toggleSpeaker()
                "ACTION_ANSWER_CALL" -> CallManager.answer()
                "ACTION_DECLINE_CALL" -> CallManager.reject()
                else -> Log.w("SmartDialerCallAction", "Unknown call action: ${intent.action}")
            }
        } catch (t: Throwable) {
            Log.e("SmartDialerCallAction", "Call action failed: ${intent.action}", t)
        }
    }
}
