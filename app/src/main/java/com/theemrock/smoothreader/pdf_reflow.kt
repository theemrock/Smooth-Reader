package com.theemrock.smoothreader

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class PdfBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class PdfTextLine(
    val text: String,
    val bounds: PdfBounds,
    val sourceOrder: Int,
    val baseline: Float,
    val ambiguous: Boolean = false
)

enum class PdfPageTextState { TEXT, NO_TEXT, EXTRACTION_ERROR, AMBIGUOUS }

data class PdfExtractedPage(
    val pageIndex: Int,
    val width: Float,
    val height: Float,
    val rotation: Int,
    val lines: List<PdfTextLine>,
    val textState: PdfPageTextState,
    val reason: String? = null
)

enum class PdfReflowDecision { ALLOWED, UNRELIABLE, UNAVAILABLE }

data class PdfPageCapability(
    val pageIndex: Int,
    val width: Float,
    val height: Float,
    val rotation: Int,
    val textState: PdfPageTextState,
    val reason: String? = null
)

data class PdfDocumentCapabilities(
    val pages: List<PdfPageCapability>,
    val decision: PdfReflowDecision
)

data class PdfSourceMapEntry(
    val pageIndex: Int,
    val sourceLineIndex: Int,
    val sourceBounds: PdfBounds,
    val paragraphIndex: Int,
    val canonicalStart: Int,
    val canonicalEnd: Int
)

data class PdfNormalizationResult(
    val paragraphs: List<String>,
    val sourceMap: List<PdfSourceMapEntry>,
    val capabilities: PdfDocumentCapabilities
)

object PdfReadingOrder {
    fun order(page: PdfExtractedPage): List<PdfTextLine> = page.lines.sortedWith { first, second ->
        val tolerance = max(first.bounds.height, second.bounds.height).coerceAtLeast(1f) * 0.5f
        if (abs(first.bounds.top - second.bounds.top) <= tolerance) {
            first.bounds.left.compareTo(second.bounds.left).takeIf { it != 0 }
                ?: first.sourceOrder.compareTo(second.sourceOrder)
        } else {
            first.bounds.top.compareTo(second.bounds.top)
        }
    }
}

object PdfCapabilities {
    fun aggregate(pages: List<PdfExtractedPage>): PdfDocumentCapabilities {
        val pageCapabilities = pages.map {
            PdfPageCapability(it.pageIndex, it.width, it.height, it.rotation, it.textState, it.reason)
        }
        val textCount = pages.count { it.textState == PdfPageTextState.TEXT }
        val uncertainCount = pages.count {
            it.textState == PdfPageTextState.EXTRACTION_ERROR || it.textState == PdfPageTextState.AMBIGUOUS
        }
        val decision = when {
            textCount == 0 && uncertainCount == 0 -> PdfReflowDecision.UNAVAILABLE
            textCount == 0 -> PdfReflowDecision.UNRELIABLE
            uncertainCount > pages.size / 2 -> PdfReflowDecision.UNRELIABLE
            else -> PdfReflowDecision.ALLOWED
        }
        return PdfDocumentCapabilities(pageCapabilities, decision)
    }
}

class PdfReflowNormalizer {
    fun normalize(pages: List<PdfExtractedPage>): PdfNormalizationResult {
        val sortedPages = pages.sortedBy { it.pageIndex }
        val headerLines = repeatedTopBandLines(sortedPages)
        val paragraphs = mutableListOf<String>()
        val sourceMap = mutableListOf<PdfSourceMapEntry>()
        var current = StringBuilder()
        var currentIndex = 0
        var previousLine: PdfTextLine? = null
        var previousPage: PdfExtractedPage? = null

        fun flush() {
            current.toString().trim().takeIf { it.isNotBlank() }?.let(paragraphs::add)
            current = StringBuilder()
            currentIndex = paragraphs.size
            previousLine = null
            previousPage = null
        }

        for (page in sortedPages) {
            val ordered = PdfReadingOrder.order(page).filterNot { line ->
                line.text.isBlank() || line in headerLines || isPageNumber(line, page)
            }
            if (ordered.isEmpty()) {
                if (page.textState != PdfPageTextState.TEXT) flush()
                continue
            }
            for (line in ordered) {
                val clean = line.text.replace('\u00A0', ' ').trim()
                if (clean.isBlank()) continue
                val pageChanged = previousPage?.pageIndex != null && previousPage?.pageIndex != page.pageIndex
                val split = current.isNotEmpty() && shouldSplit(previousLine!!, previousPage!!, line, page, pageChanged)
                if (split) flush()

                var separator = if (current.isEmpty()) "" else " "
                if (current.isNotEmpty() && previousLine?.text?.trimEnd()?.endsWith('\u00AD') == true) {
                    separator = ""
                } else if (current.isNotEmpty() && current.last() == '-' && strongHardHyphenWrap(previousLine!!, previousPage!!, line)) {
                    current.setLength(current.length - 1)
                    separator = ""
                }
                current.append(separator)
                val canonicalStart = current.length
                current.append(clean.replace("\u00AD", ""))
                sourceMap += PdfSourceMapEntry(
                    page.pageIndex,
                    line.sourceOrder,
                    line.bounds,
                    currentIndex,
                    canonicalStart,
                    current.length
                )
                previousLine = line
                previousPage = page
            }
        }
        flush()
        return PdfNormalizationResult(paragraphs, sourceMap, PdfCapabilities.aggregate(sortedPages))
    }

