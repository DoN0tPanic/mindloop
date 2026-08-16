package com.local.spacedcards.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4FE8),
    onPrimary = Color(0xFFF9F8FF),
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1F156D),
    inversePrimary = Color(0xFFC9C1FF),
    secondary = Color(0xFF7265CD),
    onSecondary = Color(0xFFFCF9FF),
    secondaryContainer = Color(0xFFE8E2FF),
    onSecondaryContainer = Color(0xFF261B58),
    tertiary = Color(0xFF586AC7),
    onTertiary = Color(0xFFFBFBFF),
    tertiaryContainer = Color(0xFFE1E6FF),
    onTertiaryContainer = Color(0xFF172658),
    background = Color(0xFFF8F4EC),
    onBackground = Color(0xFF1E241F),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF1E241F),
    surfaceVariant = Color(0xFFE5E1D8),
    onSurfaceVariant = Color(0xFF474D49),
    surfaceTint = Color(0xFF5B4FE8),
    inverseSurface = Color(0xFF2E3430),
    inverseOnSurface = Color(0xFFEEF2EC),
    surfaceDim = Color(0xFFDDDAD2),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F4EE),
    surfaceContainer = Color(0xFFF2EEE8),
    surfaceContainerHigh = Color(0xFFECE7E1),
    surfaceContainerHighest = Color(0xFFE6E1DB),
    outline = Color(0xFF767D78),
    outlineVariant = Color(0xFFC6CBC5),
    error = Color(0xFFB44D4D),
    onError = Color(0xFFFFFBF8),
    errorContainer = Color(0xFFF9DEDB),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9B90F5),
    onPrimary = Color(0xFF1D1548),
    primaryContainer = Color(0xFF39307C),
    onPrimaryContainer = Color(0xFFE6E1FF),
    inversePrimary = Color(0xFF5B4FE8),
    secondary = Color(0xFFC9C2FF),
    onSecondary = Color(0xFF241850),
    secondaryContainer = Color(0xFF3F376E),
    onSecondaryContainer = Color(0xFFE7E2FF),
    tertiary = Color(0xFFBBC5FF),
    onTertiary = Color(0xFF1D2755),
    tertiaryContainer = Color(0xFF344074),
    onTertiaryContainer = Color(0xFFE0E7FF),
    background = Color(0xFF111715),
    onBackground = Color(0xFFE8EFE8),
    surface = Color(0xFF202B27),
    onSurface = Color(0xFFE8EFE8),
    surfaceVariant = Color(0xFF39453F),
    onSurfaceVariant = Color(0xFFC4D0C8),
    surfaceTint = Color(0xFF9B90F5),
    inverseSurface = Color(0xFFE0E7E1),
    inverseOnSurface = Color(0xFF2C332F),
    surfaceDim = Color(0xFF101613),
    surfaceBright = Color(0xFF35413C),
    surfaceContainerLowest = Color(0xFF0B100E),
    surfaceContainerLow = Color(0xFF18211D),
    surfaceContainer = Color(0xFF1D2723),
    surfaceContainerHigh = Color(0xFF28322E),
    surfaceContainerHighest = Color(0xFF33403A),
    outline = Color(0xFF8B9891),
    outlineVariant = Color(0xFF4A5650),
    error = Color(0xFFFFB4AA),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

@Composable
fun SpacedCardsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
