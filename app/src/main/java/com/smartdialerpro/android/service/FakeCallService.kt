package com.smartdialerpro.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.smartdialerpro.android.R
import com.smartdialerpro.android.ui.screens.fakecall.FakeCallActivity

/**
 * Foreground service that keeps the Magic (fake) call visible in the
 * notification shade with a CallStyle notification, mirroring the
 * original Dialar FakeCallService. It never touches Telecom.
 */
class FakeCallService : Service() {

    companion object {
        private const val TAG = "FakeCallService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "fake_ongoing_call_channel"

        const val ACTION_START = "com.smartdialerpro.ACTION_START_FAKE_SERVICE"
        const val ACTION_UPDATE = "com.smartdialerpro.ACTION_UPDATE_FAKE_SERVICE"
        const val ACTION_STOP = "com.smartdialerpro.ACTION_STOP_FAKE_SERVICE"

        fun startFakeCall(context: Context) {
            val intent = Intent(context, FakeCallService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to start fake call service", t)
            }
        }

        fun updateState(context: Context) {
            val intent = Intent(context, FakeCallService::class.java).setAction(ACTION_UPDATE)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }

        fun stopFakeCall(context: Context) {
            val intent = Intent(context, FakeCallService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || ACTION_STOP == intent.action) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!FakeCallSession.isActive) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startCallForeground(buildNotification())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Ongoing fake calls", NotificationManager.IMPORTANCE_HIGH)
                channel.setSound(null, null)
                channel.enableLights(false)
                channel.enableVibration(false)
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startCallForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Foreground fake call notification failed", t)
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    private fun openCallPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, FakeCallActivity::class.java).apply {
            action = "ACTION_RETURN_TO_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPending(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, FakeCallActionReceiver::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun callPerson(): Person {
        val name = FakeCallSession.name.takeIf { it.isNotBlank() }
        val number = FakeCallSession.number
        return Person.Builder()
            .setName(name ?: number.ifBlank { "Phone call" })
            .apply { if (number.isNotBlank()) setUri("tel:$number") }
            .setImportant(true)
            .build()
    }

    private fun buildNotification(): Notification {
        val name = FakeCallSession.name.takeIf { it.isNotBlank() }
        val number = FakeCallSession.number
        val primary = name ?: number.ifBlank { "Phone call" }
        val title = if (FakeCallSession.connected) "Ongoing call" else "Calling"
        var secondary = if (name != null && number.isNotBlank()) number else ""
        val sim = FakeCallSession.simLabel
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

        if (FakeCallSession.connected && FakeCallSession.startTimeMillis > 0L) {
            builder.setWhen(FakeCallSession.startTimeMillis).setUsesChronometer(true)
        }

        builder
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    callPerson(),
                    actionPending("ACTION_END_FAKE_CALL", 1)
                )
            )
            .addAction(R.drawable.ic_call, if (FakeCallSession.muted) "Unmute" else "Mute", actionPending("ACTION_FAKE_MUTE", 6))
            .addAction(R.drawable.ic_volume_up, if (FakeCallSession.speaker) "Earpiece" else "Speaker", actionPending("ACTION_FAKE_SPEAKER", 2))
            .addAction(R.drawable.ic_call_end, "End", actionPending("ACTION_END_FAKE_CALL", 1))

        return builder.build()
    }
}
