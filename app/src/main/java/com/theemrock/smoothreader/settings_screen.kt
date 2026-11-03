package com.theemrock.smoothreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ReaderSettingsStore(context) }
    val settings by store.settings.collectAsState(initial = ReaderSettings.defaults())
    val scope = rememberCoroutineScope()
    val engines = remember { installedTtsEngines(context) }
    var selectedEngine by remember { mutableStateOf(TtsEnginePrefs.get(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionTitle("Tema renkleri") }
            items(ReaderTheme.values().toList()) { theme ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(themeLabel(theme), style = MaterialTheme.typography.titleMedium)
                    Text("Yazı rengi", style = MaterialTheme.typography.bodyMedium)
                    val currentText = settings.textColors.getValue(theme)
                    ColorRow(READER_TEXT_PALETTE, currentText) { argb ->
                        scope.launch { store.setTextColor(theme, argb) }
                    }
                    Text("Highlight rengi", style = MaterialTheme.typography.bodyMedium)
                    val currentHighlight = settings.highlightColors.getValue(theme)
                    ColorRow(READER_HIGHLIGHT_PALETTE, currentHighlight) { argb ->
                        scope.launch { store.setHighlightColor(theme, argb) }
                    }
                }
                HorizontalDivider()
            }

            item { SectionTitle("Yazı") }
            item {
                Text("Yazı boyutu", style = MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FONT_SIZE_OPTIONS) { size ->
                        FilterChip(
                            selected = settings.fontSizeSp == size,
                            onClick = { scope.launch { store.setFontSizeSp(size) } },
                            label = { Text("${size.toInt()}") }
                        )
                    }
                }
            }
            item {
                Text("Yazı tipi", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFontType.values().forEach { type ->
                        FilterChip(
                            selected = settings.fontType == type,
                            onClick = { scope.launch { store.setFontType(type) } },
                            label = { Text(fontTypeLabel(type)) }
                        )
                    }
                }
            }

            item { SectionTitle("Seslendirme motoru") }
            item {
                Text(
                    "Değişiklik bir sonraki okuma oturumunda geçerli olur.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(listOf(TtsEngineOption("", "Varsayılan")) + engines) { engine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            TtsEnginePrefs.set(context, engine.packageName)
                            selectedEngine = engine.packageName
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedEngine == engine.packageName,
                        onClick = null
                    )
                    Text(engine.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun ColorRow(palette: List<Color>, selectedArgb: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(palette) { color ->
            ColorSwatch(
                color = color,
                selected = color.toArgb() == selectedArgb,
                onClick = { onPick(color.toArgb()) }
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
            .clickable(onClick = onClick)
    )
}

private fun themeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.GUNDUZ -> "Gündüz"
    ReaderTheme.GECE -> "Gece"
    ReaderTheme.SEPYA -> "Sepya"
}

private fun fontTypeLabel(type: ReaderFontType): String = when (type) {
    ReaderFontType.SANS -> "Sans"
    ReaderFontType.SERIF -> "Serif"
    ReaderFontType.MONOSPACE -> "Mono"
}
