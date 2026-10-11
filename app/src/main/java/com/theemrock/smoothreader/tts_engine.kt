package com.theemrock.smoothreader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal interface SpeechEngine {
    interface Listener {
        fun onReady(success: Boolean)
        fun onStart(utteranceId: String?)
        fun onRangeStart(utteranceId: String?, start: Int, end: Int)
        fun onDone(utteranceId: String?)
        fun onError(utteranceId: String?, errorCode: Int?)
        fun onStop(utteranceId: String?, interrupted: Boolean)
    }

    fun setListener(listener: Listener)
    fun setRate(rate: Float)
    fun speak(text: String, utteranceId: String, volume: Float): Int
    fun stop()
    fun shutdown()
}

/** The only Android TextToSpeech adapter used by Reader playback. */
internal class TtsEngine(context: Context) : TextToSpeech.OnInitListener, SpeechEngine {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var listener: SpeechEngine.Listener? = null
    private var initialized: Boolean? = null
    private var released = false

    override fun setListener(listener: SpeechEngine.Listener) {
        this.listener = listener
        initialized?.let(listener::onReady)
    }

    override fun onInit(status: Int) {
        if (released) return
        val ready = status == TextToSpeech.SUCCESS
        initialized = ready
        if (ready) {
            val languageResult = tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
                ?: TextToSpeech.LANG_MISSING_DATA
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts?.language = Locale.getDefault()
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = listener?.onStart(utteranceId) ?: Unit

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    listener?.onRangeStart(utteranceId, start, end)
                }

                override fun onDone(utteranceId: String?) = listener?.onDone(utteranceId) ?: Unit

                override fun onError(utteranceId: String?) {
                    listener?.onError(utteranceId, null)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    listener?.onError(utteranceId, errorCode)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    listener?.onStop(utteranceId, interrupted)
                }
            })
        }
        listener?.onReady(ready)
    }

    override fun setRate(rate: Float) {
        if (!released) tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    override fun speak(text: String, utteranceId: String, volume: Float): Int {
        if (released || initialized != true) return TextToSpeech.ERROR
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            ?: TextToSpeech.ERROR
    }

    override fun stop() {
        if (!released) tts?.stop()
    }

    override fun shutdown() {
        if (released) return
        released = true
        tts?.shutdown()
        tts = null
        initialized = false
        listener = null
    }
}
