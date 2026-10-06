package com.theemrock.smoothreader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PdfExtraction(val pages: List<PdfExtractedPage>)

interface PdfDocumentEngine {
    suspend fun extract(input: InputStream): PdfExtraction
}

class PdfBoxDocumentEngine(context: Context) : PdfDocumentEngine {
    private val appContext = context.applicationContext

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    override suspend fun extract(input: InputStream): PdfExtraction = withContext(Dispatchers.IO) {
        PDDocument.load(input.buffered()).use { document ->
            val stripper = CompactLineStripper()
            val pages = ArrayList<PdfExtractedPage>(document.numberOfPages)
            for (pageIndex in 0 until document.numberOfPages) {
                currentCoroutineContext().ensureActive()
                val page = document.getPage(pageIndex)
                val mediaBox = page.mediaBox
                val rotation = ((page.rotation % 360) + 360) % 360
                try {
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.beginPage()
                    stripper.getText(document)
                    currentCoroutineContext().ensureActive()
                    val lines = stripper.takeLines()
                    val state = when {
                        lines.isEmpty() -> PdfPageTextState.NO_TEXT
                        rotation == 90 || rotation == 270 -> PdfPageTextState.AMBIGUOUS
                        else -> PdfPageTextState.TEXT
                    }
                    pages += PdfExtractedPage(
                        pageIndex = pageIndex,
                        width = if (rotation == 90 || rotation == 270) mediaBox.height else mediaBox.width,
                        height = if (rotation == 90 || rotation == 270) mediaBox.width else mediaBox.height,
                        rotation = rotation,
                        lines = lines,
                        textState = state,
                        reason = if (state == PdfPageTextState.AMBIGUOUS) "rotated_page" else null
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pages += PdfExtractedPage(
                        pageIndex,
                        mediaBox.width,
                        mediaBox.height,
                        rotation,
                        emptyList(),
                        PdfPageTextState.EXTRACTION_ERROR,
                        e.javaClass.simpleName
                    )
                }
            }
            PdfExtraction(pages)
        }
    }

    private class CompactLineStripper : PDFTextStripper() {
        private val lines = mutableListOf<PdfTextLine>()
        private var sourceOrder = 0

        init {
            sortByPosition = true
            suppressDuplicateOverlappingText = true
        }

        fun beginPage() {
            lines.clear()
            sourceOrder = 0
        }

        fun takeLines(): List<PdfTextLine> = lines.toList()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val clean = text.replace('\u0000', ' ').trim()
            if (clean.isNotBlank() && textPositions.isNotEmpty()) {
                val left = textPositions.minOf { it.xDirAdj }
                val right = textPositions.maxOf { it.xDirAdj + it.widthDirAdj }
                val baseline = textPositions.map { it.yDirAdj }.average().toFloat()
                val top = textPositions.minOf { it.yDirAdj - it.heightDir }
                val bottom = textPositions.maxOf { it.yDirAdj }
                if (right > left && bottom > top) {
                    lines += PdfTextLine(
                        text = clean,
                        bounds = PdfBounds(left, top, right, bottom),
                        sourceOrder = sourceOrder++,
                        baseline = baseline
                    )
                }
            }
            super.writeString(text, textPositions)
        }
    }
}
