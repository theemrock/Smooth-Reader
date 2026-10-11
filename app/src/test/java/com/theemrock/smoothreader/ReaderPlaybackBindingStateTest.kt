package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackBindingStateTest {
    private fun preparation(bookId: String = "book") = PlaybackPreparation(
        bookId = bookId,
        source = ReadingSource(listOf("Bir. İki.")),
        position = ReadingPosition(0, 4),
        title = "Gerçek Kitap",
        author = "Yazar",
        mode = ReadingMode.LISTEN
    )

    @Test fun sameBookReconnectDoesNotPrepareOrRestartPlayback() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation())

        val actions = state.connected(activeBookId = "book")

        assertNull(actions.preparation)
        assertFalse(actions.startPlayback)
    }

    @Test fun pendingPlayWaitsForPreparationAndIsConsumedOnce() {
        val state = ReaderPlaybackBindingState()
        state.requestPlay()

        val tooEarly = state.connected(activeBookId = null)
        state.updatePreparation(preparation())
        val ready = state.connected(activeBookId = null)
        val repeated = state.connected(activeBookId = "book")

        assertNull(tooEarly.preparation)
        assertFalse(tooEarly.startPlayback)
        assertEquals("book", ready.preparation?.bookId)
        assertTrue(ready.startPlayback)
        assertNull(repeated.preparation)
        assertFalse(repeated.startPlayback)
    }

    @Test fun differentBookConnectionPreparesReplacementWithoutAutoplay() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation(bookId = "second"))

        val actions = state.connected(activeBookId = "first")

        assertEquals("second", actions.preparation?.bookId)
        assertFalse(actions.startPlayback)
    }
}
