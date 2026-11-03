package com.theemrock.smoothreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightsScreen(onBack: () -> Unit, onGoTo: (Highlight) -> Unit) {
    val context = LocalContext.current
    val store = remember { HighlightStore(context) }
    val highlights by store.all.collectAsState(initial = emptyList())
    var pending by remember { mutableStateOf<Highlight?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yer imleri", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { pad ->
        if (highlights.isEmpty()) {
            Box(
                modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Henüz yer imi yok. Okuyucuda bir metne basılı tutup işaretleyebilirsiniz.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(highlights, key = { it.id }) { highlight ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pending = highlight },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(highlight.bookTitle, style = MaterialTheme.typography.titleMedium)
                        highlight.bookAuthor?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "\"${highlight.text}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pending?.let { highlight ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Buraya gidilsin mi?") },
            text = { Text("Kitaptaki kayıtlı okuma ilerlemeniz değişmeyecek.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onGoTo(highlight)
                }) { Text("Evet") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Hayır") }
            }
        )
    }
}
