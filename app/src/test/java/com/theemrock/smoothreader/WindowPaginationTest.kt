package com.theemrock.smoothreader

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WindowPaginationTest {
    private fun source() = ReadingSource(List(120) { "Paragraph $it: " + "readable text ".repeat(20) })
    private fun engine(source: ReadingSource = source(), position: ReadingPosition = ReadingPosition(), chars: Int = 1024) =
        WindowPaginator(source, PaginationTicket(1, 1), position, chars)

    private suspend fun completeBlock(p: WindowPaginator, index: Int, pageChars: Int = 120) {
        while (p.block(index)?.complete != true) p.step(index) { it.length <= pageChars }
    }

    @Test fun initialOpeningPublishesFirstPageBeforeWholeBookIsReady() = runBlocking {
        val p = engine()
        assertEquals(0, p.nextBlock())
        p.step(0) { it.length <= 120 }
        assertEquals(1, p.block(0)!!.pages.size)
        assertEquals(ReadingPosition(), p.block(0)!!.pages.first().start)
        assertEquals(0, p.totalPages)
        assertEquals(1, p.cachedBlockCount)
    }

    @Test fun resumePreparesCanonicalBlockWithoutReadingPrefix() = runBlocking {
        val position = ReadingPosition(80, 45)
        val p = engine(position = position)
        assertTrue(p.focus > 0)
        assertEquals(p.focus, p.nextBlock())
        completeBlock(p, p.focus)
        val session = ReadingSession(p.source, position, null, true)
        assertTrue(session.acceptWindow(p.block(p.focus)!!.pages))
        val page = session.pages[session.pageIndex]
        assertTrue(comparePositions(page.start, position) <= 0)
        assertTrue(comparePositions(page.end, position) >= 0)
        assertEquals(position, session.position)
        assertNull(p.block(0))
    }

    @Test fun nextAndPreviousWindowsArePrefetchedAndDemandWins() = runBlocking {
        val p = engine(position = ReadingPosition(40, 0))
        val focus = p.focus
        completeBlock(p, focus)
        assertEquals(focus + 1, p.nextBlock())
        completeBlock(p, focus + 1)
        assertEquals(focus - 1, p.nextBlock())
        p.demand = focus + 3
        assertEquals(focus + 3, p.nextBlock())
        completeBlock(p, focus + 3)
        p.focus = focus + 3
        p.demand = focus + 2
        assertEquals(focus + 2, p.nextBlock())
    }

    @Test fun windowsCarrySourcePositionsAndJoinWithoutMissingText() = runBlocking {
        val s = ReadingSource(listOf("Aİ📚B".repeat(120), "word ".repeat(180)))
        val p = engine(s, chars = 256)
        val result = mutableListOf<String>()
        for (index in 0 until p.blockCount) {
            completeBlock(p, index, 90)
            val block = p.block(index)!!
            for (page in block.pages) {
                assertEquals(page.text, s.slice(s.offsetOf(page.start), s.offsetOf(page.end)).trimEnd())
                assertFalse(page.text.first().isLowSurrogate())
                assertFalse(page.text.last().isHighSurrogate())
                result += page.text
            }
        }
        fun compact(text: String) = text.filterNot { it.isWhitespace() }
        assertEquals(compact(s.text), compact(result.joinToString("")))
    }

    @Test fun fontRepaginationKeepsCanonicalAnchorWithoutSavingWindowStart() = runBlocking {
        val s = source()
        val position = ReadingPosition(50, 61)
        val session = ReadingSession(s, position, null, true)
        for (capacity in listOf(90, 220, 130)) {
            val p = engine(s, position)
            completeBlock(p, p.focus, capacity)
            assertTrue(session.acceptWindow(p.block(p.focus)!!.pages))
            assertEquals(position, session.position)
            assertNull(session.migrationPosition())
        }
    }

    @Test fun completedMeasurementResolvesCanonicalPositionInSkippedWhitespace() = runBlocking {
        val s = ReadingSource(listOf("Hello.", "World."))
        val canonical = ReadingPosition(0, 6)
        val p = engine(s, canonical)
        completeBlock(p, p.focus, pageChars = 6)
        val block = p.block(p.focus)!!
        val selected = block.pages[pageForPosition(block.pages, canonical)]

        assertTrue(block.complete)
        assertFalse(pageContainsPosition(selected, canonical, s.positionAt(s.length)))
        assertTrue(canonicalMeasurementReady(block.pages.last().end, canonical, block.complete))
    }

    @Test fun staleLayoutAndOtherBookTicketsAreRejected() {
        val epoch = PaginationEpoch()
        val firstLayout = epoch.next(1)
        val nextLayout = epoch.next(1)
        assertFalse(epoch.accepts(firstLayout))
        assertTrue(epoch.accepts(nextLayout))
        val secondBook = epoch.next(2)
        assertFalse(epoch.accepts(nextLayout))
        assertTrue(epoch.accepts(secondBook))
    }

    @Test fun preparingWindowsDoesNotAdvanceCanonicalPosition() = runBlocking {
        val position = ReadingPosition(50, 12)
        val p = engine(position = position)
        val session = ReadingSession(p.source, position, null, true)
        completeBlock(p, p.focus)
        session.acceptWindow(p.block(p.focus)!!.pages)
        repeat(25) { p.nextBlock()?.let { index -> p.step(index) { it.length <= 120 } } }
        assertEquals(position, session.position)
        assertNull(session.migrationPosition())
        assertFalse(session.navigateWindow(emptyList(), 0))
        assertEquals(position, session.position)
    }

    @Test fun userNavigationWritesOnlyReadyTargetPosition() = runBlocking {
        val p = engine()
        completeBlock(p, 0)
        val session = ReadingSession(p.source, ReadingPosition(), null, true)
        session.acceptWindow(p.block(0)!!.pages)
        assertFalse(session.navigateWindow(emptyList(), 0))
        assertEquals(ReadingPosition(), session.position)
        completeBlock(p, 1)
        val next = p.block(1)!!.pages
        assertTrue(session.navigateWindow(next, 0))
        assertEquals(next.first().start, session.position)
        val previous = p.block(0)!!.pages
        assertTrue(session.navigateWindow(previous, previous.lastIndex))
        assertEquals(previous.last().start, session.position)
    }

    @Test fun cacheIsBoundedAndGlobalCountsSurviveEviction() = runBlocking {
        val p = engine()
        var expected = 0
        for (index in 0 until p.blockCount) {
            completeBlock(p, index)
            expected += p.block(index)!!.pages.size
            assertTrue(p.cachedBlockCount <= 6)
        }
        assertEquals(expected, p.totalPages)
        assertNotNull(p.locateGlobal(expected - 1))
        assertNull(p.locateGlobal(expected))
    }

    @Test fun legacyPageNeverUsesPartialWindowOrdinal() = runBlocking {
        val s = source()
        val p = engine(s, ReadingPosition(50, 0))
        val session = ReadingSession(s, ReadingPosition(50, 0), 12, false)
        completeBlock(p, p.focus)
        session.acceptWindow(p.block(p.focus)!!.pages)
        assertEquals(ReadingPosition(50, 0), session.position)
        assertNull(session.migrationPosition())
        assertNull(p.locateGlobal(12))
        for (index in 0..2) completeBlock(p, index)
        val location = p.locateGlobal(12)!!
        val target = p.block(location.first)!!.pages[location.second].start
        session.resolveLegacy(target)
        session.acceptWindow(p.block(location.first)!!.pages)
        assertEquals(target, session.migrationPosition())
        session.markCanonical()
        assertNull(session.migrationPosition())
    }

    @Test fun cancellationCannotPublishPartialMeasurement() = runBlocking {
        val p = engine()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            p.step(0) { awaitCancellation() }
        }
        job.cancelAndJoin()
        assertTrue(p.block(0)!!.pages.isEmpty())
        assertEquals(0, p.totalPages)
    }

    @Test fun errorAndEmptyContentDoNotResetPosition() = runBlocking {
        val saved = ReadingPosition(3, 12)
        val session = ReadingSession(source(), saved, null, true)
        val empty = engine(ReadingSource(emptyList()))
        assertNull(empty.nextBlock())
        assertFalse(session.acceptWindow(emptyList()))
        assertEquals(saved, session.position)
        val p = engine()
        try {
            p.step(0) { throw IllegalStateException("measurement failed") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertTrue(p.block(0)!!.pages.isEmpty())
        assertEquals(saved, session.position)
    }

    @Test fun measurementCandidatesAreBoundedEvenForHugeParagraph() = runBlocking {
        val s = ReadingSource(listOf("x".repeat(100_000)))
        val p = engine(s)
        var maximum = 0
        p.step(0) { maximum = maxOf(maximum, it.length); it.length < 300 }
        assertTrue(maximum <= 1104)
        assertTrue(maximum < s.length)
    }

    @Test fun trailingSurrogateBoundaryDoesNotCreateEmptyEndWindow() = runBlocking {
        val s = ReadingSource(listOf("a".repeat(255) + "📚"))
        val p = engine(s, ReadingPosition(0, 257), chars = 256)
        assertEquals(1, p.blockCount)
        assertEquals(0, p.focus)
        completeBlock(p, 0, 90)
        val session = ReadingSession(s, ReadingPosition(0, 257), null, true)
        session.acceptWindow(p.block(0)!!.pages)
        assertEquals(p.block(0)!!.pages.lastIndex, session.pageIndex)
        assertTrue(session.pages.last().text.endsWith("📚"))
    }

    @Test fun differentBooksDoNotSharePageTextOrCounts() = runBlocking {
        val first = engine(ReadingSource(listOf("First book ".repeat(100))))
        val second = WindowPaginator(ReadingSource(listOf("İkinci kitap")), PaginationTicket(2, 1), ReadingPosition())
        completeBlock(first, 0)
        assertNull(second.block(0))
        assertEquals(0, second.totalPages)
        completeBlock(second, 0)
        assertEquals("İkinci kitap", second.block(0)!!.pages.single().text)
        assertTrue(first.block(0)!!.pages.first().text.startsWith("First book"))
    }
}
