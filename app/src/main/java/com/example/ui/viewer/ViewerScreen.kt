package com.example.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.engine.BookmarkStore
import com.example.engine.FileUtils
import com.example.engine.OpenResult
import com.example.engine.PageLink
import com.example.engine.PdfEngine
import com.example.engine.PrintHelper
import com.example.engine.RecentFiles
import com.example.engine.ViewerLaunch
import com.example.engine.ViewerSession
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepObsidian
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private enum class ViewerPanel { NONE, PAGES, CONTENTS, RESULTS, OPTIONS }

private val ChromeColor = Color(0xF2101828)

/** Entry point for the reader. The document to show comes from [ViewerLaunch]. */
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = remember { ViewerLaunch.uri }
    val startPage = remember { ViewerLaunch.startPage }
    val startQuery = remember { ViewerLaunch.query }
    var result by remember { mutableStateOf<OpenResult?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var passwordWrong by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        if (uri == null) {
            result = OpenResult.Failed("No document was selected.")
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Not every provider allows keeping access; the document still opens now.
            }
            val opened = ViewerSession.open(context, uri, null)
            result = opened
            if (opened is OpenResult.NeedsPassword) askPassword = true
            if (opened is OpenResult.Ok) {
                RecentFiles.add(context, uri, opened.session.displayName)
            }
        }
    }

    val current = result
    if (current is OpenResult.Ok) {
        ViewerContent(
            session = current.session,
            startPage = startPage,
            startQuery = startQuery,
            onBack = onBack
        )
    } else if (current is OpenResult.Failed) {
        StatusScreen(title = "This PDF can't be opened", detail = current.message, loading = false, onBack = onBack)
    } else {
        StatusScreen(title = "Opening PDF", detail = "", loading = !askPassword, onBack = onBack)
    }

    if (askPassword && uri != null) {
        PasswordDialog(
            wrong = passwordWrong,
            onSubmit = { password ->
                scope.launch {
                    passwordWrong = false
                    val opened = ViewerSession.open(context, uri, password)
                    if (opened is OpenResult.WrongPassword) {
                        passwordWrong = true
                    } else {
                        askPassword = false
                        result = opened
                        if (opened is OpenResult.Ok) {
                            RecentFiles.add(context, uri, opened.session.displayName)
                        }
                    }
                }
            },
            onCancel = onBack
        )
    }
}

@Composable
private fun StatusScreen(title: String, detail: String, loading: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidian)
            .statusBarsPadding()
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loading) CircularProgressIndicator(color = CyanAccent)
            Text(text = title, color = TextPrimary, fontSize = 18.sp)
            if (detail.isNotEmpty()) {
                Text(text = detail, color = TextMuted, fontSize = 14.sp)
            }
            if (!loading) {
                TextButton(onClick = onBack) { Text("Go back", color = CyanAccent) }
            }
        }
    }
}

