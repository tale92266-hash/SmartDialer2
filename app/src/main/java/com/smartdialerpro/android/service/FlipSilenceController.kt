package com.smartdialerpro.android.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Flip-to-silence: while a call is ringing, turning the phone
 * face-down silences the ringer without declining the call. The
 * caller supplies the silence action — the system ringer for real
 * calls (via TelecomManager.silenceRinger), the app's own ringtone
 * player for fake calls — because only the caller knows which one
 * is ringing.
 */
class FlipSilenceController(
    context: Context,
    private val onFlip: () -> Unit
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listening = false
    private var fired = false
    private var faceDownSince = 0L

    @Synchronized
    fun start() {
        if (listening || accelerometer == null) return
        try {
            listening = sensorManager?.registerListener(
                this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL
            ) == true
            fired = false
            faceDownSince = 0L
        } catch (t: Throwable) {
            Log.w(TAG, "Flip listener start failed", t)
        }
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (t: Throwable) {
        } finally {
            listening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (fired) return
        // Face-down on a table: z points strongly negative and the
        // device is roughly level (x/y small).
        val z = event.values[2]
        val x = event.values[0]
        val y = event.values[1]
        val faceDown = z < -8f && kotlin.math.abs(x) < 3.5f && kotlin.math.abs(y) < 3.5f
        val now = System.currentTimeMillis()
        if (faceDown) {
            if (faceDownSince == 0L) faceDownSince = now
            if (now - faceDownSince >= 300L) {
                fired = true
                try {
                    onFlip()
                } catch (t: Throwable) {
                    Log.w(TAG, "Flip silence action failed", t)
                }
            }
        } else {
            faceDownSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "FlipSilence"
    }
}
