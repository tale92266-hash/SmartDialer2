package com.smartdialerpro.android.service

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Duration picker for Magic (fake) calls, ported from the original
 * Dialar FakeCallDialogHelper. Random durations never repeat within a
 * day until the pool is exhausted, and stay a few seconds away from the
 * previous pick so every fake call feels like a different real call.
 */
object FakeCallDurations {

    const val MODE_SHORT = 0
    const val MODE_MID = 1
    const val MODE_LONG = 2

    /** The highlighted quick option: instant start, fixed 120 seconds. */
    const val QUICK_TWO_MINUTES = 120

    private fun prefs(context: Context) =
        context.getSharedPreferences("FakeCallTracker", Context.MODE_PRIVATE)

    /**
     * "Smart Random": mostly long calls, sometimes mid/short, with a cap
     * on consecutive long picks so the pattern never looks staged.
     */
    fun ultimateRandom(context: Context): Int {
        val p = prefs(context)
        val consecutiveLong = p.getInt("consecutive_long", 0)
        val category = if (consecutiveLong >= 4) {
            if (Random.nextInt(100) < 60) 1 else 0
        } else {
            when (Random.nextInt(100)) {
                in 0 until 75 -> 2
                in 75 until 90 -> 1
                else -> 0
            }
        }
        val duration = when (category) {
            2 -> {
                p.edit().putInt("consecutive_long", consecutiveLong + 1).apply()
                Random.nextInt(30) + 61
            }
            1 -> {
                p.edit().putInt("consecutive_long", 0).apply()
                Random.nextInt(17) + 31
            }
            else -> {
                p.edit().putInt("consecutive_long", 0).apply()
                Random.nextInt(15) + 1
            }
        }
        return duration
    }

    /**
     * Mode durations: short = 1-28s, mid = 31-47s, long = 61-87s.
     * Each mode keeps its own per-day pool of used values.
     */
    fun smartRandom(context: Context, modeIndex: Int): Int {
        val p = prefs(context)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        if (p.getString("saved_date", "") != today) {
            p.edit().clear().putString("saved_date", today).apply()
        }
        var min = 1
        var max = 15
        var minGap = 3
        when (modeIndex) {
            MODE_SHORT -> { min = 1; max = 28; minGap = 4 }
            MODE_MID -> { min = 31; max = 47; minGap = 4 }
            MODE_LONG -> { min = 61; max = 87; minGap = 6 }
        }
        val key = "used_durations_mode_$modeIndex"
        val lastValKey = "last_val_mode_$modeIndex"
        val usedSet = p.getStringSet(key, emptySet()) ?: emptySet()
        val lastDuration = p.getInt(lastValKey, -1)

        var pool = (min..max).filter { i ->
            !usedSet.contains(i.toString()) && (lastDuration == -1 || kotlin.math.abs(i - lastDuration) >= minGap)
        }
        if (pool.isEmpty()) {
            pool = (min..max).filter { i -> !usedSet.contains(i.toString()) && i != lastDuration }
        }
        val mutableUsed = usedSet.toMutableSet()
        if (pool.isEmpty()) {
            mutableUsed.clear()
            pool = (min..max).filter { it != lastDuration }
        }
        val picked = pool[Random.nextInt(pool.size)]
        mutableUsed.add(picked.toString())
        p.edit().putStringSet(key, mutableUsed).putInt(lastValKey, picked).apply()
        return picked
    }
}
