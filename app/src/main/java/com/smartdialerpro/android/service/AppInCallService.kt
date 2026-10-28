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
import androidx.core.app.Person
import com.smartdialerpro.android.R
import com.smartdialerpro.android.ui.screens.incall.InCallActivity

/** Bridges Android Telecom's complete call lifecycle to CallManager. */
class AppInCallService : InCallService() {
    companion object {
        private const val TAG = "AppInCallService"
        private const val CHANNEL_ID = "ongoing_call_channel"
        /** Low-importance channel used while the in-call screen is in the
         * foreground: the notification sits quietly in the shade instead
         * of popping over the call screen as a heads-up. */
        private const val QUIET_CHANNEL_ID = "ongoing_call_channel_quiet"
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

    /** The caller as a compat Person, so call notifications render in the system call style. */
    private fun callPerson(): Person {
        val name = CallManager.callerName.value?.takeIf { it.isNotBlank() }
        val number = CallManager.callNumber.value
        return Person.Builder()
            .setName(name ?: number.ifBlank { "Phone call" })
            .apply { if (number.isNotBlank()) setUri("tel:$number") }
            .setImportant(true)
            .build()
    }

    /** Channel + priority for the current moment: quiet while the user
     * is already looking at the call screen, alerting otherwise so an
     * incoming call can still wake the phone from another app. */
    private fun activeChannelId() =
        if (CallManager.inCallUiForeground) QUIET_CHANNEL_ID else CHANNEL_ID

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

        val builder = NotificationCompat.Builder(this, activeChannelId())
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(primary)
            .setSubText(secondary)
            .setContentIntent(openCallPendingIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (CallManager.inCallUiForeground) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)

        if (state == Call.STATE_ACTIVE) builder.setWhen(System.currentTimeMillis() - duration * 1000L).setUsesChronometer(true)

        builder
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    callPerson(),
                    actionPending("ACTION_END_CALL", 1)
                )
            )
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

        val builder = NotificationCompat.Builder(this, activeChannelId())
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming call")
            .setContentText(primary)
            .setSubText(secondary)
            .setContentIntent(openCallPendingIntent(5))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (CallManager.inCallUiForeground) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // The full-screen intent is what makes the call screen open over
        // the lock screen — and what pops the heads-up over an already
        // visible call screen. Only attach it while the call screen is
        // not already in the foreground.
        if (!CallManager.inCallUiForeground) {
            builder.setFullScreenIntent(openCallPendingIntent(5), true)
        }
        return builder
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callPerson(),
                    actionPending("ACTION_DECLINE_CALL", 4),
                    actionPending("ACTION_ANSWER_CALL", 3)
                )
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ongoing Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming and ongoing call controls"
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            manager?.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL_ID, "Call in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing call controls while the call screen is open"
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