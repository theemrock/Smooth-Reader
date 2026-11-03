package com.theemrock.smoothreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Route entry for "Hızlı Oku": resolves the book from the library, then shows [RsvpScreen].
 * Reached from the resume screen's "Hızlı Oku" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RsvpRouteScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val libraryStore = remember { LibraryStore(context) }
    val books by libraryStore.booksFlow.collectAsState(initial = emptyList())
    val book = books.firstOrNull { it.id == bookId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "Hızlı Oku") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        if (book == null) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            RsvpScreen(book = book, modifier = Modifier.padding(padding))
        }
    }
}
