package com.theemrock.smoothreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * ==========================================
 * OKUMA TEMALARI
 * ==========================================
 */
internal enum class ReaderTheme {
    GECE,
    GUNDUZ,
    SEPYA
}

internal data class ReaderThemeColors(
    val background: Color,
    val text: Color,
    val highlight: Color,
    val activeText: Color
)

private data class ReaderPaper(
    val drawableRes: Int,
    val alpha: Float
)

private data class ReaderTextLayout(
    val text: String,
    val result: TextLayoutResult
)

internal fun themeColorsFor(
    theme: ReaderTheme
): ReaderThemeColors {

    return when (theme) {

        ReaderTheme.GUNDUZ -> {
            ReaderThemeColors(
                background = Color.White,
                text = Color.Black,
                highlight = Color(0x4019C3E6),
                activeText = Color.Black
            )
        }

        ReaderTheme.GECE -> {
            ReaderThemeColors(
                background = Color(0xFF121212),
                text = Color(0xFFE0E0E0),
                highlight = Color(0x59F0B84B),
                activeText = Color(0xFFE0E0E0)
            )
        }

        ReaderTheme.SEPYA -> {
            ReaderThemeColors(
                background = Color(0xFFF5ECD9),
                text = Color(0xFF4A3728),
                highlight = Color(0x4019C3E6),
                activeText = Color(0xFF2E86DE)
            )
        }
    }
}

internal fun highlightedReaderText(
    text: String,
    activeStart: Int,
    activeEnd: Int,
    highlight: Color,
    activeText: Color
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (activeStart in 0 until text.length &&
        activeEnd in 1..text.length &&
        activeEnd > activeStart
    ) {
        addStyle(
            SpanStyle(color = activeText, background = highlight),
            activeStart,
            activeEnd
        )
    }
}

private fun paperFor(theme: ReaderTheme): ReaderPaper = when (theme) {
    ReaderTheme.GUNDUZ -> ReaderPaper(R.drawable.reader_paper_day, 0.50f)
    ReaderTheme.GECE -> ReaderPaper(R.drawable.reader_paper_night, 0.35f)
    ReaderTheme.SEPYA -> ReaderPaper(R.drawable.reader_paper_sepia, 0.50f)
}

/*
 * ==========================================
 * FONT TİPLERİ
 * ==========================================
 */
private enum class ReaderFontType {
    SANS,
    SERIF,
    MONOSPACE
}

private val FONT_SIZE_OPTIONS = listOf(
    16f,
    18f,
    20f,
    22f,
    24f,
    26f,
    28f,
    30f,
    32f,
    36f
)

