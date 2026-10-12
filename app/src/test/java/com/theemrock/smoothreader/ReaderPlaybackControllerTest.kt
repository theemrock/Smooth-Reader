package com.theemrock.smoothreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackControllerTest {
    private data class SpeakCall(
        val text: String,
        val utteranceId: String,
        val volume: Float
    )

    private class FakeSpeechEngine : SpeechEngine {
        private var listener: SpeechEngine.Listener? = null
        val speaks = mutableListOf<SpeakCall>()
        var stopCount = 0
        var shutdownCount = 0
        var appliedRate = 0f

        override fun setListener(listener: SpeechEngine.Listener) {
            this.listener = listener
        }

        override fun setRate(rate: Float) {
            appliedRate = rate
        }

        override fun speak(text: String, utteranceId: String, volume: Float): Int {
            speaks += SpeakCall(text, utteranceId, volume)
            return 0
        }

        override fun stop() {
            stopCount++
        }

        override fun shutdown() {
            shutdownCount++
        }

        fun ready(success: Boolean = true) = listener?.onReady(success)
        fun range(utteranceId: String, start: Int, end: Int) =
            listener?.onRangeStart(utteranceId, start, end)
        fun done(utteranceId: String) = listener?.onDone(utteranceId)
    }

    private fun controller(
        engine: FakeSpeechEngine,
        persisted: MutableList<ReadingPosition> = mutableListOf()
    ) = ReaderPlaybackController(
        engine = engine,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        persistPosition = { _, position, _ -> persisted += position },
        log = {}
    )

    @Test fun playRequestedBeforeTtsReadyStartsWhenInitializationCompletes() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())

        controller.play()
        assertTrue(controller.state.value.isPlaying)
        assertTrue(engine.speaks.isEmpty())

        engine.ready()

        assertEquals(1, engine.speaks.size)
        assertEquals("Bir.", engine.speaks.single().text)
    }

    @Test fun onDonePersistsCanonicalEndAndStartsNextChunkWithoutUi() {
        val engine = FakeSpeechEngine()
        val persisted = mutableListOf<ReadingPosition>()
        val controller = controller(engine, persisted)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())
        engine.ready()
        controller.play()
        val firstId = engine.speaks.single().utteranceId

        engine.done(firstId)

        assertEquals(ReadingPosition(0, 4), controller.state.value.position)
        assertEquals(listOf(ReadingPosition(0, 4)), persisted)
        assertEquals(listOf("Bir.", "İki."), engine.speaks.map { it.text })
    }

    @Test fun staleCallbacksAfterPauseCannotMoveCanonicalPosition() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())
        engine.ready()
        controller.play()
        val staleId = engine.speaks.single().utteranceId

        controller.pause("test")
        engine.range(staleId, 2, 3)
        engine.done(staleId)

        assertFalse(controller.state.value.isPlaying)
        assertEquals(ReadingPosition(), controller.state.value.position)
        assertEquals(1, engine.speaks.size)
    }

    @Test fun seekDuringPlaybackRestartsFromNewCanonicalPosition() {
        val engine = FakeSpeechEngine()
        val persisted = mutableListOf<ReadingPosition>()
        val controller = controller(engine, persisted)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())
        engine.ready()
        controller.play()
        val oldId = engine.speaks.single().utteranceId

        controller.seek(ReadingPosition(0, 5), restartIfPlaying = true, reason = "test")

        assertEquals(ReadingPosition(0, 5), controller.state.value.position)
        assertEquals(ReadingPosition(0, 5), persisted.last())
        assertEquals("İki.", engine.speaks.last().text)
        assertTrue(engine.speaks.last().utteranceId != oldId)
    }

    @Test fun selectedWordPausesRejectsStaleCallbackAndNextPlayStartsExactlyThere() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir uzun cümle.")), ReadingPosition())
        engine.ready()
        controller.play()
        val staleId = engine.speaks.single().utteranceId

        controller.pause("wordSelection")
        controller.seek(ReadingPosition(0, 4), restartIfPlaying = false, reason = "wordSelection")
        engine.range(staleId, 2, 3)
        engine.done(staleId)

        assertFalse(controller.state.value.isPlaying)
        assertEquals(ReadingPosition(0, 4), controller.state.value.position)
        assertEquals(1, engine.speaks.size)

        controller.play()

        assertEquals("uzun cümle.", engine.speaks.last().text)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test fun speedAndSoundChangesRestartOnlyActivePlaybackFromCanonicalPosition() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition(0, 5))
        engine.ready()
        controller.play()
        val afterPlay = engine.speaks.size

        controller.setSpeed(6, "test")
        controller.toggleSound("test")

        assertEquals(afterPlay + 2, engine.speaks.size)
        assertEquals(ReadingPosition(0, 5), controller.state.value.position)
        assertFalse(controller.state.value.isSoundEnabled)
        assertEquals(0f, engine.speaks.last().volume, 0f)
        assertTrue(engine.appliedRate > 0f)
    }

    @Test fun pausedSpeedAndSoundChangesDoNotStartPlayback() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir.")), ReadingPosition())
        engine.ready()

        controller.setSpeed(6, "test")
        controller.toggleSound("test")

        assertFalse(controller.state.value.isPlaying)
        assertTrue(engine.speaks.isEmpty())
        assertFalse(controller.state.value.isSoundEnabled)
    }

    @Test fun releaseIsIdempotentAndRejectsFurtherPlayback() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir.")), ReadingPosition())
        engine.ready()

        controller.release()
        controller.release()
        controller.play()

        assertEquals(1, engine.shutdownCount)
        assertEquals(1, engine.stopCount)
        assertTrue(engine.speaks.isEmpty())
    }

    @Test fun preparingSameBookKeepsActiveSessionAndPosition() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("book", ReadingSource(listOf("Bir. İki.")), ReadingPosition())
        engine.ready()
        controller.play()
        val utteranceId = engine.speaks.single().utteranceId
        engine.range(utteranceId, 2, 3)
        val sessionId = controller.state.value.sessionId

        val prepared = controller.prepare(
            "book",
            ReadingSource(listOf("Bu içerik aktif oturumun yerini almamalı.")),
            ReadingPosition()
        )

        assertFalse(prepared)
        assertTrue(controller.hasSession("book"))
        assertTrue(controller.state.value.isPlaying)
        assertEquals(ReadingPosition(0, 2), controller.state.value.position)
        assertEquals(sessionId, controller.state.value.sessionId)
        assertEquals(0, engine.stopCount)
        assertEquals(1, engine.speaks.size)
    }

    @Test fun preparingDifferentBookInvalidatesOldCallback() {
        val engine = FakeSpeechEngine()
        val controller = controller(engine)
        controller.prepare("first", ReadingSource(listOf("Eski. Metin.")), ReadingPosition())
        engine.ready()
        controller.play()
        val staleId = engine.speaks.single().utteranceId

        val prepared = controller.prepare(
            "second",
            ReadingSource(listOf("Yeni. Metin.")),
            ReadingPosition()
        )
        controller.play()
        engine.done(staleId)

        assertTrue(prepared)
        assertTrue(controller.hasSession("second"))
        assertEquals("second", controller.state.value.bookId)
        assertEquals(ReadingPosition(), controller.state.value.position)
        assertEquals(listOf("Eski.", "Yeni."), engine.speaks.map { it.text })
        assertEquals(1, engine.stopCount)
    }
}
