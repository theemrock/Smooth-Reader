package com.theemrock.smoothreader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {

    @Test fun defaultsMatchBuiltInThemeColors() {
        val defaults = ReaderSettings.defaults()
        ReaderTheme.values().forEach { theme ->
            assertEquals(themeColorsFor(theme).text, defaults.colorsFor(theme).text)
            assertEquals(themeColorsFor(theme).highlight, defaults.colorsFor(theme).highlight)
        }
    }

    @Test fun overrideAppliesOnlyToItsThemeAndKeepsActiveText() {
        val defaults = ReaderSettings.defaults()
        val custom = defaults.copy(
            textColors = defaults.textColors + (ReaderTheme.GECE to 0xFF00FF00.toInt())
        )

        assertEquals(Color(0xFF00FF00), custom.colorsFor(ReaderTheme.GECE).text)
        assertEquals(themeColorsFor(ReaderTheme.GECE).activeText, custom.colorsFor(ReaderTheme.GECE).activeText)
        assertEquals(themeColorsFor(ReaderTheme.SEPYA).text, custom.colorsFor(ReaderTheme.SEPYA).text)
    }

    @Test fun unknownOrMissingFontTypeFallsBackToSans() {
        assertEquals(ReaderFontType.SERIF, parseFontType("SERIF"))
        assertEquals(ReaderFontType.SANS, parseFontType("bilinmeyen"))
        assertEquals(ReaderFontType.SANS, parseFontType(null))
    }
}
