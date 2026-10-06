package com.theemrock.smoothreader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class SmoothAppColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigh: Color,
    val border: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val disabled: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color
)

private val SmoothSuccess = Color(0xFF35C978)
private val SmoothWarning = Color(0xFFF0B84B)
private val SmoothError = Color(0xFFEF5A67)
private val SmoothInfo = Color(0xFF4D9FFF)
private val SmoothLightSurfaceRaised = Color(0xFFF0F3F5)
private val SmoothLightBorder = Color(0xFFD8DEE3)

internal val SmoothDarkAppColors = SmoothAppColors(
    background = Color(0xFF090B0D),
    surface = Color(0xFF111418),
    surfaceRaised = Color(0xFF191D22),
    surfaceHigh = Color(0xFF22272D),
    border = Color(0xFF2B3138),
    divider = Color(0xFF20252B),
    textPrimary = Color(0xFFF4F6F8),
    textSecondary = Color(0xFFA8B0BA),
    textTertiary = Color(0xFF747D87),
    disabled = Color(0xFF525A63),
    accent = Color(0xFF19C3E6),
    success = SmoothSuccess,
    warning = SmoothWarning,
    error = SmoothError,
    info = SmoothInfo
)

internal val SmoothLightAppColors = SmoothAppColors(
    background = Color(0xFFF5F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = SmoothLightSurfaceRaised,
    // V1 does not define distinct light values for these roles.
    surfaceHigh = SmoothLightSurfaceRaised,
    border = SmoothLightBorder,
    divider = SmoothLightBorder,
    textPrimary = Color(0xFF111418),
    textSecondary = Color(0xFF59636E),
    textTertiary = Color(0xFF7B858F),
    disabled = Color(0xFFADB4BB),
    accent = Color(0xFF087F99),
    success = SmoothSuccess,
    warning = SmoothWarning,
    error = SmoothError,
    info = SmoothInfo
)