@Composable
private fun ViewerContent(
    session: ViewerSession,
    startPage: Int,
    startQuery: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lastIndex = max(0, session.pageCount - 1)
    val state = remember(session) { ViewerState(startPage.coerceIn(0, lastIndex)) }
    val search = remember(session) { SearchState() }
    val bookmarks = remember(session) { mutableStateListOf<Int>() }
    var panel by remember { mutableStateOf(ViewerPanel.NONE) }
    var contentsTab by remember { mutableStateOf(0) }
    var showGoTo by remember { mutableStateOf(false) }
    var showPrint by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(session) {
        onDispose { session.close() }
    }
    LaunchedEffect(session) { session.loadPageRatios() }
    LaunchedEffect(session) {
        bookmarks.clear()
        bookmarks.addAll(BookmarkStore.get(context, session.sourceUri.toString()))
    }
    LaunchedEffect(state.currentPage) { ViewerLaunch.startPage = state.currentPage }
    LaunchedEffect(startQuery) {
        if (startQuery != null && startQuery.isNotBlank()) {
            search.active = true
            search.query = startQuery
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            message = null
        }
    }
    LaunchedEffect(search.query) {
        val q = search.query.trim()
        search.clearResults()
        if (q.length >= 2) {
            delay(350)
            search.running = true
            session.search(
                query = q,
                onHit = { hit ->
                    search.hits.add(hit)
                    if (search.hits.size == 1) {
                        search.current = 0
                        state.jumpTo = hit.page
                    }
                },
                onProgress = { done, total ->
                    search.progress = if (total > 0) done.toFloat() / total else 0f
                }
            )
            search.running = false
        }
    }

    ImmersiveEffect(state.fullscreen || state.presentation)
    KeepScreenOn()

    BackHandler(enabled = true) {
        if (panel != ViewerPanel.NONE) {
            panel = ViewerPanel.NONE
        } else if (state.presentation) {
            state.presentation = false
        } else if (search.active) {
            search.active = false
            search.query = ""
        } else if (state.fullscreen) {
            state.fullscreen = false
            state.chromeVisible = true
        } else {
            onBack()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target: Uri? ->
        if (target != null) {
            scope.launch {
                try {
                    PdfEngine.saveFileToDestination(context, session.file, target)
                    message = "Saved a copy."
                } catch (e: Exception) {
                    message = "Could not save the copy."
                }
            }
        }
    }

    fun jumpTo(page: Int) {
        state.jumpTo = page.coerceIn(0, lastIndex)
    }

    fun openLink(link: PageLink) {
        val target = link.uri
        if (target != null) {
            val scheme = Uri.parse(target).scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                } catch (e: Exception) {
                    message = "No app can open this link."
                }
            } else {
                message = "This link type is not supported."
            }
        } else if (link.targetPage >= 0) {
            jumpTo(link.targetPage)
        }
    }

    fun gotoHit(index: Int) {
        if (search.hits.isEmpty()) return
        val wrapped = ((index % search.hits.size) + search.hits.size) % search.hits.size
        search.current = wrapped
        state.jumpTo = search.hits[wrapped].page
    }

    val hideBars = state.fullscreen || state.presentation
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val topPad = if (hideBars) 0.dp else statusTop + 56.dp
    val bottomPad = if (hideBars) 0.dp else navBottom + 112.dp
    val marked = bookmarks.contains(state.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropFor(state.colorMode))
    ) {
        if (state.presentation) {
            PresentationView(session = session, state = state)
        } else if (state.reflow) {
            ReflowView(
                session = session,
                state = state,
                topPad = topPad,
                bottomPad = bottomPad,
                onTap = { state.chromeVisible = !state.chromeVisible }
            )
        } else if (state.viewMode == ViewMode.CONTINUOUS) {
            ContinuousView(
                session = session,
                state = state,
                search = search,
                topPad = topPad,
                bottomPad = bottomPad,
                onLink = { openLink(it) },
                onTap = { state.chromeVisible = !state.chromeVisible }
            )
        } else {
            PagedView(
                session = session,
                state = state,
                search = search,
                topPad = topPad,
                bottomPad = bottomPad,
                twoPage = state.viewMode == ViewMode.TWO_PAGE,
                onLink = { openLink(it) },
                onTap = { state.chromeVisible = !state.chromeVisible }
            )
        }

        if (!state.presentation) {
            AnimatedVisibility(
                visible = state.chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (search.active) {
                    SearchBar(
                        search = search,
                        onClose = {
                            search.active = false
                            search.query = ""
                        },
                        onPrev = { gotoHit(search.current - 1) },
                        onNext = { gotoHit(search.current + 1) },
                        onList = { panel = ViewerPanel.RESULTS }
                    )
                } else {
                    ViewerTopBar(
                        title = session.displayName,
                        subtitle = "Page ${state.currentPage + 1} of ${session.pageCount}",
                        bookmarked = marked,
                        onBack = onBack,
                        onSearch = { search.active = true },
                        onBookmark = {
                            val on = BookmarkStore.toggle(context, session.sourceUri.toString(), state.currentPage)
                            bookmarks.clear()
                            bookmarks.addAll(BookmarkStore.get(context, session.sourceUri.toString()))
                            message = if (on) "Bookmarked page ${state.currentPage + 1}" else "Bookmark removed"
                        },
                        onMore = { panel = ViewerPanel.OPTIONS }
                    )
                }
            }
            AnimatedVisibility(
                visible = state.chromeVisible && !search.active,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ViewerBottomBar(
                    state = state,
                    pageCount = session.pageCount,
                    onPages = { panel = ViewerPanel.PAGES },
                    onContents = {
                        contentsTab = 0
                        panel = ViewerPanel.CONTENTS
                    },
                    onOptions = { panel = ViewerPanel.OPTIONS }
                )
            }
            if (!state.chromeVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC111827))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = "${state.currentPage + 1} / ${session.pageCount}", color = TextPrimary, fontSize = 12.sp)
                }
            }
        } else {
            IconButton(
                onClick = { state.presentation = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "End slide show", tint = Color(0xAAFFFFFF))
            }
        }

        val note = message
        if (note != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 130.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xEE1F2937))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(text = note, color = TextPrimary, fontSize = 13.sp)
            }
        }

        when (panel) {
            ViewerPanel.PAGES -> ThumbnailsPanel(
                session = session,
                currentPage = state.currentPage,
                onPick = {
                    jumpTo(it)
                    panel = ViewerPanel.NONE
                },
                onClose = { panel = ViewerPanel.NONE }
            )
            ViewerPanel.CONTENTS -> ContentsPanel(
                session = session,
                currentPage = state.currentPage,
                bookmarks = bookmarks,
                initialTab = contentsTab,
                onToggleBookmark = {
                    BookmarkStore.toggle(context, session.sourceUri.toString(), state.currentPage)
                    bookmarks.clear()
                    bookmarks.addAll(BookmarkStore.get(context, session.sourceUri.toString()))
                },
                onJump = {
                    jumpTo(it)
                    panel = ViewerPanel.NONE
                },
                onClose = { panel = ViewerPanel.NONE }
            )
            ViewerPanel.RESULTS -> SearchResultsPanel(
                hits = search.hits,
                current = search.current,
                running = search.running,
                onPick = {
                    gotoHit(it)
                    panel = ViewerPanel.NONE
                },
                onClose = { panel = ViewerPanel.NONE }
            )
            ViewerPanel.OPTIONS -> OptionsPanel(
                state = state,
                pageCount = session.pageCount,
                onClose = { panel = ViewerPanel.NONE },
                onGoTo = {
                    panel = ViewerPanel.NONE
                    showGoTo = true
                },
                onPrint = {
                    panel = ViewerPanel.NONE
                    showPrint = true
                },
                onShare = {
                    panel = ViewerPanel.NONE
                    scope.launch {
                        try {
                            shareDocument(context, session)
                        } catch (e: Exception) {
                            message = "Could not share this file."
                        }
                    }
                },
                onSaveCopy = {
                    panel = ViewerPanel.NONE
                    saveLauncher.launch(session.displayName)
                },
                onFullscreen = {
                    panel = ViewerPanel.NONE
                    state.fullscreen = !state.fullscreen
                    state.chromeVisible = !state.fullscreen
                },
                onPresent = {
                    panel = ViewerPanel.NONE
                    state.reflow = false
                    state.resetZoom()
                    state.presentation = true
                    message = "Tap the right side for next, the left side for previous."
                }
            )
            ViewerPanel.NONE -> {
            }
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = session.pageCount,
            onDismiss = { showGoTo = false },
            onGo = {
                showGoTo = false
                jumpTo(it)
            }
        )
    }
    if (showPrint) {
        PrintDialog(
            pageCount = session.pageCount,
            currentPage = state.currentPage,
            onDismiss = { showPrint = false },
            onPrint = { pages, annotations ->
                showPrint = false
                scope.launch {
                    message = "Preparing to print"
                    try {
                        PrintHelper.print(context, session, pages, annotations)
                        message = null
                    } catch (e: Exception) {
                        message = "Could not start printing."
                    }
                }
            }
        )
    }
}

