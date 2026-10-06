package com.theemrock.smoothreader

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ReaderPlaceholderScreen(
    title: String,
    onBack: () -> Unit
) {
    PlaceholderScreen(title = title, onBack = onBack)
}
