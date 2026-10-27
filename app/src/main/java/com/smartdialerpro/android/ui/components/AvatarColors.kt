package com.smartdialerpro.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deterministic per-contact avatar colours taken from the current theme, so
 * list avatars are colourful but always readable in light and dark themes.
 * The same key always maps to the same colours (String.hashCode is stable).
 */
@Composable
fun avatarColorsFor(key: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.surfaceVariant to scheme.onSurfaceVariant,
        scheme.primary to scheme.onPrimary
    )
    val index = (key.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
}
