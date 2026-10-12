package com.theemrock.smoothreader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.*
import org.junit.Test

class ReaderProgressionTest {
    private val source = ReadingSource(
        listOf(
            "Merhaba dünya! İkinci cümle mi? Evet, üçüncü cümle.",
            "Şimdi yeni paragraf başladı. Türkçe İ, ğ ve ş korunur."
        )
    )

    @Test fun sentenceRangeMapsToCanonicalSourcePosition() {
        val page = source.page(0, source.length, source.text)
        val sentences = sentenceRanges(source, page)
        assertEquals(ReadingPosition(0, 0), sentences[0].start)
        assertEquals(ReadingPosition(0, 14), sentences[0].end)
        assertEquals("Merhaba dünya!", page.text.substring(sentences[0].localStart, sentences[0].localEnd))
    }

    @Test fun turkishSentencePunctuationProducesExpectedBoundaries() {
        val page = source.page(0, source.length, source.text)
        assertEquals(
            listOf("Merhaba dünya!", "İkinci cümle mi?", "Evet, üçüncü cümle.",
                "Şimdi yeni paragraf başladı.", "Türkçe İ, ğ ve ş korunur."),
            sentenceRanges(source, page).map { page.text.substring(it.localStart, it.localEnd) }
        )
    }

    @Test fun verticalSurfaceMovesCurrentAndAdjacentInOneCoordinateSystem() {
        assertEquals(VerticalPageGeometry(-240f, 760f, -1240f), verticalPageGeometry(-240f, 1000f))
        assertEquals(VerticalPageGeometry(240f, 1240f, -760f), verticalPageGeometry(240f, 1000f))
    }

    @Test fun normalizedSettleDecisionUsesViewportFractionAndVelocity() {
        assertEquals(0.20f, dragFraction(-200f, 1000f), 0.001f)
        assertEquals(0, normalizedVerticalSettle(-200f, 1000f, 0f).committedDirection)
        assertEquals(1, normalizedVerticalSettle(-360f, 1000f, 0f).committedDirection)
        assertEquals(-1, normalizedVerticalSettle(360f, 1000f, 0f).committedDirection)
        assertEquals(1, normalizedVerticalSettle(-80f, 1000f, -1600f).committedDirection)
    }

    @Test fun settleDurationUsesRemainingDistance() {
        val almostFinished = settleDurationMillis(900f, 1000f)
        val mostlyRemaining = settleDurationMillis(100f, 1000f)
        assertTrue(almostFinished < mostlyRemaining)
        assertTrue(mostlyRemaining >= 400)
    }

    @Test fun viewportOwnerHasDeterministicManualTakeoverAndNoAutoRestart() {
        val automatic = VerticalViewportState(VerticalViewportOwner.AUTO_TRACKING, -120f)
        val dragging = automatic.takeManualOwnership()
        assertEquals(VerticalViewportOwner.DRAGGING, dragging.owner)
        val idle = dragging.finishWithoutCommit()
        assertEquals(VerticalViewportOwner.IDLE, idle.owner)
        assertFalse(idle.resumeAutomatically)
    }

    @Test fun commitRebasePreservesVisiblePageCoordinate() {
        assertEquals(0f, rebaseCommittedOffset(-1000f, 1000f, 1), 0.001f)
        assertEquals(0f, rebaseCommittedOffset(1000f, 1000f, -1), 0.001f)
    }

    @Test fun listenSpringRetargetPreservesPositionAndVelocity() {
        val moving = ListenTrackingMotion(offset = -120f, velocity = -45f, target = -200f)
        val retargeted = moving.retarget(-260f)
        assertEquals(-120f, retargeted.offset, 0.001f)
        assertEquals(-45f, retargeted.velocity, 0.001f)
        val stepped = advanceListenTracking(retargeted, 0.016f)
        assertTrue(stepped.offset < retargeted.offset)
        assertTrue(stepped.velocity < retargeted.velocity)
    }

    @Test fun listenCommitRebasesViewportAndTargetTogether() {
        val motion = ListenTrackingMotion(-1000f, -80f, -1000f)
        val rebased = motion.rebase(1000f, 1)
        assertEquals(0f, rebased.offset, 0.001f)
        assertEquals(0f, rebased.target, 0.001f)
        assertEquals(-80f, rebased.velocity, 0.001f)
    }

    @Test fun listenTrackingRetargetsWithoutChangingOwnerOrOffset() {
        val tracking = VerticalViewportState(VerticalViewportOwner.AUTO_TRACKING, -120f, targetOffset = -200f)
        val retargeted = tracking.retarget(-340f)
        assertEquals(VerticalViewportOwner.AUTO_TRACKING, retargeted.owner)
        assertEquals(-120f, retargeted.offset, 0.001f)
        assertEquals(-340f, retargeted.targetOffset, 0.001f)
    }

    @Test fun staleViewportTokenCannotCommit() {
        val token = VerticalViewportToken(3, 7, 11)
        assertTrue(token.accepts(3, 7, 11))
        assertFalse(token.accepts(3, 8, 11))
        assertFalse(token.accepts(3, 7, 12))
    }

