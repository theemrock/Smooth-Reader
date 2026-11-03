package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Test

// Behaviour under test: pure RSVP helpers in rsvp_engine.kt (word split, ORP index, delay, WPM clamp).
// No existing RSVP tests; user instruction: "görev biene kadar ASLA DURMA".
class RsvpEngineTest {

    @Test
    fun wordsSplitAcrossParagraphsInOrder() {
        val words = rsvpWords(listOf("Bir  iki", "  üç\tdört "))
        assertEquals(listOf("Bir", "iki", "üç", "dört"), words)
    }

    @Test
    fun orpIsFiftyPercentOfWordLength() {
        assertEquals(0, orpIndex("a"))
        assertEquals(2, orpIndex("kitap"))              // 5 letters -> index 2
        assertEquals(3, orpIndex("merhaba"))            // 7 letters -> index 3
        assertEquals(6, orpIndex("bilgisayarlar"))      // 13 letters -> index 6
        assertEquals(8, orpIndex("uluslararasılaşma"))  // 17 letters -> index 8
    }

    @Test
    fun orpStaysInsideWord() {
        assertEquals(0, orpIndex(""))
        assertEquals(0, orpIndex("x"))
    }

    @Test
    fun punctuationIsRemovedFromWords() {
        assertEquals(listOf("Bugün", "saray"), rsvpWords(listOf("Bugün, saray.")))
        assertEquals(listOf("kelime"), rsvpWords(listOf("— \"kelime\"!")))
    }

    @Test
    fun delayMatchesWpmAndLongWordBonus() {
        assertEquals(300L, rsvpDelayMs(200, "kitap"))
        assertEquals(60L, rsvpDelayMs(1000, "kitap"))
        assertEquals(225L, rsvpDelayMs(400, "uluslararası")) // 150 ms base * 1.5 (>9 letters)
    }

    @Test
    fun wpmIsClampedAndSnapped() {
        assertEquals(RSVP_MIN_WPM, clampWpm(50))
        assertEquals(RSVP_MAX_WPM, clampWpm(5000))
        assertEquals(330, clampWpm(327))
        assertEquals(RSVP_DEFAULT_WPM, clampWpm(RSVP_DEFAULT_WPM))
    }

    @Test
    fun countUsesDotAsThousandsSeparator() {
        assertEquals("999", formatCount(999))
        assertEquals("37.018", formatCount(37018))
        assertEquals("1.234.567", formatCount(1234567))
    }

    @Test
    fun remainingTimeSumsDelaysToEnd() {
        assertEquals(120L, rsvpRemainingMs(listOf("a", "b"), 0, 1000)) // 2 x 60 ms
        assertEquals(60L, rsvpRemainingMs(listOf("a", "b"), 1, 1000))
        assertEquals(0L, rsvpRemainingMs(listOf("a", "b"), 2, 1000))
    }

    @Test
    fun startIndexMapsReadingPositionToWord() {
        val paragraphs = listOf("bir iki", "üç dört beş")
        assertEquals(0, rsvpStartWordIndex(paragraphs, 0, 0))
        assertEquals(1, rsvpStartWordIndex(paragraphs, 0, 4))  // "iki"
        assertEquals(1, rsvpStartWordIndex(paragraphs, 0, 3))  // on whitespace -> next word
        assertEquals(2, rsvpStartWordIndex(paragraphs, 1, 0))  // "üç" (after 2 words)
        assertEquals(3, rsvpStartWordIndex(paragraphs, 1, 6))  // "dört"
        assertEquals(0, rsvpStartWordIndex(emptyList(), 0, 0))
    }

    @Test
    fun wordIndexRoundTripsToReadingPosition() {
        val paragraphs = listOf("bir iki", "üç dört beş")
        assertEquals(ReadingPosition(0, 0), rsvpPositionForWordIndex(paragraphs, 0))
        assertEquals(ReadingPosition(0, 4), rsvpPositionForWordIndex(paragraphs, 1))
        assertEquals(ReadingPosition(1, 0), rsvpPositionForWordIndex(paragraphs, 2))
        assertEquals(ReadingPosition(1, 3), rsvpPositionForWordIndex(paragraphs, 3))
        for (k in 0..4) {
            val pos = rsvpPositionForWordIndex(paragraphs, k)
            assertEquals(k, rsvpStartWordIndex(paragraphs, pos.paragraphIndex, pos.characterOffset))
        }
    }

    @Test
    fun durationFormatsAsHhMm() {
        assertEquals("00:00", formatHhMm(0L))
        assertEquals("01:02", formatHhMm(3_720_000L))
    }
}
