package com.theemrock.smoothreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.theemrock.smoothreader.ui.theme.SmoothIconSize
import com.theemrock.smoothreader.ui.theme.SmoothSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeScreen(
    bookId: String,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    vm: ResumeViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(bookId) { vm.load(bookId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.book?.title ?: "Devam",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            modifier = Modifier.size(SmoothIconSize.standard)
                        )
                    }
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
                        .padding(SmoothSpacing.l),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Bu kitap şu an açılamadı:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(SmoothSpacing.s))
                    Text(
                        state.error ?: "Bilinmeyen hata",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(SmoothSpacing.l))
                    Button(onClick = onBack) {
                        Text("Geri", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            else -> {
                val scroll = rememberScrollState()

                Column(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(SmoothSpacing.l)
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
                            Spacer(Modifier.height(SmoothSpacing.s))
                        }

                        state.before.forEach {
                            PreviewPara(text = it, dim = true)
                            Spacer(Modifier.height(SmoothSpacing.m))
                        }

                        FocusPara(text = state.focus)

                        Spacer(Modifier.height(SmoothSpacing.m))

                        state.after.forEach {
                            PreviewPara(text = it, dim = true)
                            Spacer(Modifier.height(SmoothSpacing.m))
                        }

                        Spacer(Modifier.height(SmoothSpacing.l))
                        Text(
                            text = "Konum: ${state.focusIndex + 1} / ${state.totalParagraphs}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(SmoothSpacing.l))

                    // Alt buton (Devam Et)
                    Button(
                        onClick = { onContinue(bookId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Devam Et", style = MaterialTheme.typography.labelLarge)
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
            .padding(SmoothSpacing.l)
    ) {
        Text("Kaldığın yer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(SmoothSpacing.s))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PreviewPara(text: String, dim: Boolean) {
    val c = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Text(text, style = MaterialTheme.typography.bodyMedium, color = c)
}
