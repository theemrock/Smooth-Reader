package com.theemrock.smoothreader

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class HomeUiState(
    val books: List<Book> = emptyList(),
    val progressByBookId: Map<String, ReadingProgress> = emptyMap()
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val libraryStore = LibraryStore(app.applicationContext)
    private val progressStore = ProgressStore(app.applicationContext)

    val uiState: StateFlow<HomeUiState> =
        combine(libraryStore.booksFlow, progressStore.progressFlow) { books, progress ->
            HomeUiState(books = books, progressByBookId = progress)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val importMessages = messageChannel.receiveAsFlow()

    fun importUris(uris: List<Uri>) {
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val books = mutableListOf<Book>()
            val errors = mutableListOf<String>()
            for (uri in uris.distinct()) {
                val name = queryDisplayName(cr, uri)
                val label = name ?: "Seçilen dosya"
                try {
                    val mime = try { cr.getType(uri) } catch (_: Exception) { null }
                    val type = ImportFileType.detect(mime, name)
                    if (type == null) {
                        errors += "$label: Dosya türü desteklenmiyor veya belirlenemedi. Yalnızca EPUB, TXT ve DOCX eklenebilir."
                        continue
                    }
                    try {
                        if (cr.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        check(cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
                    } catch (_: Exception) {
                        errors += "$label: Kalıcı okuma izni alınamadı. Dosyayı başka bir belge sağlayıcısından seçin."
                        continue
                    }
                    // Verify access without parsing or loading the whole book.
                    cr.openInputStream(uri)?.use { it.read() }
                        ?: throw java.io.IOException("Dosya açılamadı")
                    val (title, author) = splitTitleAuthor(name ?: "Adsız kitap")
                    books += Book(
                        id = UUID.randomUUID().toString(),
                        title = title.ifBlank { "Adsız kitap" },
                        author = author,
                        type = type,
                        uri = uri.toString()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errors += "$label: Dosya okunamadığı için eklenmedi. Erişim iznini kontrol edin."
                }
            }
            if (books.isNotEmpty()) {
                try {
                    libraryStore.upsertBooks(books)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errors += "Kitaplar kütüphaneye kaydedilemedi. Lütfen tekrar deneyin."
                }
            }
            if (errors.isNotEmpty()) messageChannel.send(errors.joinToString("\n"))
        }
    }
    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            cr.query(uri, projection, null, null, null)?.use { c ->
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && c.moveToFirst()) c.getString(index)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private fun splitTitleAuthor(fileName: String): Pair<String, String?> {
        // Basit: "Title - Author.ext" veya "Author - Title.ext"
        val clean = fileName.substringBeforeLast(".")
        val parts = clean.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            else -> clean to null
        }
    }
}
