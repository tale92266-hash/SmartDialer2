package com.smartdialerpro.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartdialerpro.android.R
import com.smartdialerpro.android.ui.screens.incall.InCallActivity

/** Bridges Android Telecom's complete call lifecycle to CallManager. */
class AppInCallService : InCallService() {
    companion object {
        private const val TAG = "AppInCallService"
        private const val CHANNEL_ID = "ongoing_call_channel"
        private const val NOTIFICATION_ID = 1001
        private const val LEGACY_INCOMING_NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        call ?: return

        val wasEmpty = CallManager.callsSnapshot.isEmpty()
        CallManager.addCall(call, this)
        createNotificationChannel()
        updateNotification()
        cancelLegacyIncomingNotification()

        if (wasEmpty || call.state == Call.STATE_RINGING) openCallActivity()
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        call ?: return

        CallManager.removeCall(call)

        if (CallManager.callsSnapshot.isEmpty()) {
            cancelAllNotifications()
            stopCallForeground()
            stopSelf()
        } else {
            updateNotification()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let(CallManager::updateAudioState)
    }

    fun updateNotification() {
        val call = CallManager.selectedCall
        val state = CallManager.callState.value
        if (call == null || state == Call.STATE_DISCONNECTED) {
            if (CallManager.callsSnapshot.isEmpty()) cancelAllNotifications()
            return
        }

        val notification = if (state == Call.STATE_RINGING) buildIncomingNotification() else buildOngoingNotification()
        startCallForeground(notification)
        cancelLegacyIncomingNotification()
    }

    fun cancelNotification() {
        cancelAllNotifications()
        stopCallForeground()
    }

    private fun startCallForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if (CallManager.isRecording.value) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Foreground call notification update failed", t)
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } catch (notifyError: Throwable) {
                Log.e(TAG, "Unable to post call notification", notifyError)
            }
        }
    }

    private fun stopCallForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to stop call foreground service", t)
        }
    }

    private fun cancelAllNotifications() {
        getSystemService(NotificationManager::class.java)?.apply {
            cancel(NOTIFICATION_ID)
            cancel(LEGACY_INCOMING_NOTIFICATION_ID)
        }
    }

    private fun cancelLegacyIncomingNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(LEGACY_INCOMING_NOTIFICATION_ID)
    }

    private fun openCallActivity() {
        try {
            startActivity(Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to open in-call activity", t)
        }
    }

    private fun openCallPendingIntent(requestCode: Int = 0): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPending(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, CallActionReceiver::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoingNotification(): Notification {
        val state = CallManager.callState.value
        val name = CallManager.callerName.value?.takeIf { it.isNotBlank() }
        val number = CallManager.callNumber.value
        val sim = CallManager.simInfo.value
        val duration = CallManager.duration.value
        val recording = CallManager.isRecording.value

        val title = when (state) {
            Call.STATE_DIALING -> "Calling"
            Call.STATE_CONNECTING -> "Connecting"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_ACTIVE -> if (recording) "Recording call" else "Ongoing call"
            else -> "Call"
        }
        val primary = name ?: number.ifBlank { "Phone call" }
        var secondary = if (name != null && number.isNotBlank()) number else ""
        if (state == Call.STATE_ACTIVE) secondary = (if (secondary.isNotBlank()) "$secondary • " else "") + formatDuration(duration)
        if (recording) secondary = (if (secondary.isNotBlank()) "$secondary • " else "") + "Recording"
        if (!sim.isNullOrBlank()) secondary = (if (secondary.isNotBlank()) "$secondary • " else "") + sim

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(primary)
            .setSubText(secondary)
            .setContentIntent(openCallPendingIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)

        if (state == Call.STATE_ACTIVE) builder.setWhen(System.currentTimeMillis() - duration * 1000L).setUsesChronometer(true)

        builder
            .addAction(R.drawable.ic_call, if (CallManager.isMuted.value) "Unmute" else "Mute", actionPending("ACTION_TOGGLE_MUTE", 6))
            .addAction(R.drawable.ic_volume_up, if (CallManager.audioRoute.value == CallAudioState.ROUTE_SPEAKER) "Earpiece" else "Speaker", actionPending("ACTION_TOGGLE_SPEAKER", 2))
            .addAction(R.drawable.ic_call_end, "End", actionPending("ACTION_END_CALL", 1))

        return builder.build()
    }

    private fun buildIncomingNotification(): Notification {
        val name = CallManager.callerName.value?.takeIf { it.isNotBlank() }
        val number = CallManager.callNumber.value
        val sim = CallManager.simInfo.value
        val primary = name ?: number.ifBlank { "Incoming call" }
        val secondary = buildString {
            if (name != null && number.isNotBlank()) append(number)
            if (!sim.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append(sim)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming call")
            .setContentText(primary)
            .setSubText(secondary)
            .setContentIntent(openCallPendingIntent(5))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openCallPendingIntent(5), true)
            .addAction(R.drawable.ic_call_end, "Decline", actionPending("ACTION_DECLINE_CALL", 4))
            .addAction(R.drawable.ic_call, "Answer", actionPending("ACTION_ANSWER_CALL", 3))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ongoing Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming and ongoing call controls"
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }
}