package com.smartdialerpro.android.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Turns the screen off while the phone is held to the ear during a
 * call, using the proximity screen-off wake lock — the same mechanism
 * the system dialer uses. All failures are silent: a phone without a
 * proximity sensor simply keeps the screen on.
 */
class CallProximityController(context: Context) {
    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun start() {
        try {
            if (wakeLock?.isHeld == true) return
            val lock = powerManager?.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "SmartDialer:proximity"
            ) ?: return
            // No timeout: released explicitly on stop(). The lock only
            // blanks the screen while the sensor reports "near".
            lock.acquire()
            wakeLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "Proximity wake lock unavailable", t)
        }
    }

    @Synchronized
    fun stop() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "Proximity wake lock release failed", t)
        } finally {
            wakeLock = null
        }
    }

    companion object {
        private const val TAG = "CallProximity"
    }
}
