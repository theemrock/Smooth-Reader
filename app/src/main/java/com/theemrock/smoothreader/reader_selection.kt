package com.theemrock.smoothreader

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString

/**
 * Bridges Compose's standard text selection to the reader.
 * "Kopyala" and "İşaretle" both go through Compose's copy path; the pending flag
 * tells the clipboard bridge which of the two the user picked.
 */
internal class ReaderSelectionActions {
    var menu by mutableStateOf<SelectionMenu?>(null)
    var highlightRequested = false
    var onHighlight: (String) -> Unit = {}
}

internal data class SelectionMenu(
    val rect: Rect,
    val onCopy: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?
)

internal class ReaderClipboardManager(
    private val context: Context,
    private val actions: ReaderSelectionActions
) : ClipboardManager {

    private fun system() = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager

    override fun getText(): AnnotatedString? =
        system().primaryClip?.getItemAt(0)?.text?.toString()?.let { AnnotatedString(it) }

    override fun hasText(): Boolean = system().hasPrimaryClip()

    override fun setText(annotatedString: AnnotatedString) {
        val text = annotatedString.text
        android.util.Log.d("SR_SELECTION", "setText called | length=${text.length} highlight=${actions.highlightRequested}")
        if (actions.highlightRequested) {
            actions.highlightRequested = false
            actions.onHighlight(text)
        } else {
            system().setPrimaryClip(ClipData.newPlainText("Smooth Reader", text))
        }
    }
}

internal class ReaderTextToolbar(private val actions: ReaderSelectionActions) : TextToolbar {
    override val status: TextToolbarStatus
        get() = if (actions.menu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        android.util.Log.d("SR_SELECTION", "showMenu called | rect=$rect copy=${onCopyRequested != null}")
        actions.menu = SelectionMenu(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() {
        actions.menu = null
    }
}
