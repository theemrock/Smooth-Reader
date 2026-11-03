package com.theemrock.smoothreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * RSVP reader. Starts at the book's saved reading position and writes its position back on
 * pause and on leaving the screen, so the normal reader resumes at the same place.
 * Nothing is written if the user never moved away from the starting word.
 */
@Composable
internal fun RsvpScreen(book: Book, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parser = remember { DocumentParserV1() }
    val progressStore = remember(context) { ProgressStore(context) }
    var paragraphs by remember(book.id) { mutableStateOf<List<String>?>(null) }
    var words by remember(book.id) { mutableStateOf<List<String>?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var index by rememberSaveable(book.id) { mutableIntStateOf(0) }
    var resumeIndex by remember(book.id) { mutableIntStateOf(0) }
    var playing by rememberSaveable(book.id) { mutableStateOf(false) }
    var wpm by rememberSaveable { mutableIntStateOf(RSVP_DEFAULT_WPM) }

    LaunchedEffect(book.id) {
        val cr = context.contentResolver
        when (val res = withContext(Dispatchers.IO) {
            ParsedCache.getOrParse(book.id, parse = { parser.parse(book, cr) })
        }) {
            is ParseResult.Success -> {
                val loaded = res.parsed.paragraphs
                val stored = withContext(Dispatchers.IO) {
                    progressStore.storedProgress(book.id).first().first
                }
                val pos = stored?.position ?: ReadingPosition()
                val start = rsvpStartWordIndex(loaded, pos.paragraphIndex, pos.characterOffset)
                paragraphs = loaded
                words = rsvpWords(loaded)
                resumeIndex = start.coerceIn(0, (words?.lastIndex ?: 0).coerceAtLeast(0))
                index = resumeIndex
            }
            is ParseResult.Error -> error = res.message
        }
    }

    val list = words
    LaunchedEffect(playing, wpm, list) {
        if (list == null) return@LaunchedEffect
        while (playing && index < list.lastIndex) {
            delay(rsvpDelayMs(wpm, list[index]))
            index++
        }
        if (index >= list.lastIndex) playing = false
    }

    // Saves the word the user reached as the book's reading position. Skipped if nothing moved.
    fun persist(paragraphsNow: List<String>, at: Int) {
        val pos = rsvpPositionForWordIndex(paragraphsNow, at)
        CoroutineScope(Dispatchers.IO).launch {
            progressStore.setProgress(ReadingProgress(book.id, pos, paragraphsNow.size))
        }
    }

    LaunchedEffect(playing) {
        val ps = paragraphs ?: return@LaunchedEffect
        if (!playing && index != resumeIndex) persist(ps, index)
    }

    DisposableEffect(book.id) {
        onDispose {
            val ps = paragraphs
            if (ps != null && index != resumeIndex) persist(ps, index)
        }
    }

    val errorMessage = error
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            errorMessage != null -> Text(errorMessage, style = MaterialTheme.typography.bodyLarge)
            list == null -> Text("Kitap hazırlanıyor…", style = MaterialTheme.typography.bodyLarge)
            list.isEmpty() -> Text("Kitapta kelime yok.", style = MaterialTheme.typography.bodyLarge)
            else -> {
                val current = index.coerceIn(0, list.lastIndex)
                val word = list[current]
                val orp = orpIndex(word)
                val wordStyle = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp
                )
                // ORP letter stays on the screen centre: equal-weight halves, left text right-aligned to the centre, right text left-aligned.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text(text = word.take(orp), style = wordStyle, maxLines = 1, softWrap = false)
                    }
                    Text(
                        text = word.getOrNull(orp)?.toString().orEmpty(),
                        style = wordStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(text = word.drop(orp + 1), style = wordStyle, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "${formatCount(current + 1)} / ${formatCount(list.size)}",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "Kalan süre: ${formatHhMm(rsvpRemainingMs(list, current, wpm))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (index >= list.lastIndex) index = resumeIndex
                        playing = !playing
                    }) {
                        Text(if (playing) "Durdur" else "Başlat")
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Hız: $wpm kelime/dk", style = MaterialTheme.typography.labelLarge)
                // Inset from the screen edges so swipes that start there are not taken as the system back gesture.
                Slider(
                    value = wpm.toFloat(),
                    onValueChange = { wpm = clampWpm(it.roundToInt()) },
                    valueRange = RSVP_MIN_WPM.toFloat()..RSVP_MAX_WPM.toFloat(),
                    steps = (RSVP_MAX_WPM - RSVP_MIN_WPM) / RSVP_WPM_STEP - 1,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}
