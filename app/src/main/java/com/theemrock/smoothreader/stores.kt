package com.theemrock.smoothreader

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "ebook_reader_v1")

class LibraryStore(private val context: Context) {

    private val KEY_BOOKS = stringPreferencesKey("books_json")

    val booksFlow: Flow<List<Book>> = context.dataStore.data.map { prefs ->
        decodeBooks(prefs[KEY_BOOKS].orEmpty())
    }

    suspend fun upsertBooks(newBooks: List<Book>) {
        context.dataStore.edit { prefs ->
            val current = decodeBooks(prefs[KEY_BOOKS].orEmpty()).toMutableList()
            val byId = current.associateBy { it.id }.toMutableMap()
            val knownUris = current.mapTo(mutableSetOf()) { it.uri }
            for (b in newBooks) {
                // Keep the original ID and progress when re-importing a URI.
                if (knownUris.add(b.uri)) byId[b.id] = b
            }
            val merged = byId.values.sortedByDescending { it.addedAt }
            prefs[KEY_BOOKS] = encodeBooks(merged)
        }
    }

    suspend fun deleteBook(bookId: String) {
        context.dataStore.edit { prefs ->
            val current = decodeBooks(prefs[KEY_BOOKS].orEmpty()).filterNot { it.id == bookId }
            prefs[KEY_BOOKS] = encodeBooks(current)
        }
    }

    private fun encodeBooks(list: List<Book>): String {
        val arr = JSONArray()
        list.forEach { b ->
            val o = JSONObject()
            o.put("id", b.id)
            o.put("title", b.title)
            o.put("author", b.author ?: "")
            o.put("type", b.type.name)
            o.put("uri", b.uri)
            o.put("addedAt", b.addedAt)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decodeBooks(json: String): List<Book> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val author = o.optString("author").takeIf { it.isNotBlank() }
                    val type = runCatching { BookType.valueOf(o.getString("type")) }.getOrNull()
                        ?: continue
                    add(
                        Book(
                            id = o.getString("id"),
                            title = o.optString("title", "Untitled"),
                            author = author,
                            type = type,
                            uri = o.getString("uri"),
                            addedAt = o.optLong("addedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

class ProgressStore(private val context: Context) {
    private val legacyKey = stringPreferencesKey("progress_json")
    private val canonicalKey = stringPreferencesKey("reading_position_v2_json")

    val progressFlow: Flow<Map<String, ReadingProgress>> = context.dataStore.data.map { prefs ->
        ProgressJson.decode(prefs[legacyKey].orEmpty(), legacy = true) +
            ProgressJson.decode(prefs[canonicalKey].orEmpty())
    }

    internal fun storedProgress(bookId: String): Flow<Pair<ReadingProgress?, Boolean>> = context.dataStore.data.map { prefs ->
        val canonical = ProgressJson.decode(prefs[canonicalKey].orEmpty())[bookId]
        (canonical ?: ProgressJson.decode(prefs[legacyKey].orEmpty(), legacy = true)[bookId]) to (canonical != null)
    }

    suspend fun setProgress(p: ReadingProgress, onlyIfAbsent: Boolean = false) {
        context.dataStore.edit { prefs ->
            prefs[canonicalKey] = ProgressJson.upsert(
                prefs[canonicalKey].orEmpty(),
                p.copy(updatedAt = System.currentTimeMillis()), onlyIfAbsent
            )
        }
    }

    suspend fun updateTotalParagraphs(bookId: String, total: Int) {
        if (total <= 0) return
        context.dataStore.edit { prefs ->
            val raw = prefs[canonicalKey].orEmpty()
            val existing = ProgressJson.decode(raw)[bookId] ?: return@edit
            if (existing.totalParagraphs != total) {
                // Read and update atomically, preserving the latest position and timestamp.
                prefs[canonicalKey] = ProgressJson.upsert(raw, existing.copy(totalParagraphs = total))
            }
        }
    }
}
