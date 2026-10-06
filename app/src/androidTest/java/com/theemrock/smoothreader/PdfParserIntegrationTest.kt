package com.theemrock.smoothreader

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfParserIntegrationTest {
    @Test fun osmanProducesCanonicalParagraphsAndReopensFromArtifact() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = InstrumentationRegistry.getArguments().getString("osmanPdfPath")
            ?: error("Missing instrumentation argument: osmanPdfPath")
        val fixture = File(context.cacheDir, "parser-osman.pdf")
        instrumentation.uiAutomation.executeShellCommand("cat $source").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(fixture).use(input::copyTo)
            }
        }
        val artifactDirectory = File(context.cacheDir, "pdf-parser-integration").apply { deleteRecursively() }
        val countingEngine = CountingEngine(PdfBoxDocumentEngine(context))
        val parser = PdfParser(context, countingEngine, PdfArtifactStore(artifactDirectory))
        val book = Book("osman-integration", "Osman", type = BookType.PDF, uri = Uri.fromFile(fixture).toString())

        val first = parser.parse(book, context.contentResolver)
        val second = parser.parse(book, context.contentResolver)

	        assertTrue("first parse result: $first", first is ParseResult.Success)
        assertTrue(second is ParseResult.Success)
        val paragraphs = (first as ParseResult.Success).parsed.paragraphs
        assertTrue(paragraphs.size > 100)
        assertTrue(paragraphs.sumOf { it.length } > 80_000)
        assertFalse(paragraphs.any { it.matches(Regex("^\\d{1,4}$")) })
        assertEquals(paragraphs, (second as ParseResult.Success).parsed.paragraphs)
        assertEquals("Second parse must be a disk-cache hit", 1, countingEngine.calls)
        assertTrue(artifactDirectory.listFiles().orEmpty().single().length() > 0)
    }

    private class CountingEngine(private val delegate: PdfDocumentEngine) : PdfDocumentEngine {
        var calls = 0
        override suspend fun extract(input: InputStream): PdfExtraction {
            calls++
            return delegate.extract(input)
        }
    }
}