// -------------------------------------------------------------------- bars

@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onBookmark: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChromeColor)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onSearch) {
            Icon(imageVector = Icons.Default.Search, contentDescription = "Search in document", tint = TextPrimary)
        }
        IconButton(onClick = onBookmark) {
            Icon(
                imageVector = if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                tint = if (bookmarked) CyanAccent else TextPrimary
            )
        }
        IconButton(onClick = onMore) {
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More options", tint = TextPrimary)
        }
    }
}

@Composable
private fun SearchBar(
    search: SearchState,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onList: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // The field may not be attached yet; the person can tap it.
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChromeColor)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close search", tint = TextPrimary)
        }
        BasicTextField(
            value = search.query,
            onValueChange = { search.query = it },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(CyanAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (search.query.isEmpty()) {
                        Text(text = "Search in document", color = TextMuted, fontSize = 16.sp)
                    }
                    inner()
                }
            }
        )
        val count = if (search.hits.isEmpty()) {
            if (search.running) "..." else if (search.query.trim().length >= 2) "0" else ""
        } else {
            "${search.current + 1}/${search.hits.size}"
        }
        Text(text = count, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onPrev, enabled = search.hits.isNotEmpty()) {
            Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = TextPrimary)
        }
        IconButton(onClick = onNext, enabled = search.hits.isNotEmpty()) {
            Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = TextPrimary)
        }
        IconButton(onClick = onList, enabled = search.hits.isNotEmpty()) {
            Icon(imageVector = Icons.Default.FormatListNumbered, contentDescription = "All matches", tint = TextPrimary)
        }
    }
}

