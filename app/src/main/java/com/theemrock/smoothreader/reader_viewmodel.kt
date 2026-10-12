package com.theemrock.smoothreader

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.Job

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
    val canonicalPosition: ReadingPosition? = null,
)

internal fun ReaderUiState.beginPaginationLayout(layoutVersion: Long): ReaderUiState = copy(
    isPreparingPages = true,
    totalPages = 0,
    globalPageNumber = null,
    layoutVersion = layoutVersion,
    verticalTransitionId = 0,
    verticalTransitionContentVersion = 0,
    verticalTransitionLayoutVersion = 0
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private data class ReaderBookMetadata(val title: String, val author: String?) {
        val header: String = if (author == null) title else "$title · $author"
    }

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
    private data class PageRequest(val block: Int, val index: Int, val exactPosition: ReadingPosition? = null) // -1 means last page
    private var pageRequest: PageRequest? = null

    private var currentBookId: String? = null
    private var currentMode: ReadingMode = ReadingMode.READ
    private var currentMetadata = ReaderBookMetadata("Kitap", null)
    private var verticalTransitionGeneration = 0L
    private var automaticTransitionPending = false

    /*
     * ReaderScreen tarafından verilen
     * mevcut sayfanın metni.
     */
    private var currentPageText: String = ""

    private val appContext = app.applicationContext
    private val playbackBindingState = ReaderPlaybackBindingState()
    private var playbackPreparation: PlaybackPreparation? = null
    private var playbackBinder: ReaderPlaybackService.LocalBinder? = null
    private var playbackStateJob: Job? = null
    private var playbackStoppingJob: Job? = null
    private var playbackBound = false
    private var cleared = false
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (cleared) return
            val binder = service as? ReaderPlaybackService.LocalBinder ?: return
            playbackBinder = binder
            playbackStateJob?.cancel()
            playbackStateJob = viewModelScope.launch {
                binder.state.collect(::applyPlaybackSnapshot)
            }
            playbackStoppingJob?.cancel()
            playbackStoppingJob = viewModelScope.launch {
                binder.stopping.collect { serviceStopping ->
                    if (serviceStopping && playbackBindingState.serviceStopping()) {
                        disconnectPlaybackService()
                    }
                }
            }
            applyPlaybackConnection()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackStateJob?.cancel()
            playbackStateJob = null
            playbackStoppingJob?.cancel()
            playbackStoppingJob = null
            playbackBinder = null
            playbackBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    private fun ensurePlaybackBound() {
        if (cleared || playbackBound) return
        playbackBound = appContext.bindService(
            Intent(appContext, ReaderPlaybackService::class.java),
            playbackConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun applyPlaybackConnection() {
        val binder = playbackBinder ?: return
        val actions = playbackBindingState.connected(binder.state.value)
        actions.preparation?.let { preparation ->
            binder.prepareSession(
                preparation.bookId,
                preparation.source,
                preparation.position,
                preparation.title,
                preparation.author,
                preparation.mode
            )
        }
        actions.snapshotToApply?.let(::applyPlaybackSnapshot)
        actions.selectedStart?.let { position ->
            if (binder.state.value.isPlaying) binder.pause("wordSelection")
            binder.seek(position, restartIfPlaying = false, reason = "wordSelection")
        }
        if (actions.startPlayback) {
            ContextCompat.startForegroundService(
                appContext,
                ReaderPlaybackService.actionIntent(appContext, ReaderPlaybackService.ACTION_PLAY)
            )
        }
    }

    private fun disconnectPlaybackService() {
        playbackStateJob?.cancel()
        playbackStateJob = null
        playbackStoppingJob?.cancel()
        playbackStoppingJob = null
        playbackBinder = null
        if (playbackBound) {
            appContext.unbindService(playbackConnection)
            playbackBound = false
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
        currentMode = mode
        currentMetadata = ReaderBookMetadata("Kitap", null)
        playbackPreparation = null
        ensurePlaybackBound()
        val version = ++loadVersion
        paginationEpoch.next(version)
        paginator = null
        readyTicket = null
        pageRequest = null
        queuedDirection = null
        automaticTransitionPending = false
        readingSession = null
        migrationPending = false
        currentPageText = ""
        _state.value = ReaderUiState(isLoading = true, contentVersion = version)

        // Metadata is independent of pagination and must not delay the first readable page.
        viewModelScope.launch {
            val metadata = loadBookMetadata(bookId)
            if (version == loadVersion) {
                currentMetadata = metadata
                _state.value = _state.value.copy(bookHeader = metadata.header)
                playbackPreparation = playbackPreparation?.copy(
                    title = metadata.title,
                    author = metadata.author,
                    mode = currentMode
                )?.also(playbackBindingState::updatePreparation)
                playbackBinder?.updateMetadata(
                    bookId,
                    metadata.title,
                    metadata.author,
                    currentMode
                )
            }
        }

        viewModelScope.launch {
            try {
                val parsed = ParsedCache.get(bookId)
                require(parsed != null && parsed.paragraphs.any { it.isNotBlank() })
                val (stored, canonical) = progressStore.storedProgress(bookId).first()
                val legacyPage = if (canonical) null else legacyProgressStore.loadPage(bookId).first()
                val source = withContext(Dispatchers.Default) { ReadingSource(parsed.paragraphs) }
                if (version != loadVersion) return@launch
                val session = ReadingSession(
                    source, stored?.position ?: ReadingPosition(),
                    legacyPage, canonical
                )
                readingSession = session
                _state.value = ReaderUiState(
                    isLoading = false, paragraphs = parsed.paragraphs, contentVersion = version,
                    isPreparingPages = true, bookHeader = _state.value.bookHeader
                )
                playbackPreparation = PlaybackPreparation(
                    bookId = bookId,
                    source = source,
                    position = session.position,
                    title = currentMetadata.title,
                    author = currentMetadata.author,
                    mode = currentMode
                ).also(playbackBindingState::updatePreparation)
                applyPlaybackConnection()
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
    private suspend fun loadBookMetadata(bookId: String): ReaderBookMetadata = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val book = LibraryStore(app.applicationContext).booksFlow.first().firstOrNull { it.id == bookId }
                ?: return@withContext ReaderBookMetadata("Kitap", null)
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
            ReaderBookMetadata(title, author)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ReaderBookMetadata("Kitap", null)
        }
    }

    fun setCurrentPageText(
        text: String
    ) {

        currentPageText = text

    }

    fun selectTtsStartAtPageOffset(localOffset: Int): Boolean {
        val session = readingSession ?: return false
        val page = session.pages.getOrNull(session.pageIndex) ?: return false
        val position = canonicalWordStartForPageOffset(session.source, page, localOffset) ?: return false
        selectCanonicalPlaybackStart(position)
        return true
    }

    private fun selectCanonicalPlaybackStart(position: ReadingPosition) {
        playbackPreparation = playbackPreparation?.copy(position = position)
            ?.also(playbackBindingState::updatePreparation)
        playbackBindingState.selectStart(position)
        ensurePlaybackBound()
        applyPlaybackConnection()
    }

    fun seekToBookProgress(progress: Float) {
        val session = readingSession ?: return
        val position = canonicalPositionForBookProgress(session.source, progress)
        selectCanonicalPlaybackStart(position)
        projectPlaybackPosition(
            position,
            playbackBinder?.state?.value?.generation ?: 0L
        )
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
        automaticTransitionPending = false
        engine.demand = engine.focus
        _state.value = _state.value.beginPaginationLayout(ticket.layout)
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

        val req = pageRequest
        if (req != null && req.exactPosition != null &&
            req.exactPosition != session.position) {
            TtsDiagnostics.log("TTS pagination request stale | requestedPosition: ${req.exactPosition}, currentPosition: ${session.position}, block: ${req.block}")
            pageRequest = null
        }

        val legacy = session.pendingLegacyPage()?.takeIf { it >= 0 }
        if (legacy != null) {
            val location = engine.locateGlobal(legacy)
            if (location != null) {
                val page = engine.block(location.first)?.pages?.getOrNull(location.second)
                if (page != null) {
                    session.resolveLegacy(page.start)
                    playbackBinder?.adoptLegacyPosition(session.position)
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
            if (request.exactPosition != null) {
                if (block?.complete == true && block.pages.isNotEmpty()) {
                    val index = pageForPosition(block.pages, request.exactPosition)
                    val page = block.pages.getOrNull(index)
                    if (page != null &&
                        pageContainsPosition(
                            page,
                            request.exactPosition,
                            session.source.positionAt(session.source.length)
                        ) &&
                        request.exactPosition == session.position &&
                        session.acceptExactWindow(block.pages.toList(), index, request.exactPosition)) {
                        activeBlock = request.block
                        engine.focus = activeBlock
                        engine.demand = null
                        pageRequest = null
                        readyTicket = engine.ticket
                        TtsDiagnostics.log("TTS pagination request accepted | exactPosition: ${request.exactPosition}, block: ${request.block}, pageIndex: $index, pageStart: ${page.start}, pageEnd: ${page.end}")
                    } else {
                        TtsDiagnostics.log("TTS pagination request rejected | exactPosition: ${request.exactPosition}, currentPosition: ${session.position}, block: ${request.block}, pageStart: ${page?.start}, pageEnd: ${page?.end}")
                        pageRequest = null
                        engine.demand = null
                        _state.value = _state.value.copy(isPreparingPages = false)
                    }
                }
            } else {
                val index = if (request.index < 0 && block?.complete == true) block.pages.lastIndex else request.index
                if (block != null && index in block.pages.indices) {
                    val changed = session.navigateWindow(block.pages.toList(), index)
                    activeBlock = request.block
                    engine.focus = activeBlock
                    engine.demand = null
                    pageRequest = null
                    readyTicket = engine.ticket
                    if (changed) {
                        playbackPreparation = playbackPreparation?.copy(position = session.position)
                            ?.also(playbackBindingState::updatePreparation)
                        playbackBinder?.seek(
                            session.position,
                            restartIfPlaying = true,
                            reason = "explicitNavigation"
                        )
                    }
                } else if (block?.complete == true && block.pages.isEmpty()) {
                    val next = request.block + if (request.index < 0) -1 else 1
                    if (next in 0 until engine.blockCount) {
                        pageRequest = PageRequest(next, request.index)
                        engine.demand = next
                    } else {
                        pageRequest = null
                        engine.demand = null
                        if (request.index >= 0) stop("navigationNoTarget")
                    }
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
        if (readyTicket != engine.ticket && !canonicalMeasurementReady(
                pages.last().end, anchor, active.complete
            )) return
        session.acceptWindow(pages.toList())
        readyTicket = engine.ticket
        if (pageRequest == null) engine.demand = null
        val page = session.pages[session.pageIndex]
        val previousPageText = session.pages.getOrNull(session.pageIndex - 1)?.text
            ?: engine.block(activeBlock - 1)?.takeIf { it.complete }?.pages?.lastOrNull()?.text
        val nextPageText = session.pages.getOrNull(session.pageIndex + 1)?.text
            ?: engine.block(activeBlock + 1)?.pages?.firstOrNull()?.text
        val activeSentence = activeSentenceForPosition(
            sentenceRanges(session.source, page),
            session.position
        )
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
            activeSentenceEnd = activeSentence?.localEnd ?: -1,
            canonicalPosition = session.position
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
            else -> { if (direction > 0) stop("navigationEnd"); return }
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

    fun navigateByScreen(direction: Int) {
        if (direction != -1 && direction != 1) return
        playbackBindingState.requestPause()
        playbackBinder?.takeIf { it.state.value.isPlaying }
            ?.pause("intentionalScreenNavigation")
        navigateWindow(direction)
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
            playbackBinder?.interruptForSeek("prevPageAwaitingCommit")
        }

        navigateWindow(-1)

        // Playback restarts from the committed canonical position in publishWindow().
    }

    /*
     * ==========================================
     * PLAY / PAUSE
     * ==========================================
     */
    fun togglePlayPause() {

        if (_state.value.isPlaying) {
            stop("userPause")
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
        playbackBindingState.requestPlay()
        ensurePlaybackBound()
        applyPlaybackConnection()
    }

    /*
     * ==========================================
     * DURDUR
     * ==========================================
     */
    fun stop(reason: String = "external") {
        playbackBinder?.pause(reason)
    }

    /*
     * ==========================================
     * YAVAŞLAT
     * ==========================================
     */
    fun slowDown() {
        playbackBinder?.setSpeed(
            _state.value.speed - 1,
            "speedDecreased"
        )
    }

    /*
     * ==========================================
     * HIZLANDIR
     * ==========================================
     */
    fun speedUp() {
        playbackBinder?.setSpeed(
            _state.value.speed + 1,
            "speedIncreased"
        )
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
    fun toggleSound() {
        playbackBinder?.toggleSound("soundToggled")
    }

    private fun requestAutomaticNextPage() {
        val state = _state.value
        if (state.nextPageText == null) {
            if (automaticTransitionPending) return
            val engine = paginator ?: return
            val preparationBlock = if (engine.block(activeBlock)?.complete == false) activeBlock else activeBlock + 1
            if (preparationBlock !in 0 until engine.blockCount) {
                stop("automaticTransitionNoBlock")
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

    fun completeAutomaticVerticalTransition(id: Long, contentVersion: Long, layoutVersion: Long): Boolean {
        val state = _state.value
        val accepted = acceptsAutomaticVerticalTransition(
            requested = VerticalViewportToken(contentVersion, layoutVersion, id),
            current = VerticalViewportToken(
                state.verticalTransitionContentVersion,
                state.verticalTransitionLayoutVersion,
                state.verticalTransitionId
            ),
            isPlaying = state.isPlaying
        ) && state.contentVersion == contentVersion && state.layoutVersion == layoutVersion
        if (!accepted) return false
        nextPage()
        if (_state.value.verticalTransitionId == id) {
            _state.value = _state.value.copy(verticalTransitionId = 0)
        }
        return true
    }

    fun completeManualVerticalTransition(direction: Int, contentVersion: Long, layoutVersion: Long) {
        val state = _state.value
        if (state.contentVersion != contentVersion || state.layoutVersion != layoutVersion) return
        if (direction > 0) nextPage() else if (direction < 0) prevPage()
    }

    private fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.bookId != null && snapshot.bookId != currentBookId) return
        _state.value = _state.value.copy(
            isPlaying = snapshot.isPlaying,
            isSoundEnabled = snapshot.isSoundEnabled,
            speed = snapshot.speed
        )
        val position = snapshot.position ?: return
        projectPlaybackPosition(position, snapshot.generation)
    }

    /** Projects controller-owned canonical position into pagination and highlight state. */
    private fun projectPlaybackPosition(position: ReadingPosition, generation: Long) {
        val session = readingSession ?: return
        val changed = session.updatePosition(position)
        val engine = paginator
        val page = session.pages.getOrNull(session.pageIndex)
        val isInsidePage = page != null &&
            comparePositions(session.position, page.start) >= 0 &&
            comparePositions(session.position, page.end) < 0

        if (!isInsidePage) {
            _state.value = _state.value.copy(
                activeSentenceStart = -1,
                activeSentenceEnd = -1,
                readingFraction = if (session.source.length == 0) 0f else
                    session.source.offsetOf(session.position).toFloat() / session.source.length,
                canonicalPosition = session.position
            )

            if (engine == null || !changed ||
                (pageRequest != null && pageRequest?.exactPosition == null) || engine.blockCount <= 0
            ) {
                return
            }

            if (pageRequest?.exactPosition == session.position) return

            val blockIdx = engine.blockAt(session.position).coerceIn(0, engine.blockCount - 1)
            pageRequest = PageRequest(blockIdx, -1, exactPosition = session.position)
            TtsDiagnostics.log("TTS pagination request created | exactPosition: ${session.position}, block: $blockIdx, currentGen: $generation")
            engine.demand = blockIdx
            _state.value = _state.value.copy(isPreparingPages = true)
            paginationWake.trySend(Unit)
            return
        }

        val active = sentenceRanges(session.source, page).firstOrNull {
            comparePositions(it.start, session.position) <= 0 && comparePositions(session.position, it.end) < 0
        }
        _state.value = _state.value.copy(
            activeSentenceStart = active?.localStart ?: -1,
            activeSentenceEnd = active?.localEnd ?: -1,
            readingFraction = if (session.source.length == 0) 0f else
                session.source.offsetOf(session.position).toFloat() / session.source.length,
            canonicalPosition = session.position
        )
    }

    fun onManualVerticalDragStarted() {
        // A real vertical gesture owns playback independently from a possibly
        // delayed UI isPlaying snapshot. Invalidating the generation also
        // rejects an onRangeStart/onDone already queued by Android TTS.
        stop("manualVerticalDrag")
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

    fun exitReader() {
        val binder = playbackBinder
        if (binder != null) {
            binder.stop("readerExit")
            disconnectPlaybackService()
        } else {
            appContext.stopService(Intent(appContext, ReaderPlaybackService::class.java))
        }
    }

    /*
     * ==========================================
     * VIEWMODEL KAPANIŞI
     * ==========================================
     */
    override fun onCleared() {
        cleared = true
        disconnectPlaybackService()
        TtsDiagnostics.log("ReaderViewModel onCleared | playback ownership remains with service")
        super.onCleared()
    }
}
