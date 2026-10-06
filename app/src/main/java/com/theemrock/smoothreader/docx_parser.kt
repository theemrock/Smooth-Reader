package com.theemrock.smoothreader

import android.content.ContentResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

class DocxParser : DocumentParser {
    override suspend fun parse(book: Book, contentResolver: ContentResolver): ParseResult =
        withContext(Dispatchers.IO) {
            try {
                val input = contentResolver.openInputStream(book.uriObj())
                    ?: return@withContext ParseResult.Error("DOCX dosyası açılamadı.")
                input.use { parseStream(book.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ParseResult.Error("DOCX dosyası okunamadı. Dosyayı ve erişim iznini kontrol edin.")
            }
        }

    // Also used by JVM tests; no Android provider is required for ZIP/XML parsing.
    internal fun parseStream(bookId: String, input: InputStream): ParseResult {
        return try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry
                        ?: return ParseResult.Error("Geçerli DOCX ana içeriği (word/document.xml) bulunamadı.")
                    if (!entry.isDirectory && entry.name == "word/document.xml") {
                        val handler = DocumentHandler()
                        val reader = SAXParserFactory.newInstance().apply {
                            isNamespaceAware = true
                        }.newSAXParser().xmlReader
                        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
                        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                        reader.contentHandler = handler
                        reader.entityResolver = handler
                        reader.errorHandler = handler
                        // Stream only the main XML, without retaining images or other ZIP entries.
                        reader.parse(InputSource(object : FilterInputStream(zip) {
                            private var remaining = 64L * 1024 * 1024
                            private fun count(size: Int): Int {
                                if (size > 0) remaining -= size
                                if (remaining < 0) throw SAXException("DOCX ana içeriği çok büyük.")
                                return size
                            }
                            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int =
                                count(`in`.read(b, off, len))
                            override fun close() = Unit // ZipInputStream is owned by use above.
                        }))
                        if (!handler.hasBody) throw SAXException("DOCX gövdesi bulunamadı.")
                        return if (handler.paragraphs.isEmpty()) {
                            ParseResult.Error("DOCX dosyasında okunabilir metin bulunamadı.")
                        } else {
                            ParseResult.Success(ParsedBook(bookId, handler.paragraphs))
                        }
                    }
                    zip.closeEntry()
                }
                @Suppress("UNREACHABLE_CODE")
                ParseResult.Error("DOCX içeriği bulunamadı.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ParseResult.Error("DOCX içeriği okunamadı: dosya bozuk, geçersiz veya ana içerik çok büyük (en fazla 64 MiB).")
        }
    }
}

private class DocumentHandler : DefaultHandler2() {
    val paragraphs = mutableListOf<String>()
    var hasBody = false
        private set
    private var depth = 0
    private var bodyDepth = 0
    private var ignoredDepth = 0
    private var textDepth = 0
    private var paragraph: StringBuilder? = null
    private var documentNamespace: String? = null

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("DOCX içinde DTD desteklenmiyor.")
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("Harici XML kaynağı desteklenmiyor.")
    }

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        depth++
        if (depth == 1) {
            if (localName != "document" || uri !in setOf(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                    "http://purl.oclc.org/ooxml/wordprocessingml/main"
                )) throw SAXException("Word document kökü bulunamadı.")
            documentNamespace = uri
        }
        if (ignoredDepth > 0 || uri != documentNamespace) return
        if (localName == "body" && depth == 2) {
            if (hasBody) throw SAXException("Birden fazla belge gövdesi.")
            hasBody = true
            bodyDepth = depth
        }
        if (bodyDepth == 0) return
        // Drawing/textbox layout and deleted revisions are not main reading content.
        if (localName in setOf("drawing", "pict", "object", "del", "moveFrom", "pPr", "rPr")) {
            ignoredDepth = depth
            return
        }
        when (localName) {
            "p" -> {
                if (paragraph != null) throw SAXException("İç içe DOCX paragrafı.")
                paragraph = StringBuilder()
            }
            "t" -> if (paragraph != null) textDepth = depth
            "tab" -> paragraph?.append('\t')
            "br", "cr" -> paragraph?.append('\n')
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (textDepth > 0 && ignoredDepth == 0) paragraph?.append(ch, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (ignoredDepth == 0 && uri == documentNamespace) {
            if (localName == "t") textDepth = 0
            if (localName == "p") {
                paragraph?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(paragraphs::add)
                paragraph = null
            }
            if (localName == "body") bodyDepth = 0
        }
        if (depth == ignoredDepth) ignoredDepth = 0
        depth--
    }
}
