package com.theemrock.smoothreader

import kotlin.math.roundToInt

data class ReadingPage(
    val text: String,
    val start: ReadingPosition,
    val end: ReadingPosition
)

/** Offsets are recorded while building the exact text consumed by pagination. */
internal class ReadingSource(val paragraphs: List<String>) {
    private val starts = IntArray(paragraphs.size)
    val length: Int
    init {
        var offset = 0
        paragraphs.forEachIndexed { index, paragraph ->
            starts[index] = offset
            offset += paragraph.length
            if (index < paragraphs.lastIndex) offset += 2
        }
        length = offset
    }
    val text: String by lazy(LazyThreadSafetyMode.NONE) {
        buildString {
            paragraphs.forEachIndexed { index, paragraph ->
                if (index > 0) append("\n\n")
                append(paragraph)
            }
        }
    }

    fun clamp(position: ReadingPosition): ReadingPosition {
        if (paragraphs.isEmpty()) return ReadingPosition()
        val index = position.paragraphIndex.coerceIn(0, paragraphs.lastIndex)
        return ReadingPosition(index, position.characterOffset.coerceIn(0, paragraphs[index].length))
    }

    fun positionAt(offset: Int): ReadingPosition {
        if (paragraphs.isEmpty()) return ReadingPosition()
        val safe = offset.coerceIn(0, length)
        val found = starts.binarySearch(safe)
        val index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        // Separator offsets belong to the end of the preceding paragraph.
        return ReadingPosition(index, (safe - starts[index]).coerceAtMost(paragraphs[index].length))
    }

    fun page(start: Int, end: Int, displayedText: String): ReadingPage =
        ReadingPage(displayedText, positionAt(start), positionAt(end))

    fun offsetOf(position: ReadingPosition): Int {
        if (paragraphs.isEmpty()) return 0
        val safe = clamp(position)
        return starts[safe.paragraphIndex] + safe.characterOffset
    }

    /** Copies only the requested range, never the remaining book. */
    fun slice(start: Int, end: Int): String {
        require(start in 0..length && end in start..length)
        if (start == end) return ""
        var index = positionAt(start).paragraphIndex
        return buildString(end - start) {
            while (index < paragraphs.size && starts[index] < end) {
                val paragraph = paragraphs[index]
                val localStart = (start - starts[index]).coerceIn(0, paragraph.length)
                val localEnd = (end - starts[index]).coerceIn(0, paragraph.length)
                if (localEnd > localStart) append(paragraph, localStart, localEnd)
                val separator = starts[index] + paragraph.length
                if (index < paragraphs.lastIndex) {
                    for (offset in separator until separator + 2) {
                        if (offset in start until end) append('\n')
                    }
                }
                index++
            }
        }
    }
}

internal fun comparePositions(a: ReadingPosition, b: ReadingPosition): Int =
    if (a.paragraphIndex != b.paragraphIndex) a.paragraphIndex.compareTo(b.paragraphIndex)
    else a.characterOffset.compareTo(b.characterOffset)

internal fun canonicalPositionForBookProgress(
    source: ReadingSource,
    progress: Float
): ReadingPosition = source.positionAt(
    (source.length * progress.coerceIn(0f, 1f)).roundToInt()
)

internal fun pageForPosition(pages: List<ReadingPage>, position: ReadingPosition): Int =
    pages.indexOfLast { comparePositions(it.start, position) <= 0 }.coerceAtLeast(0)

internal fun pageContainsPosition(
    page: ReadingPage,
    position: ReadingPosition,
    bookEnd: ReadingPosition
): Boolean = comparePositions(position, page.start) >= 0 &&
    (comparePositions(position, page.end) < 0 ||
        (position == bookEnd && page.end == bookEnd))

internal fun canonicalMeasurementReady(
    measuredEnd: ReadingPosition,
    position: ReadingPosition,
    blockComplete: Boolean
): Boolean = blockComplete || comparePositions(measuredEnd, position) > 0

/** No instance is created until both the book and stored progress have loaded. */
internal class ReadingSession(
    val source: ReadingSource,
    initialPosition: ReadingPosition,
    private val legacyPage: Int?,
    canonicalExists: Boolean
) {
    var position = initialPosition
        private set
    var canonical = canonicalExists
        private set
    var pages: List<ReadingPage> = emptyList()
        private set
    var pageIndex = 0
        private set
    private var initialized = false
    private var migrationAllowed = false

    fun paginate(newPages: List<ReadingPage>): Boolean {
        if (source.paragraphs.none { it.isNotBlank() } || newPages.isEmpty() ||
            newPages.any { source.clamp(it.start) != it.start || source.clamp(it.end) != it.end ||
                comparePositions(it.start, it.end) > 0 } ||
            newPages.zipWithNext().any { comparePositions(it.first.start, it.second.start) > 0 }) {
            pages = emptyList()
            return false
        }
        pages = newPages
        if (!initialized) {
            migrationAllowed = legacyPage == null || legacyPage in pages.indices
            if (!canonical && legacyPage != null && legacyPage in pages.indices) {
                position = pages[legacyPage].start
            }
            initialized = true
        }
        pageIndex = pageForPosition(pages, source.clamp(position))
        return true
    }

    fun migrationPosition(): ReadingPosition? {
        if (canonical || pages.isEmpty()) return null
        // An invalid legacy page must never silently become a persisted zero.
        if (!migrationAllowed) return null
        return source.clamp(position)
    }

    fun markCanonical() { canonical = true }

    fun pendingLegacyPage(): Int? = if (!canonical && !initialized) legacyPage else null

    fun resolveLegacy(position: ReadingPosition) {
        if (pendingLegacyPage() == null) return
        this.position = source.clamp(position)
        initialized = true
        migrationAllowed = true
    }

    fun cancelLegacy() {
        if (!initialized && legacyPage != null) {
            initialized = true
            migrationAllowed = false
        }
    }

    /** A partial window is never passed through the legacy global-page migration path. */
    fun acceptWindow(newPages: List<ReadingPage>): Boolean {
        if (source.paragraphs.none { it.isNotBlank() } || newPages.isEmpty() || newPages.any {
                source.clamp(it.start) != it.start || source.clamp(it.end) != it.end ||
                    comparePositions(it.start, it.end) > 0
            }) return false
        pages = newPages
        if (!initialized && legacyPage == null) {
            initialized = true
            migrationAllowed = true
        }
        pageIndex = pageForPosition(pages, source.clamp(position))
        return true
    }

    fun navigateWindow(newPages: List<ReadingPage>, index: Int): Boolean {
        if (index !in newPages.indices || !acceptWindow(newPages)) return false
        val changed = position != newPages[index].start
        cancelLegacy()
        pageIndex = index
        position = newPages[index].start
        return changed
    }

    fun moveTo(index: Int): Boolean {
        if (index !in pages.indices || index == pageIndex) return false
        position = pages[index].start
        pageIndex = index
        return true
    }

    fun updatePosition(newPosition: ReadingPosition): Boolean {
        val safe = source.clamp(newPosition)
        val changed = position != safe
        position = safe
        if (pages.isNotEmpty()) pageIndex = pageForPosition(pages, safe)
        return changed
    }

    fun acceptExactWindow(newPages: List<ReadingPage>, index: Int, exactPosition: ReadingPosition): Boolean {
        if (index !in newPages.indices || newPages.isEmpty()) return false
        val page = newPages[index]
        val safe = source.clamp(exactPosition)
        if (!pageContainsPosition(page, safe, source.positionAt(source.length))) return false
        if (!acceptWindow(newPages)) return false
        cancelLegacy()
        pageIndex = index
        position = safe
        return true
    }
}
