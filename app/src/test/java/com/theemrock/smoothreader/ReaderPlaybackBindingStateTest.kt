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

        val paused = PlaybackSnapshot(
            bookId = "book",
            position = ReadingPosition(0, 9),
            isPlaying = false,
            isSoundEnabled = false,
            speed = 7
        )

        val actions = state.connected(paused)

        assertNull(actions.preparation)
        assertFalse(actions.startPlayback)
        assertEquals(paused, actions.snapshotToApply)
    }

    @Test fun pendingPlayWaitsForPreparationAndIsConsumedOnce() {
        val state = ReaderPlaybackBindingState()
        state.requestPlay()

        val tooEarly = state.connected(PlaybackSnapshot())
        state.updatePreparation(preparation())
        val ready = state.connected(PlaybackSnapshot())
        val repeated = state.connected(PlaybackSnapshot(bookId = "book", isPlaying = true))

        assertNull(tooEarly.preparation)
        assertFalse(tooEarly.startPlayback)
        assertEquals("book", ready.preparation?.bookId)
        assertTrue(ready.startPlayback)
        assertNull(repeated.preparation)
        assertFalse(repeated.startPlayback)
        assertTrue(repeated.snapshotToApply?.isPlaying == true)
    }

    @Test fun playingSessionReconnectRequestsImmediateSnapshotProjection() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation())
        val playing = PlaybackSnapshot(
            bookId = "book",
            position = ReadingPosition(0, 12),
            isPlaying = true,
            speed = 8
        )

        val actions = state.connected(playing)

        assertEquals(playing, actions.snapshotToApply)
        assertFalse(actions.startPlayback)
    }

    @Test fun differentBookConnectionPreparesReplacementWithoutAutoplay() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation(bookId = "second"))

        val actions = state.connected(PlaybackSnapshot(bookId = "first"))

        assertEquals("second", actions.preparation?.bookId)
        assertFalse(actions.startPlayback)
    }

    @Test fun serviceStopDisconnectsClientAndNextPlayUsesFreshService() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation())
        state.requestPlay()

        assertTrue(state.serviceStopping())

        state.requestPlay()
        val freshConnection = state.connected(PlaybackSnapshot())
        assertEquals("book", freshConnection.preparation?.bookId)
        assertTrue(freshConnection.startPlayback)
    }

    @Test fun selectedWordStartCancelsPendingPlayAndIsDeliveredOnce() {
        val state = ReaderPlaybackBindingState()
        state.updatePreparation(preparation())
        state.requestPlay()
        val selected = ReadingPosition(0, 5)

        state.selectStart(selected)
        val first = state.connected(
            PlaybackSnapshot(bookId = "book", position = ReadingPosition(), isPlaying = true)
        )
        val repeated = state.connected(
            PlaybackSnapshot(bookId = "book", position = selected, isPlaying = false)
        )

        assertFalse(first.startPlayback)
        assertEquals(selected, first.selectedStart)
        assertNull(repeated.selectedStart)
    }
}
