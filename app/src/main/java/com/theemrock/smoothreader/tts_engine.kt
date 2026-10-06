package com.theemrock.smoothreader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsEngine(
    context: Context,
    onReady: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var readyCallback: ((Boolean) -> Unit)? = onReady

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // TR öncelik, olmazsa default
            val res = tts?.setLanguage(Locale("tr", "TR")) ?: TextToSpeech.LANG_MISSING_DATA
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
        }
        readyCallback?.invoke(ready)
        readyCallback = null
    }

    fun isReady(): Boolean = ready

    fun setRate(rate: Float) {
        // Android TTS için tipik aralık: 0.5 - 2.0
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun speak(text: String, utteranceId: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
