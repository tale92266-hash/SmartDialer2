package com.smartdialerpro.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared state for the currently running Magic (fake) call. The
 * FakeCallActivity owns the call and updates this; FakeCallService
 * reads it to build the ongoing-call notification, and the app's
 * return-to-call banner observes it. Nothing here
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

    private val _activeFlow = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _activeFlow
    private val _connectedFlow = MutableStateFlow(false)
    val connectedFlow: StateFlow<Boolean> = _connectedFlow

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
        _connectedFlow.value = false
        _activeFlow.value = true
    }

    fun markConnected(startTimeMillis: Long) {
        connected = true
        this.startTimeMillis = startTimeMillis
        _connectedFlow.value = true
    }

    fun clear() {
        isActive = false
        connected = false
        muted = false
        speaker = false
        startTimeMillis = 0L
        _connectedFlow.value = false
        _activeFlow.value = false
    }
}
