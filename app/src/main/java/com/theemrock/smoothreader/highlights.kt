package com.theemrock.smoothreader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user highlight. Offsets are absolute character offsets into ReadingSource.text,
 * so they stay valid for a given book while its parsed text is unchanged.
 */
internal data class Highlight(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val createdAt: Long
)

internal fun newHighlight(
    bookId: String,
    bookTitle: String,
    bookAuthor: String?,
    text: String,
    startOffset: Int,
    createdAt: Long = System.currentTimeMillis()
) = Highlight(
    id = UUID.randomUUID().toString(),
    bookId = bookId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    text = text,
    startOffset = startOffset,
    endOffset = startOffset + text.length,
    createdAt = createdAt
)

/** Ranges (relative to the page text) of highlights of one book that intersect the page. */
internal fun highlightRangesForPage(
    highlights: List<Highlight>,
    bookId: String,
    pageStart: Int,
    pageEnd: Int
): List<IntRange> = highlights
    .asSequence()
    .filter { it.bookId == bookId }
    .mapNotNull { h ->
        val start = maxOf(h.startOffset, pageStart)
        val end = minOf(h.endOffset, pageEnd)
        if (end > start) (start - pageStart) until (end - pageStart) else null
    }
    .toList()

internal object HighlightJson {
    fun encode(list: List<Highlight>): String {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("bookId", h.bookId)
                    .put("bookTitle", h.bookTitle)
                    .put("bookAuthor", h.bookAuthor ?: "")
                    .put("text", h.text)
                    .put("startOffset", h.startOffset)
                    .put("endOffset", h.endOffset)
                    .put("createdAt", h.createdAt)
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<Highlight> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Highlight(
                            id = o.getString("id"),
                            bookId = o.getString("bookId"),
                            bookTitle = o.optString("bookTitle", "Kitap"),
                            bookAuthor = o.optString("bookAuthor").takeIf { it.isNotBlank() },
                            text = o.getString("text"),
                            startOffset = o.getInt("startOffset"),
                            endOffset = o.getInt("endOffset"),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

internal class HighlightStore(private val context: Context) {

    private val key = stringPreferencesKey("highlights_json")

    val all: Flow<List<Highlight>> = context.dataStore.data.map { prefs ->
        HighlightJson.decode(prefs[key].orEmpty()).sortedByDescending { it.createdAt }
    }

    fun forBook(bookId: String): Flow<List<Highlight>> = all.map { list ->
        list.filter { it.bookId == bookId }
    }

    suspend fun add(highlight: Highlight) {
        context.dataStore.edit { prefs ->
            val current = HighlightJson.decode(prefs[key].orEmpty())
            prefs[key] = HighlightJson.encode(current + highlight)
        }
    }
}
