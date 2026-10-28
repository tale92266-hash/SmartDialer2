package com.smartdialerpro.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = LightError,
    onError = LightOnError
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = DarkError,
    onError = DarkOnError
)

@Composable
fun SmartDialerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accent: String = com.smartdialerpro.android.data.ThemeSettings.ACCENT_BLUE,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = when (accent) {
        com.smartdialerpro.android.data.ThemeSettings.ACCENT_VIOLET -> baseScheme.copy(
            primary = if (darkTheme) AccentVioletDarkPrimary else AccentVioletLightPrimary,
            primaryContainer = if (darkTheme) AccentVioletDarkContainer else AccentVioletLightContainer,
            onPrimaryContainer = if (darkTheme) AccentVioletDarkPrimary else AccentVioletLightPrimary
        )
        com.smartdialerpro.android.data.ThemeSettings.ACCENT_GREEN -> baseScheme.copy(
            primary = if (darkTheme) AccentGreenDarkPrimary else AccentGreenLightPrimary,
            primaryContainer = if (darkTheme) AccentGreenDarkContainer else AccentGreenLightContainer,
            onPrimaryContainer = if (darkTheme) AccentGreenDarkPrimary else AccentGreenLightPrimary
        )
        com.smartdialerpro.android.data.ThemeSettings.ACCENT_ORANGE -> baseScheme.copy(
            primary = if (darkTheme) AccentOrangeDarkPrimary else AccentOrangeLightPrimary,
            primaryContainer = if (darkTheme) AccentOrangeDarkContainer else AccentOrangeLightContainer,
            onPrimaryContainer = if (darkTheme) AccentOrangeDarkPrimary else AccentOrangeLightPrimary
        )
        com.smartdialerpro.android.data.ThemeSettings.ACCENT_ROSE -> baseScheme.copy(
            primary = if (darkTheme) AccentRoseDarkPrimary else AccentRoseLightPrimary,
            primaryContainer = if (darkTheme) AccentRoseDarkContainer else AccentRoseLightContainer,
            onPrimaryContainer = if (darkTheme) AccentRoseDarkPrimary else AccentRoseLightPrimary
        )
        else -> baseScheme
    }.let { scheme ->
        if (darkTheme && pureBlack) {
            scheme.copy(
                background = PureBlackBackground,
                surface = PureBlackSurface,
                surfaceVariant = PureBlackSurfaceVariant
            )
        } else scheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
