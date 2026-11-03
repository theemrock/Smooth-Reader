package com.theemrock.smoothreader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal enum class ReaderFontType {
    SANS,
    SERIF,
    MONOSPACE
}

internal const val DEFAULT_FONT_SIZE_SP = 18f

internal fun parseFontType(name: String?): ReaderFontType =
    runCatching { ReaderFontType.valueOf(name.orEmpty()) }.getOrDefault(ReaderFontType.SANS)

/** Selectable colors. Text colors are opaque; highlight colors keep their alpha. */
internal val READER_TEXT_PALETTE: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF222222), Color(0xFF4A3728), Color(0xFFE0E0E0),
    Color(0xFFFFFFFF), Color(0xFF1B263B), Color(0xFF2E7D32), Color(0xFF8B0000)
)

internal val READER_HIGHLIGHT_PALETTE: List<Color> = listOf(
    Color(0x4019C3E6), Color(0x59F0B84B), Color(0x404CAF50), Color(0x59FFEB3B),
    Color(0x40E91E63), Color(0x409C27B0), Color(0x40FF5722), Color(0x403F51B5)
)

/** Per-theme text and highlight colors (ARGB). Background, paper and active text stay built-in. */
internal data class ReaderSettings(
    val textColors: Map<ReaderTheme, Int>,
    val highlightColors: Map<ReaderTheme, Int>,
    val fontSizeSp: Float,
    val fontType: ReaderFontType
) {
    fun colorsFor(theme: ReaderTheme): ReaderThemeColors {
        val base = themeColorsFor(theme)
        return base.copy(
            text = textColors[theme]?.let { Color(it) } ?: base.text,
            highlight = highlightColors[theme]?.let { Color(it) } ?: base.highlight
        )
    }

    companion object {
        fun defaults(): ReaderSettings = ReaderSettings(
            textColors = ReaderTheme.values().associateWith { themeColorsFor(it).text.toArgb() },
            highlightColors = ReaderTheme.values().associateWith { themeColorsFor(it).highlight.toArgb() },
            fontSizeSp = DEFAULT_FONT_SIZE_SP,
            fontType = ReaderFontType.SANS
        )
    }
}

private fun textKey(theme: ReaderTheme) = intPreferencesKey("reader_text_${theme.name}")
private fun highlightKey(theme: ReaderTheme) = intPreferencesKey("reader_highlight_${theme.name}")
private val KEY_FONT_SIZE = floatPreferencesKey("reader_font_size_sp")
private val KEY_FONT_TYPE = stringPreferencesKey("reader_font_type")

private fun Preferences.toReaderSettings(): ReaderSettings {
    val defaults = ReaderSettings.defaults()
    return ReaderSettings(
        textColors = ReaderTheme.values().associateWith { this[textKey(it)] ?: defaults.textColors.getValue(it) },
        highlightColors = ReaderTheme.values().associateWith {
            this[highlightKey(it)] ?: defaults.highlightColors.getValue(it)
        },
        fontSizeSp = (this[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE_SP).coerceIn(12f, 40f),
        fontType = parseFontType(this[KEY_FONT_TYPE])
    )
}

internal class ReaderSettingsStore(private val context: Context) {

    val settings: Flow<ReaderSettings> = context.dataStore.data.map { it.toReaderSettings() }

    suspend fun setTextColor(theme: ReaderTheme, argb: Int) {
        context.dataStore.edit { it[textKey(theme)] = argb }
    }

    suspend fun setHighlightColor(theme: ReaderTheme, argb: Int) {
        context.dataStore.edit { it[highlightKey(theme)] = argb }
    }

    suspend fun setFontSizeSp(sp: Float) {
        context.dataStore.edit { it[KEY_FONT_SIZE] = sp }
    }

    suspend fun setFontType(type: ReaderFontType) {
        context.dataStore.edit { it[KEY_FONT_TYPE] = type.name }
    }
}

/**
 * TTS engine package is read synchronously when ReaderPlaybackService starts, so it lives in
 * SharedPreferences rather than DataStore. Empty means the system default engine.
 */
internal object TtsEnginePrefs {
    private const val FILE = "reader_tts_prefs"
    private const val KEY_ENGINE = "engine_package"

    fun get(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ENGINE, "").orEmpty()

    fun set(context: Context, enginePackage: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, enginePackage).apply()
    }
}

internal data class TtsEngineOption(val packageName: String, val label: String)

internal fun installedTtsEngines(context: Context): List<TtsEngineOption> {
    val pm = context.packageManager
    return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_ALL)
        .map { TtsEngineOption(it.serviceInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
}