@Composable
fun ReaderScreen(
    bookId: String,
    mode: ReadingMode,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {

    val state by viewModel.uiState.collectAsState()
    val latestState by rememberUpdatedState(state)
    val exitReader = {
        viewModel.exitReader()
        onBack()
    }
    BackHandler(onBack = exitReader)

    /*
     * ==========================================
     * MENÜLER
     * ==========================================
     */
    var themeMenuExpanded by remember {
        mutableStateOf(false)
    }

    var fontMenuExpanded by remember {
        mutableStateOf(false)
    }


    /*
     * ==========================================
     * HIZ
     * ==========================================
     */
    var showSlowSpeed by remember {
        mutableStateOf(false)
    }

    var showFastSpeed by remember {
        mutableStateOf(false)
    }

    /*
     * ==========================================
     * SAYFA DİYALOĞU
     * ==========================================
     */
    var showPageDialog by remember {
        mutableStateOf(false)
    }

    var progressPreview by remember(state.contentVersion) {
        mutableStateOf<Float?>(null)
    }

    /*
     * ==========================================
     * FONT DİYALOĞU
     * ==========================================
     */
    var fontSizeDialogOpen by remember {
        mutableStateOf(false)
    }

    /*
     * ==========================================
     * TEMA
     * ==========================================
     */
    var readerTheme by remember {
        mutableStateOf(
            ReaderTheme.GUNDUZ
        )
    }

    /*
     * ==========================================
     * FONT
     * ==========================================
     */
    var fontSizeSp by remember {
        mutableStateOf(18f)
    }

    var fontType by remember {
        mutableStateOf(
            ReaderFontType.SANS
        )
    }

    var verticalViewport by remember {
        mutableStateOf(VerticalViewportState(VerticalViewportOwner.IDLE, 0f))
    }
    var viewportGeneration by remember { mutableLongStateOf(0L) }
    var settleDirection by remember { mutableStateOf(0) }
    var settleDurationMs by remember { mutableStateOf(0) }
    var readerPageHeightPx by remember { mutableStateOf(1) }
    var activeSentenceCenterPx by remember { mutableStateOf<Float?>(null) }
    var currentTextLayout by remember { mutableStateOf<ReaderTextLayout?>(null) }
    var listenMotion by remember { mutableStateOf(ListenTrackingMotion(0f, 0f, 0f)) }

    /*
     * ==========================================
     * TEMA RENKLERİ
     * ==========================================
     */
    val themeColors = remember(readerTheme) {
        themeColorsFor(readerTheme)
    }
    val readerPaper = remember(readerTheme) {
        paperFor(readerTheme)
    }
    val readerPaperPainter = painterResource(readerPaper.drawableRes)

    /*
     * ==========================================
     * FONT AİLESİ
     * ==========================================
     */
    val selectedFontFamily =
        when (fontType) {

            ReaderFontType.SANS ->
                FontFamily.SansSerif

            ReaderFontType.SERIF ->
                FontFamily.Serif

            ReaderFontType.MONOSPACE ->
                FontFamily.Monospace
        }

    /*
     * ==========================================
     * TEXT MEASURER
     * ==========================================
     */
    val textMeasurer = rememberTextMeasurer()

    val density = LocalDensity.current

    /*
     * ==========================================
     * EKRAN AÇIK KALSIN
     * ==========================================
     */
    val view = LocalView.current
    val platformDoubleTapSlopPx = remember(view) {
        android.view.ViewConfiguration.get(view.context).scaledDoubleTapSlop.toFloat()
    }

    DisposableEffect(Unit) {

        view.keepScreenOn = true

        onDispose {
            view.keepScreenOn = false
        }
    }

    /*
     * ==========================================
     * HIZ GÖSTERİMİ
     * ==========================================
     */
    LaunchedEffect(showSlowSpeed) {

        if (showSlowSpeed) {

            delay(500)

            showSlowSpeed = false
        }
    }

    LaunchedEffect(showFastSpeed) {

        if (showFastSpeed) {

            delay(500)

            showFastSpeed = false
        }
    }

    /*
     * ==========================================
     * KİTABI YÜKLE
     * ==========================================
     */
    LaunchedEffect(bookId, mode) {

        viewModel.load(bookId, mode)
    }

    /*
     * ==========================================
     * YÜKLENİYOR
     * ==========================================
     */
    if (state.isLoading) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    themeColors.background
                )
                .paint(
                    painter = readerPaperPainter,
                    sizeToIntrinsics = false,
                    contentScale = ContentScale.Crop,
                    alpha = readerPaper.alpha
                ),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text = "Kitap yükleniyor...",
                fontSize = 18.sp,
                color = themeColors.text
            )
        }

        return
    }

    /*
     * ==========================================
     * ANA EKRAN
     * ==========================================
     */
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                themeColors.background
            )
            .paint(
                painter = readerPaperPainter,
                sizeToIntrinsics = false,
                contentScale = ContentScale.Crop,
                alpha = readerPaper.alpha
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(
                horizontal = 16.dp
            )
    ) {

        /*
         * ==========================================
         * METİN ALANI YÜKSEKLİĞİ
         * ==========================================
         *
         * Menü kapalı:
         * %80
         *
         * Menü açık:
         * %70
         */
        /*
         * BoxWithConstraints yüksekliğini px'e çevir.
         */
        val horizontalPaddingPx =
            with(density) {
                32.dp.roundToPx()
            }

        val textWidthPx =
            (
                    with(density) {
                        maxWidth.roundToPx()
                    } - horizontalPaddingPx
                    ).coerceAtLeast(1)

        /*
         * Metin alanında:
         *
         * üst padding
         * METİN başlığı
         * alt boşluk
         *
         * için yaklaşık 50dp ayırıyoruz.
         */
        // The measured reader Box is the single height source for both
        // pagination and the bounded vertical surface.
        val availableTextHeightPx = readerPageHeightPx

        /*
         * ==========================================
         * SAYFALAMA STYLE
         * ==========================================
         */
        val paginationStyle = TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = (
                    fontSizeSp + 10
                    ).sp,
            fontFamily = selectedFontFamily
        )

        /*
         * ==========================================
         * GERÇEK SAYFALAMA
         * ==========================================
         *
         * Font değişince bu yeniden hesaplanır.
         *
         * Menü açılıp kapanınca da yeniden
         * hesaplanır çünkü kullanılabilir metin
         * yüksekliği değişiyor.
         */
        LaunchedEffect(
            state.contentVersion, fontSizeSp, fontType, textWidthPx,
            availableTextHeightPx, density.density, density.fontScale, textMeasurer
        ) {
            if (availableTextHeightPx <= 1) return@LaunchedEffect
            var measurements = 0
            viewModel.paginateWindow(state.contentVersion) { candidate ->
                // TextMeasurer stays on the Compose thread. At most two bounded
                // measurements run between frame suspension points.
                if (measurements++ % 2 == 0) {
                    androidx.compose.runtime.withFrameNanos { }
                }
                !textMeasurer.measure(
                    text = candidate,
                    style = paginationStyle,
                    constraints = Constraints(maxWidth = textWidthPx, maxHeight = availableTextHeightPx),
                    skipCache = true
                ).hasVisualOverflow
            }
        }

        val currentText = state.pageText
        val handleReaderDoubleTap by rememberUpdatedState<(Offset) -> Unit> { position ->
            val layout = currentTextLayout?.takeIf { it.text == currentText } ?: return@rememberUpdatedState
            val localOffset = layout.result.getOffsetForPosition(position)
            viewModel.selectTtsStartAtPageOffset(localOffset)
        }
        LaunchedEffect(currentText) {
            activeSentenceCenterPx = null
            currentTextLayout = null
            viewModel.setCurrentPageText(
                currentText
            )
        }

        LaunchedEffect(state.layoutVersion) {
            if (state.layoutVersion > 0L) {
                verticalViewport = VerticalViewportState(
                    if (state.isPlaying)
                        VerticalViewportOwner.AUTO_TRACKING else VerticalViewportOwner.IDLE,
                    0f
                )
                listenMotion = ListenTrackingMotion(0f, 0f, 0f)
                activeSentenceCenterPx = null
                viewportGeneration++
            }
        }

        LaunchedEffect(state.isPlaying) {
            if (state.isPlaying &&
                verticalViewport.owner != VerticalViewportOwner.DRAGGING &&
                verticalViewport.owner != VerticalViewportOwner.SETTLING) {
                verticalViewport = verticalViewport.copy(owner = VerticalViewportOwner.AUTO_TRACKING)
                listenMotion = ListenTrackingMotion(
                    verticalViewport.offset, 0f, verticalViewport.offset
                )
                viewportGeneration++
            } else if (!state.isPlaying && verticalViewport.owner == VerticalViewportOwner.AUTO_TRACKING) {
                verticalViewport = verticalViewport.copy(owner = VerticalViewportOwner.IDLE)
                viewportGeneration++
            }
        }

        // Single owner/mutator for every animated vertical viewport change.
        LaunchedEffect(
            verticalViewport.owner, viewportGeneration, state.contentVersion,
            state.layoutVersion
        ) {
            when (verticalViewport.owner) {
                VerticalViewportOwner.SETTLING -> {
                    val start = verticalViewport.offset
                    val target = verticalViewport.targetOffset
                    val durationNanos = settleDurationMs.coerceAtLeast(1) * 1_000_000L
                    val started = withFrameNanos { it }
                    var fraction = 0f
                    while (fraction < 1f && verticalViewport.owner == VerticalViewportOwner.SETTLING) {
                        val now = withFrameNanos { it }
                        fraction = ((now - started).toFloat() / durationNanos).coerceIn(0f, 1f)
                        val eased = fraction * fraction * (3f - 2f * fraction)
                        verticalViewport = verticalViewport.copy(offset = start + (target - start) * eased)
                    }
                    if (verticalViewport.owner == VerticalViewportOwner.SETTLING) {
                        if (settleDirection != 0) {
                            verticalViewport = verticalViewport.copy(owner = VerticalViewportOwner.COMMITTING)
                            viewModel.completeManualVerticalTransition(
                                settleDirection, state.contentVersion, state.layoutVersion
                            )
                            verticalViewport = VerticalViewportState(
                                VerticalViewportOwner.IDLE,
                                rebaseCommittedOffset(target, readerPageHeightPx.toFloat(), settleDirection)
                            )
                        } else {
                            verticalViewport = VerticalViewportState(VerticalViewportOwner.IDLE, 0f)
                        }
                    }
                }

                VerticalViewportOwner.AUTO_TRACKING -> {
                    var rejectedTransitionId = 0L
                    var previousFrame = withFrameNanos { it }
                    while (verticalViewport.owner == VerticalViewportOwner.AUTO_TRACKING && latestState.isPlaying) {
                        val frame = withFrameNanos { it }
                        val elapsedMs = ((frame - previousFrame) / 1_000_000L).coerceAtMost(50L)
                        previousFrame = frame
                        val height = readerPageHeightPx.toFloat()
                        val transitionPending = shouldAttemptAutomaticVerticalTransition(
                            latestState.verticalTransitionId,
                            rejectedTransitionId
                        )
                        val target = if (transitionPending) -height else activeSentenceTrackingTarget(
                            activeSentenceCenterPx, listenMotion.target, height, height
                        )
                        listenMotion = advanceListenTracking(
                            listenMotion.retarget(target), elapsedMs / 1000f
                        )
                        verticalViewport = verticalViewport.copy(
                            offset = listenMotion.offset,
                            targetOffset = listenMotion.target
                        )
                        if (transitionPending && listenMotion.offset <= -height + 1f) {
                            verticalViewport = verticalViewport.copy(owner = VerticalViewportOwner.COMMITTING)
                            val accepted = viewModel.completeAutomaticVerticalTransition(
                                latestState.verticalTransitionId,
                                latestState.verticalTransitionContentVersion,
                                latestState.verticalTransitionLayoutVersion
                            )
                            listenMotion = listenMotion.afterAutomaticTransitionCompletion(
                                accepted, height
                            )
                            verticalViewport = VerticalViewportState(
                                if (latestState.isPlaying) VerticalViewportOwner.AUTO_TRACKING
                                else VerticalViewportOwner.IDLE,
                                listenMotion.offset,
                                listenMotion.target
                            )
                            if (!accepted) {
                                rejectedTransitionId = latestState.verticalTransitionId
                            }
                        }
                    }
                }

                else -> Unit
            }
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            /*
             * ==========================================
             * METİN
             * ==========================================
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = 8.dp,
                            bottom = 8.dp
                        )
                ) {

                    /*
                     * BAŞLIK
                     */
                    Text(
                        text = state.bookHeader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                bottom = 8.dp
                            )
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 2000,
                                repeatDelayMillis = 2000,
                                velocity = 20.dp
                            ),
                        maxLines = 1,
                        softWrap = false,
                        fontSize = 14.sp,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            themeColors.text
                    )

                    /*
                     * ==========================================
                     * SAYFA METNİ + SWIPE
                     * ==========================================
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .onSizeChanged { readerPageHeightPx = it.height.coerceAtLeast(1) }
                            .pointerInput(Unit) {
                                var totalDrag = 0f
                                var releaseVelocity = 0f
                                var lastDragTime = 0L

                                detectVerticalDragGestures(

                                            onDragStart = {
                                                totalDrag = verticalViewport.offset
                                                releaseVelocity = 0f
                                                lastDragTime = 0L
                                                viewModel.onManualVerticalDragStarted()
                                                verticalViewport = verticalViewport.takeManualOwnership()
                                                listenMotion = ListenTrackingMotion(
                                                    verticalViewport.offset, 0f, verticalViewport.offset
                                                )
                                                viewportGeneration++
                                            },

                                            onVerticalDrag = {
                                                    change,
                                                    dragAmount ->

                                                change.consume()

                                                totalDrag +=
                                                    dragAmount
                                                if (lastDragTime != 0L) {
                                                    val elapsed = (change.uptimeMillis - lastDragTime).coerceAtLeast(1L)
                                                    val instantVelocity = dragAmount * 1000f / elapsed
                                                    releaseVelocity = releaseVelocity * 0.65f + instantVelocity * 0.35f
                                                }
                                                lastDragTime = change.uptimeMillis
                                                verticalViewport = verticalViewport.copy(
                                                    owner = VerticalViewportOwner.DRAGGING,
                                                    offset = totalDrag.coerceIn(
                                                        -readerPageHeightPx.toFloat(), readerPageHeightPx.toFloat()
                                                    )
                                                )
                                            },

                                            onDragEnd = {
                                                val requested = normalizedVerticalSettle(
                                                    verticalViewport.offset,
                                                    readerPageHeightPx.toFloat(),
                                                    releaseVelocity
                                                )
                                                val direction = when (requested.committedDirection) {
                                                    1 -> if (state.nextPageText != null) 1 else 0
                                                    -1 -> if (state.previousPageText != null) -1 else 0
                                                    else -> 0
                                                }
                                                val target = if (direction == 0) 0f else requested.targetOffset
                                                settleDirection = direction
                                                settleDurationMs = settleDurationMillis(verticalViewport.offset, target)
                                                verticalViewport = verticalViewport.copy(
                                                    owner = VerticalViewportOwner.SETTLING,
                                                    targetOffset = target,
                                                    resumeAutomatically = false
                                                )
                                                viewportGeneration++
                                            },
                                            onDragCancel = {
                                                settleDirection = 0
                                                settleDurationMs = settleDurationMillis(verticalViewport.offset, 0f)
                                                verticalViewport = verticalViewport.copy(
                                                    owner = VerticalViewportOwner.SETTLING,
                                                    targetOffset = 0f,
                                                    resumeAutomatically = false
                                                )
                                                viewportGeneration++
                                            }
                                )
                            }
                    ) {

                        run {
                            val renderedText = if (currentText.isEmpty()) {
                                AnnotatedString(if (state.isPreparingPages) "Sayfa hazırlanıyor…" else "Sayfa hazırlanamadı.")
                            } else highlightedReaderText(
                                text = currentText,
                                activeStart = state.activeSentenceStart,
                                activeEnd = state.activeSentenceEnd,
                                highlight = themeColors.highlight,
                                activeText = themeColors.activeText
                            )
                            SelectionContainer {
                                Text(
                                    text = renderedText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            translationY = verticalPageGeometry(
                                                verticalViewport.offset, readerPageHeightPx.toFloat()
                                            ).currentY
                                        }
                                        .pointerInput(
                                            currentText,
                                            state.layoutVersion,
                                            platformDoubleTapSlopPx
                                        ) {
                                            observeDoubleTapsWithoutConsuming(platformDoubleTapSlopPx) {
                                                handleReaderDoubleTap(it)
                                            }
                                        },
                                    fontSize = fontSizeSp.sp,
                                    lineHeight = (fontSizeSp + 10).sp,
                                    fontFamily = selectedFontFamily,
                                    color = themeColors.text,
                                    onTextLayout = { result ->
                                        currentTextLayout = ReaderTextLayout(currentText, result)
                                        activeSentenceCenterPx = if (
                                            state.activeSentenceStart in 0 until currentText.length &&
                                            state.activeSentenceEnd in 1..currentText.length &&
                                            state.activeSentenceEnd > state.activeSentenceStart
                                        ) {
                                            val startBox = result.getBoundingBox(state.activeSentenceStart)
                                            val endBox = result.getBoundingBox(state.activeSentenceEnd - 1)
                                            (startBox.top + endBox.bottom) * 0.5f
                                        } else null
                                    }
                                )
                            }
                        }
                        if (state.previousPageText != null) {
                            Text(
                                text = state.previousPageText.orEmpty(),
                                modifier = Modifier.graphicsLayer {
                                    translationY = verticalPageGeometry(
                                        verticalViewport.offset, readerPageHeightPx.toFloat()
                                    ).previousY
                                },
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 10).sp,
                                fontFamily = selectedFontFamily,
                                color = themeColors.text
                            )
                        }
                        if (state.nextPageText != null) {
                            Text(
                                text = state.nextPageText.orEmpty(),
                                modifier = Modifier.graphicsLayer {
                                    translationY = verticalPageGeometry(
                                        verticalViewport.offset, readerPageHeightPx.toFloat()
                                    ).nextY
                                },
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 10).sp,
                                fontFamily = selectedFontFamily,
                                color = themeColors.text
                            )
                        }
                    }
                }
            }

            /*
             * ==========================================
             * ALT ALAN
             * ==========================================
             */
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment =
                    Alignment.BottomCenter
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = 4.dp
                        ),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    /*
                     * ==========================================
                     * PROGRESS
                     * ==========================================
                     */
                    val displayedProgress = progressPreview
                        ?: state.readingFraction.coerceIn(0f, 1f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                progressPreview = null
                                viewModel.navigateByScreen(-1)
                            },
                            enabled = !state.isLoading && !state.isPreparingPages &&
                                state.globalPageNumber != 1
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Önceki ekran",
                                tint = themeColors.text
                            )
                        }

                        Slider(
                            value = displayedProgress,
                            onValueChange = { progressPreview = it.coerceIn(0f, 1f) },
                            onValueChangeFinished = {
                                progressPreview?.let(viewModel::seekToBookProgress)
                                progressPreview = null
                            },
                            enabled = !state.isLoading && !state.isPreparingPages,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = themeColors.text,
                                activeTrackColor = themeColors.text,
                                inactiveTrackColor = themeColors.text.copy(alpha = 0.24f)
                            )
                        )

                        IconButton(
                            onClick = {
                                progressPreview = null
                                viewModel.navigateByScreen(1)
                            },
                            enabled = !state.isLoading && !state.isPreparingPages &&
                                !(state.totalPages > 0 &&
                                    state.globalPageNumber == state.totalPages)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Sonraki ekran",
                                tint = themeColors.text
                            )
                        }
                    }

                    /*
                     * ==========================================
                     * SAYFA NUMARASI
                     * ==========================================
                     */
                    Text(
                        text = if (progressPreview != null) {
                            "%${(displayedProgress * 100).roundToInt()}"
                        } else if (state.totalPages > 0 && state.globalPageNumber != null) {
                            "${state.globalPageNumber} / ${state.totalPages}"
                        } else {
                            "${state.globalPageNumber ?: "…"} / …"
                        },
                        modifier = Modifier
                            .clickable {

                                if (
                                    state.totalPages > 0
                                ) {

                                    showPageDialog =
                                        true
                                }
                            }
                            .padding(
                                horizontal = 12.dp,
                                vertical = 2.dp
                            ),

                        fontSize = 14.sp,

                        fontWeight =
                            FontWeight.Medium,

                        color =
                            themeColors.text
                    )

                    /*
                     * ==========================================
                     * AYAR MENÜSÜ
                     * ==========================================
                     */
                    run {

                        Spacer(
                            modifier =
                                Modifier.height(2.dp)
                        )

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceEvenly,

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            /*
                             * HOME
                             */
                            IconButton(
                                onClick = exitReader
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = "Ana sayfa",
                                    tint = themeColors.text,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            IconButton(onClick = viewModel::toggleSound) {
                                Icon(
                                    imageVector = if (state.isSoundEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                    contentDescription = if (state.isSoundEnabled) "Sesi kapat" else "Sesi aç",
                                    tint = themeColors.text,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            /*
                             * SLOW
                             */
                            IconButton(
                                onClick = {
                                    viewModel.slowDown()
                                    showFastSpeed = false
                                    showSlowSpeed = true
                                }
                            ) {
                                if (showSlowSpeed) {
                                    Text(
                                        text = "%${state.speed * 10}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = themeColors.text
                                    )
                                } else {
                                    Text(
                                        text = "🐢",
                                        fontSize = 26.sp
                                    )
                                }
                            }

                            /*
                             * PLAY / PAUSE
                             */
                            IconButton(
                                onClick = {
                                    viewModel.togglePlayPause()
                                }
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) {
                                        Icons.Filled.Pause
                                    } else {
                                        Icons.Filled.PlayArrow
                                    },
                                    contentDescription = if (state.isPlaying) {
                                        "Duraklat"
                                    } else {
                                        "Başlat"
                                    },
                                    tint = themeColors.text,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            /*
                             * FAST
                             */
                            IconButton(
                                onClick = {
                                    viewModel.speedUp()
                                    showSlowSpeed = false
                                    showFastSpeed = true
                                }
                            ) {
                                if (showFastSpeed) {
                                    Text(
                                        text = "%${state.speed * 10}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = themeColors.text
                                    )
                                } else {
                                    Text(
                                        text = "🐇",
                                        fontSize = 26.sp
                                    )
                                }
                            }

                            /*
                             * ==================================
                             * TEMA
                             * ==================================
                             */
                            Box {

                                IconButton(
                                    onClick = {

                                        themeMenuExpanded =
                                            true

                                        fontMenuExpanded =
                                            false

                                    }
                                ) {

                                    Icon(

                                        imageVector =
                                            when (
                                                readerTheme
                                            ) {

                                                ReaderTheme.GECE ->
                                                    Icons.Filled.DarkMode

                                                ReaderTheme.GUNDUZ ->
                                                    Icons.Filled.LightMode

                                                ReaderTheme.SEPYA ->
                                                    Icons.Filled.MenuBook
                                            },

                                        contentDescription =
                                            "Tema",

                                        tint =
                                            themeColors.text,

                                        modifier =
                                            Modifier.size(28.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded =
                                        themeMenuExpanded,

                                    onDismissRequest = {

                                        themeMenuExpanded =
                                            false
                                    }
                                ) {

                                    DropdownMenuItem(

                                        text = {
                                            Text("Gece")
                                        },

                                        leadingIcon = {

                                            Icon(
                                                imageVector =
                                                    Icons.Filled.DarkMode,

                                                contentDescription =
                                                    null
                                            )
                                        },

                                        onClick = {

                                            readerTheme =
                                                ReaderTheme.GECE

                                            themeMenuExpanded =
                                                false
                                        }
                                    )

                                    DropdownMenuItem(

                                        text = {
                                            Text("Gündüz")
                                        },

                                        leadingIcon = {

                                            Icon(
                                                imageVector =
                                                    Icons.Filled.LightMode,

                                                contentDescription =
                                                    null
                                            )
                                        },

                                        onClick = {

                                            readerTheme =
                                                ReaderTheme.GUNDUZ

                                            themeMenuExpanded =
                                                false
                                        }
                                    )

                                    DropdownMenuItem(

                                        text = {
                                            Text("Sepya")
                                        },

                                        leadingIcon = {

                                            Text("📖")
                                        },

                                        onClick = {

                                            readerTheme =
                                                ReaderTheme.SEPYA

                                            themeMenuExpanded =
                                                false
                                        }
                                    )
                                }
                            }

                            /*
                             * ==================================
                             * FONT
                             * ==================================
                             */
                            Box {

                                IconButton(
                                    onClick = {

                                        fontMenuExpanded =
                                            true

                                        themeMenuExpanded =
                                            false

                                    }
                                ) {

                                    Icon(

                                        imageVector =
                                            Icons.Filled.FormatSize,

                                        contentDescription =
                                            "Font",

                                        tint =
                                            themeColors.text,

                                        modifier =
                                            Modifier.size(28.dp)
                                    )
                                }

                                DropdownMenu(

                                    expanded =
                                        fontMenuExpanded,

                                    onDismissRequest = {

                                        fontMenuExpanded =
                                            false
                                    }
                                ) {

                                    /*
                                     * A-
                                     */
                                    DropdownMenuItem(

                                        text = {
                                            Text("A−")
                                        },

                                        onClick = {

                                            fontSizeSp =
                                                (
                                                        fontSizeSp - 2f
                                                        ).coerceAtLeast(
                                                        12f
                                                    )

                                            fontMenuExpanded =
                                                false
                                        }
                                    )

                                    /*
                                     * A
                                     */
                                    DropdownMenuItem(

                                        text = {
                                            Text("A")
                                        },

                                        onClick = {

                                            fontMenuExpanded =
                                                false

                                            fontSizeDialogOpen =
                                                true
                                        }
                                    )

                                    /*
                                     * A+
                                     */
                                    DropdownMenuItem(

                                        text = {
                                            Text("A+")
                                        },

                                        onClick = {

                                            fontSizeSp =
                                                (
                                                        fontSizeSp + 2f
                                                        ).coerceAtMost(
                                                        40f
                                                    )

                                            fontMenuExpanded =
                                                false
                                        }
                                    )
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    /*
     * ==========================================
     * FONT AYARLARI
     * ==========================================
     */
    if (fontSizeDialogOpen) {

        AlertDialog(

            onDismissRequest = {

                fontSizeDialogOpen =
                    false
            },

            title = {

                Text(
                    "Yazı ayarları"
                )
            },

            text = {

                Column {

                    Text(
                        text = "Yazı boyutu",
                        fontWeight =
                            FontWeight.Bold
                    )

                    FONT_SIZE_OPTIONS.forEach { size ->

                        TextButton(

                            onClick = {

                                fontSizeSp =
                                    size

                                fontSizeDialogOpen =
                                    false
                            }
                        ) {

                            Text(
                                "${size.toInt()} sp",
                                fontSize =
                                    16.sp
                            )
                        }
                    }

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(
                        text = "Yazı tipi",
                        fontWeight =
                            FontWeight.Bold
                    )

                    TextButton(

                        onClick = {

                            fontType =
                                ReaderFontType.SANS
                        }
                    ) {

                        Text(
                            if (
                                fontType ==
                                ReaderFontType.SANS
                            ) {
                                "✓ Sans Serif"
                            } else {
                                "Sans Serif"
                            }
                        )
                    }

                    TextButton(

                        onClick = {

                            fontType =
                                ReaderFontType.SERIF
                        }
                    ) {

                        Text(
                            if (
                                fontType ==
                                ReaderFontType.SERIF
                            ) {
                                "✓ Serif"
                            } else {
                                "Serif"
                            }
                        )
                    }

                    TextButton(

                        onClick = {

                            fontType =
                                ReaderFontType.MONOSPACE
                        }
                    ) {

                        Text(
                            if (
                                fontType ==
                                ReaderFontType.MONOSPACE
                            ) {
                                "✓ Monospace"
                            } else {
                                "Monospace"
                            }
                        )
                    }
                }
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        fontSizeDialogOpen =
                            false
                    }
                ) {

                    Text("Kapat")
                }
            }
        )
    }

    /*
     * ==========================================
     * SAYFAYA GİT
     * ==========================================
     */
    if (showPageDialog) {

        var pageInput by remember {

            mutableStateOf(
                (
                        state.globalPageNumber ?: 1
                        ).toString()
            )
        }

        AlertDialog(

            onDismissRequest = {

                showPageDialog =
                    false
            },

            title = {

                Text(
                    "Sayfaya git"
                )
            },

            text = {

                OutlinedTextField(

                    value = pageInput,

                    onValueChange = {

                        pageInput =
                            it.filter { char ->
                                char.isDigit()
                            }
                    },

                    singleLine = true,

                    label = {

                        Text(
                            "Sayfa"
                        )
                    }
                )
            },

            confirmButton = {

                Button(

                    onClick = {

                        val requestedPage =
                            pageInput.toIntOrNull()

                        if (
                            requestedPage != null &&
                            state.totalPages > 0
                        ) {

                            val target =
                                requestedPage.coerceIn(
                                    1,
                                    state.totalPages
                                )

                            viewModel.goToPage(target)
                        }
                        showPageDialog =
                            false
                    }
                ) {

                    Text(
                        "Git"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        showPageDialog =
                            false
                    }
                ) {

                    Text(
                        "İptal"
                    )
                }
            }
        )
    }
}
