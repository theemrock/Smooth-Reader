package com.theemrock.smoothreader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Bu ekranı sıradaki adımda yazıyoruz.")
        }
    }
}
