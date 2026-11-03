package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {

    private fun highlight(bookId: String, start: Int, end: Int) = Highlight(
        id = "$bookId-$start",
        bookId = bookId,
        bookTitle = "Kitap",
        bookAuthor = null,
        text = "x".repeat(end - start),
        startOffset = start,
        endOffset = end,
        createdAt = 0L
    )

    @Test fun rangesAreClippedAndRelativeToThePage() {
        val highlights = listOf(
            highlight("b", 5, 12),   // starts before the page, ends inside it
            highlight("b", 30, 40),  // partly inside
            highlight("b", 90, 95),  // after the page
            highlight("other", 31, 33)
        )

        val ranges = highlightRangesForPage(highlights, "b", pageStart = 10, pageEnd = 35)

        assertEquals(listOf(0 until 2, 20 until 25), ranges)
    }

    @Test fun otherBooksAndEmptyIntersectionsAreIgnored() {
        val highlights = listOf(highlight("other", 0, 50), highlight("b", 40, 45))

        assertEquals(emptyList<IntRange>(), highlightRangesForPage(highlights, "b", 0, 40))
    }

    @Test fun newHighlightEndsAfterItsText() {
        val h = newHighlight("b", "Kitap", null, "merhaba", startOffset = 468, createdAt = 1L)

        assertEquals(468 + "merhaba".length, h.endOffset)
    }
}
