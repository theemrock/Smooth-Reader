package com.theemrock.smoothreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeScreen(
    bookId: String,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    onListen: (String) -> Unit,
    vm: ResumeViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(bookId) { vm.load(bookId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.book?.title ?: "Devam") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(32.dp)        )            }
                }
            )
        }
    ) { pad ->
        when {
            state.isLoading -> {
                Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Column(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Bu kitap şu an açılamadı:", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.error ?: "Bilinmeyen hata", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Geri") }
                }
            }

            else -> {
                val scroll = rememberScrollState()

                Column(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Preview alanı
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                    ) {
                        if (state.book?.author != null) {
                            Text(
                                text = state.book!!.author!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        state.before.forEach {
                            PreviewPara(text = it, dim = true)
                            Spacer(Modifier.height(10.dp))
                        }

                        FocusPara(text = state.focus)

                        Spacer(Modifier.height(10.dp))

                        state.after.forEach {
                            PreviewPara(text = it, dim = true)
                            Spacer(Modifier.height(10.dp))
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Konum: ${state.focusIndex + 1} / ${state.totalParagraphs}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Alt butonlar
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onRead(bookId) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Oku") }

                        Button(
                            onClick = { onListen(bookId) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Dinle") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusPara(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large
            )
            .padding(14.dp)
    ) {
        Text("Kaldığın yer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PreviewPara(text: String, dim: Boolean) {
    val c = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Text(text, style = MaterialTheme.typography.bodyMedium, color = c)
}