    private fun shouldSplit(
        previous: PdfTextLine,
        previousPage: PdfExtractedPage,
        current: PdfTextLine,
        currentPage: PdfExtractedPage,
        pageChanged: Boolean
    ): Boolean {
        if (pageChanged) {
            val terminal = previous.text.trimEnd('\u00AD').trimEnd().lastOrNull()
                ?.let { it in ".!?…:;" } == true
            val continuation = current.text.trimStart().firstOrNull()?.isLowerCase() == true
            val compatibleIndent = abs(previous.bounds.left - current.bounds.left) <= currentPage.width * 0.08f
            return terminal || !continuation || !compatibleIndent
        }
        val gap = current.bounds.top - previous.bounds.bottom
        val lineHeight = max(previous.bounds.height, current.bounds.height).coerceAtLeast(1f)
        val largeGap = gap > lineHeight * 1.3f
        val indent = current.bounds.left - previous.bounds.left
        val indentedAfterTerminal = indent > currentPage.width * 0.05f &&
            previous.text.trimEnd().lastOrNull()?.let { it in ".!?…" } == true
        return largeGap || indentedAfterTerminal
    }

    private fun strongHardHyphenWrap(
        previous: PdfTextLine,
        page: PdfExtractedPage,
        current: PdfTextLine
    ): Boolean {
        val previousText = previous.text.trimEnd()
        return previousText.length > 1 && previousText[previousText.lastIndex - 1].isLetter() &&
            current.text.trimStart().firstOrNull()?.isLowerCase() == true &&
            previous.bounds.right >= page.width * 0.85f
    }

    private fun isPageNumber(line: PdfTextLine, page: PdfExtractedPage): Boolean {
        val inBand = line.bounds.top <= page.height * 0.10f || line.bounds.bottom >= page.height * 0.90f
        return inBand && line.text.trim().matches(Regex("(?i)^(?:\\d{1,4}|[ivxlcdm]{1,8})$"))
    }

    private fun repeatedTopBandLines(pages: List<PdfExtractedPage>): Set<PdfTextLine> {
        val candidates = pages.flatMap { page ->
            page.lines.filter { it.bounds.top <= page.height * 0.12f && it.text.trim().length in 3..100 }
                .map { page to it }
        }
        if (candidates.isEmpty()) return emptySet()
        val minimum = max(3, pages.size / 3)
        return candidates.filter { (page, line) ->
            val signature = headerSignature(line.text)
            candidates.asSequence()
                .filter { (otherPage, _) -> otherPage.pageIndex != page.pageIndex }
                .map { (_, other) -> headerSignature(other.text) }
                .count { other -> editDistanceAtMostOne(signature, other) } >= minimum - 1
        }.mapTo(mutableSetOf()) { it.second }
    }

    private fun headerSignature(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(Regex("\\d+"), "#")
        .replace(Regex("[^\\p{L}#]+"), "")

    private fun editDistanceAtMostOne(first: String, second: String): Boolean {
        if (first == second) return true
        if (abs(first.length - second.length) > 1) return false
        val shorter = if (first.length <= second.length) first else second
        val longer = if (first.length <= second.length) second else first
        var differences = 0
        var i = 0
        var j = 0
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++; j++
            } else {
                if (++differences > 1) return false
                if (shorter.length == longer.length) i++
                j++
            }
        }
        return true
    }
}
