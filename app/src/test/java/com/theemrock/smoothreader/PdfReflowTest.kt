package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReflowTest {
    @Test fun geometricOrderingUsesTopThenLeft() {
        val page = page(
            line("second", 0, 20f, 100f),
            line("first", 1, 20f, 20f),
            line("same row right", 2, 220f, 20f)
        )

        assertEquals(listOf("first", "same row right", "second"), PdfReadingOrder.order(page).map { it.text })
    }

    @Test fun geometricOrderingRemainsTransitiveAcrossOverlappingRows() {
        val page = page(
            line("top", 0, 300f, 100f),
            line("middle", 1, 200f, 107f),
            line("bottom", 2, 100f, 114f)
        )

        assertEquals(listOf("top", "middle", "bottom"), PdfReadingOrder.order(page).map { it.text })
    }

    @Test fun rotatedPageStillUsesNormalizedCoordinates() {
        val page = page(
            line("below", 0, 20f, 80f),
            line("above", 1, 20f, 20f),
            rotation = 90
        )

        assertEquals(listOf("above", "below"), PdfReadingOrder.order(page).map { it.text })
    }

    @Test fun joinsPhysicalLinesAndSplitsOnLargeGap() {
        val result = normalize(
            page(
                line("Birinci fiziksel satır", 0, 40f, 100f),
                line("aynı paragrafın devamıdır.", 1, 40f, 122f),
                line("Yeni paragraf başlar.", 2, 40f, 180f)
            )
        )

        assertEquals(listOf("Birinci fiziksel satır aynı paragrafın devamıdır.", "Yeni paragraf başlar."), result.paragraphs)
    }

    @Test fun repeatedHeaderVariationAndGeometricPageNumbersAreSuppressed() {
        val pages = (0..4).map { index ->
            page(
                line(if (index == 2) "ROBOTLARIN YÜKSELİŞl" else "ROBOTLARIN YÜKSELİŞİ", 0, 40f, 15f),
                line("Gövde metni $index.", 1, 40f, 120f),
                line("${index + 10}", 2, 280f, 780f),
                index = index
            )
        }

        val result = normalize(*pages.toTypedArray())

        assertFalse(result.paragraphs.any { "ROBOTLARIN" in it })
        assertFalse(result.paragraphs.any { it.matches(Regex("\\d+")) })
        assertTrue(result.paragraphs.any { "Gövde metni 3" in it })
    }

    @Test fun bodyNumbersArePreserved() {
        val result = normalize(page(line("2024 yılında 42 deney yapıldı.", 0, 40f, 300f)))
        assertEquals(listOf("2024 yılında 42 deney yapıldı."), result.paragraphs)
    }

    @Test fun standaloneTableOfContentsPageNumberAtRightMarginIsSuppressed() {
        val result = normalize(
            page(
                line("Chapter title", 0, 40f, 160f, right = 220f),
                line("111", 1, 550f, 162f, right = 570f)
            )
        )

        assertEquals(listOf("Chapter title"), result.paragraphs)
    }

    @Test fun softHyphenAndStrongWrapHyphenJoinButCompoundHyphenStays() {
        val result = normalize(
            page(
                line("geliş\u00AD", 0, 40f, 100f, right = 560f),
                line("meler sürüyor ve Türkçe-İngilizce", 1, 40f, 122f, right = 560f),
                line("sözlük korunuyor.", 2, 40f, 144f)
            )
        )

        assertEquals(listOf("gelişmeler sürüyor ve Türkçe-İngilizce sözlük korunuyor."), result.paragraphs)
    }

    @Test fun weakHardHyphenEvidencePreservesHyphen() {
        val result = normalize(
            page(
                line("Türkçe-", 0, 40f, 100f, right = 180f),
                line("İngilizce sözlük.", 1, 40f, 122f)
            )
        )

        assertEquals(listOf("Türkçe- İngilizce sözlük."), result.paragraphs)
    }

    @Test fun strongCrossPageContinuationJoinsParagraph() {
        val result = normalize(
            page(line("Cümle sonraki sayfada", 0, 40f, 740f, right = 560f), index = 0),
            page(line("devam ederek tamamlanır.", 0, 40f, 90f), index = 1)
        )

        assertEquals(listOf("Cümle sonraki sayfada devam ederek tamamlanır."), result.paragraphs)
        assertEquals(setOf(0, 1), result.sourceMap.map { it.pageIndex }.toSet())
        assertEquals(0, result.sourceMap.first().paragraphIndex)
    }

    @Test fun emptyAndAmbiguousPagesDoNotDiscardReliableText() {
        val text = page(line("Güvenilir metin.", 0, 40f, 100f), index = 0)
        val empty = page(index = 1, state = PdfPageTextState.NO_TEXT)
        val ambiguous = page(line("A B C", 0, 40f, 100f, ambiguous = true), index = 2, state = PdfPageTextState.AMBIGUOUS)

        val result = normalize(text, empty, ambiguous)

        assertTrue(result.paragraphs.contains("Güvenilir metin."))
        assertTrue(result.paragraphs.contains("A B C"))
        assertEquals(PdfReflowDecision.ALLOWED, result.capabilities.decision)
    }

    @Test fun capabilitiesDistinguishUnavailableAndUnreliable() {
        assertEquals(
            PdfReflowDecision.UNAVAILABLE,
            PdfCapabilities.aggregate(listOf(page(index = 0, state = PdfPageTextState.NO_TEXT))).decision
        )
        assertEquals(
            PdfReflowDecision.UNRELIABLE,
            PdfCapabilities.aggregate(listOf(
                page(index = 0, state = PdfPageTextState.EXTRACTION_ERROR),
                page(index = 1, state = PdfPageTextState.AMBIGUOUS)
            )).decision
        )
    }

    private fun normalize(vararg pages: PdfExtractedPage): PdfNormalizationResult =
        PdfReflowNormalizer().normalize(pages.toList())

    private fun page(
        vararg lines: PdfTextLine,
        index: Int = 0,
        rotation: Int = 0,
        state: PdfPageTextState = if (lines.isEmpty()) PdfPageTextState.NO_TEXT else PdfPageTextState.TEXT
    ) = PdfExtractedPage(index, 600f, 800f, rotation, lines.toList(), state)

    private fun line(
        text: String,
        source: Int,
        left: Float,
        top: Float,
        right: Float = 520f,
        bottom: Float = top + 16f,
        ambiguous: Boolean = false
    ) = PdfTextLine(text, PdfBounds(left, top, right, bottom), source, top + 14f, ambiguous)
}
