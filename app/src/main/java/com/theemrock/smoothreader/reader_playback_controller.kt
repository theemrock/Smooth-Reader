package com.theemrock.smoothreader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PlaybackSnapshot(
    val bookId: String? = null,
    val position: ReadingPosition? = null,
    val isPlaying: Boolean = false,
    val isSoundEnabled: Boolean = true,
    val speed: Int = 5,
    val generation: Long = 0,
    val sessionId: Long = 0,
    val isReady: Boolean = false
)

internal class ReaderPlaybackController(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope,
    private val persistPosition: suspend (String, ReadingPosition, Int) -> Unit,
    private val onPersistenceError: () -> Unit = {},
    private val log: (String) -> Unit = TtsDiagnostics::log
) : SpeechEngine.Listener {
    private data class PlaybackSession(
        val id: Long,
        val bookId: String,
        val source: ReadingSource,
        var position: ReadingPosition
    )

    private data class SpokenChunk(
        val generation: Long,
        val session: PlaybackSession,
        val range: SentenceRange,
        val text: String
    )

    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    private val progressWriteMutex = Mutex()
    private var sessionSequence = 0L
    private var generation = 0L
    private var session: PlaybackSession? = null
    private var spokenChunk: SpokenChunk? = null
    private var ready = false
    private var released = false

    init {
        engine.setListener(this)
    }

    fun hasSession(bookId: String): Boolean = !released && session?.bookId == bookId

    fun prepare(bookId: String, source: ReadingSource, initialPosition: ReadingPosition): Boolean {
        if (released || hasSession(bookId)) return false
        if (session != null || _state.value.isPlaying || spokenChunk != null) {
            invalidateAndStop("prepare")
        }
        val prepared = PlaybackSession(
            id = ++sessionSequence,
            bookId = bookId,
            source = source,
            position = source.clamp(initialPosition)
        )
        session = prepared
        spokenChunk = null
        _state.value = PlaybackSnapshot(
            bookId = bookId,
            position = prepared.position,
            generation = generation,
            sessionId = prepared.id,
            isReady = ready
        )
        applyRate()
        return true
    }

    fun clear(reason: String) {
        if (released) return
        invalidateAndStop(reason)
        session = null
        _state.value = PlaybackSnapshot(generation = generation, isReady = ready)
    }

    fun play() {
        if (released || _state.value.isPlaying || session == null) return
        val requestedGeneration = ++generation
        spokenChunk = null
        _state.value = _state.value.copy(isPlaying = true, generation = generation)
        if (ready) startCurrentChunk(requestedGeneration)
    }

    fun pause(reason: String) {
        if (released) return
        generation++
        spokenChunk = null
        _state.value = _state.value.copy(isPlaying = false, generation = generation)
        log("tts.stop request | reason: $reason, currentGen: $generation, isPlaying: false")
        engine.stop()
    }

    /** Interrupts speech while a visual navigation target is still being committed. */
    fun interruptForSeek(reason: String) {
        if (released || !_state.value.isPlaying) return
        generation++
        spokenChunk = null
        _state.value = _state.value.copy(generation = generation)
        log("tts.stop request | reason: $reason, currentGen: $generation, isPlaying: true")
        engine.stop()
    }

    fun seek(position: ReadingPosition, restartIfPlaying: Boolean, reason: String) {
        val active = session ?: return
        if (released) return
        val safe = active.source.clamp(position)
        if (safe != active.position) updateCanonical(active, safe)
        if (restartIfPlaying && _state.value.isPlaying) restartSpeech(reason)
    }

    /** One-time legacy-page migration; persistence remains an only-if-absent UI migration. */
    fun adoptLegacyPosition(position: ReadingPosition) {
        val active = session ?: return
        if (released || _state.value.isPlaying) return
        active.position = active.source.clamp(position)
        _state.value = _state.value.copy(position = active.position)
    }

    fun setSpeed(speed: Int, reason: String) {
        if (released) return
        val safe = speed.coerceIn(1, 10)
        if (safe == _state.value.speed) return
        _state.value = _state.value.copy(speed = safe)
        applyRate()
        if (_state.value.isPlaying) restartSpeech(reason)
    }

    fun toggleSound(reason: String) {
        if (released) return
        _state.value = _state.value.copy(isSoundEnabled = !_state.value.isSoundEnabled)
        if (_state.value.isPlaying) restartSpeech(reason)
    }

    fun release() {
        if (released) return
        released = true
        generation++
        spokenChunk = null
        _state.value = _state.value.copy(
            isPlaying = false,
            generation = generation,
            isReady = false
        )
        log("tts.stop request | reason: release, currentGen: $generation, isPlaying: false")
        engine.stop()
        log("TTS shutdown request | reason: release, currentGen: $generation")
        engine.shutdown()
        ready = false
        session = null
    }

    override fun onReady(success: Boolean) {
        scope.launch {
            if (released) return@launch
            ready = success
            _state.value = _state.value.copy(isReady = success)
            log("TTS init status | success: $success, currentGen: $generation")
            if (!success) {
                spokenChunk = null
                _state.value = _state.value.copy(isPlaying = false)
                return@launch
            }
            applyRate()
            if (_state.value.isPlaying && spokenChunk == null) startCurrentChunk(generation)
        }
    }

    override fun onStart(utteranceId: String?) {
        log("onStart | utteranceId: $utteranceId, currentGen: $generation, chunkGen: ${spokenChunk?.generation}, isPlaying: ${_state.value.isPlaying}")
    }

    override fun onRangeStart(utteranceId: String?, start: Int, end: Int) {
        val chunk = matchingChunk(utteranceId) ?: return
        if (start < 0 || start >= chunk.text.length) return
        scope.launch {
            val active = session
            if (!accepts(chunk, active)) return@launch
            val startOffset = chunk.session.source.offsetOf(chunk.range.start)
            val endOffset = chunk.session.source.offsetOf(chunk.range.end)
            val sourceOffset = startOffset + start
            if (sourceOffset < startOffset || sourceOffset > endOffset ||
                sourceOffset < 0 || sourceOffset >= chunk.session.source.length
            ) return@launch
            updateCanonical(chunk.session, chunk.session.source.positionAt(sourceOffset))
        }
    }

    override fun onDone(utteranceId: String?) {
        log("onDone entry | utteranceId: $utteranceId, currentGen: $generation, chunkGen: ${spokenChunk?.generation}, isPlaying: ${_state.value.isPlaying}")
        val chunk = matchingChunk(utteranceId)
        if (chunk == null) {
            log("onDone stale callback | utteranceId: $utteranceId, currentGen: $generation")
            return
        }
        val continuation = scope.launch {
            val active = session
            if (!accepts(chunk, active)) {
                log("onDone coroutine early return | reason: stale session or generation, utteranceId: $utteranceId, chunkGen: ${chunk.generation}, currentGen: $generation")
                return@launch
            }
            log("onDone canonical before | utteranceId: $utteranceId, gen: ${chunk.generation}, currentPosition: ${chunk.session.position}, targetPosition: ${chunk.range.end}")
            updateCanonical(chunk.session, chunk.range.end)
            log("onDone canonical after | utteranceId: $utteranceId, gen: ${chunk.generation}, currentPosition: ${chunk.session.position}")
            val next = nextReadingChunk(chunk.session.source, chunk.session.position)
            log("onDone nextReadingChunk result | utteranceId: $utteranceId, gen: ${chunk.generation}, currentPosition: ${chunk.session.position}, result: ${if (next == null) "null" else "chunk"}, nextStart: ${next?.first?.start}, nextEnd: ${next?.first?.end}")
            if (next == null) pause("endOfBook") else speakChunk(next)
        }
        continuation.invokeOnCompletion { cause ->
            if (cause != null) {
                val outcome = if (cause is CancellationException) "cancelled" else "failed"
                log("onDone coroutine $outcome | utteranceId: $utteranceId, chunkGen: ${chunk.generation}, currentGen: $generation, cause: ${cause::class.java.simpleName}")
            }
        }
    }

    override fun onError(utteranceId: String?, errorCode: Int?) {
        log("onError | utteranceId: $utteranceId, errorCode: $errorCode, currentGen: $generation, chunkGen: ${spokenChunk?.generation}, isPlaying: ${_state.value.isPlaying}")
    }

    override fun onStop(utteranceId: String?, interrupted: Boolean) {
        log("onStop | utteranceId: $utteranceId, interrupted: $interrupted, currentGen: $generation, chunkGen: ${spokenChunk?.generation}, isPlaying: ${_state.value.isPlaying}")
    }

    private fun startCurrentChunk(requestedGeneration: Long) {
        val active = session ?: return
        val chunk = nextReadingChunk(active.source, active.position)
        if (chunk == null) pause("startNoChunk") else speakChunk(chunk, requestedGeneration)
    }

    private fun speakChunk(chunk: Pair<SentenceRange, String>, requestedGeneration: Long? = null) {
        val active = session
        val range = chunk.first
        log("speakChunk entry | ttsReady: $ready, isPlaying: ${_state.value.isPlaying}, requestedGen: $requestedGeneration, currentGen: $generation, chunkStart: ${range.start}, chunkEnd: ${range.end}")
        if (released || !ready || !_state.value.isPlaying || active == null || chunk.second.isEmpty()) {
            log("speakChunk early return | reason: readiness, playback, session or empty chunk, requestedGen: $requestedGeneration, currentGen: $generation, chunkStart: ${range.start}, chunkEnd: ${range.end}")
            return
        }
        if (requestedGeneration != null && requestedGeneration != generation) {
            log("speakChunk early return | reason: requestedGeneration mismatch, requestedGen: $requestedGeneration, currentGen: $generation, chunkStart: ${range.start}, chunkEnd: ${range.end}")
            return
        }
        val chunkGeneration = requestedGeneration ?: ++generation
        _state.value = _state.value.copy(generation = generation)
        spokenChunk = SpokenChunk(chunkGeneration, active, range, chunk.second)
        applyRate()
        val utteranceId = "reader_$chunkGeneration"
        val result = engine.speak(chunk.second, utteranceId, utteranceVolume(_state.value.isSoundEnabled))
        log("speakChunk tts.speak result | utteranceId: $utteranceId, gen: $chunkGeneration, chunkStart: ${range.start}, chunkEnd: ${range.end}, result: $result")
    }

    private fun restartSpeech(reason: String) {
        val active = session ?: return
        val requestedGeneration = ++generation
        spokenChunk = null
        _state.value = _state.value.copy(generation = generation)
        log("tts.stop request | reason: controlledRestart/$reason, currentGen: $generation, isPlaying: true")
        engine.stop()
        if (!ready) return
        val chunk = nextReadingChunk(active.source, active.position)
        if (chunk == null) pause("controlledRestartNoChunk/$reason")
        else speakChunk(chunk, requestedGeneration)
    }

    private fun updateCanonical(active: PlaybackSession, position: ReadingPosition) {
        if (session !== active) return
        val safe = active.source.clamp(position)
        if (safe == active.position) return
        active.position = safe
        _state.value = _state.value.copy(position = safe)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    progressWriteMutex.withLock {
                        persistPosition(active.bookId, safe, active.source.paragraphs.size)
                    }
                } catch (error: Exception) {
                    log("progress persistence failed | bookId: ${active.bookId}, position: $safe, cause: ${error::class.java.simpleName}")
                    onPersistenceError()
                }
            }
        }
    }

    private fun matchingChunk(utteranceId: String?): SpokenChunk? {
        val chunk = spokenChunk ?: return null
        return chunk.takeIf { utteranceId == "reader_${it.generation}" && it.generation == generation }
    }

    private fun accepts(chunk: SpokenChunk, active: PlaybackSession?): Boolean =
        spokenChunk === chunk && session === active && active === chunk.session &&
            _state.value.isPlaying && chunk.generation == generation

    private fun applyRate() {
        if (!ready || released) return
        val speed = _state.value.speed.coerceIn(1, 10)
        engine.setRate(0.75f + (speed - 1) * (1.0f / 9.0f))
    }

    private fun invalidateAndStop(reason: String) {
        generation++
        spokenChunk = null
        log("tts.stop request | reason: $reason, currentGen: $generation, isPlaying: ${_state.value.isPlaying}")
        engine.stop()
    }
}
