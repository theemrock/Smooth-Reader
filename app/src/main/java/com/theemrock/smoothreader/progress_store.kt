package com.theemrock.smoothreader

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Projede başka datastore varsa çakışmaması için benzersiz isim
private val Context.readerPageDataStore by preferencesDataStore(name = "reader_page_store")

class ReaderProgressStore(private val context: Context) {

    private fun pageKey(bookId: String) =
        intPreferencesKey("page_${bookId.hashCode()}")

    fun loadPage(bookId: String): Flow<Int?> {
        val key = pageKey(bookId)

        return context.readerPageDataStore.data.map { prefs ->
            prefs[key]
        }
    }

    // Legacy records are retained for one-time migration; new code never writes pages.
}
