package com.theemrock.smoothreader

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.theemrock.smoothreader.ui.theme.SmoothAppTheme

internal data class PlaybackOpenRequest(val bookId: String, val mode: ReadingMode)

internal object TtsDiagnostics {
    @Volatile
    var activityState: String = "NOT_CREATED"
        private set

    fun activity(state: String, event: String) {
        activityState = state
        log(event)
    }

    fun log(message: String) {
        Log.d(
            "SR_TTS_DIAG",
            "[monoMs=${SystemClock.elapsedRealtime()}] [wallMs=${System.currentTimeMillis()}] " +
                "[${Thread.currentThread().name}] [activity=$activityState] $message"
        )
    }
}

class MainActivity : ComponentActivity() {
    private val playbackOpenRequest = mutableStateOf<PlaybackOpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playbackOpenRequest.value = playbackRequest(intent)
        TtsDiagnostics.activity("CREATED", "MainActivity onCreate")
        setContent {
            SmoothAppTheme {
                AppNavHost(
                    playbackOpenRequest = playbackOpenRequest.value,
                    onPlaybackOpenConsumed = ::consumePlaybackOpenRequest
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playbackOpenRequest.value = playbackRequest(intent)
    }

    private fun playbackRequest(intent: Intent?): PlaybackOpenRequest? {
        val bookId = intent?.getStringExtra(ReaderPlaybackService.EXTRA_BOOK_ID)
            ?.takeIf(String::isNotBlank) ?: return null
        val mode = intent.getStringExtra(ReaderPlaybackService.EXTRA_READING_MODE)
            ?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
            ?: ReadingMode.LISTEN
        return PlaybackOpenRequest(bookId, mode)
    }

    private fun consumePlaybackOpenRequest() {
        playbackOpenRequest.value = null
        intent?.removeExtra(ReaderPlaybackService.EXTRA_BOOK_ID)
        intent?.removeExtra(ReaderPlaybackService.EXTRA_READING_MODE)
    }

    override fun onStart() {
        super.onStart()
        TtsDiagnostics.activity("STARTED", "MainActivity onStart")
    }

    override fun onResume() {
        super.onResume()
        TtsDiagnostics.activity("RESUMED", "MainActivity onResume")
    }

    override fun onPause() {
        super.onPause()
        TtsDiagnostics.activity("PAUSED", "MainActivity onPause")
    }

    override fun onStop() {
        super.onStop()
        TtsDiagnostics.activity("STOPPED", "MainActivity onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        TtsDiagnostics.activity("DESTROYED", "MainActivity onDestroy")
    }
}
