package com.smartdialerpro.android.service

/**
 * Shared state for the currently running Magic (fake) call. The
 * FakeCallActivity owns the call and updates this; FakeCallService
 * reads it to build the ongoing-call notification. Nothing here
 * touches Telecom: a fake call is a pure on-device simulation.
 */
object FakeCallSession {
    @Volatile var isActive: Boolean = false
    @Volatile var name: String = ""
    @Volatile var number: String = ""
    @Volatile var simSlot: Int = 0
    @Volatile var simLabel: String? = null
    @Volatile var durationSecs: Int = 0
    @Volatile var connected: Boolean = false
    @Volatile var muted: Boolean = false
    @Volatile var speaker: Boolean = false
    @Volatile var startTimeMillis: Long = 0L

    fun start(name: String, number: String, simSlot: Int, simLabel: String?, durationSecs: Int) {
        this.name = name
        this.number = number
        this.simSlot = simSlot
        this.simLabel = simLabel
        this.durationSecs = durationSecs
        connected = false
        muted = false
        speaker = false
        startTimeMillis = 0L
        isActive = true
    }

    fun clear() {
        isActive = false
        connected = false
        muted = false
        speaker = false
        startTimeMillis = 0L
    }
}
