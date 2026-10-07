package com.theemrock.smoothreader

import java.util.Locale

internal object ImportFileType {
    val pickerMimeTypes = arrayOf(
        "application/epub+zip",
        "text/plain",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        // Some providers expose EPUB/DOCX as archives or generic binary files.
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream"
    )

    fun detect(mimeType: String?, displayName: String?): BookType? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val name = displayName?.trim().orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val extensionType = when (extension) {
            "epub" -> BookType.EPUB
            "txt" -> BookType.TXT
            "docx" -> BookType.DOCX
            else -> null
        }
        // An explicit unknown/unsupported extension must not become TXT via MIME.
        if (extension.isNotEmpty() && extensionType == null) return null
        val mimeBookType = when (mime) {
            "application/epub+zip" -> BookType.EPUB
            "text/plain" -> BookType.TXT
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> BookType.DOCX
            null, "", "*/*", "application/octet-stream", "binary/octet-stream" -> return extensionType
            "application/zip", "application/x-zip-compressed" ->
                return extensionType?.takeIf { it == BookType.EPUB || it == BookType.DOCX }
            else -> return null
        }
        // Conflicting concrete metadata is not sufficient for safe identification.
        return mimeBookType.takeIf { extensionType == null || extensionType == it }
    }
}
