package com.theemrock.smoothreader

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

data class ParsedBook(
    val bookId: String,
    val paragraphs: List<String>
)

sealed class ParseResult {
    data class Success(val parsed: ParsedBook) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

interface DocumentParser {
    suspend fun parse(
        book: Book,
        contentResolver: ContentResolver
    ): ParseResult
}

/* =========================================================
   TXT PARSER
   ========================================================= */

class TxtParser : DocumentParser {

    override suspend fun parse(
        book: Book,
        contentResolver: ContentResolver
    ): ParseResult = withContext(Dispatchers.IO) {

        try {

            val uri = Uri.parse(book.uri)

            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext ParseResult.Error(
                    "Dosya açılamadı."
                )

            val text = inputStream.use { input ->

                BufferedReader(
                    InputStreamReader(
                        input,
                        Charsets.UTF_8
                    )
                ).use { reader ->

                    reader.readText()
                }
            }

            val paragraphs = normalizeToParagraphs(text)

            if (paragraphs.isEmpty()) {

                return@withContext ParseResult.Error(
                    "Kitapta okunabilir metin bulunamadı."
                )
            }

            ParseResult.Success(
                ParsedBook(
                    bookId = book.id,
                    paragraphs = paragraphs
                )
            )

        } catch (e: Exception) {

            ParseResult.Error(
                "TXT dosyası okunamadı: ${e.message ?: "Bilinmeyen hata"}"
            )
        }
    }
}


/* =========================================================
   EPUB PARSER
   ========================================================= */

class EpubParser : DocumentParser {

    override suspend fun parse(
        book: Book,
        contentResolver: ContentResolver
    ): ParseResult = withContext(Dispatchers.IO) {

        try {

            val uri = Uri.parse(book.uri)

            val paragraphs = parseEpubStreams(openStream = {
                contentResolver.openInputStream(uri)
            }).orEmpty()

            if (paragraphs.isEmpty()) {

                return@withContext ParseResult.Error(
                    "EPUB içinde okunabilir metin bulunamadı."
                )
            }

            ParseResult.Success(
                ParsedBook(
                    bookId = book.id,
                    paragraphs = paragraphs
                )
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {

            ParseResult.Error(
                "EPUB dosyası okunamadı: ${e.message ?: "Bilinmeyen hata"}"
            )
        }
    }
}


/* =========================================================
   EPUB DATA
   ========================================================= */

private data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

private data class EpubOpfInfo(
    val manifest: Map<String, EpubManifestItem>,
    val spine: List<String>
)


/* =========================================================
   EPUB OPF BUL
   ========================================================= */

private fun findOpfPath(input: InputStream): String? {
    return try {
        var result: String? = null
        newSaxParser().parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
                if (result == null && name.equals("rootfile", ignoreCase = true)) {
                    attributes.getValue("full-path")?.takeIf { it.isNotBlank() }?.let {
                        result = normalizeZipPath(it)
                    }
                }
            }
        })
        result

    } catch (_: Exception) {

        null
    }
}


/* =========================================================
   OPF PARSE
   ========================================================= */

private fun parseOpf(input: InputStream): EpubOpfInfo {

    val manifest =
        mutableMapOf<String, EpubManifestItem>()

    val spine =
        mutableListOf<String>()

    newSaxParser().parse(input, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
            when {
                name.equals("item", ignoreCase = true) -> {
                    val id = attributes.getValue("id")
                    val href = attributes.getValue("href")
                    val mediaType = attributes.getValue("media-type")

                    if (!id.isNullOrBlank() && !href.isNullOrBlank() && !mediaType.isNullOrBlank()) {
                        manifest[id] = EpubManifestItem(id = id, href = href, mediaType = mediaType)
                    }
                }
                name.equals("itemref", ignoreCase = true) -> {
                    attributes.getValue("idref")?.takeIf { it.isNotBlank() }?.let(spine::add)
                }
            }
        }
    })

    return EpubOpfInfo(
        manifest = manifest,
        spine = spine
    )
}


/* =========================================================
   XHTML -> TEXT
   ========================================================= */