@Composable
private fun ViewerBottomBar(
    state: ViewerState,
    pageCount: Int,
    onPages: () -> Unit,
    onContents: () -> Unit,
    onOptions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChromeColor)
            .navigationBarsPadding()
            .padding(bottom = 4.dp)
    ) {
        if (pageCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "${state.currentPage + 1}", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(32.dp))
                Slider(
                    value = state.currentPage.toFloat().coerceIn(0f, (pageCount - 1).toFloat()),
                    onValueChange = { state.jumpTo = it.roundToInt() },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = CyanAccent,
                        activeTrackColor = CyanAccent,
                        inactiveTrackColor = Color(0x30FFFFFF)
                    )
                )
                Text(text = "$pageCount", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(32.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BarButton(Icons.Default.GridView, "Pages", onPages)
            BarButton(Icons.Default.Bookmarks, "Contents", onContents)
            BarButton(
                Icons.Default.ViewAgenda,
                state.viewMode.label,
                {
                    val all = ViewMode.values()
                    state.viewMode = all[(state.viewMode.ordinal + 1) % all.size]
                    state.resetZoom()
                }
            )
            BarButton(Icons.Default.ZoomIn, "${(state.zoom * 100f).roundToInt()}%", {
                if (state.zoom > 1.05f) state.resetZoom() else state.zoom = 2f
            })
            BarButton(Icons.Default.RotateRight, "Rotate", { state.rotation = (state.rotation + 90) % 360 })
            BarButton(Icons.Default.Tune, "More", onOptions)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// -------------------------------------------------------------------- platform helpers

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun ImmersiveEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        val controller: WindowInsetsControllerCompat? = if (window != null) WindowCompat.getInsetsController(window, view) else null
        if (controller != null) {
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private suspend fun shareDocument(context: Context, session: ViewerSession) {
    val file: File = withContext(Dispatchers.IO) {
        val dir = FileUtils.newDir(context, "share")
        var name = FileUtils.safeName(session.displayName)
        if (!name.lowercase().endsWith(".pdf")) name += ".pdf"
        val target = File(dir, name)
        session.file.copyTo(target, true)
        target
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "application/pdf"
    intent.putExtra(Intent.EXTRA_STREAM, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}
