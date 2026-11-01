package com.example.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.engine.AttachmentInfo
import com.example.engine.OutlineEntry
import com.example.engine.PageRanges
import com.example.engine.PdfEngine
import com.example.engine.SearchHit
import com.example.engine.ViewerSession
import com.example.ui.components.AppTextField
import com.example.ui.components.ChoiceChips
import com.example.ui.components.LabeledSlider
import com.example.ui.components.SwitchRow
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RadiantRose
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private val PanelColor = Color(0xFF111827)

/** A sheet that slides up from the bottom over a dimmed page. Tapping outside closes it. */
@Composable
fun BottomPanel(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(PanelColor)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
            content()
        }
    }
}

// -------------------------------------------------------------------- thumbnails

@Composable
fun ThumbnailsPanel(session: ViewerSession, currentPage: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    BottomPanel(title = "Pages", onClose = onClose) {
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = max(0, currentPage - 3))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(88.dp),
            state = gridState,
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(count = session.pageCount) { i ->
                ThumbCell(session = session, index = i, selected = i == currentPage, onPick = onPick)
            }
        }
    }
}

@Composable
private fun ThumbCell(session: ViewerSession, index: Int, selected: Boolean, onPick: (Int) -> Unit) {
    var bmp by remember(session, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index) { bmp = session.thumbnail(index) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onPick(index) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .background(Color.White)
                .then(if (selected) Modifier.border(2.dp, CyanAccent, shape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val shown = bmp
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = "${index + 1}",
            color = if (selected) CyanAccent else TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// -------------------------------------------------------------------- contents, bookmarks, attachments

@Composable
fun ContentsPanel(
    session: ViewerSession,
    currentPage: Int,
    bookmarks: List<Int>,
    initialTab: Int,
    onToggleBookmark: () -> Unit,
    onJump: (Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    var outline by remember { mutableStateOf<List<OutlineEntry>?>(null) }
    var attachments by remember { mutableStateOf<List<AttachmentInfo>?>(null) }
    var pendingAttachment by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        outline = session.loadOutline()
        attachments = session.loadAttachments()
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = pendingAttachment
        if (uri != null && name != null) {
            scope.launch {
                val bytes = session.readAttachment(name)
                if (bytes != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    BottomPanel(title = "Navigate", onClose = onClose) {
        ChoiceChips(
            options = listOf("Contents", "Bookmarks", "Attachments"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        when (tab) {
            0 -> {
                val items = outline
                if (items == null) {
                    EmptyNote("Loading table of contents")
                } else if (items.isEmpty()) {
                    EmptyNote("This document has no table of contents. Use Pages to browse it.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(items) { _, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = entry.pageIndex >= 0) { onJump(entry.pageIndex) }
                                    .padding(start = (20 + entry.level * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.title,
                                    color = if (entry.pageIndex >= 0) TextPrimary else TextMuted,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (entry.pageIndex >= 0) {
                                    Text(text = "${entry.pageIndex + 1}", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                val marked = bookmarks.contains(currentPage)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleBookmark() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (marked) Icons.Default.Delete else Icons.Default.Add,
                        contentDescription = null,
                        tint = CyanAccent
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = if (marked) "Remove bookmark from page ${currentPage + 1}" else "Bookmark page ${currentPage + 1}",
                        color = CyanAccent,
                        fontSize = 14.sp
                    )
                }
                if (bookmarks.isEmpty()) {
                    EmptyNote("No bookmarks yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(bookmarks) { _, page ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onJump(page) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Text(text = "Page ${page + 1}", color = TextPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            else -> {
                val files = attachments
                if (files == null) {
                    EmptyNote("Looking for attachments")
                } else if (files.isEmpty()) {
                    EmptyNote("This document has no attached files.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(files) { _, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = file.name, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = PdfEngine.formatFileSize(file.sizeBytes), color = TextMuted, fontSize = 12.sp)
                                }
                                IconButton(onClick = {
                                    pendingAttachment = file.name
                                    saver.launch(file.name)
                                }) {
                                    Icon(imageVector = Icons.Default.Download, contentDescription = "Save ${file.name}", tint = CyanAccent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
    )
}

// -------------------------------------------------------------------- search results

@Composable
fun SearchResultsPanel(
    hits: List<SearchHit>,
    current: Int,
    running: Boolean,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    BottomPanel(title = if (running) "Results (searching)" else "Results: ${hits.size}", onClose = onClose) {
        if (hits.isEmpty()) {
            EmptyNote(if (running) "Searching the document" else "No matches found.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(hits) { index, hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .background(if (index == current) Color(0x2238BDF8) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(text = "Page ${hit.page + 1}", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = hit.snippet, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- options

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.size(14.dp))
        Text(text = label, color = TextPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text = text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun OptionsPanel(
    state: ViewerState,
    pageCount: Int,
    onClose: () -> Unit,
    onGoTo: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    onFullscreen: () -> Unit,
    onPresent: () -> Unit
) {
    BottomPanel(title = "View options", onClose = onClose) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OptionLabel("Page layout")
            ChoiceChips(
                options = ViewMode.values().map { it.label },
                selectedIndex = state.viewMode.ordinal,
                onSelect = {
                    state.viewMode = ViewMode.values()[it]
                    state.resetZoom()
                }
            )
            OptionLabel("Zoom and rotation")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { state.zoom = (state.zoom - 0.5f).coerceAtLeast(1f); if (state.zoom <= 1f) state.resetZoom() }) {
                    Icon(imageVector = Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = TextPrimary)
                }
                Text(text = "${(state.zoom * 100f).roundToInt()}%", color = TextPrimary, fontSize = 14.sp)
                IconButton(onClick = { state.zoom = (state.zoom + 0.5f).coerceAtMost(4f) }) {
                    Icon(imageVector = Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = TextPrimary)
                }
                TextButton(onClick = { state.resetZoom() }) { Text("Fit to width", color = CyanAccent) }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { state.rotation = (state.rotation + 270) % 360 }) {
                    Icon(imageVector = Icons.Default.RotateLeft, contentDescription = "Rotate left", tint = TextPrimary)
                }
                IconButton(onClick = { state.rotation = (state.rotation + 90) % 360 }) {
                    Icon(imageVector = Icons.Default.RotateRight, contentDescription = "Rotate right", tint = TextPrimary)
                }
            }
            OptionLabel("Appearance")
            ChoiceChips(
                options = ColorMode.values().map { it.label },
                selectedIndex = state.colorMode.ordinal,
                onSelect = { state.colorMode = ColorMode.values()[it] }
            )
            OptionLabel("Reading")
            SwitchRow(
                label = "Reflow text view",
                checked = state.reflow,
                onChange = { state.reflow = it },
                description = "Shows the text only, large and easy to read. Works with screen readers."
            )
            if (state.reflow) {
                LabeledSlider(
                    label = "Text size",
                    value = state.reflowTextSize,
                    onValueChange = { state.reflowTextSize = it },
                    valueRange = 14f..36f,
                    valueText = "${state.reflowTextSize.roundToInt()} sp"
                )
            }
            ActionRow(Icons.Default.FormatListNumbered, "Go to page (1 to $pageCount)", onGoTo)
            ActionRow(Icons.Default.Slideshow, "Start slide show", onPresent)
            ActionRow(Icons.Default.Fullscreen, if (state.fullscreen) "Leave full screen" else "Full screen", onFullscreen)
            ActionRow(Icons.Default.Print, "Print", onPrint)
            ActionRow(Icons.Default.Share, "Share PDF", onShare)
            ActionRow(Icons.Default.Download, "Save a copy", onSaveCopy)
        }
    }
}

// -------------------------------------------------------------------- dialogs

@Composable
fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val number = text.toIntOrNull()
    val valid = number != null && number >= 1 && number <= pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Go to page", color = TextPrimary) },
        text = {
            AppTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
                label = "Page number (1 to $pageCount)",
                keyboardType = KeyboardType.Number
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (number != null) onGo(number - 1) }) {
                Text("Go", color = if (valid) CyanAccent else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun PrintDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onPrint: (List<Int>?, Boolean) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("") }
    var annotations by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelColor,
        title = { Text("Print", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceChips(
                    options = listOf("Whole document", "This page", "Page range"),
                    selectedIndex = mode,
                    onSelect = {
                        mode = it
                        error = null
                    }
                )
                if (mode == 2) {
                    AppTextField(
                        value = range,
                        onValueChange = { range = it },
                        label = "Pages",
                        placeholder = "For example 1-3, 7"
                    )
                }
                SwitchRow(label = "Include annotations", checked = annotations, onChange = { annotations = it })
                val problem = error
                if (problem != null) {
                    Text(text = problem, color = RadiantRose, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val pages: List<Int>? = when (mode) {
                        0 -> null
                        1 -> listOf(currentPage)
                        else -> PageRanges.parse(range, pageCount)
                    }
                    onPrint(pages, annotations)
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }) { Text("Print", color = CyanAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = PanelColor,
        title = { Text("This PDF is password protected", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color(0x40FFFFFF),
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextMuted,
                        cursorColor = CyanAccent
                    )
                )
                if (wrong) {
                    Text(text = "That password is not correct. Try again.", color = RadiantRose, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) {
                Text("Open", color = if (password.isNotEmpty()) CyanAccent else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextSecondary) }
        }
    )
}

// -------------------------------------------------------------------- reflow

@Composable
fun ReflowView(
    session: ViewerSession,
    state: ViewerState,
    topPad: Dp,
    bottomPad: Dp,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = reflowColors(state.colorMode)
    val background = colors.first
    val foreground = colors.second
    val lastIndex = max(0, session.pageCount - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, lastIndex))
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { state.currentPage = it }
    }
    LaunchedEffect(state.jumpTo) {
        val target = state.jumpTo
        if (target != null) {
            listState.scrollToItem(target.coerceIn(0, lastIndex))
            state.jumpTo = null
        }
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(background)
                .tapGestures(onTap = onTap, onDoubleTap = { }),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPad + 12.dp, bottom = bottomPad + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(count = session.pageCount, key = { it }) { i ->
                ReflowPage(session = session, index = i, color = foreground, sizeSp = state.reflowTextSize)
            }
        }
    }
}

@Composable
private fun ReflowPage(session: ViewerSession, index: Int, color: Color, sizeSp: Float) {
    val text by produceState<String?>(initialValue = null, session, index) {
        value = session.pageText(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Page ${index + 1}", color = color.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val shown = text
        if (shown == null) {
            Text(text = "Loading", color = color.copy(alpha = 0.55f), fontSize = sizeSp.sp)
        } else if (shown.isBlank()) {
            Text(
                text = "No readable text on this page. It may be a scanned image.",
                color = color.copy(alpha = 0.55f),
                fontSize = (sizeSp * 0.8f).sp
            )
        } else {
            Text(text = shown, color = color, fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.55f).sp)
        }
    }
}
