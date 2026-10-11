package com.theemrock.smoothreader

internal interface PlaybackAudioFocus {
    fun requestPlaybackFocus(): Boolean
    fun abandonPlaybackFocus()
}

internal interface PlaybackForeground {
    fun startForegroundPlayback()
    fun stopForegroundPlayback()
}

internal class ReaderPlaybackCoordinator(
    private val controller: ReaderPlaybackController,
    private val audioFocus: PlaybackAudioFocus,
    private val foreground: PlaybackForeground,
    private val stopService: () -> Unit
) {
    private var hasAudioFocus = false
    private var stopped = false

    fun play(): Boolean {
        if (stopped || controller.state.value.bookId == null) return false
        foreground.startForegroundPlayback()
        hasAudioFocus = audioFocus.requestPlaybackFocus()
        if (!hasAudioFocus) return false
        controller.play()
        if (!controller.state.value.isPlaying) abandonFocus()
        return controller.state.value.isPlaying
    }

    fun pause(reason: String) {
        if (stopped) return
        if (controller.state.value.isPlaying) controller.pause(reason)
        abandonFocus()
    }

    fun onAudioFocusLost(reason: String) {
        pause(reason)
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (!isPlaying) abandonFocus()
    }

    fun stop(reason: String) {
        if (stopped) return
        stopped = true
        controller.clear(reason)
        abandonFocus()
        foreground.stopForegroundPlayback()
        stopService()
    }

    private fun abandonFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        audioFocus.abandonPlaybackFocus()
    }
}
