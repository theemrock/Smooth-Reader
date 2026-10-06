package com.theemrock.smoothreader

import android.app.Application
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

data class ReaderUiState(
    val isLoading: Boolean = true,
    val bookHeader: String = "Kitap",
    val paragraphs: List<String> = emptyList(),

    val pageIndex: Int = 0,
    val totalPages: Int = 0,
    val contentVersion: Long = 0,
    val layoutVersion: Long = 0,
    val pageText: String = "",
    val previousPageText: String? = null,
    val nextPageText: String? = null,
    val globalPageNumber: Int? = null,
    val readingFraction: Float = 0f,
    val isPreparingPages: Boolean = false,
    val activeSentenceStart: Int = -1,
    val activeSentenceEnd: Int = -1,
    val verticalTransitionId: Long = 0,
    val verticalTransitionContentVersion: Long = 0,
    val verticalTransitionLayoutVersion: Long = 0,

    val isPlaying: Boolean = false,
    val isSoundEnabled: Boolean = true,
    val speed: Int = 5,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val progressStore =
        ProgressStore(app.applicationContext)
    private val legacyProgressStore = ReaderProgressStore(app.applicationContext)
    private var readingSession: ReadingSession? = null
    private var loadVersion = 0L
    private val progressWriteMutex = Mutex()
    private var migrationPending = false
    private val paginationEpoch = PaginationEpoch()
    private var paginator: WindowPaginator? = null
    private val paginationWake = Channel<Unit>(Channel.CONFLATED)
    private var activeBlock = 0
    private var emptySeekDirection = 1
    private var readyTicket: PaginationTicket? = null
    private var queuedDirection: Int? = null
    private data class PageRequest(val block: Int, val index: Int) // -1 means last page
    private var pageRequest: PageRequest? = null

    private var currentBookId: String? = null
    private var verticalTransitionGeneration = 0L
    private var automaticTransitionPending = false

    /*
     * ReaderScreen tarafından verilen
     * mevcut sayfanın metni.
     */
    private var currentPageText: String = ""

    /*
     * TTS
     */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /*
     * TTS'nin gerçekten konuştuğu sayfa.
     *
     * Bu değer sayesinde eski bir TTS callback'i
     * yeni sayfayı yanlışlıkla ilerletemez.
     */

    /*
     * Kullanıcı manuel sayfa değiştirdiğinde
     * eski TTS callback'inin sayfa ilerletmesini
     * engellemek için nesil numarası.
     */
    private var speechGeneration = 0L
    private data class SpokenPage(
        val generation: Long,
        val pageIndex: Int,
        val pageStartOffset: Int,
        val spokenStart: Int
    )
    private var spokenPage: SpokenPage? = null

    init {

        tts = TextToSpeech(
            getApplication()
        ) { status ->

            if (status == TextToSpeech.SUCCESS) {

                tts?.language = Locale("tr", "TR")

                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(
                            utteranceId: String?
                        ) {
                            // TTS başladı.
                        }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            val spoken = spokenPage ?: return
                            if (utteranceId != "reader_${spoken.generation}" ||
                                spoken.generation != speechGeneration) return
                            viewModelScope.launch {
                                val session = readingSession ?: return@launch
                                if (!_state.value.isPlaying ||
                                    spoken.generation != speechGeneration) return@launch
                                val sourceOffset = spoken.pageStartOffset + spoken.spokenStart + start
                                updateActivePosition(session.source.positionAt(sourceOffset))
                            }
                        }

                        override fun onDone(
                            utteranceId: String?
                        ) {
                            val spoken = spokenPage ?: return
                            if (utteranceId != "reader_${spoken.generation}" ||
                                spoken.generation != speechGeneration) return

                            /*
                             * Callback TTS thread'inden gelir.
                             * UI/state değişikliğini ViewModel scope'una
                             * taşıyoruz.
                             */
                            viewModelScope.launch {

                                /*
                                 * O sırada hâlâ Play açık mı?
                                 */
                                if (
                                    !_state.value.isPlaying ||
                                    spoken.generation != speechGeneration
                                ) {
                                    return@launch
                                }

                                /*
                                 * TTS'nin bitirdiği sayfa hâlâ
                                 * ekrandaki sayfa mı?
                                 */
                                if (
                                    spoken.pageIndex !=
                                    _state.value.pageIndex
                                ) {
                                    return@launch
                                }

                                /*
                                 * Son sayfadaysak dur.
                                 */
                                if (
                                    _state.value.totalPages > 0 &&
                                    _state.value.pageIndex >=
                                    _state.value.totalPages - 1
                                ) {

                                    stop()

                                    return@launch
                                }

                                /*
                                 * TTS gerçekten bitti.
                                 * Şimdi sonraki sayfaya geç.
                                 */
                                requestAutomaticNextPage()
                            }
                        }

                        override fun onError(
                            utteranceId: String?
                        ) {

                            if (
                                utteranceId != "reader_page"
                            ) {
                                return
                            }

                            /*
                             * Hata durumunda da uygulama
                             * kilitlenmesin.
                             *
                             * Sayfa otomatik ilerletilmiyor.
                             * Kullanıcı Play/Pause ile tekrar
                             * deneyebilir.
                             */
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int
                        ) {

                            if (
                                utteranceId != "reader_page"
                            ) {
                                return
                            }

                            /*
                             * TTS hata verdiğinde burada
                             * sessizce bekliyoruz.
                             */
                        }
                    }
                )

                ttsReady = true

                applyTtsRate()
            }
        }
    }

    /*
     * ==========================================
     * KİTABI YÜKLE
     * ==========================================
     */
    @Suppress("UNUSED_PARAMETER")
    fun load(bookId: String, mode: ReadingMode = ReadingMode.READ) {
        currentBookId = bookId
        val version = ++loadVersion
        paginationEpoch.next(version)
        paginator = null
        readyTicket = null
        pageRequest = null
        queuedDirection = null
        automaticTransitionPending = false
        readingSession = null
        migrationPending = false
        tts?.stop()
        speechGeneration++
        spokenPage = null
        currentPageText = ""
        _state.value = ReaderUiState(isLoading = true, contentVersion = version)

        // Metadata is independent of pagination and must not delay the first readable page.
        viewModelScope.launch {
            val header = loadBookHeader(bookId)
            if (version == loadVersion) _state.value = _state.value.copy(bookHeader = header)
        }

        viewModelScope.launch {
            try {
                val parsed = ParsedCache.get(bookId)
                require(parsed != null && parsed.paragraphs.any { it.isNotBlank() })
                val (stored, canonical) = progressStore.storedProgress(bookId).first()
                val legacyPage = if (canonical) null else legacyProgressStore.loadPage(bookId).first()
                val source = withContext(Dispatchers.Default) { ReadingSource(parsed.paragraphs) }
                if (version != loadVersion) return@launch
                readingSession = ReadingSession(
                    source, stored?.position ?: ReadingPosition(),
                    legacyPage, canonical
                )
                _state.value = ReaderUiState(
                    isLoading = false, paragraphs = parsed.paragraphs, contentVersion = version,
                    isPreparingPages = true, bookHeader = _state.value.bookHeader
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (version != loadVersion) return@launch
                // No session: pagination of this message cannot write progress or migrate.
                readingSession = null
                _state.value = ReaderUiState(
                    isLoading = false, paragraphs = listOf("Kitap veya okuma konumu yüklenemedi"),
                    contentVersion = version, pageText = "Kitap veya okuma konumu yüklenemedi",
                    bookHeader = _state.value.bookHeader
                )
            }
        }
    }
    /*
     * ==========================================
     * MEVCUT SAYFA METNİ
     * ==========================================
     */
    private suspend fun loadBookHeader(bookId: String): String = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val book = LibraryStore(app.applicationContext).booksFlow.first().firstOrNull { it.id == bookId }
                ?: return@withContext "Kitap"
            fun clean(value: String?): String? = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            val title = clean(book.title) ?: run {
                try {
                    app.contentResolver.query(book.uriObj(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) clean(cursor.getString(index)) else null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { null }
            } ?: "Kitap"
            val author = clean(book.author)
            if (author == null) title else "$title · $author"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Kitap"
        }
    }

    fun setCurrentPageText(
        text: String
    ) {

        currentPageText = text

        /*
         * Sayfa değiştiğinde Play açıksa
         * yeni sayfayı okut.
         *
         * Burada mevcut sayfanın değiştiğini
         * kontrol etmek için yeni konuşma
         * nesli oluşturuyoruz.
         */
        if (_state.value.isPlaying) {

            speakCurrentPage()
        }
    }

    /*
     * ==========================================
     * TOPLAM SAYFA
     * ==========================================
     */
    suspend fun paginateWindow(contentVersion: Long, fits: suspend (String) -> Boolean) {
        if (contentVersion != loadVersion) return
        val session = readingSession ?: return
        val ticket = paginationEpoch.next(contentVersion)
        val engine = WindowPaginator(session.source, ticket, session.position)
        paginator = engine
        activeBlock = engine.focus
        emptySeekDirection = if (session.source.offsetOf(session.position) >= session.source.length - 1) -1 else 1
        readyTicket = null
        pageRequest = null
        queuedDirection = null
        engine.demand = engine.focus
        _state.value = _state.value.copy(
            isPreparingPages = true, totalPages = 0, globalPageNumber = null,
            layoutVersion = ticket.layout
        )
        try {
            while (paginationEpoch.accepts(ticket)) {
                currentCoroutineContext().ensureActive()
                engine.legacyPage = session.pendingLegacyPage()?.takeIf { it >= 0 }
                val block = engine.nextBlock()
                if (block == null) {
                    paginationWake.receive()
                    continue
                }
                engine.step(block, fits)
                currentCoroutineContext().ensureActive()
                if (!paginationEpoch.accepts(ticket)) return
                publishWindow(engine, session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (paginationEpoch.accepts(ticket)) {
                _state.value = _state.value.copy(isPreparingPages = false)
                Toast.makeText(getApplication(), "Sayfa hazırlanamadı. Mevcut konum korundu.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun publishWindow(engine: WindowPaginator, session: ReadingSession) {
        if (!paginationEpoch.accepts(engine.ticket) || readingSession !== session) return
        val legacy = session.pendingLegacyPage()?.takeIf { it >= 0 }
        if (legacy != null) {
            val location = engine.locateGlobal(legacy)
            if (location != null) {
                val page = engine.block(location.first)?.pages?.getOrNull(location.second)
                if (page != null) {
                    session.resolveLegacy(page.start)
                    activeBlock = location.first
                    engine.focus = activeBlock
                    engine.demand = null
                    readyTicket = null
                } else {
                    engine.demand = location.first
                }
            }
        }
        val request = pageRequest
        if (request != null) {
            val block = engine.block(request.block)
            val index = if (request.index < 0 && block?.complete == true) block.pages.lastIndex else request.index
            if (block != null && index in block.pages.indices) {
                val changed = session.navigateWindow(block.pages.toList(), index)
                activeBlock = request.block
                engine.focus = activeBlock
                engine.demand = null
                pageRequest = null
                readyTicket = engine.ticket
                if (changed) persistProgress(session, session.position)
            } else if (block?.complete == true && block.pages.isEmpty()) {
                val next = request.block + if (request.index < 0) -1 else 1
                if (next in 0 until engine.blockCount) {
                    pageRequest = PageRequest(next, request.index)
                    engine.demand = next
                } else {
                    pageRequest = null
                    engine.demand = null
                    if (request.index >= 0) stop()
                }
            }
        }
        val active = engine.block(activeBlock) ?: return
        val pages = active.pages
        if (pages.isEmpty()) {
            if (active.complete && readyTicket != engine.ticket) {
                var next = activeBlock + emptySeekDirection
                if (next !in 0 until engine.blockCount) {
                    emptySeekDirection = -emptySeekDirection
                    next = activeBlock + emptySeekDirection
                }
                if (next in 0 until engine.blockCount) {
                    activeBlock = next
                    engine.focus = next
                    engine.demand = next
                }
            }
            return
        }
        val anchor = session.source.clamp(session.position)
        // Do not display an earlier partial page while the canonical anchor is still unmeasured.
        if (readyTicket != engine.ticket && !active.complete && comparePositions(pages.last().end, anchor) < 0) return
        session.acceptWindow(pages.toList())
        readyTicket = engine.ticket
        if (pageRequest == null) engine.demand = null
        val page = session.pages[session.pageIndex]
        val previousPageText = session.pages.getOrNull(session.pageIndex - 1)?.text
            ?: engine.block(activeBlock - 1)?.takeIf { it.complete }?.pages?.lastOrNull()?.text
        val nextPageText = session.pages.getOrNull(session.pageIndex + 1)?.text
            ?: engine.block(activeBlock + 1)?.pages?.firstOrNull()?.text
        val activeSentence = sentenceRanges(session.source, page).firstOrNull {
            comparePositions(it.start, session.position) <= 0 && comparePositions(session.position, it.end) < 0
        } ?: sentenceRanges(session.source, page).firstOrNull()
        _state.value = _state.value.copy(
            pageIndex = session.pageIndex,
            pageText = page.text,
            previousPageText = previousPageText,
            nextPageText = nextPageText,
            totalPages = engine.totalPages,
            globalPageNumber = engine.globalNumber(activeBlock, session.pageIndex),
            readingFraction = if (session.source.length == 0) 0f else
                session.source.offsetOf(session.position).toFloat() / session.source.length,
            isPreparingPages = pageRequest != null,
            activeSentenceStart = activeSentence?.localStart ?: -1,
            activeSentenceEnd = activeSentence?.localEnd ?: -1
        )
        if (automaticTransitionPending && nextPageText != null) {
            automaticTransitionPending = false
            requestAutomaticNextPage()
        }
        val migration = session.migrationPosition()
        if (migration != null && !migrationPending) {
            migrationPending = true
            persistProgress(session, migration, onlyIfAbsent = true)
        }
        queuedDirection?.let {
            queuedDirection = null
            navigateWindow(it)
        }
    }

    private fun navigateWindow(direction: Int) {
        val session = readingSession ?: return
        val engine = paginator ?: return
        if (!paginationEpoch.accepts(engine.ticket)) return
        session.cancelLegacy()
        if (readyTicket != engine.ticket) {
            queuedDirection = direction
            paginationWake.trySend(Unit)
            return
        }
        val active = engine.block(activeBlock) ?: return
        val targetIndex = session.pageIndex + direction
        val request = when {
            targetIndex in active.pages.indices -> PageRequest(activeBlock, targetIndex)
            direction > 0 && !active.complete -> PageRequest(activeBlock, targetIndex)
            direction > 0 && activeBlock + 1 < engine.blockCount -> PageRequest(activeBlock + 1, 0)
            direction < 0 && activeBlock > 0 -> PageRequest(activeBlock - 1, -1)
            else -> { if (direction > 0) stop(); return }
        }
        pageRequest = request
        engine.demand = request.block
        _state.value = _state.value.copy(isPreparingPages = true)
        publishWindow(engine, session)
        paginationWake.trySend(Unit)
    }

    fun goToPage(oneBasedPage: Int) {
        val engine = paginator ?: return
        val session = readingSession ?: return
        if (engine.totalPages <= 0 || !paginationEpoch.accepts(engine.ticket)) return
        val target = engine.locateGlobal(oneBasedPage.coerceIn(1, engine.totalPages) - 1) ?: return
        session.cancelLegacy()
        pageRequest = PageRequest(target.first, target.second)
        engine.demand = target.first
        _state.value = _state.value.copy(isPreparingPages = true)
        publishWindow(engine, session)
        paginationWake.trySend(Unit)
    }
    /*
     * ==========================================
     * SONRAKİ SAYFA
     * ==========================================
     */
    fun nextPage() {
        navigateWindow(1)
    }
    /*
     * ==========================================
     * ÖNCEKİ SAYFA
     * ==========================================
     */
    fun prevPage() {

        val s = _state.value

        if (readingSession == null ||
            (readyTicket == paginator?.ticket && activeBlock == 0 && s.pageIndex <= 0)) {
            return
        }

        /*
         * Manuel geri dönüşte mevcut TTS'yi kes.
         */
        if (s.isPlaying) {

            speechGeneration++

            tts?.stop()

        }

        navigateWindow(-1)

        /*
         * Play açıksa yeni sayfa
         * ReaderScreen üzerinden okutulacak.
         */
    }

    /*
     * ==========================================
     * PLAY / PAUSE
     * ==========================================
     */
    fun togglePlayPause() {

        if (_state.value.isPlaying) {

            stop()

        } else {

            start()
        }
    }

    /*
     * ==========================================
     * BAŞLAT
     * ==========================================
     */
    private fun start() {

        if (!ttsReady) {
            return
        }

        if (
            currentPageText
                .trim()
                .isEmpty()
        ) {
            return
        }

        /*
         * Yeni konuşma nesli.
         */
        speechGeneration++

        val generation =
            speechGeneration

        /*
         * Play durumunu aç.
         */
        _state.value =
            _state.value.copy(
                isPlaying = true
            )

        /*
         * Mevcut sayfayı okut.
         */
        speakCurrentPage(generation)
    }

    /*
     * ==========================================
     * DURDUR
     * ==========================================
     */
    fun stop() {

        /*
         * Eski callback'leri geçersiz kıl.
         */
        speechGeneration++

        spokenPage = null

        _state.value =
            _state.value.copy(
                isPlaying = false
            )

        /*
         * Konuşmayı kes.
         */
        tts?.stop()

        // Stopping playback does not change the canonical reading position.
    }

    /*
     * ==========================================
     * YAVAŞLAT
     * ==========================================
     */
    fun slowDown() {

        val s = _state.value

        val newSpeed =
            (
                    s.speed - 1
                    ).coerceAtLeast(1)

        if (newSpeed == s.speed) {
            return
        }

        _state.value =
            s.copy(
                speed = newSpeed
            )

        applyTtsRate()
        restartSpeechIfPlaying()
    }

    /*
     * ==========================================
     * HIZLANDIR
     * ==========================================
     */
    fun speedUp() {

        val s = _state.value

        val newSpeed =
            (
                    s.speed + 1
                    ).coerceAtMost(10)

        if (newSpeed == s.speed) {
            return
        }

        _state.value =
            s.copy(
                speed = newSpeed
            )

        applyTtsRate()
        restartSpeechIfPlaying()
    }

    /*
     * ==========================================
     * TTS HIZI
     * ==========================================
     *
     * 1 = 0.75
     * 5 = yaklaşık 1.19
     * 10 = 1.75
     *
     * Burada sayfa geçiş süresi yok.
     *
     * Sayfa ne zaman bittiyse TTS callback'i
     * o zaman sonraki sayfaya geçirecek.
     */
    private fun applyTtsRate() {

        if (!ttsReady) {
            return
        }

        val sp =
            _state.value.speed
                .coerceIn(1, 10)

        val rate =
            0.75f +
                    (
                            sp - 1
                            ) *
                    (
                            1.0f / 9.0f
                            )

        tts?.setSpeechRate(rate)
    }

    /*
     * ==========================================
     * SAYFAYI SESLİ OKU
     * ==========================================
     */
    private fun speakCurrentPage(
        requestedGeneration: Long? = null
    ) {

        val generation = requestedGeneration ?: ++speechGeneration

        if (!ttsReady) {
            return
        }

        if (!_state.value.isPlaying) {
            return
        }

        /*
         * Eski bir çağrıysa iptal et.
         */
        if (
            generation != speechGeneration
        ) {
            return
        }

        val text = currentPageText

        if (text.isEmpty()) {
            return
        }

        /*
         * Hangi sayfanın konuşulduğunu
         * kaydediyoruz.
         */
        val session = readingSession ?: return
        val page = session.pages.getOrNull(session.pageIndex) ?: return
        val pageStartOffset = session.source.offsetOf(page.start)
        val spokenStart = (session.source.offsetOf(session.position) - pageStartOffset)
            .coerceIn(0, text.length)
        spokenPage = SpokenPage(generation, _state.value.pageIndex, pageStartOffset, spokenStart)

        /*
         * Mevcut konuşmayı kes.
         *
         * Böylece manuel sayfa değişiminde
         * eski sayfa arkadan devam etmez.
         */
        tts?.stop()

        /*
         * Güncel hızı uygula.
         */
        applyTtsRate()

        /*
         * Yeni sayfayı okut.
         */
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, utteranceVolume(_state.value.isSoundEnabled))
        }
        tts?.speak(
            text.substring(spokenStart),
            TextToSpeech.QUEUE_FLUSH,
            params,
            "reader_$generation"
        )
    }

    fun toggleSound() {
        val state = _state.value
        _state.value = state.copy(isSoundEnabled = !state.isSoundEnabled)
        restartSpeechIfPlaying()
    }

    private fun restartSpeechIfPlaying() {
        if (!_state.value.isPlaying) return
        val generation = ++speechGeneration
        spokenPage = null
        tts?.stop()
        speakCurrentPage(generation)
    }

    private fun requestAutomaticNextPage() {
        val state = _state.value
        if (state.nextPageText == null) {
            if (automaticTransitionPending) return
            val engine = paginator ?: return
            val preparationBlock = if (engine.block(activeBlock)?.complete == false) activeBlock else activeBlock + 1
            if (preparationBlock !in 0 until engine.blockCount) {
                stop()
                return
            }
            automaticTransitionPending = true
            engine.demand = preparationBlock
            _state.value = state.copy(isPreparingPages = true)
            paginationWake.trySend(Unit)
            return
        }
        _state.value = state.copy(
            verticalTransitionId = ++verticalTransitionGeneration,
            verticalTransitionContentVersion = state.contentVersion,
            verticalTransitionLayoutVersion = state.layoutVersion
        )
    }

    fun completeAutomaticVerticalTransition(id: Long, contentVersion: Long, layoutVersion: Long) {
        val state = _state.value
        if (state.verticalTransitionId != id || state.contentVersion != contentVersion ||
            state.layoutVersion != layoutVersion ||
            state.verticalTransitionContentVersion != contentVersion ||
            state.verticalTransitionLayoutVersion != layoutVersion || !state.isPlaying) return
        nextPage()
        if (_state.value.verticalTransitionId == id) {
            _state.value = _state.value.copy(verticalTransitionId = 0)
        }
    }

    fun completeManualVerticalTransition(direction: Int, contentVersion: Long, layoutVersion: Long) {
        val state = _state.value
        if (state.contentVersion != contentVersion || state.layoutVersion != layoutVersion) return
        if (direction > 0) nextPage() else if (direction < 0) prevPage()
    }

    private fun updateActivePosition(position: ReadingPosition) {
        val session = readingSession ?: return
        val page = session.pages.getOrNull(session.pageIndex) ?: return
        if (comparePositions(position, page.start) < 0 || comparePositions(position, page.end) >= 0) return
        if (session.updatePosition(position)) persistProgress(session, session.position)
        val active = sentenceRanges(session.source, page).firstOrNull {
            comparePositions(it.start, position) <= 0 && comparePositions(position, it.end) < 0
        }
        _state.value = _state.value.copy(
            activeSentenceStart = active?.localStart ?: -1,
            activeSentenceEnd = active?.localEnd ?: -1,
            readingFraction = if (session.source.length == 0) 0f else
                session.source.offsetOf(session.position).toFloat() / session.source.length
        )
    }

    fun onManualVerticalDragStarted() {
        // A real vertical gesture owns playback independently from a possibly
        // delayed UI isPlaying snapshot. Invalidating the generation also
        // rejects an onRangeStart/onDone already queued by Android TTS.
        stop()
    }

    /*
     * ==========================================
     * İLERLEMEYİ KAYDET
     * ==========================================
     */
    private fun persistProgress(
        session: ReadingSession,
        position: ReadingPosition,
        onlyIfAbsent: Boolean = false
    ) {
        val bookId = currentBookId ?: return
        val progress = ReadingProgress(bookId, position, session.source.paragraphs.size)
        // Enter immediately and finish queued writes in order even if this screen is closed.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    progressWriteMutex.withLock {
                        progressStore.setProgress(progress, onlyIfAbsent)
                    }
                    session.markCanonical()
                } catch (_: Exception) {
                    Toast.makeText(getApplication(), "Okuma konumu kaydedilemedi. Önceki kayıt korundu.", Toast.LENGTH_LONG).show()
                } finally {
                    if (onlyIfAbsent && readingSession === session) migrationPending = false
                }
            }
        }
    }
    /*
     * ==========================================
     * VIEWMODEL KAPANIŞI
     * ==========================================
     */
    override fun onCleared() {

        super.onCleared()

        speechGeneration++

        tts?.stop()

        tts?.shutdown()

        tts = null

        ttsReady = false
    }
}
