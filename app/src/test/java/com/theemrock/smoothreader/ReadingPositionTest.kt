package com.theemrock.smoothreader

import org.junit.Assert.*
import org.junit.Test

class ReadingPositionTest {
    private val source = ReadingSource(listOf("First paragraph", "Middle paragraph", "Last paragraph"))
    private val pages = listOf(
        ReadingPage("First", ReadingPosition(0, 0), ReadingPosition(0, 5)),
        ReadingPage("paragraph", ReadingPosition(0, 6), ReadingPosition(0, 15)),
        ReadingPage("Middle", ReadingPosition(1, 0), ReadingPosition(1, 6)),
        ReadingPage("paragraph", ReadingPosition(1, 7), ReadingPosition(1, 16)),
        ReadingPage("Last paragraph", ReadingPosition(2, 0), ReadingPosition(2, 14))
    )

    @Test fun sourceOffsetsIncludeSeparatorsAndTrimmedWhitespace() {
        val s = ReadingSource(listOf("A   ", "İ📚B", "C"))
        assertEquals("A   \n\nİ📚B\n\nC", s.text)
        // The second page starts at the original offset, not at the trimmed text length.
        assertEquals(ReadingPosition(1, 0), s.page(6, 10, "İ📚B").start)
        assertEquals(ReadingPosition(1, 3), s.positionAt(9)) // UTF-16 index after emoji
        assertEquals(ReadingPosition(2, 0), s.positionAt(12))
        assertEquals(ReadingPosition(0, 4), s.positionAt(5)) // separator
    }

    @Test fun mapsFirstMiddleLastAndNonzeroCharacterOffset() {
        assertEquals(0, pageForPosition(pages, ReadingPosition()))
        assertEquals(2, pageForPosition(pages, ReadingPosition(1, 3)))
        assertEquals(3, pageForPosition(pages, ReadingPosition(1, 7)))
        assertEquals(4, pageForPosition(pages, ReadingPosition(2, 14)))
    }

    @Test fun partialPageEndDoesNotContainBoundaryUntilNextPageIsMeasured() {
        val position = ReadingPosition(0, 5)
        val partial = ReadingPage("First", ReadingPosition(0, 0), position)

        assertFalse(pageContainsPosition(partial, position, ReadingPosition(0, 15)))
        assertFalse(canonicalMeasurementReady(partial.end, position, blockComplete = false))
    }

    @Test fun finalPageContainsTheRealBookEnd() {
        val bookEnd = ReadingPosition(0, 15)
        val finalPage = ReadingPage("paragraph", ReadingPosition(0, 6), bookEnd)

        assertTrue(pageContainsPosition(finalPage, bookEnd, bookEnd))
        assertTrue(canonicalMeasurementReady(finalPage.end, bookEnd, blockComplete = true))
        val session = ReadingSession(ReadingSource(listOf("First paragraph")), bookEnd, null, true)
        assertTrue(session.acceptExactWindow(listOf(finalPage), 0, bookEnd))
        assertEquals(bookEnd, session.position)
    }

    @Test fun clampsForDisplayWithoutRewritingCanonicalPosition() {
        assertEquals(ReadingPosition(), source.clamp(ReadingPosition(-9, -7)))
        assertEquals(ReadingPosition(2, 14), source.clamp(ReadingPosition(99, 999)))
        assertEquals(ReadingPosition(1, 16), source.clamp(ReadingPosition(1, 999)))
        val saved = ReadingPosition(99, 999)
        val session = ReadingSession(source, saved, 0, true)
        assertTrue(session.paginate(pages))
        assertEquals(4, session.pageIndex)
        assertEquals(saved, session.position)
        assertNull(session.migrationPosition())
    }

    @Test fun realPageMoveUsesItsSourceStartAndBoundaryNoopDoesNotMove() {
        val session = ReadingSession(source, ReadingPosition(1, 3), null, true)
        assertFalse(session.moveTo(0)) // pagination not ready
        session.paginate(pages)
        assertFalse(session.moveTo(2))
        assertEquals(ReadingPosition(1, 3), session.position)
        assertTrue(session.moveTo(3))
        assertEquals(ReadingPosition(1, 7), session.position)
        assertFalse(session.moveTo(99))
        assertEquals(ReadingPosition(1, 7), session.position)
        assertTrue(session.moveTo(0))
        assertEquals(ReadingPosition(), session.position)
    }

    @Test fun repaginationPreservesAnchorEvenWhenPageCountIsUnchanged() {
        val session = ReadingSession(source, ReadingPosition(1, 8), null, true)
        session.paginate(pages)
        val changed = pages.toMutableList().apply {
            this[3] = ReadingPage("graph", ReadingPosition(1, 10), ReadingPosition(1, 16))
        }
        session.paginate(changed)
        assertEquals(2, session.pageIndex)
        assertEquals(ReadingPosition(1, 8), session.position)
        assertNull(session.migrationPosition())
    }

    @Test fun legacyPageMigratesOnlyAfterValidPagination() {
        val session = ReadingSession(source, ReadingPosition(), 3, false)
        assertNull(session.migrationPosition())
        assertFalse(session.paginate(emptyList()))
        assertNull(session.migrationPosition())
        assertTrue(session.paginate(pages))
        assertEquals(ReadingPosition(1, 7), session.migrationPosition())
        session.markCanonical()
        assertNull(session.migrationPosition())
        session.moveTo(4)
        session.paginate(pages)
        assertEquals(ReadingPosition(2, 0), session.position)
    }

    @Test fun canonicalAlwaysWinsOverLegacyPage() {
        val session = ReadingSession(source, ReadingPosition(1, 8), 0, true)
        session.paginate(pages)
        assertEquals(3, session.pageIndex)
        assertEquals(ReadingPosition(1, 8), session.position)
        assertNull(session.migrationPosition())
    }

    @Test fun invalidLegacyPageNeverMigratesToZero() {
        for (legacy in listOf(-1, 999)) {
            val session = ReadingSession(source, ReadingPosition(1, 2), legacy, false)
            session.paginate(pages)
            assertEquals(ReadingPosition(1, 2), session.position)
            assertNull(session.migrationPosition())
        }
    }

    @Test fun legacyParagraphMigratesWithoutPageRecord() {
        val session = ReadingSession(source, ReadingPosition(1, 0), null, false)
        session.paginate(pages)
        assertEquals(ReadingPosition(1, 0), session.migrationPosition())
    }

    @Test fun emptyBookOrInvalidPaginationCannotProduceSavePosition() {
        val saved = ReadingPosition(1, 8)
        val empty = ReadingSession(ReadingSource(emptyList()), saved, 0, false)
        assertFalse(empty.paginate(pages))
        assertNull(empty.migrationPosition())
        assertFalse(empty.moveTo(0))
        assertEquals(saved, empty.position)
        val session = ReadingSession(source, saved, null, true)
        session.paginate(pages)
        assertFalse(session.paginate(emptyList()))
        assertFalse(session.moveTo(0))
        assertNull(session.migrationPosition())
        assertEquals(saved, session.position)
        assertFalse(session.paginate(listOf(ReadingPage("invalid", ReadingPosition(100, 0), ReadingPosition(100, 9)))))
        assertEquals(saved, session.position)
    }
}
