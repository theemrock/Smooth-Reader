package com.theemrock.smoothreader

import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxRobotMemoryGateTest {
    @Test
    fun fullRobotExtractionIsBoundedAndPositioned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val source = InstrumentationRegistry.getArguments().getString("robotPdfPath")
            ?: error("Missing instrumentation argument: robotPdfPath")
        val fixture = File(context.cacheDir, "robot-memory-gate.pdf")
        instrumentation.uiAutomation.executeShellCommand("cat $source").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(fixture).use(input::copyTo)
            }
        }

        val runtime = Runtime.getRuntime()
        fun javaHeapBytes(): Long = runtime.totalMemory() - runtime.freeMemory()
        val beforeOpenHeap = javaHeapBytes()
        val beforeOpenPssKb = Debug.getPss().toLong()
        val beforeOpenNative = Debug.getNativeHeapAllocatedSize()
        val startedAt = System.nanoTime()
        var afterOpenHeap = 0L
        var afterOpenPssKb = 0L
        var afterOpenNative = 0L
        var peakHeap = beforeOpenHeap
        var peakPssKb = beforeOpenPssKb
        var peakNative = beforeOpenNative
        var textPages = 0
        var characters = 0L
        var positions = 0L
        var positiveBounds = 0L
        var replacementCharacters = 0L

        PDDocument.load(fixture).use { document ->
            assertEquals(336, document.numberOfPages)
            afterOpenHeap = javaHeapBytes()
            afterOpenPssKb = Debug.getPss().toLong()
            afterOpenNative = Debug.getNativeHeapAllocatedSize()
            val stripper = PositionCountingStripper()
            for (pageIndex in 0 until document.numberOfPages) {
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.resetPageCounters()
                val text = stripper.getText(document)
                if (text.isNotBlank()) textPages++
                characters += text.length
                replacementCharacters += text.count { it == '\uFFFD' }
                positions += stripper.pagePositions
                positiveBounds += stripper.pagePositiveBounds
                peakHeap = max(peakHeap, javaHeapBytes())
                peakPssKb = max(peakPssKb, Debug.getPss().toLong())
                peakNative = max(peakNative, Debug.getNativeHeapAllocatedSize())
            }
        }
        runtime.gc()
        val afterCloseHeap = javaHeapBytes()
        val afterClosePssKb = Debug.getPss().toLong()
        val afterCloseNative = Debug.getNativeHeapAllocatedSize()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val metrics = buildString {
            append("pages=336 textPages=$textPages chars=$characters positions=$positions ")
            append("positiveBounds=$positiveBounds replacement=$replacementCharacters elapsedMs=$elapsedMs ")
            append("heap=$beforeOpenHeap/$afterOpenHeap/$peakHeap/$afterCloseHeap ")
            append("pssKb=$beforeOpenPssKb/$afterOpenPssKb/$peakPssKb/$afterClosePssKb ")
            append("native=$beforeOpenNative/$afterOpenNative/$peakNative/$afterCloseNative")
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "PDFBOX_GATE $metrics\n") })

        assertEquals(metrics, 329, textPages)
        assertTrue(metrics, characters in 550_000L..700_000L)
        assertTrue(metrics, positions > 70_000)
        assertEquals(metrics, positions, positiveBounds)
        assertEquals(metrics, 0L, replacementCharacters)
    }

    private class PositionCountingStripper : PDFTextStripper() {
        var pagePositions = 0L
            private set
        var pagePositiveBounds = 0L
            private set

        fun resetPageCounters() {
            pagePositions = 0
            pagePositiveBounds = 0
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            pagePositions += textPositions.size
            pagePositiveBounds += textPositions.count {
                it.widthDirAdj > 0f && it.heightDir > 0f
            }
            super.writeString(text, textPositions)
        }
    }
}
