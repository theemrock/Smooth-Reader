package com.theemrock.smoothreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (String) -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) vm.importUris(uris)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.importMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("KÜTÜPHANE TEST", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { /* v1: sonra */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Ara")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    docPicker.launch(
                        ImportFileType.pickerMimeTypes
                    )
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ekle")
            }
        }
    ) { pad ->
        if (state.books.isEmpty()) {
            EmptyLibrary(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(18.dp),
                onImport = {
                    docPicker.launch(
                        ImportFileType.pickerMimeTypes
                    )
                }
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.books, key = { it.id }) { book ->
                    val progress = state.progressByBookId[book.id]
                    LibraryBookCard(
                        book = book,
                        progressPercent = progress?.percent() ?: 0,
                        showPercent = true, // v1: sonra ayara bağlarız
                        onClick = { onOpenBook(book.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    modifier: Modifier = Modifier,
    onImport: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Henüz kitap yok.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sağ alttaki + ile EPUB / DOCX / TXT ekleyebilirsin.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onImport) { Text("Kitap Ekle") }
    }
}

@Composable
private fun LibraryBookCard(
    book: Book,
    progressPercent: Int,
    showPercent: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        // Kapak alanı (2:3)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // V1: kapak yoksa placeholder
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📖", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    book.type.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Kitap adı + yazar
        Text(
            text = book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        val author = book.author
        if (!author.isNullOrBlank()) {
            Text(
                text = author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(16.dp)) // yazar yoksa hizayı koru
        }

        Spacer(Modifier.height(8.dp))

        // Progress bar + %
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = (progressPercent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
            )
            if (showPercent) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${progressPercent.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
