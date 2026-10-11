package com.theemrock.smoothreader

internal data class PlaybackPreparation(
    val bookId: String,
    val source: ReadingSource,
    val position: ReadingPosition,
    val title: String,
    val author: String?,
    val mode: ReadingMode
)

internal data class PlaybackConnectionActions(
    val preparation: PlaybackPreparation?,
    val startPlayback: Boolean,
    val snapshotToApply: PlaybackSnapshot?
)

internal class ReaderPlaybackBindingState {
    private var preparation: PlaybackPreparation? = null
    private var playRequested = false

    fun updatePreparation(value: PlaybackPreparation) {
        preparation = value
    }

    fun requestPlay() {
        playRequested = true
    }

    fun connected(activeSnapshot: PlaybackSnapshot): PlaybackConnectionActions {
        val pending = preparation
        val activeBookId = activeSnapshot.bookId
        val prepare = pending?.takeIf { it.bookId != activeBookId }
        val sessionReady = pending != null && (activeBookId == pending.bookId || prepare != null)
        val start = playRequested && sessionReady
        if (start) playRequested = false
        return PlaybackConnectionActions(
            preparation = prepare,
            startPlayback = start,
            snapshotToApply = activeSnapshot.takeIf { pending != null && it.bookId == pending.bookId }
        )
    }

    fun serviceStopping(): Boolean {
        playRequested = false
        return true
    }
}
