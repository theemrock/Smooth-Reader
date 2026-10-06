package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {
    private val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun document(body: String, ns: String = namespace) =
        """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$ns"><w:body>$body</w:body></w:document>"""

    private fun archive(xml: String, path: String = "word/document.xml"): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(path))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun parse(bytes: ByteArray): ParseResult =
        DocxParser().parseStream("docx-test", ByteArrayInputStream(bytes))

    private fun paragraphs(body: String): List<String> {
        val result = parse(archive(document(body)))
        assertTrue("Expected success, got $result", result is ParseResult.Success)
        return (result as ParseResult.Success).parsed.also {
            assertEquals("docx-test", it.bookId)
        }.paragraphs
    }

    @Test fun extractsParagraphsInOrderAndDropsEmptyParagraphs() {
        assertEquals(listOf("First paragraph", "Second paragraph"), paragraphs("""
            <w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>
            <w:p/><w:p><w:r><w:t xml:space="preserve">   </w:t></w:r></w:p>
            <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
        """))
    }

    @Test fun preservesTurkishUnicodeAndXmlEntities() {
        assertEquals(listOf("İı Şş Ğğ Üü Öö Çç — 日本語 📚 & <kitap>"), paragraphs(
            "<w:p><w:r><w:t>İı Şş Ğğ Üü Öö Çç — 日本語 📚 &amp; &lt;kitap&gt;</w:t></w:r></w:p>"))
    }

    @Test fun joinsRunsWithoutInventingSpacesAndIncludesHyperlinkText() {
        assertEquals(listOf("Birleştirilmiş metin bağlantı"), paragraphs("""
            <w:p><w:r><w:t>Birleş</w:t></w:r>
            <w:r><w:rPr><w:b/></w:rPr><w:t>tirilmiş</w:t></w:r>
            <w:r><w:t xml:space="preserve"> metin </w:t></w:r>
            <w:hyperlink><w:r><w:t>bağlantı</w:t></w:r></w:hyperlink></w:p>
        """))
    }

    @Test fun preservesTabsAndBreaksButIgnoresTabStopDefinitions() {
        assertEquals(listOf("A\tB\nC\nD"), paragraphs("""
            <w:p><w:r><w:t>A</w:t></w:r>
            <w:pPr><w:tabs><w:tab w:pos="720"/></w:tabs></w:pPr>
            <w:r><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t><w:cr/><w:t>D</w:t></w:r></w:p>
        """))
    }

    @Test fun keepsTableCellParagraphsInDocumentOrder() {
        assertEquals(listOf("Before", "A1", "A2", "B", "C", "After"), paragraphs("""
            <w:p><w:r><w:t>Before</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc>
              <w:p><w:r><w:t>A1</w:t></w:r></w:p><w:p><w:r><w:t>A2</w:t></w:r></w:p>
            </w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:t>After</w:t></w:r></w:p>
        """))
    }

    @Test fun ignoresDeletedTextFieldInstructionsAndDrawingText() {
        assertEquals(listOf("Visible inserted"), paragraphs("""
            <w:p><w:r><w:instrText>FIELD CODE</w:instrText><w:t>Visible</w:t></w:r>
            <w:del><w:r><w:delText>deleted</w:delText></w:r></w:del>
            <w:r><w:drawing><w:txbxContent><w:p><w:r><w:t>Textbox</w:t></w:r></w:p></w:txbxContent></w:drawing></w:r>
            <w:ins><w:r><w:t xml:space="preserve"> inserted</w:t></w:r></w:ins></w:p>
        """))
    }

    @Test fun acceptsStrictWordNamespace() {
        val result = parse(archive(document("<w:p><w:r><w:t>Strict</w:t></w:r></w:p>",
            "http://purl.oclc.org/ooxml/wordprocessingml/main")))
        assertEquals(listOf("Strict"), (result as ParseResult.Success).parsed.paragraphs)
    }

    @Test fun rejectsNonZipMissingContentAndMalformedXml() {
        for (bytes in listOf(
            "Plain text is not DOCX".toByteArray(),
            byteArrayOf(),
            archive(document(""), "word/other.xml"),
            archive("<w:document"),
            archive(document("<w:p><w:r><w:t>Partial</w:t></w:r></w:p>").dropLast(5)),
            archive("<document><body>Wrong namespace</body></document>"),
            archive("<w:document xmlns:w=\"$namespace\"/>"),
            archive(document("<w:p/>"))
        )) assertTrue(parse(bytes) is ParseResult.Error)
    }

    @Test fun rejectsDtdAndEntityDeclarations() {
        val xml = """<!DOCTYPE w:document [<!ENTITY secret SYSTEM "file:///nonexistent-docx-test">]>
            <w:document xmlns:w="$namespace"><w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>"""
        assertTrue(parse(archive(xml)) is ParseResult.Error)
    }
}
