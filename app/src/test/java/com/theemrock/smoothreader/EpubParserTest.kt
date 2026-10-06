package com.theemrock.smoothreader

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @After
    fun clearCache() = ParsedCache.clear()

    @Test
    fun `spine order is preserved and unused entries are not materialized`() = runBlocking {
        val epub = epubBytes(
            linkedMapOf(
                "META-INF/container.xml" to container("OPS/content.opf"),
                "OPS/content.opf" to opf(
                    manifest = listOf(
                        Triple("second", "chapters/second.xhtml", "application/xhtml+xml"),
                        Triple("cover", "images/cover.jpg", "image/jpeg"),
                        Triple("first", "chapters/first.xhtml", "application/xhtml+xml"),
                        Triple("unused", "chapters/unused.xhtml", "application/xhtml+xml")
                    ),
                    spine = listOf("first", "second")
                ),
                "OPS/images/cover.jpg" to "not-an-image",
                "OPS/styles/book.css" to "body { color: black; }",
                "OPS/chapters/second.xhtml" to "<html><body><p>Second</p></body></html>",
                "OPS/chapters/unused.xhtml" to "<html><body><p>Unused</p></body></html>",
                "OPS/chapters/first.xhtml" to "<html><body><p>First</p></body></html>"
            )
        )
        val stats = EpubZipReadStats()

        val paragraphs = parseEpubStreams({ ByteArrayInputStream(epub) }, stats)

        assertEquals(listOf("First", "Second"), paragraphs)
        assertEquals(2, stats.streamOpenCount)
        assertEquals(
            listOf(
                "META-INF/container.xml",
                "OPS/content.opf",
                "OPS/chapters/second.xhtml",
                "OPS/chapters/first.xhtml"
            ),
            stats.parsedEntryNames
        )
        assertFalse(stats.parsedEntryNames.any { it.endsWith("cover.jpg") })
        assertFalse(stats.parsedEntryNames.any { it.endsWith("book.css") })
        assertFalse(stats.parsedEntryNames.any { it.endsWith("unused.xhtml") })
    }

    @Test
    fun `empty or invalid epub fails safely`() = runBlocking {
        assertNull(parseEpubStreams(openStream = { ByteArrayInputStream(byteArrayOf()) }))
        assertNull(parseEpubStreams(openStream = {
            ByteArrayInputStream(epubBytes(mapOf("empty.txt" to "")))
        }))
    }

    @Test
    fun `cache hit skips parser and cache miss stores matching book`() = runBlocking {
        var calls = 0
        ParsedCache.put(ParsedBook("cached", listOf("ready")))

        val hit = ParsedCache.getOrParse("cached", parse = {
            calls++
            ParseResult.Error("must not run")
        })
        val miss = ParsedCache.getOrParse("new", parse = {
            calls++
            ParseResult.Success(ParsedBook("new", listOf("parsed")))
        })

        assertEquals(listOf("ready"), (hit as ParseResult.Success).parsed.paragraphs)
        assertEquals(listOf("parsed"), (miss as ParseResult.Success).parsed.paragraphs)
        assertEquals(listOf("parsed"), ParsedCache.get("new")?.paragraphs)
        assertEquals(1, calls)
    }

    @Test
    fun `stale or wrong book parse result cannot enter cache`() = runBlocking {
        val stale = ParsedCache.getOrParse(
            bookId = "A",
            parse = { ParseResult.Success(ParsedBook("A", listOf("old"))) },
            acceptResult = { false }
        )
        val wrong = ParsedCache.getOrParse(
            bookId = "B",
            parse = { ParseResult.Success(ParsedBook("A", listOf("wrong"))) }
        )

        assertTrue(stale is ParseResult.Success)
        assertTrue(wrong is ParseResult.Success)
        assertNull(ParsedCache.get("A"))
        assertNull(ParsedCache.get("B"))
    }

    private fun container(opfPath: String) =
        """<?xml version="1.0"?><container><rootfiles><rootfile full-path="$opfPath"/></rootfiles></container>"""

    private fun opf(manifest: List<Triple<String, String, String>>, spine: List<String>): String =
        """<?xml version="1.0"?><package><manifest>${manifest.joinToString("") {
            "<item id=\"${it.first}\" href=\"${it.second}\" media-type=\"${it.third}\"/>"
        }}</manifest><spine>${spine.joinToString("") { "<itemref idref=\"$it\"/>" }}</spine></package>"""

    private fun epubBytes(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
