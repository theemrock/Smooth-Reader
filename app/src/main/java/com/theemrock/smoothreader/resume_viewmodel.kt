package com.theemrock.smoothreader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

data class ResumeUiState(
    val isLoading: Boolean = true,
    val book: Book? = null,
    val error: String? = null,
    val focusIndex: Int = 0,
    val totalParagraphs: Int = 0,
    val before: List<String> = emptyList(),
    val focus: String = "",
    val after: List<String> = emptyList()
)

class ResumeViewModel(app: Application) : AndroidViewModel(app) {

    private val libraryStore = LibraryStore(app.applicationContext)
    private val progressStore = ProgressStore(app.applicationContext)
    private val parser = DocumentParserV1()

    private val _bookId = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ResumeUiState())

    val uiState: StateFlow<ResumeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadVersion: Long = 0

    fun load(bookId: String) {
        if (_bookId.value == bookId && !_state.value.isLoading) return
        _bookId.value = bookId
        val version = ++loadVersion

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
          try {
            _state.value = ResumeUiState(isLoading = true)

            val book = libraryStore.booksFlow.firstOrNull()?.firstOrNull { it.id == bookId }
            if (version != loadVersion) return@launch
            if (book == null) {
                _state.value = ResumeUiState(isLoading = false, error = "Kitap bulunamadı.")
                return@launch
            }

            val cr = getApplication<Application>().contentResolver
            val parsed = when (val res = ParsedCache.getOrParse(
                bookId = bookId,
                parse = { parser.parse(book, cr) },
                acceptResult = { version == loadVersion }
            )) {
                is ParseResult.Success -> {
                    if (version != loadVersion) return@launch
                    res.parsed
                }
                is ParseResult.Error -> {
                    if (version != loadVersion) return@launch
                    _state.value = ResumeUiState(
                        isLoading = false,
                        book = book,
                        error = res.message
                    )
                    return@launch
                }
            }

            val total = parsed.paragraphs.size
            // Metadata updates must not initialize/migrate a position before Reader pagination.
            progressStore.updateTotalParagraphs(bookId, total)
            progressStore.progressFlow.collect { progressMap ->
            if (version != loadVersion) return@collect
            val focusIndex = progressMap[bookId]?.position?.paragraphIndex ?: 0
            val safeIndex = focusIndex.coerceIn(0, (total - 1).coerceAtLeast(0))

            // preview window: üst 2, alt 2
            val before = mutableListOf<String>()
            val after = mutableListOf<String>()

            for (i in (safeIndex - 2)..(safeIndex - 1)) {
                if (i in 0 until total) before += parsed.paragraphs[i]
            }
            for (i in (safeIndex + 1)..(safeIndex + 2)) {
                if (i in 0 until total) after += parsed.paragraphs[i]
            }

            _state.value = ResumeUiState(
                isLoading = false,
                book = book,
                error = null,
                focusIndex = safeIndex,
                totalParagraphs = total,
                before = before,
                focus = parsed.paragraphs.getOrElse(safeIndex) { "" },
                after = after
            )
            }
          } catch (e: CancellationException) {
              throw e
          } catch (_: Exception) {
              if (version == loadVersion) {
                  _state.value = ResumeUiState(isLoading = false, error = "Kitap veya okuma konumu yüklenemedi.")
              }
          }
        }
    }
}
