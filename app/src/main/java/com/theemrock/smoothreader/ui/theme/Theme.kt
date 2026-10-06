package com.theemrock.smoothreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalSmoothAppColors = staticCompositionLocalOf {
    SmoothDarkAppColors
}

val MaterialTheme.smoothColors: SmoothAppColors
    @Composable
    get() = LocalSmoothAppColors.current

private fun darkMaterialColorScheme(colors: SmoothAppColors): ColorScheme = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.background,
    primaryContainer = colors.surfaceHigh,
    onPrimaryContainer = colors.accent,
    secondary = colors.textSecondary,
    onSecondary = colors.background,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.textPrimary,
    tertiary = colors.info,
    onTertiary = colors.background,
    tertiaryContainer = colors.surfaceHigh,
    onTertiaryContainer = colors.textPrimary,
    background = colors.background,
    onBackground = colors.textPrimary,
    surface = colors.surface,
    onSurface = colors.textPrimary,
    surfaceBright = colors.surfaceHigh,
    surfaceDim = colors.background,
    surfaceContainerLowest = colors.background,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceRaised,
    surfaceContainerHighest = colors.surfaceHigh,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textSecondary,
    surfaceTint = colors.surface,
    inverseSurface = colors.textPrimary,
    inverseOnSurface = colors.background,
    inversePrimary = colors.accent,
    error = colors.error,
    onError = colors.background,
    errorContainer = colors.surfaceHigh,
    onErrorContainer = colors.error,
    outline = colors.border,
    outlineVariant = colors.divider,
    scrim = colors.background
)

private fun lightMaterialColorScheme(colors: SmoothAppColors): ColorScheme = lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.surface,
    primaryContainer = colors.surfaceRaised,
    onPrimaryContainer = colors.accent,
    secondary = colors.textSecondary,
    onSecondary = colors.surface,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.textPrimary,
    tertiary = colors.info,
    onTertiary = colors.surface,
    tertiaryContainer = colors.surfaceRaised,
    onTertiaryContainer = colors.textPrimary,
    background = colors.background,
    onBackground = colors.textPrimary,
    surface = colors.surface,
    onSurface = colors.textPrimary,
    surfaceBright = colors.surfaceHigh,
    surfaceDim = colors.background,
    surfaceContainerLowest = colors.background,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceRaised,
    surfaceContainerHighest = colors.surfaceHigh,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textSecondary,
    surfaceTint = colors.surface,
    inverseSurface = colors.textPrimary,
    inverseOnSurface = colors.surface,
    inversePrimary = colors.accent,
    error = colors.error,
    onError = colors.textPrimary,
    errorContainer = colors.surfaceRaised,
    onErrorContainer = colors.error,
    outline = colors.border,
    outlineVariant = colors.divider,
    scrim = colors.textPrimary
)

@Composable
fun SmoothAppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) SmoothDarkAppColors else SmoothLightAppColors
    val materialColors = if (darkTheme) {
        darkMaterialColorScheme(appColors)
    } else {
        lightMaterialColorScheme(appColors)
    }

    CompositionLocalProvider(LocalSmoothAppColors provides appColors) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = Typography,
            shapes = SmoothShapes,
            content = content
        )
    }
}
