package com.theemrock.smoothreader

import androidx.compose.ui.graphics.Color
import com.theemrock.smoothreader.ui.theme.SmoothDarkAppColors
import com.theemrock.smoothreader.ui.theme.SmoothLightAppColors
import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothColorSystemTest {

    @Test
    fun darkAppPaletteMatchesSmoothColorSystemV1() {
        assertEquals(Color(0xFF090B0D), SmoothDarkAppColors.background)
        assertEquals(Color(0xFF111418), SmoothDarkAppColors.surface)
        assertEquals(Color(0xFF191D22), SmoothDarkAppColors.surfaceRaised)
        assertEquals(Color(0xFF22272D), SmoothDarkAppColors.surfaceHigh)
        assertEquals(Color(0xFF2B3138), SmoothDarkAppColors.border)
        assertEquals(Color(0xFF20252B), SmoothDarkAppColors.divider)
        assertEquals(Color(0xFFF4F6F8), SmoothDarkAppColors.textPrimary)
        assertEquals(Color(0xFFA8B0BA), SmoothDarkAppColors.textSecondary)
        assertEquals(Color(0xFF747D87), SmoothDarkAppColors.textTertiary)
        assertEquals(Color(0xFF525A63), SmoothDarkAppColors.disabled)
        assertEquals(Color(0xFF19C3E6), SmoothDarkAppColors.accent)
        assertEquals(Color(0xFF35C978), SmoothDarkAppColors.success)
        assertEquals(Color(0xFFF0B84B), SmoothDarkAppColors.warning)
        assertEquals(Color(0xFFEF5A67), SmoothDarkAppColors.error)
        assertEquals(Color(0xFF4D9FFF), SmoothDarkAppColors.info)
    }

    @Test
    fun lightAppPaletteUsesSpecifiedTokensAndAliasesUnspecifiedOnes() {
        assertEquals(Color(0xFFF5F7F8), SmoothLightAppColors.background)
        assertEquals(Color.White, SmoothLightAppColors.surface)
        assertEquals(Color(0xFFF0F3F5), SmoothLightAppColors.surfaceRaised)
        assertEquals(SmoothLightAppColors.surfaceRaised, SmoothLightAppColors.surfaceHigh)
        assertEquals(Color(0xFFD8DEE3), SmoothLightAppColors.border)
        assertEquals(SmoothLightAppColors.border, SmoothLightAppColors.divider)
        assertEquals(Color(0xFF111418), SmoothLightAppColors.textPrimary)
        assertEquals(Color(0xFF59636E), SmoothLightAppColors.textSecondary)
        assertEquals(Color(0xFF7B858F), SmoothLightAppColors.textTertiary)
        assertEquals(Color(0xFFADB4BB), SmoothLightAppColors.disabled)
        assertEquals(Color(0xFF087F99), SmoothLightAppColors.accent)
        assertEquals(SmoothDarkAppColors.success, SmoothLightAppColors.success)
        assertEquals(SmoothDarkAppColors.warning, SmoothLightAppColors.warning)
        assertEquals(SmoothDarkAppColors.error, SmoothLightAppColors.error)
        assertEquals(SmoothDarkAppColors.info, SmoothLightAppColors.info)
    }
}
