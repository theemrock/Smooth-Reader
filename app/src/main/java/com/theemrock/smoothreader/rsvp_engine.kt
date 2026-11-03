package com.theemrock.smoothreader

internal const val RSVP_MIN_WPM = 200
internal const val RSVP_MAX_WPM = 1000
internal const val RSVP_DEFAULT_WPM = 300
internal const val RSVP_WPM_STEP = 10

/** One displayable word: its range inside the paragraph and the cleaned text. */
private data class WordToken(val range: IntRange, val text: String)

/**
 * Whitespace-separated tokens of one paragraph, punctuation removed.
 * Tokens with no letters or digits left (a lone dash, a quote mark) are dropped.
 */
private fun wordTokens(paragraph: String): List<WordToken> =
    Regex("\\S+").findAll(paragraph).mapNotNull { match ->
        val clean = match.value.filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) null else WordToken(match.range, clean)
    }.toList()

/** Words in reading order, punctuation removed. */
internal fun rsvpWords(paragraphs: List<String>): List<String> =
    paragraphs.flatMap { paragraph -> wordTokens(paragraph).map { it.text } }

/**
 * Optimal Recognition Point: the character the eye should fixate on.
 * Fixed at 50% of the word length.
 */
internal fun orpIndex(word: String): Int {
    if (word.isEmpty()) return 0
    return (word.length / 2).coerceAtMost(word.lastIndex)
}

/** Time one word stays on screen. Long words get 50% extra time. */
internal fun rsvpDelayMs(wpm: Int, word: String): Long {
    val base = 60_000L / wpm.coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)
    return if (word.length > 9) base * 3 / 2 else base
}

/** Snaps to the WPM step and clamps to the allowed range. */
internal fun clampWpm(value: Int): Int {
    val stepped = Math.round(value.toFloat() / RSVP_WPM_STEP) * RSVP_WPM_STEP
    return stepped.coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)
}

/**
 * RSVP word index for a reading position (paragraph + character offset inside it).
 * Points at the word containing the offset, or the next word if the offset sits on whitespace.
 * Counts words exactly as [rsvpWords] does.
 */
internal fun rsvpStartWordIndex(paragraphs: List<String>, paragraphIndex: Int, characterOffset: Int): Int {
    if (paragraphs.isEmpty()) return 0
    val target = paragraphIndex.coerceIn(0, paragraphs.lastIndex)
    var before = 0
    for (i in 0 until target) before += wordTokens(paragraphs[i]).size
    val paragraph = paragraphs[target]
    val offset = characterOffset.coerceIn(0, paragraph.length)
    val tokens = wordTokens(paragraph)
    val local = tokens.indexOfFirst { it.range.last >= offset }.let { if (it < 0) tokens.size else it }
    return before + local
}

/**
 * Reading position (paragraph + character offset) of the word at [wordIndex].
 * Inverse of [rsvpStartWordIndex]; used to save RSVP progress into the book's reading position.
 */
internal fun rsvpPositionForWordIndex(paragraphs: List<String>, wordIndex: Int): ReadingPosition {
    var remaining = wordIndex.coerceAtLeast(0)
    paragraphs.forEachIndexed { paragraphIndex, paragraph ->
        val tokens = wordTokens(paragraph)
        if (remaining < tokens.size) {
            return ReadingPosition(paragraphIndex, tokens[remaining].range.first)
        }
        remaining -= tokens.size
    }
    val last = paragraphs.lastIndex.coerceAtLeast(0)
    return ReadingPosition(last, paragraphs.getOrNull(last)?.length ?: 0)
}

/** Thousands separated with "." (Turkish style): 37018 -> "37.018". */
internal fun formatCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(".").reversed()

/** Time left from [fromIndex] (inclusive) to the end at [wpm]. */
internal fun rsvpRemainingMs(words: List<String>, fromIndex: Int, wpm: Int): Long {
    var total = 0L
    for (i in fromIndex.coerceAtLeast(0) until words.size) {
        total += rsvpDelayMs(wpm, words[i])
    }
    return total
}

/** Milliseconds as hh:mm. */
internal fun formatHhMm(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)
}
