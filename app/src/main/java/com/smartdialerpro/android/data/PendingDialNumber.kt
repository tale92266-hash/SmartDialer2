package com.smartdialerpro.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A phone number delivered by a tel: link (ACTION_DIAL / ACTION_VIEW)
 * from another app. MainActivity stores it before the UI starts; the
 * Home screen consumes it, pre-filling the dialpad. A plain volatile
 * slot is enough for the number itself, but every delivery must also
 * bump [version]: with singleTask launch mode the activity is reused
 * via onNewIntent, and the Home screen only re-reads the slot when the
 * version changes, so a second tel: tap reaches the dialpad too.
 */
object PendingDialNumber {
    @Volatile
    private var pending: String? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun offer(number: String) {
        val clean = number.trim()
        if (clean.isNotEmpty()) {
            pending = clean
            _version.value += 1
        }
    }

    /** Returns the pending number once, clearing it. */
    fun take(): String? {
        val value = pending
        pending = null
        return value
    }
}