    @Test fun activeSentenceProducesBoundedUpwardViewportTarget() {
        assertEquals(0f, activeSentenceViewportTarget(100f, 1000f, 1000f), 0.01f)
        assertEquals(-200f, activeSentenceViewportTarget(700f, 1000f, 1000f), 0.01f)
        assertEquals(-1000f, activeSentenceViewportTarget(1700f, 1000f, 1000f), 0.01f)
    }

    @Test fun soundOffUsesSilentVolumeWithoutStoppingPlayback() {
        val position = ReadingPosition(4, 27)
        val transition = soundToggleTransition(
            PlaybackControlState(true, true, position, generation = 8)
        )
        assertTrue(transition.state.isPlaying)
        assertFalse(transition.state.isSoundEnabled)
        assertEquals(position, transition.state.position)
        assertEquals(9L, transition.state.generation)
        assertTrue(transition.restartSpeech)
        assertEquals(0f, utteranceVolume(transition.state.isSoundEnabled), 0f)
    }

    @Test fun soundOnUsesNormalVolumeAndPausedToggleDoesNotStartPlayback() {
        val position = ReadingPosition(2, 9)
        val transition = soundToggleTransition(
            PlaybackControlState(false, false, position, generation = 3)
        )
        assertFalse(transition.state.isPlaying)
        assertTrue(transition.state.isSoundEnabled)
        assertEquals(position, transition.state.position)
        assertEquals(3L, transition.state.generation)
        assertFalse(transition.restartSpeech)
        assertEquals(1f, utteranceVolume(transition.state.isSoundEnabled), 0f)
    }

    @Test fun speedChangeRestartsOnlyActivePlaybackFromCanonicalPosition() {
        val position = ReadingPosition(6, 41)
        val active = speedChangeTransition(PlaybackControlState(true, false, position, 11))
        assertTrue(active.restartSpeech)
        assertEquals(position, active.state.position)
        assertEquals(12L, active.state.generation)
        assertFalse(active.state.isSoundEnabled)

        val paused = speedChangeTransition(PlaybackControlState(false, false, position, 11))
        assertFalse(paused.restartSpeech)
        assertFalse(paused.state.isPlaying)
        assertEquals(11L, paused.state.generation)
    }

    @Test fun staleSpeechGenerationIsRejectedAfterControlledRestart() {
        val transition = soundToggleTransition(
            PlaybackControlState(true, true, ReadingPosition(1, 5), generation = 20)
        )
        assertFalse(acceptsSpeechGeneration(callbackGeneration = 20, transition.state))
        assertTrue(acceptsSpeechGeneration(callbackGeneration = 21, transition.state))
    }

    @Test fun activeSentenceHighlightUsesExactRangeWithoutChangingTextMetrics() {
        val rendered = highlightedReaderText(
            text = "Bir. İki.",
            activeStart = 5,
            activeEnd = 8,
            highlight = Color(0x4019C3E6)
        )

        val span = rendered.spanStyles.single()
        assertEquals(5, span.start)
        assertEquals(8, span.end)
        assertEquals(Color(0x4019C3E6), span.item.background)
        assertEquals(TextUnit.Unspecified, span.item.fontSize)
        assertNull(span.item.fontWeight)
    }

    @Test fun readerThemeHighlightsRemainVisibleAndReadable() {
        ReaderTheme.entries.forEach { theme ->
            val colors = themeColorsFor(theme)
            val paintedBackground = colors.highlight.compositeOver(colors.background)

            assertTrue(colors.highlight.alpha in 0.20f..0.40f)
            assertTrue(contrastRatio(paintedBackground, colors.background) >= 1.10f)
            assertTrue(contrastRatio(colors.text, paintedBackground) >= 4.50f)
        }
    }

    @Test fun manualVerticalDragPausesWithoutAutomaticRestart() {
        val listen = progressionAfterDrag(ReadingMode.LISTEN, isPlaying = true)
        val read = progressionAfterDrag(ReadingMode.READ, isPlaying = true)
        assertFalse(listen.isPlaying)
        assertFalse(read.isPlaying)
        assertFalse(listen.resumeAutomatically)
        assertFalse(read.resumeAutomatically)
    }

    @Test fun staleSpeechAndLayoutTransitionTokensAreRejected() {
        val gate = ProgressionTokenGate()
        val speech1 = gate.nextSpeech()
        val speech2 = gate.nextSpeech()
        assertFalse(gate.acceptsSpeech(speech1))
        assertTrue(gate.acceptsSpeech(speech2))
        val layout1 = gate.nextLayout(4)
        val layout2 = gate.nextLayout(4)
        assertFalse(gate.acceptsLayout(layout1))
        assertTrue(gate.acceptsLayout(layout2))
        assertFalse(gate.acceptsLayout(layout2.copy(contentVersion = 5)))
    }

    @Test fun progressionNeverConvertsCanonicalPositionToPageIndex() {
        val position = ReadingPosition(1, 11)
        val update = ProgressionUpdate(position, targetPageDirection = 1)
        assertEquals(position, update.position)
        assertEquals(1, update.targetPageDirection)
    }
}

private fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