private fun xhtmlToText(input: InputStream): String {
    val html = InputStreamReader(input, Charsets.UTF_8).readText()

    return html
        .replace(
            Regex(
                "<script\\b[^>]*>.*?</script>",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            ),
            ""
        )
        .replace(
            Regex(
                "<style\\b[^>]*>.*?</style>",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            ),
            ""
        )
        .replace(
            Regex(
                "</(p|div|section|article|h1|h2|h3|h4|h5|h6|li|blockquote|br)\\s*>",
                RegexOption.IGNORE_CASE
            ),
            "\n\n"
        )
        .replace(
            Regex(
                "<li\\b[^>]*>",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        .replace(
            Regex(
                "<[^>]+>",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("\u00A0", " ")
        .replace("\u200B", "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .joinToString("\n") {
            it.trim()
        }
        .replace(
            Regex("\n{3,}"),
            "\n\n"
        )
        .trim()
}


/* =========================================================
   ZIP PATH NORMALIZE
   ========================================================= */

private fun normalizeZipPath(
    path: String
): String {

    val result =
        mutableListOf<String>()

    val cleanPath =
        path
            .replace("\\", "/")
            .removePrefix("/")

    for (part in cleanPath.split("/")) {

        when (part) {

            "",
            "." -> {
                // hiçbir şey yapma
            }

            ".." -> {

                if (result.isNotEmpty()) {
                    result.removeAt(result.lastIndex)
                }
            }

            else -> {
                result.add(part)
            }
        }
    }

    return result.joinToString("/")
}


/* =========================================================
   TEXT -> PARAGRAPHS
   ========================================================= */

fun normalizeToParagraphs(
    text: String
): List<String> {

    val normalized =
        text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\u00A0", " ")
            .replace("\u200B", "")
            .trim()

    if (normalized.isBlank()) {
        return emptyList()
    }

    val blocks =
        normalized
            .split(
                Regex("\n\\s*\n+")
            )
            .map {
                it
                    .replace(
                        Regex("[ \\t]+"),
                        " "
                    )
                    .replace(
                        Regex(" *\n *"),
                        " "
                    )
                    .trim()
            }
            .filter {
                it.isNotBlank()
            }

    val result =
        mutableListOf<String>()

    for (block in blocks) {

        if (block.length <= 1200) {

            result.add(block)

        } else {

            result.addAll(
                splitLongParagraph(block)
            )
        }
    }

    return result
}


/* =========================================================
   UZUN PARAGRAFLARI BÖL
   ========================================================= */

private fun splitLongParagraph(
    paragraph: String
): List<String> {

    val words =
        paragraph.split(
            Regex("\\s+")
        )

    val result =
        mutableListOf<String>()

    var current =
        StringBuilder()

    for (word in words) {

        if (
            current.isNotEmpty() &&
            current.length + word.length + 1 > 1200
        ) {

            result.add(
                current.toString().trim()
            )

            current =
                StringBuilder()
        }

        if (current.isNotEmpty()) {
            current.append(" ")
        }

        current.append(word)
    }

    if (current.isNotEmpty()) {

        result.add(
            current.toString().trim()
        )
    }

    return result
}


/* =========================================================
   DOCUMENT PARSER V1
   ========================================================= */

class DocumentParserV1 : DocumentParser {

    private val txtParser =
        TxtParser()

    private val epubParser =
        EpubParser()

    private val docxParser = DocxParser()

    override suspend fun parse(
        book: Book,
        contentResolver: ContentResolver
    ): ParseResult {

        return when (book.type) {

            BookType.TXT -> {

                txtParser.parse(
                    book,
                    contentResolver
                )
            }

            BookType.EPUB -> {

                epubParser.parse(
                    book,
                    contentResolver
                )
            }

            BookType.DOCX -> docxParser.parse(book, contentResolver)

            BookType.DOC,
            BookType.ODT,
            BookType.RTF -> {

                ParseResult.Error(
                    "${book.type.name} desteği henüz eklenmedi."
                )
            }
        }
    }
}

private fun newSaxParser() = SAXParserFactory.newInstance().apply {
    isNamespaceAware = true
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
}.newSAXParser()

internal data class EpubZipReadStats(
    var streamOpenCount: Int = 0,
    val parsedEntryNames: MutableList<String> = mutableListOf()
)

internal suspend fun parseEpubStreams(
    openStream: () -> InputStream?,
    stats: EpubZipReadStats = EpubZipReadStats()
): List<String>? {
    val trackedOpen = {
        stats.streamOpenCount++
        openStream()
    }
    val (opfPath, opfInfo) = readEpubMetadata(trackedOpen, stats) ?: return null
    if (opfInfo.spine.isEmpty()) return emptyList()
    val opfDirectory = opfPath.substringBeforeLast("/", "")
    val paths = opfInfo.spine.mapNotNull { id ->
        opfInfo.manifest[id]?.let { item ->
            normalizeZipPath(if (opfDirectory.isBlank()) item.href else "$opfDirectory/${item.href}")
        }
    }
    return readSpineParagraphs(trackedOpen, paths, stats)
}

private suspend fun readEpubMetadata(
    openStream: () -> InputStream?,
    stats: EpubZipReadStats? = null
): Pair<String, EpubOpfInfo>? {
    var opfPath: String? = null
    var opfInfo: EpubOpfInfo? = null
    val input = openStream() ?: return null
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val path = normalizeZipPath(entry.name)
                when {
                    path == "META-INF/container.xml" -> {
                        stats?.parsedEntryNames?.add(path)
                        opfPath = findOpfPath(NonClosingInputStream(zip))
                    }
                    opfPath != null && path == opfPath -> {
                        stats?.parsedEntryNames?.add(path)
                        opfInfo = parseOpf(NonClosingInputStream(zip))
                    }
                }
            }
            zip.closeEntry()
        }
    }
    val path = opfPath ?: return null
    val info = opfInfo ?: readZipEntry(openStream, path, ::parseOpf, stats) ?: return null
    return path to info
}

private suspend fun <T> readZipEntry(
    openStream: () -> InputStream?,
    targetPath: String,
    consume: (InputStream) -> T,
    stats: EpubZipReadStats? = null
): T? {
    val input = openStream() ?: return null
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: return null
            if (!entry.isDirectory && normalizeZipPath(entry.name) == targetPath) {
                stats?.parsedEntryNames?.add(targetPath)
                return consume(NonClosingInputStream(zip))
            }
            zip.closeEntry()
        }
    }
}

private suspend fun readSpineParagraphs(
    openStream: () -> InputStream?,
    spinePaths: List<String>,
    stats: EpubZipReadStats? = null
): List<String> {
    if (spinePaths.isEmpty()) return emptyList()
    val indexesByPath = spinePaths.withIndex().groupBy({ it.value }, { it.index })
    val chapters = arrayOfNulls<List<String>>(spinePaths.size)
    val input = openStream() ?: return emptyList()
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            val path = normalizeZipPath(entry.name)
            val indexes = if (entry.isDirectory) null else indexesByPath[path]
            if (indexes != null) {
                stats?.parsedEntryNames?.add(path)
                val chapter = normalizeToParagraphs(xhtmlToText(NonClosingInputStream(zip)))
                indexes.forEach { chapters[it] = chapter }
            }
            zip.closeEntry()
        }
    }
    return buildList {
        chapters.forEach { chapter -> if (chapter != null) addAll(chapter) }
    }
}

private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}
