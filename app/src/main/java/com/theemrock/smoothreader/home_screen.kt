package com.theemrock.smoothreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theemrock.smoothreader.ui.theme.SmoothFullShape
import com.theemrock.smoothreader.ui.theme.SmoothIconSize
import com.theemrock.smoothreader.ui.theme.SmoothSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
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
                    Text("Kütüphane", style = MaterialTheme.typography.titleLarge)
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
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Ekle",
                    modifier = Modifier.size(SmoothIconSize.standard)
                )
            }
        }
    ) { pad ->
        if (state.books.isEmpty()) {
            EmptyLibrary(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(SmoothSpacing.l),
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
                contentPadding = PaddingValues(SmoothSpacing.l),
                horizontalArrangement = Arrangement.spacedBy(SmoothSpacing.m),
                verticalArrangement = Arrangement.spacedBy(SmoothSpacing.m)
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
        Spacer(Modifier.height(SmoothSpacing.s))
        Text(
            "Sağ alttaki + ile EPUB / DOCX / TXT ekleyebilirsin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(SmoothSpacing.l))
        Button(onClick = onImport) {
            Text("Kitap Ekle", style = MaterialTheme.typography.labelLarge)
        }
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
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // V1: kapak yoksa placeholder
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📖", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(SmoothSpacing.s))
                Text(
                    book.type.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(Modifier.height(SmoothSpacing.s))

        // Kitap adı + yazar
        Text(
            text = book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
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
            Spacer(Modifier.height(SmoothSpacing.l)) // yazar yoksa hizayı koru
        }

        Spacer(Modifier.height(SmoothSpacing.s))

        // Progress bar + %
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = (progressPercent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f),
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(SmoothFullShape),
            )
            if (showPercent) {
                Spacer(Modifier.width(SmoothSpacing.s))
                Text(
                    text = "${progressPercent.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
