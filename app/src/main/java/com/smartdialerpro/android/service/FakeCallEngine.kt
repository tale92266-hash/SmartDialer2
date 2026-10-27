package com.smartdialerpro.android.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.random.Random

enum class FakeCallState { IDLE, DIALING, RINGING, ACTIVE, DISCONNECTED }

interface FakeCallEngineListener {
    fun onStateChanged(state: FakeCallState, statusText: String)
    fun onTimeUpdated(formatted: String)
    fun onAudioStateChanged(muted: Boolean, speaker: Boolean)
    fun onCallEnded()
}

/**
 * The fake-call state machine, ported from the original Dialar
 * FakeCallEngine: a short random dialing phase, a few rings, then a
 * connected call with a running timer. No Telecom, no SIM, no network.
 */
class FakeCallEngine(private val context: android.content.Context, private var listener: FakeCallEngineListener?) {

    private var currentState = FakeCallState.IDLE
    private var isCleanedUp = false
    private var isMuted = false
    private var isSpeaker = false
    private var callStartUptime = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentState == FakeCallState.ACTIVE && !isCleanedUp) {
                val secs = ((SystemClock.elapsedRealtime() - callStartUptime) / 1000).toInt()
                listener?.onTimeUpdated(String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60))
                handler.postDelayed(this, 1000)
            }
        }
    }

    fun initialize() {
        if (isCleanedUp) return
        updateState(FakeCallState.DIALING, "Calling...")
        val dialingDelayMs = 1000 + Random.nextInt(1001)
        handler.postDelayed({
            if (isCleanedUp) return@postDelayed
            updateState(FakeCallState.RINGING, "Ringing...")
            val numberOfRings = 2 + Random.nextInt(2)
            val ringingDelayMs = numberOfRings * 3000L
            handler.postDelayed({
                if (isCleanedUp) return@postDelayed
                updateState(FakeCallState.ACTIVE, "Ongoing Call")
                callStartUptime = SystemClock.elapsedRealtime()
                handler.post(timerRunnable)
            }, ringingDelayMs)
        }, dialingDelayMs.toLong())
    }

    fun disconnect() {
        if (isCleanedUp) return
        updateState(FakeCallState.DISCONNECTED, "Call Ended")
        cleanup()
    }

    fun toggleMute() {
        if (currentState == FakeCallState.ACTIVE && !isCleanedUp) {
            isMuted = !isMuted
            listener?.onAudioStateChanged(isMuted, isSpeaker)
        }
    }

    fun toggleSpeaker() {
        if (currentState == FakeCallState.ACTIVE && !isCleanedUp) {
            isSpeaker = !isSpeaker
            listener?.onAudioStateChanged(isMuted, isSpeaker)
        }
    }

    @Synchronized
    fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        handler.removeCallbacksAndMessages(null)
        listener?.onCallEnded()
        listener = null
    }

    fun getCurrentState(): FakeCallState = currentState

    private fun updateState(newState: FakeCallState, statusText: String) {
        if (isCleanedUp && newState != FakeCallState.DISCONNECTED) return
        currentState = newState
        listener?.onStateChanged(newState, statusText)
    }
}
