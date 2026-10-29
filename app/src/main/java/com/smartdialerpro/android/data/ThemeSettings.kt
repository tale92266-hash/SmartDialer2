package com.smartdialerpro.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Appearance choices: accent colour + pure-black dark theme. Kept in
 * app_prefs like the other settings, exposed as StateFlows so the
 * theme recomposes live when the user picks a new accent — no app
 * restart needed.
 */
object ThemeSettings {
    const val ACCENT_BLUE = "blue"
    const val ACCENT_VIOLET = "violet"
    const val ACCENT_GREEN = "green"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_ROSE = "rose"
    /** Material You: colours taken from the wallpaper (Android 12+). */
    const val ACCENT_DYNAMIC = "wallpaper"

    val ACCENTS = listOf(ACCENT_BLUE, ACCENT_VIOLET, ACCENT_GREEN, ACCENT_ORANGE, ACCENT_ROSE, ACCENT_DYNAMIC)

    fun accentLabel(id: String): String = when (id) {
        ACCENT_VIOLET -> "Violet"
        ACCENT_GREEN -> "Green"
        ACCENT_ORANGE -> "Orange"
        ACCENT_ROSE -> "Rose"
        ACCENT_DYNAMIC -> "Wallpaper colours"
        else -> "Blue"
    }

    private val _accent = MutableStateFlow(ACCENT_BLUE)
    val accent = _accent.asStateFlow()

    private val _pureBlack = MutableStateFlow(false)
    val pureBlack = _pureBlack.asStateFlow()

    /** Call-screen gradient theme id (see CallThemes). */
    private val _callTheme = MutableStateFlow(CallThemes.DEFAULT_ID)
    val callTheme = _callTheme.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedAccent = prefs.getString("theme_accent", ACCENT_BLUE) ?: ACCENT_BLUE
        _accent.value = if (savedAccent in ACCENTS) savedAccent else ACCENT_BLUE
        _pureBlack.value = prefs.getBoolean("theme_pure_black", false)
        val savedCallTheme = prefs.getString("call_theme", CallThemes.DEFAULT_ID) ?: CallThemes.DEFAULT_ID
        _callTheme.value = if (CallThemes.THEMES.any { it.id == savedCallTheme }) savedCallTheme else CallThemes.DEFAULT_ID
    }

    fun setAccent(context: Context, id: String) {
        val value = if (id in ACCENTS) id else ACCENT_BLUE
        _accent.value = value
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme_accent", value).apply()
    }

    fun setPureBlack(context: Context, enabled: Boolean) {
        _pureBlack.value = enabled
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("theme_pure_black", enabled).apply()
    }

    fun setCallTheme(context: Context, id: String) {
        val value = if (CallThemes.THEMES.any { it.id == id }) id else CallThemes.DEFAULT_ID
        _callTheme.value = value
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("call_theme", value).apply()
    }
}
