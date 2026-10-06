package com.theemrock.smoothreader

import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfArtifactStoreTest {
    @Test fun matchingFingerprintIsCacheHit() = withStore { store ->
        val artifact = artifact(fingerprint())
        runBlocking { store.write(artifact) }
        assertEquals(artifact, store.read(artifact.fingerprint))
    }

    @Test fun staleFingerprintAndSchemaAreCacheMisses() = withStore { store ->
        val artifact = artifact(fingerprint())
        runBlocking { store.write(artifact) }

        assertNull(store.read(fingerprint(size = 999)))
        assertNull(store.read(fingerprint(normalizationVersion = 2)))
    }

    @Test fun corruptArtifactIsCacheMiss() = withStore { store ->
        val expected = fingerprint()
        store.fileFor(expected.bookId).apply {
            parentFile!!.mkdirs()
            writeText("not-json")
        }
        assertNull(store.read(expected))
    }

    @Test fun cancellationDoesNotPublishPartialArtifact() = withStore { store ->
        val artifact = artifact(fingerprint())
        try {
            runBlocking {
                store.write(artifact) { throw CancellationException("cancel") }
            }
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(store.fileFor(artifact.fingerprint.bookId).exists())
        assertTrue(store.directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    private fun fingerprint(
        size: Long = 123,
        normalizationVersion: Int = 1
    ) = PdfArtifactFingerprint(
        bookId = "book",
        sourceUri = "content://book.pdf",
        sourceSize = size,
        sourceLastModified = 456,
        prefixHash = "abc",
        engineVersion = "pdfbox-android-2.0.27.0",
        normalizationVersion = normalizationVersion
    )

    private fun artifact(fingerprint: PdfArtifactFingerprint) = PdfCanonicalArtifact(
        fingerprint = fingerprint,
        capabilities = PdfDocumentCapabilities(
            listOf(PdfPageCapability(0, 600f, 800f, 0, PdfPageTextState.TEXT)),
            PdfReflowDecision.ALLOWED
        ),
        paragraphs = listOf("Bir paragraf."),
        sourceMap = listOf(PdfSourceMapEntry(0, 0, PdfBounds(1f, 2f, 3f, 4f), 0, 0, 13))
    )

    private fun withStore(block: (PdfArtifactStore) -> Unit) {
        val directory = createTempDir(prefix = "pdf-artifact-")
        try {
            block(PdfArtifactStore(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
