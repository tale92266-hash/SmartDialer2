package com.smartdialerpro.android.data

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Call-screen background themes: original gradient presets (no copied
 * artwork). The default is the dark violet gradient the call screens
 * have always used.
 */
object CallThemes {
    data class Theme(val id: String, val label: String, val colors: List<Color>)

    const val DEFAULT_ID = "midnight"

    val THEMES = listOf(
        Theme(
            DEFAULT_ID, "Midnight",
            listOf(Color(0xFF08070D), Color(0xFF15102A), Color(0xFF241238), Color(0xFF100B19))
        ),
        Theme(
            "ocean", "Ocean",
            listOf(Color(0xFF02111F), Color(0xFF0A2E4D), Color(0xFF0E5A8A), Color(0xFF06263F))
        ),
        Theme(
            "sunset", "Sunset",
            listOf(Color(0xFF1A0A12), Color(0xFF4A1530), Color(0xFF8A2E1E), Color(0xFF2A0E1E))
        ),
        Theme(
            "forest", "Forest",
            listOf(Color(0xFF07130C), Color(0xFF123524), Color(0xFF1E5A38), Color(0xFF0A2015))
        ),
        Theme(
            "neon", "Neon",
            listOf(Color(0xFF050510), Color(0xFF101038), Color(0xFF1E1E5E), Color(0xFF0A2A3A))
        ),
        Theme(
            "royal", "Royal",
            listOf(Color(0xFF0D0714), Color(0xFF2A1245), Color(0xFF4A2372), Color(0xFF170B26))
        ),
        Theme(
            "rose", "Rose",
            listOf(Color(0xFF160A12), Color(0xFF3A1230), Color(0xFF6E1E4E), Color(0xFF220D1C))
        )
    )

    fun brushFor(id: String): Brush {
        val theme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]
        return Brush.verticalGradient(theme.colors)
    }

    fun label(id: String): String = THEMES.firstOrNull { it.id == id }?.label ?: THEMES[0].label
}
