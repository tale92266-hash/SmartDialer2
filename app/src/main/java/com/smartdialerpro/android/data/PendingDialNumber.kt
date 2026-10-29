package com.smartdialerpro.android.data

/**
 * A phone number delivered by a tel: link (ACTION_DIAL / ACTION_VIEW)
 * from another app. MainActivity stores it before the UI starts; the
 * Home screen consumes it once, pre-filling the dialpad. A plain
 * volatile slot is enough: it is written once per activity launch
 * and read once by the first Home composition.
 */
object PendingDialNumber {
    @Volatile
    private var pending: String? = null

    fun offer(number: String) {
        val clean = number.trim()
        if (clean.isNotEmpty()) pending = clean
    }

    /** Returns the pending number once, clearing it. */
    fun take(): String? {
        val value = pending
        pending = null
        return value
    }
}
