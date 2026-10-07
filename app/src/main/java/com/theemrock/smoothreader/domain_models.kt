package com.theemrock.smoothreader

import android.net.Uri

enum class BookType {
    EPUB, DOCX, DOC, ODT, RTF, TXT
}

data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val type: BookType,
    val uri: String, // persist edilebilir olması için string saklıyoruz
    val addedAt: Long = System.currentTimeMillis()
) {
    fun uriObj(): Uri = Uri.parse(uri)
}

data class ReadingPosition(
    val paragraphIndex: Int = 0,
    val characterOffset: Int = 0
)

data class ReadingProgress(
    val bookId: String,
    val position: ReadingPosition = ReadingPosition(),
    val totalParagraphs: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun percent(): Int {
        if (totalParagraphs <= 0) return 0
        val p = (position.paragraphIndex.toFloat() / (totalParagraphs - 1).coerceAtLeast(1)) * 100f
        return p.coerceIn(0f, 100f).toInt()
    }
}

enum class ReadingMode { READ, LISTEN }
