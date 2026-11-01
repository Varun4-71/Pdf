package com.example.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.engine.NormRect
import com.example.engine.PageLink
import com.example.engine.PageTransition
import com.example.engine.SearchHit
import com.example.engine.ViewerSession
import com.example.engine.rotateNorm
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f

/** Reports taps and double taps without ever blocking scrolling or pinching. */
@Composable
fun Modifier.tapGestures(onTap: () -> Unit, onDoubleTap: (Offset) -> Unit): Modifier {
    val tap = rememberUpdatedState(onTap)
    val double = rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        detectTapGestures(
            onTap = { tap.value.invoke() },
            onDoubleTap = { offset -> double.value.invoke(offset) }
        )
    }
}

/**
 * Pinch to zoom around the fingers, and one-finger panning once zoomed in.
 * Listens before the scroll containers so it can claim two-finger gestures.
 */
@Composable
fun Modifier.pinchZoomGestures(
    state: ViewerState,
    panVertical: Boolean,
    maxPanX: (Float) -> Float,
    maxPanY: (Float) -> Float
): Modifier {
    val limitX = rememberUpdatedState(maxPanX)
    val limitY = rememberUpdatedState(maxPanY)
    return this.pointerInput(state, panVertical) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val zoomChange = event.calculateZoom()
                    val centroid = event.calculateCentroid(true)
                    val before = state.zoom
                    val after = (before * zoomChange).coerceIn(1f, MAX_ZOOM)
                    if (after != before) {
                        val factor = after / before
                        state.zoom = after
                        state.panX = ((state.panX + centroid.x) * factor - centroid.x)
                            .coerceIn(0f, limitX.value.invoke(after))
                        if (panVertical) {
                            state.panY = ((state.panY + centroid.y) * factor - centroid.y)
                                .coerceIn(0f, limitY.value.invoke(after))
                        }
                    }
                    for (change in event.changes) {
                        if (change.positionChanged()) change.consume()
                    }
                } else if (pressed == 1 && state.zoom > 1.01f) {
                    val pan = event.calculatePan()
                    state.panX = (state.panX - pan.x).coerceIn(0f, limitX.value.invoke(state.zoom))
                    if (panVertical) {
                        state.panY = (state.panY - pan.y).coerceIn(0f, limitY.value.invoke(state.zoom))
                        for (change in event.changes) {
                            if (change.positionChanged()) change.consume()
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** One rendered page with search highlights and tappable links on top. */
@Composable
fun PdfPage(
    session: ViewerSession,
    index: Int,
    width: Dp,
    rotation: Int,
    colorMode: ColorMode,
    hits: List<SearchHit>,
    activeHit: SearchHit?,
    onLink: (PageLink) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val ratio = session.ratioOf(index, rotation)
    val height = width * ratio
    var bitmap by remember(index, rotation) { mutableStateOf<Bitmap?>(null) }
    val bucket = ((widthPx + 127) / 128) * 128

    LaunchedEffect(session, index, rotation, bucket) {
        if (bitmap != null) delay(90)
        val rendered = session.renderPage(index, bucket, rotation)
        if (rendered != null) bitmap = rendered
    }
    val links by produceState(initialValue = emptyList<PageLink>(), session, index) {
        value = session.loadLinks(index)
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(Color.White)
    ) {
        val shown = bitmap
        if (shown != null) {
            val image = remember(shown) { shown.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Page ${index + 1} of ${session.pageCount}",
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilterFor(colorMode),
                modifier = Modifier.fillMaxSize()
            )
        }
        if (hits.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (hit in hits) {
                    val active = hit === activeHit
                    val color = if (active) Color(0x99FF8A00) else Color(0x66FFEB3B)
                    for (r in hit.rects) {
                        val rr = rotateNorm(r, rotation)
                        drawRect(
                            color = color,
                            topLeft = Offset(rr.left * size.width, rr.top * size.height),
                            size = Size((rr.right - rr.left) * size.width, (rr.bottom - rr.top) * size.height)
                        )
                    }
                }
            }
        }
        for (link in links) {
            val rr = rotateNorm(NormRect(link.left, link.top, link.right, link.bottom), rotation)
            Box(
                modifier = Modifier
                    .offset(x = width * rr.left, y = height * rr.top)
                    .size(width * (rr.right - rr.left), height * (rr.bottom - rr.top))
                    .clickable { onLink(link) }
            )
        }
    }
}

private fun hitsFor(search: SearchState, page: Int): List<SearchHit> {
    val out = ArrayList<SearchHit>()
    for (h in search.hits) {
        if (h.page == page) out.add(h)
    }
    return out
}

/** All pages stacked vertically. Zoom widens every page; the content pans sideways. */
@Composable
fun ContinuousView(
    session: ViewerSession,
    state: ViewerState,
    search: SearchState,
    topPad: Dp,
    bottomPad: Dp,
    onLink: (PageLink) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val lastIndex = max(0, session.pageCount - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, lastIndex))

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            var page = listState.firstVisibleItemIndex
            for (item in info.visibleItemsInfo) {
                if (item.offset <= center && item.offset + item.size >= center) {
                    page = item.index
                    break
                }
            }
            page
        }.collect { page -> state.currentPage = page }
    }
    LaunchedEffect(state.jumpTo) {
        val target = state.jumpTo
        if (target != null) {
            listState.scrollToItem(target.coerceIn(0, lastIndex))
            state.jumpTo = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewportW = maxWidth
        val viewportPx = with(density) { viewportW.toPx() }
        val contentW = viewportW * state.zoom
        val current = search.hits.getOrNull(search.current)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchZoomGestures(
                    state = state,
                    panVertical = false,
                    maxPanX = { z -> viewportPx * (z - 1f) },
                    maxPanY = { 0f }
                )
                .tapGestures(
                    onTap = onTap,
                    onDoubleTap = { offset ->
                        if (state.zoom > 1.05f) {
                            state.resetZoom()
                        } else {
                            state.zoom = 2.5f
                            state.panX = (offset.x * 1.5f).coerceIn(0f, viewportPx * 1.5f)
                        }
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(contentW)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = -state.panX }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPad + 8.dp, bottom = bottomPad + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(count = session.pageCount, key = { it }) { i ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            PdfPage(
                                session = session,
                                index = i,
                                width = contentW - 16.dp,
                                rotation = state.rotation,
                                colorMode = state.colorMode,
                                hits = hitsFor(search, i),
                                activeHit = current,
                                onLink = onLink
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fitSize(viewW: Float, viewH: Float, ratio: Float, zoom: Float): Pair<Float, Float> {
    val baseW = min(viewW, viewH / ratio)
    val w = baseW * zoom
    return Pair(w, w * ratio)
}

private fun spreadRatio(session: ViewerSession, first: Int, perSpread: Int, rotation: Int): Float {
    val r1 = session.ratioOf(first, rotation)
    if (perSpread == 1) return r1
    val r2 = if (first + 1 < session.pageCount) session.ratioOf(first + 1, rotation) else r1
    return max(r1, r2) / 2f
}

/** One page (or one two-page spread) at a time, swiped like a book. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagedView(
    session: ViewerSession,
    state: ViewerState,
    search: SearchState,
    topPad: Dp,
    bottomPad: Dp,
    twoPage: Boolean,
    onLink: (PageLink) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val perSpread = if (twoPage) 2 else 1
    val spreadCount = (session.pageCount + perSpread - 1) / perSpread
    val lastSpread = max(0, spreadCount - 1)
    val pagerState = rememberPagerState(
        initialPage = (state.currentPage / perSpread).coerceIn(0, lastSpread),
        pageCount = { spreadCount }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { spread ->
            state.currentPage = spread * perSpread
            state.resetZoom()
        }
    }
    LaunchedEffect(state.jumpTo) {
        val target = state.jumpTo
        if (target != null) {
            pagerState.scrollToPage((target / perSpread).coerceIn(0, lastSpread))
            state.jumpTo = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { (maxHeight - topPad - bottomPad).toPx() }
        val current = search.hits.getOrNull(search.current)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchZoomGestures(
                    state = state,
                    panVertical = true,
                    maxPanX = { z ->
                        val first = (state.currentPage / perSpread) * perSpread
                        val sized = fitSize(viewW, viewH, spreadRatio(session, first, perSpread, state.rotation), z)
                        max(0f, sized.first - viewW)
                    },
                    maxPanY = { z ->
                        val first = (state.currentPage / perSpread) * perSpread
                        val sized = fitSize(viewW, viewH, spreadRatio(session, first, perSpread, state.rotation), z)
                        max(0f, sized.second - viewH)
                    }
                )
                .tapGestures(
                    onTap = onTap,
                    onDoubleTap = { offset ->
                        if (state.zoom > 1.05f) {
                            state.resetZoom()
                        } else {
                            state.zoom = 2.5f
                            state.panX = (offset.x * 1.5f).coerceAtLeast(0f)
                            state.panY = (offset.y * 1.5f).coerceAtLeast(0f)
                        }
                    }
                )
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = state.zoom <= 1.01f,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { spread ->
                val first = spread * perSpread
                val ratio = spreadRatio(session, first, perSpread, state.rotation)
                val sized = fitSize(viewW, viewH, ratio, state.zoom)
                val wPx = sized.first
                val hPx = sized.second
                val wDp = with(density) { wPx.toDp() }
                val hDp = with(density) { hPx.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad, bottom = bottomPad)
                        .clipToBounds()
                ) {
                    Box(
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .size(wDp, hDp)
                            .offset {
                                val x = if (wPx <= viewW) (viewW - wPx) / 2f else -state.panX
                                val y = if (hPx <= viewH) (viewH - hPx) / 2f else -state.panY
                                IntOffset(x.roundToInt(), y.roundToInt())
                            }
                    ) {
                        Row {
                            for (k in 0 until perSpread) {
                                val pageIndex = first + k
                                if (pageIndex < session.pageCount) {
                                    PdfPage(
                                        session = session,
                                        index = pageIndex,
                                        width = wDp / perSpread,
                                        rotation = state.rotation,
                                        colorMode = state.colorMode,
                                        hits = hitsFor(search, pageIndex),
                                        activeHit = current,
                                        onLink = onLink
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(wDp / perSpread))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun transitionFor(style: String?, forward: Boolean, ms: Int): ContentTransform {
    if (style == null) {
        return fadeIn(tween<Float>(120)) togetherWith fadeOut(tween<Float>(120))
    }
    val slides = style == "Push" || style == "Cover" || style == "Uncover" || style == "Fly" ||
        style == "Wipe" || style == "Split" || style == "Blinds" || style == "Box" || style == "R"
    if (slides) {
        val enter = slideInHorizontally(tween<IntOffset>(ms)) { w -> if (forward) w else -w } + fadeIn(tween<Float>(ms))
        val exit = slideOutHorizontally(tween<IntOffset>(ms)) { w -> if (forward) -w else w } + fadeOut(tween<Float>(ms))
        return enter togetherWith exit
    }
    return fadeIn(tween<Float>(ms)) togetherWith fadeOut(tween<Float>(ms))
}

/** Full-screen slide show that plays the transitions and timings stored in the PDF. */
@Composable
fun PresentationView(
    session: ViewerSession,
    state: ViewerState,
    modifier: Modifier = Modifier
) {
    var transitions by remember { mutableStateOf<Map<Int, PageTransition>>(emptyMap()) }
    LaunchedEffect(session) { transitions = session.loadTransitions() }
    val lastIndex = max(0, session.pageCount - 1)
    val page = state.currentPage.coerceIn(0, lastIndex)
    val currentTransition = transitions[page]

    LaunchedEffect(page, currentTransition) {
        val wait = currentTransition?.autoAdvanceSec
        if (wait != null && wait > 0f && page < lastIndex) {
            delay((wait * 1000f).toLong())
            state.currentPage = page + 1
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(page, lastIndex) {
                detectTapGestures(onTap = { offset ->
                    if (offset.x < size.width / 3f) {
                        if (page > 0) state.currentPage = page - 1
                    } else {
                        if (page < lastIndex) state.currentPage = page + 1
                    }
                })
            }
    ) {
        val viewW = maxWidth.value
        val viewH = maxHeight.value
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val t = transitions[targetState]
                val ms = (((t?.durationSec ?: 0.5f) * 1000f).toInt()).coerceIn(100, 2500)
                transitionFor(t?.style, targetState >= initialState, ms)
            },
            label = "presentation"
        ) { p ->
            val ratio = session.ratioOf(p, state.rotation)
            val pageWidth = min(viewW, viewH / ratio)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PdfPage(
                    session = session,
                    index = p,
                    width = pageWidth.dp,
                    rotation = state.rotation,
                    colorMode = state.colorMode,
                    hits = emptyList(),
                    activeHit = null,
                    onLink = { }
                )
            }
        }
    }
}
