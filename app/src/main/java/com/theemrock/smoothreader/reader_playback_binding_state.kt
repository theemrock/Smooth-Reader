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
    val startPlayback: Boolean
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

    fun connected(activeBookId: String?): PlaybackConnectionActions {
        val pending = preparation
        val prepare = pending?.takeIf { it.bookId != activeBookId }
        val sessionReady = pending != null && (activeBookId == pending.bookId || prepare != null)
        val start = playRequested && sessionReady
        if (start) playRequested = false
        return PlaybackConnectionActions(prepare, start)
    }
}
