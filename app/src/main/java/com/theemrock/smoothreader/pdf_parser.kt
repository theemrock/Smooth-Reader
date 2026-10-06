package com.theemrock.smoothreader

import android.content.ContentResolver
import android.content.Context
import android.provider.OpenableColumns
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfParser(
    context: Context,
    private val engine: PdfDocumentEngine = PdfBoxDocumentEngine(context),
    private val artifactStore: PdfArtifactStore = PdfArtifactStore(context.cacheDir.resolve("pdf_reflow_v1")),
    private val normalizer: PdfReflowNormalizer = PdfReflowNormalizer()
) : DocumentParser {
    override suspend fun parse(book: Book, contentResolver: ContentResolver): ParseResult {
        return try {
        val fingerprint = withContext(Dispatchers.IO) { fingerprint(book, contentResolver) }
        artifactStore.read(fingerprint)?.let {
            return ParseResult.Success(ParsedBook(book.id, it.paragraphs))
        }
        val extraction = contentResolver.openInputStream(book.uriObj())?.use { engine.extract(it) }
            ?: return ParseResult.Error("PDF dosyası açılamadı.")
        val normalized = withContext(Dispatchers.Default) { normalizer.normalize(extraction.pages) }
        if (normalized.capabilities.decision != PdfReflowDecision.ALLOWED || normalized.paragraphs.isEmpty()) {
            return ParseResult.Error("PDF içinde güvenilir biçimde okunabilir metin bulunamadı.")
        }
        artifactStore.write(PdfCanonicalArtifact(
            fingerprint,
            normalized.capabilities,
            normalized.paragraphs,
            normalized.sourceMap
        ))
        ParseResult.Success(ParsedBook(book.id, normalized.paragraphs))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ParseResult.Error("PDF metni çıkarılamadı. Dosya bozuk, şifreli veya desteklenmiyor olabilir.")
        }
    }

    private fun fingerprint(book: Book, resolver: ContentResolver): PdfArtifactFingerprint {
        var size = -1L
        var modified = 0L
        runCatching {
            resolver.query(book.uriObj(), arrayOf(OpenableColumns.SIZE, "last_modified"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                    cursor.getColumnIndex("last_modified").takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { modified = cursor.getLong(it) }
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(book.uriObj(), "r")?.use { it.length } }.getOrNull() ?: -1
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(book.uriObj())?.use { input ->
            val buffer = ByteArray(8192)
            var remaining = 65_536
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                digest.update(buffer, 0, count)
                remaining -= count
            }
        } ?: error("PDF source unavailable")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return PdfArtifactFingerprint(
            book.id, book.uri, size, modified, hash,
            engineVersion = "pdfbox-android-2.0.27.0",
            normalizationVersion = 1
        )
    }
}
