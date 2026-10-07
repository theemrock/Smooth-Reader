package com.theemrock.smoothreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportFileTypeTest {
    @Test fun supportedNamesWorkWithGenericOrMissingMime() {
        for ((extension, type) in listOf(
            "EPUB" to BookType.EPUB,
            "TXT" to BookType.TXT,
            "DOCX" to BookType.DOCX
        )) {
            assertEquals(type, ImportFileType.detect(null, "Türkçe kitap.$extension"))
            assertEquals(type, ImportFileType.detect("application/octet-stream", "Kitap.$extension"))
        }
        assertEquals(BookType.DOCX, ImportFileType.detect("application/zip", "Kitap.docx"))
        assertEquals(BookType.EPUB, ImportFileType.detect("application/zip", "Kitap.epub"))
    }

    @Test fun specificMimeWorksWithoutFileExtension() {
        assertEquals(BookType.EPUB, ImportFileType.detect("application/epub+zip", null))
        assertEquals(BookType.TXT, ImportFileType.detect("TEXT/PLAIN; charset=utf-8", "Kitap"))
        assertEquals(BookType.DOCX, ImportFileType.detect(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Kitap"))
    }

    @Test fun unsupportedExtensionsNeverFallBackToText() {
        for (extension in listOf("doc", "odt", "rtf", "pdf", "jpg", "xyz", "zip")) {
            assertNull(ImportFileType.detect("text/plain", "Kitap.$extension"))
            assertNull(ImportFileType.detect(null, "Kitap.$extension"))
        }
    }

    @Test fun unknownAndConflictingMetadataIsRejected() {
        assertNull(ImportFileType.detect(null, null))
        assertNull(ImportFileType.detect("application/octet-stream", "12345"))
        assertNull(ImportFileType.detect("application/pdf", null))
        assertNull(ImportFileType.detect("application/pdf", "Kitap.pdf"))
        assertNull(ImportFileType.detect("text/plain", "Kitap.pdf"))
        assertNull(ImportFileType.detect("text/plain", "Kitap.docx"))
        assertNull(ImportFileType.detect("application/zip", "Kitap.txt"))
    }
}
