package com.theemrock.smoothreader

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SentenceRange(
    val start: ReadingPosition,
    val end: ReadingPosition,
    val localStart: Int = 0,
    val localEnd: Int = 0
)

private val turkishLocale = Locale("tr", "TR")

private fun isWordCoreCharacter(character: Char): Boolean =
    character.isLetterOrDigit() || when (Character.getType(character)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

private fun isWordCharacter(text: String, index: Int): Boolean {
    if (index !in text.indices) return false
    val character = text[index]
    if (isWordCoreCharacter(character)) return true
    if (character != '\'' && character != '’') return false
    return index > 0 && index < text.lastIndex &&
        isWordCoreCharacter(text[index - 1]) && isWordCoreCharacter(text[index + 1])
}

internal fun canonicalWordStartForPageOffset(
    source: ReadingSource,
    page: ReadingPage,
    localOffset: Int
): ReadingPosition? {
    if (page.text.isEmpty() || localOffset !in 0..page.text.length) return null
    val pageStart = source.offsetOf(page.start)
    val pageEnd = minOf(source.length, pageStart + page.text.length)
    val tappedOffset = (pageStart + localOffset).coerceIn(pageStart, pageEnd)
    val text = source.text

    val anchor = when {
        tappedOffset < pageEnd && isWordCharacter(text, tappedOffset) -> tappedOffset
        tappedOffset > 0 && isWordCharacter(text, tappedOffset - 1) -> tappedOffset - 1
        else -> {
            val next = (tappedOffset until pageEnd).firstOrNull { isWordCharacter(text, it) }
            next ?: (tappedOffset - 1 downTo pageStart).firstOrNull { isWordCharacter(text, it) }
        }
    } ?: return null

    var wordStart = anchor
    while (wordStart > 0 && isWordCharacter(text, wordStart - 1)) wordStart--
    return source.positionAt(wordStart)
}

private fun paragraphSentences(source: ReadingSource, paragraphIndex: Int): List<SentenceRange> {
    val paragraph = source.paragraphs.getOrNull(paragraphIndex) ?: return emptyList()
    if (paragraph.isBlank()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(turkishLocale).apply { setText(paragraph) }
    val result = ArrayList<SentenceRange>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        while (start < end && paragraph[start].isWhitespace()) start++
        var visibleEnd = end
        while (visibleEnd > start && paragraph[visibleEnd - 1].isWhitespace()) visibleEnd--
        if (visibleEnd > start) {
            result += SentenceRange(
                ReadingPosition(paragraphIndex, start),
                ReadingPosition(paragraphIndex, visibleEnd)
            )
        }
        start = end
        end = iterator.next()
    }
    return result
}

internal fun sentenceRanges(source: ReadingSource, page: ReadingPage): List<SentenceRange> {
    val pageStart = source.offsetOf(page.start)
    val pageEnd = minOf(source.offsetOf(page.end), pageStart + page.text.length)
    if (pageEnd <= pageStart) return emptyList()
    val result = ArrayList<SentenceRange>()
    for (paragraph in page.start.paragraphIndex..page.end.paragraphIndex.coerceAtMost(source.paragraphs.lastIndex)) {
        for (sentence in paragraphSentences(source, paragraph)) {
            val start = maxOf(pageStart, source.offsetOf(sentence.start))
            val end = minOf(pageEnd, source.offsetOf(sentence.end))
            if (end > start) {
                result += sentence.copy(localStart = start - pageStart, localEnd = end - pageStart)
            }
        }
    }
    return result
}

internal fun activeSentenceForPosition(
    ranges: List<SentenceRange>,
    position: ReadingPosition
): SentenceRange? = ranges.firstOrNull {
    comparePositions(it.start, position) <= 0 && comparePositions(position, it.end) < 0
}

internal data class VerticalPageGeometry(
    val currentY: Float,
    val nextY: Float,
    val previousY: Float
)

internal fun verticalPageGeometry(offset: Float, pageHeight: Float) = VerticalPageGeometry(
    currentY = offset,
    nextY = pageHeight + offset,
    previousY = offset - pageHeight
)

internal data class VerticalSettle(val targetOffset: Float, val committedDirection: Int)

internal fun dragFraction(offset: Float, pageHeight: Float): Float =
    if (pageHeight <= 0f) 0f else (abs(offset) / pageHeight).coerceIn(0f, 1f)

internal fun normalizedVerticalSettle(
    offset: Float,
    pageHeight: Float,
    velocityY: Float,
    commitFraction: Float = 0.33f,
    velocityThreshold: Float = 1400f
): VerticalSettle {
    val direction = when {
        dragFraction(offset, pageHeight) >= commitFraction -> if (offset < 0f) 1 else -1
        velocityY <= -velocityThreshold -> 1
        velocityY >= velocityThreshold -> -1
        else -> 0
    }
    return VerticalSettle(
        targetOffset = when (direction) { 1 -> -pageHeight; -1 -> pageHeight; else -> 0f },
        committedDirection = direction
    )
}

internal fun settleDurationMillis(
    offset: Float,
    targetOffset: Float,
    pixelsPerSecond: Float = 2200f
): Int = ((abs(targetOffset - offset) / pixelsPerSecond.coerceAtLeast(1f)) * 1000f)
    .roundToInt().coerceIn(120, 700)

internal enum class VerticalViewportOwner { IDLE, DRAGGING, SETTLING, AUTO_TRACKING, COMMITTING }

internal data class VerticalViewportState(
    val owner: VerticalViewportOwner,
    val offset: Float,
    val targetOffset: Float = offset,
    val resumeAutomatically: Boolean = false
) {
    fun takeManualOwnership() = copy(
        owner = VerticalViewportOwner.DRAGGING,
        targetOffset = offset,
        resumeAutomatically = false
    )
    fun finishWithoutCommit() = copy(
        owner = VerticalViewportOwner.IDLE,
        targetOffset = 0f,
        resumeAutomatically = false
    )
    fun retarget(target: Float) = copy(owner = VerticalViewportOwner.AUTO_TRACKING, targetOffset = target)
}

internal fun rebaseCommittedOffset(offset: Float, pageHeight: Float, direction: Int): Float =
    offset + direction * pageHeight

internal data class ListenTrackingMotion(
    val offset: Float,
    val velocity: Float,
    val target: Float
) {
    fun retarget(newTarget: Float) = copy(target = newTarget)
    fun rebase(pageHeight: Float, direction: Int) = copy(
        offset = rebaseCommittedOffset(offset, pageHeight, direction),
        target = rebaseCommittedOffset(target, pageHeight, direction)
    )

    fun afterAutomaticTransitionCompletion(accepted: Boolean, pageHeight: Float) =
        if (accepted) rebase(pageHeight, 1) else this
}

internal fun advanceListenTracking(
    motion: ListenTrackingMotion,
    elapsedSeconds: Float,
    stiffness: Float = 45f,
    damping: Float = 13.5f
): ListenTrackingMotion {
    val dt = elapsedSeconds.coerceIn(0f, 0.05f)
    val acceleration = (motion.target - motion.offset) * stiffness - motion.velocity * damping
    val velocity = motion.velocity + acceleration * dt
    return motion.copy(offset = motion.offset + velocity * dt, velocity = velocity)
}

internal data class VerticalViewportToken(
    val contentVersion: Long,
    val layoutVersion: Long,
    val generation: Long
) {
    fun accepts(content: Long, layout: Long, currentGeneration: Long): Boolean =
        contentVersion == content && layoutVersion == layout && generation == currentGeneration
}

internal fun acceptsAutomaticVerticalTransition(
    requested: VerticalViewportToken,
    current: VerticalViewportToken,
    isPlaying: Boolean
): Boolean = isPlaying && requested.generation != 0L && requested == current

internal fun shouldAttemptAutomaticVerticalTransition(
    transitionId: Long,
    rejectedTransitionId: Long
): Boolean = transitionId != 0L && transitionId != rejectedTransitionId

internal fun activeSentenceViewportTarget(
    sentenceCenter: Float,
    viewportHeight: Float,
    pageHeight: Float
): Float = (viewportHeight * 0.5f - sentenceCenter).coerceIn(-pageHeight, 0f)

internal fun activeSentenceTrackingTarget(
    sentenceCenter: Float?,
    currentTarget: Float,
    viewportHeight: Float,
    pageHeight: Float
): Float = sentenceCenter?.let {
    activeSentenceViewportTarget(it, viewportHeight, pageHeight)
} ?: currentTarget

internal data class PlaybackControlState(
    val isPlaying: Boolean,
    val isSoundEnabled: Boolean,
    val position: ReadingPosition,
    val generation: Long
)

internal data class PlaybackControlTransition(
    val state: PlaybackControlState,
    val restartSpeech: Boolean
)

internal fun soundToggleTransition(state: PlaybackControlState): PlaybackControlTransition =
    PlaybackControlTransition(
        state = state.copy(
            isSoundEnabled = !state.isSoundEnabled,
            generation = if (state.isPlaying) state.generation + 1 else state.generation
        ),
        restartSpeech = state.isPlaying
    )

internal fun speedChangeTransition(state: PlaybackControlState): PlaybackControlTransition =
    PlaybackControlTransition(
        state = state.copy(generation = if (state.isPlaying) state.generation + 1 else state.generation),
        restartSpeech = state.isPlaying
    )

internal fun utteranceVolume(isSoundEnabled: Boolean): Float = if (isSoundEnabled) 1f else 0f

internal fun acceptsSpeechGeneration(callbackGeneration: Long, state: PlaybackControlState): Boolean =
    callbackGeneration == state.generation

internal data class DragProgressionState(val isPlaying: Boolean, val resumeAutomatically: Boolean)

internal fun progressionAfterDrag(mode: ReadingMode, isPlaying: Boolean): DragProgressionState {
    @Suppress("UNUSED_VARIABLE") val activeMode = mode
    return DragProgressionState(isPlaying = false, resumeAutomatically = false)
}

internal data class ProgressionUpdate(
    val position: ReadingPosition,
    val targetPageDirection: Int
)

internal data class LayoutTransitionToken(val contentVersion: Long, val generation: Long)

internal class ProgressionTokenGate {
    private var speech = 0L
    private var layout = LayoutTransitionToken(-1, 0)

    fun nextSpeech(): Long = ++speech
    fun acceptsSpeech(token: Long): Boolean = token == speech
    fun nextLayout(contentVersion: Long): LayoutTransitionToken =
        LayoutTransitionToken(contentVersion, layout.generation + 1).also { layout = it }
    fun acceptsLayout(token: LayoutTransitionToken): Boolean = token == layout
}

internal fun nextReadingChunk(source: ReadingSource, currentPosition: ReadingPosition): Pair<SentenceRange, String>? {
    if (source.paragraphs.isEmpty()) return null
    val clamped = source.clamp(currentPosition)
    var pIndex = clamped.paragraphIndex
    while (pIndex in source.paragraphs.indices) {
        val sentences = paragraphSentences(source, pIndex)
        for (sentence in sentences) {
            if (comparePositions(sentence.end, clamped) > 0) {
                val effectiveStart = if (comparePositions(sentence.start, clamped) < 0) clamped else sentence.start
                val text = source.slice(source.offsetOf(effectiveStart), source.offsetOf(sentence.end))
                if (text.isNotBlank()) {
                    return SentenceRange(effectiveStart, sentence.end) to text
                }
            }
        }
        pIndex++
    }
    return null
}
