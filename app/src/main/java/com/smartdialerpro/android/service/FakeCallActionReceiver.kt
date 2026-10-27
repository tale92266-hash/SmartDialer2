package com.smartdialerpro.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Notification actions for the fake call, mirroring [CallActionReceiver].
 * The FakeCallActivity registers its handlers while the call is alive;
 * taps with no live call are harmless no-ops.
 */
object FakeCallActionHandler {
    var onEnd: (() -> Unit)? = null
    var onToggleMute: (() -> Unit)? = null
    var onToggleSpeaker: (() -> Unit)? = null
}

class FakeCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                "ACTION_END_FAKE_CALL" -> FakeCallActionHandler.onEnd?.invoke()
                "ACTION_FAKE_MUTE" -> FakeCallActionHandler.onToggleMute?.invoke()
                "ACTION_FAKE_SPEAKER" -> FakeCallActionHandler.onToggleSpeaker?.invoke()
                else -> Log.w("SmartDialerFakeCall", "Unknown fake call action: ${intent.action}")
            }
        } catch (t: Throwable) {
            Log.e("SmartDialerFakeCall", "Fake call action failed: ${intent.action}", t)
        }
    }
}
