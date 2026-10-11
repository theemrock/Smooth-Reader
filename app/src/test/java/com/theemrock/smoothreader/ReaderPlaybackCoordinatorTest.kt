package com.theemrock.smoothreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackCoordinatorTest {
    private class FakeSpeechEngine(private val events: MutableList<String>) : SpeechEngine {
        private var listener: SpeechEngine.Listener? = null
        var stopCount = 0

        override fun setListener(listener: SpeechEngine.Listener) {
            this.listener = listener
        }

        override fun setRate(rate: Float) = Unit

        override fun speak(text: String, utteranceId: String, volume: Float): Int {
            events += "speak"
            return 0
        }

        override fun stop() {
            stopCount++
        }

        override fun shutdown() = Unit

        fun ready() = listener?.onReady(true)
    }

    private class FakeAudioFocus(
        private val events: MutableList<String>,
        var grant: Boolean = true
    ) : PlaybackAudioFocus {
        var abandonCount = 0

        override fun requestPlaybackFocus(): Boolean {
            events += "focus"
            return grant
        }

        override fun abandonPlaybackFocus() {
            abandonCount++
        }
    }

    private class FakeForeground(private val events: MutableList<String>) : PlaybackForeground {
        var stopCount = 0

        override fun startForegroundPlayback() {
            events += "foreground"
        }

        override fun stopForegroundPlayback() {
            stopCount++
        }
    }

    private data class Fixture(
        val controller: ReaderPlaybackController,
        val engine: FakeSpeechEngine,
        val focus: FakeAudioFocus,
        val foreground: FakeForeground,
        val coordinator: ReaderPlaybackCoordinator,
        val serviceStops: () -> Int,
        val events: MutableList<String>
    )

    private fun fixture(focusGranted: Boolean = true): Fixture {
        val events = mutableListOf<String>()
        val engine = FakeSpeechEngine(events)
        val controller = ReaderPlaybackController(
            engine = engine,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            persistPosition = { _, _, _ -> },
            log = {}
        )
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())
        engine.ready()
        val focus = FakeAudioFocus(events, focusGranted)
        val foreground = FakeForeground(events)
        var stopCount = 0
        val coordinator = ReaderPlaybackCoordinator(
            controller = controller,
            audioFocus = focus,
            foreground = foreground,
            stopService = { stopCount++ }
        )
        return Fixture(controller, engine, focus, foreground, coordinator, { stopCount }, events)
    }

    @Test fun playPromotesForegroundBeforeFocusAndSpeech() {
        val fixture = fixture()

        assertTrue(fixture.coordinator.play())

        assertEquals(listOf("foreground", "focus", "speak"), fixture.events)
        assertTrue(fixture.controller.state.value.isPlaying)
    }

    @Test fun deniedAudioFocusKeepsPreparedSessionPaused() {
        val fixture = fixture(focusGranted = false)

        assertFalse(fixture.coordinator.play())

        assertEquals(listOf("foreground", "focus"), fixture.events)
        assertFalse(fixture.controller.state.value.isPlaying)
        assertTrue(fixture.controller.hasSession("book"))
    }

    @Test fun pauseAbandonsFocusAndKeepsCanonicalSession() {
        val fixture = fixture()
        fixture.coordinator.play()
        val sessionId = fixture.controller.state.value.sessionId

        fixture.coordinator.pause("testPause")

        assertFalse(fixture.controller.state.value.isPlaying)
        assertEquals(sessionId, fixture.controller.state.value.sessionId)
        assertTrue(fixture.controller.hasSession("book"))
        assertEquals(1, fixture.focus.abandonCount)
        assertEquals(0, fixture.foreground.stopCount)
    }

    @Test fun stopClearsSessionAndStopsForegroundAndServiceOnce() {
        val fixture = fixture()
        fixture.coordinator.play()

        fixture.coordinator.stop("testStop")
        fixture.coordinator.stop("duplicateStop")

        assertFalse(fixture.controller.hasSession("book"))
        assertFalse(fixture.controller.state.value.isPlaying)
        assertEquals(1, fixture.focus.abandonCount)
        assertEquals(1, fixture.foreground.stopCount)
        assertEquals(1, fixture.serviceStops())
    }

    @Test fun audioFocusLossPausesActivePlayback() {
        val fixture = fixture()
        fixture.coordinator.play()

        fixture.coordinator.onAudioFocusLost("focusLoss")

        assertFalse(fixture.controller.state.value.isPlaying)
        assertTrue(fixture.controller.hasSession("book"))
        assertEquals(1, fixture.focus.abandonCount)
    }

    @Test fun controllerCompletionReleasesHeldAudioFocus() {
        val fixture = fixture()
        fixture.coordinator.play()

        fixture.coordinator.onPlaybackStateChanged(isPlaying = false)
        fixture.coordinator.onPlaybackStateChanged(isPlaying = false)

        assertEquals(1, fixture.focus.abandonCount)
    }
}
