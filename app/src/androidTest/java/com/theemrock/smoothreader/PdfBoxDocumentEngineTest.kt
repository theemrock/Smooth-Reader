package com.theemrock.smoothreader

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxDocumentEngineTest {
    @Test fun osmanExtractsCompactPositionedLines() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = InstrumentationRegistry.getArguments().getString("osmanPdfPath")
            ?: error("Missing instrumentation argument: osmanPdfPath")
        val fixture = File(context.cacheDir, "engine-osman.pdf")
        instrumentation.uiAutomation.executeShellCommand("cat $source").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(fixture).use(input::copyTo)
            }
        }

        val extraction = FileInputStream(fixture).use { input ->
            PdfBoxDocumentEngine(context).extract(input)
        }

        assertEquals(106, extraction.pages.size)
        assertEquals(87, extraction.pages.count { it.textState == PdfPageTextState.TEXT })
        val text = extraction.pages.flatMap { it.lines }.joinToString("\n") { it.text }
        assertFalse(text.contains('\uFFFD'))
        for (letter in "çğıİöşü") assertTrue("Missing $letter", letter in text)
        assertTrue(extraction.pages.flatMap { it.lines }.all {
            it.bounds.width > 0f && it.bounds.height > 0f
        })
    }
}
