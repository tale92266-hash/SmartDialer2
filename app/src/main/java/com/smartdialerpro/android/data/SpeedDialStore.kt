package com.smartdialerpro.android.data

import android.content.Context

/**
 * Speed dial: long-press a dialpad digit (2-9) to call the number
 * stored for it, or to store the currently typed number. Stored in
 * app_prefs; no database needed for nine small values.
 */
object SpeedDialStore {
    private fun keyFor(digit: String) = "speed_dial_$digit"

    fun get(context: Context, digit: String): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString(keyFor(digit), null)?.takeIf { it.isNotBlank() }
    }

    fun set(context: Context, digit: String, number: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString(keyFor(digit), number).apply()
    }

    fun clear(context: Context, digit: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().remove(keyFor(digit)).apply()
    }

    /** Digits 2-9 that currently have a number stored. */
    fun assignedDigits(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return (2..9).map { it.toString() }
            .filter { prefs.getString(keyFor(it), null)?.isNotBlank() == true }
            .toSet()
    }
}
