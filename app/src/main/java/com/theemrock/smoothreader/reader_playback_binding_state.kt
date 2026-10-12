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
    val snapshotToApply: PlaybackSnapshot?,
    val selectedStart: ReadingPosition?
)

internal class ReaderPlaybackBindingState {
    private var preparation: PlaybackPreparation? = null
    private var playRequested = false
    private var selectedStart: ReadingPosition? = null

    fun updatePreparation(value: PlaybackPreparation) {
        preparation = value
    }

    fun requestPlay() {
        playRequested = true
    }

    fun requestPause() {
        playRequested = false
        selectedStart = null
    }

    fun selectStart(position: ReadingPosition) {
        preparation = preparation?.copy(position = position)
        selectedStart = position
        playRequested = false
    }

    fun connected(activeSnapshot: PlaybackSnapshot): PlaybackConnectionActions {
        val pending = preparation
        val activeBookId = activeSnapshot.bookId
        val prepare = pending?.takeIf { it.bookId != activeBookId }
        val sessionReady = pending != null && (activeBookId == pending.bookId || prepare != null)
        val start = playRequested && sessionReady
        val selection = selectedStart?.takeIf { sessionReady }
        if (start) playRequested = false
        if (selection != null) selectedStart = null
        return PlaybackConnectionActions(
            preparation = prepare,
            startPlayback = start,
            snapshotToApply = activeSnapshot.takeIf { pending != null && it.bookId == pending.bookId },
            selectedStart = selection
        )
    }

    fun serviceStopping(): Boolean {
        playRequested = false
        return true
    }
}
