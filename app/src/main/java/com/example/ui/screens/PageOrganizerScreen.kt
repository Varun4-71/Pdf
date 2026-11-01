package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.engine.BlankPdfOptions
import com.example.engine.ConvertRouter
import com.example.engine.FileUtils
import com.example.engine.ImageInput
import com.example.engine.ImagePdfOptions
import com.example.engine.PageOps
import com.example.engine.PdfCreators
import com.example.engine.PdfEngine
import com.example.model.FileKind
import com.example.model.PageSizePreset
import com.example.model.ProcessState
import com.example.ui.components.ErrorBanner
import com.example.ui.components.PdfThumbnail
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProcessStatus
import com.example.ui.components.rememberPdfPicker
import com.example.ui.components.rememberSingleFilePicker
import com.example.ui.components.FilePickerCard
import com.example.ui.components.GlassTopBar
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private class OrgSource(val file: File, val name: String)

private data class OrgPage(val uid: Int, val source: Int, val page: Int, val rotation: Int)

@Composable
private fun ToolChip(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Color(0x30FFFFFF), shape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
        Text(text = label, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun PageCell(
    source: OrgSource,
    page: OrgPage,
    number: Int,
    selected: Boolean,
    dragging: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .alpha(if (dragging) 0.25f else 1f)
            .clickable(onClick = onToggle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) CyanAccent else Color(0x40FFFFFF), shape)
        ) {
            PdfThumbnail(
                file = source.file,
                pageIndex = page.page,
                rotation = page.rotation,
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = CyanAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(Color.White, RoundedCornerShape(50))
                )
            }
        }
        Text(
            text = number.toString(),
            color = if (selected) CyanAccent else TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** Reorder, rotate, delete, duplicate, extract and insert pages with a visual grid. */
@Composable
fun PageOrganizerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sources = remember { mutableStateListOf<OrgSource>() }
    val pages = remember { mutableStateListOf<OrgPage>() }
    val selected = remember { mutableStateListOf<Int>() }
    val history = remember { mutableStateListOf<List<OrgPage>>() }
    var nextUid by remember { mutableIntStateOf(1) }
    var docName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }
    var notice by remember { mutableStateOf<String?>(null) }
    var insertMenu by remember { mutableStateOf(false) }
    var draggingUid by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var pendingScan by remember { mutableStateOf<File?>(null) }
    val gridState = rememberLazyGridState()

    fun snapshot() {
        history.add(pages.toList())
        if (history.size > 30) history.removeAt(0)
    }

    fun undo() {
        if (history.isEmpty()) return
        val last = history.removeAt(history.size - 1)
        pages.clear()
        pages.addAll(last)
        selected.removeAll { uid -> pages.none { it.uid == uid } }
    }

    fun insertionIndex(): Int {
        if (selected.isEmpty()) return pages.size
        var last = -1
        for ((i, p) in pages.withIndex()) {
            if (selected.contains(p.uid) && i > last) last = i
        }
        return last + 1
    }

    suspend fun addPagesFrom(file: File, name: String) {
        val count = withContext(Dispatchers.IO) { PageOps.pageCountOf(file) }
        if (count <= 0) {
            state = ProcessState.Error("\"" + name + "\" could not be read. Password-protected PDFs can't be inserted.")
            return
        }
        snapshot()
        sources.add(OrgSource(file, name))
        val sourceIndex = sources.size - 1
        val at = insertionIndex()
        val added = ArrayList<OrgPage>()
        for (i in 0 until count) {
            added.add(OrgPage(nextUid, sourceIndex, i, 0))
            nextUid++
        }
        pages.addAll(at, added)
        selected.clear()
        for (p in added) selected.add(p.uid)
        notice = count.toString() + (if (count == 1) " page" else " pages") + " inserted after page " + at + ". Drag to move them."
    }

    val pickMain = rememberPdfPicker { picked ->
        scope.launch {
            loading = true
            try {
                val local = withContext(Dispatchers.IO) { FileUtils.copyToCache(context, picked.uri, "pdf", "organize") }
                val count = withContext(Dispatchers.IO) { PageOps.pageCountOf(local) }
                if (count <= 0) {
                    state = ProcessState.Error("This PDF can't be read. It may be password protected.")
                } else {
                    sources.clear()
                    pages.clear()
                    selected.clear()
                    history.clear()
                    sources.add(OrgSource(local, picked.name))
                    docName = picked.name
                    for (i in 0 until count) {
                        pages.add(OrgPage(nextUid, 0, i, 0))
                        nextUid++
                    }
                    state = ProcessState.Idle
                    notice = "Tap pages to select them. Press and hold, then drag, to move a page."
                }
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not open the file.")
            }
            loading = false
        }
    }

    val pickInsertPdf = rememberSingleFilePicker(arrayOf("application/pdf")) { uri: Uri ->
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { PdfEngine.getFileName(context, uri) }
                val local = withContext(Dispatchers.IO) { FileUtils.copyToCache(context, uri, "pdf", "insert") }
                addPagesFrom(local, name)
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not read that file.")
            }
        }
    }

    val pickInsertOther = rememberSingleFilePicker(arrayOf("*/*")) { uri: Uri ->
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { PdfEngine.getFileName(context, uri) }
                val kind = ConvertRouter.kindOf(name, context.contentResolver.getType(uri))
                if (kind == FileKind.UNSUPPORTED) {
                    state = ProcessState.Error(ConvertRouter.unsupportedReason(name))
                } else {
                    val pdf = ConvertRouter.toPdf(context, uri, name, kind)
                    addPagesFrom(pdf, name)
                }
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not convert that file.")
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val file = pendingScan
        if (ok && file != null) {
            scope.launch {
                try {
                    val pdf = PdfCreators.imagesToPdf(
                        context,
                        listOf(ImageInput(Uri.fromFile(file))),
                        ImagePdfOptions(PageSizePreset.A4, 0f, 85, 2000),
                        "Scan"
                    )
                    addPagesFrom(pdf, "Scanned page")
                } catch (e: Exception) {
                    state = ProcessState.Error(e.localizedMessage ?: "Could not use that photo.")
                }
            }
        }
    }

    fun insertBlank() {
        scope.launch {
            try {
                val blank = PdfCreators.blankPdf(context, BlankPdfOptions(PageSizePreset.A4, false, 1))
                addPagesFrom(blank, "Blank page")
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not add a blank page.")
            }
        }
    }

    fun insertFromCamera() {
        try {
            val dir = File(FileUtils.workDir(context), "scans")
            dir.mkdirs()
            val file = File(dir, "scan_" + System.currentTimeMillis() + ".jpg")
            pendingScan = file
            camera.launch(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file))
        } catch (e: Exception) {
            state = ProcessState.Error("The camera could not be started.")
        }
    }

    fun toggle(uid: Int) {
        if (selected.contains(uid)) selected.remove(uid) else selected.add(uid)
    }

    fun rotateSelected(delta: Int) {
        if (selected.isEmpty()) return
        snapshot()
        for (i in pages.indices) {
            val p = pages[i]
            if (selected.contains(p.uid)) {
                pages[i] = p.copy(rotation = (((p.rotation + delta) % 360) + 360) % 360)
            }
        }
    }

    fun deleteSelected() {
        if (selected.isEmpty()) return
        if (selected.size >= pages.size) {
            notice = "A PDF needs at least one page."
            return
        }
        snapshot()
        pages.removeAll { selected.contains(it.uid) }
        selected.clear()
    }

    fun duplicateSelected() {
        if (selected.isEmpty()) return
        snapshot()
        val result = ArrayList<OrgPage>()
        for (p in pages) {
            result.add(p)
            if (selected.contains(p.uid)) {
                result.add(p.copy(uid = nextUid))
                nextUid++
            }
        }
        pages.clear()
        pages.addAll(result)
    }

    fun buildOrder(only: Boolean): List<PageOps.PageRef> {
        val list = ArrayList<PageOps.PageRef>()
        for (p in pages) {
            if (!only || selected.contains(p.uid)) list.add(PageOps.PageRef(p.source, p.page, p.rotation))
        }
        return list
    }

    fun buildPdf(extractOnly: Boolean) {
        scope.launch {
            state = ProcessState.Processing(0.05f, "Building the PDF")
            try {
                val order = buildOrder(extractOnly)
                val base = FileUtils.baseNameOf(docName)
                val file = PageOps.rebuild(
                    context,
                    sources.map { it.file },
                    order,
                    if (extractOnly) "Extracted" else "Organized"
                ) { p, message -> state = ProcessState.Processing(p, message) }
                val name = if (extractOnly) base + "_extracted.pdf" else base + "_organized.pdf"
                state = ProcessState.Success(file, name, order.size.toString() + (if (order.size == 1) " page" else " pages"))
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not build the PDF.")
            }
        }
    }

    val busy = state is ProcessState.Processing
    val hasDoc = sources.isNotEmpty()
    val anySelected = selected.isNotEmpty()

    MeshBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "Organize pages",
                subtitle = if (hasDoc) docName else "Reorder, rotate, delete and insert",
                onBackClick = onBack,
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { undo() }) {
                            Icon(imageVector = Icons.Default.Undo, contentDescription = "Undo", tint = TextPrimary)
                        }
                    }
                }
            )
            val current = state
            if (!hasDoc) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FilePickerCard(
                        selectedFile = null,
                        onPickFile = { pickMain() },
                        title = if (loading) "Opening the PDF" else "Select a PDF to organize",
                        helperText = "You can reorder pages by dragging them"
                    )
                    if (current is ProcessState.Error) {
                        ErrorBanner(message = current.message, onDismiss = { state = ProcessState.Idle })
                    }
                }
            } else if (current is ProcessState.Success || current is ProcessState.Processing) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ProcessStatus(state = current, onReset = { state = ProcessState.Idle })
                    if (current is ProcessState.Success) {
                        Text(
                            text = "Keep editing",
                            color = CyanAccent,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clickable { state = ProcessState.Idle }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            } else {
                if (current is ProcessState.Error) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ErrorBanner(message = current.message, onDismiss = { state = ProcessState.Idle })
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolChip(
                        icon = Icons.Default.SelectAll,
                        label = if (selected.size == pages.size) "Clear" else "Select all",
                        onClick = {
                            if (selected.size == pages.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                for (p in pages) selected.add(p.uid)
                            }
                        }
                    )
                    ToolChip(Icons.Default.RotateLeft, "Left", anySelected) { rotateSelected(-90) }
                    ToolChip(Icons.Default.RotateRight, "Right", anySelected) { rotateSelected(90) }
                    ToolChip(Icons.Default.Delete, "Delete", anySelected) { deleteSelected() }
                    ToolChip(Icons.Default.ContentCopy, "Duplicate", anySelected) { duplicateSelected() }
                    ToolChip(Icons.Default.PictureAsPdf, "Extract", anySelected) { buildPdf(true) }
                    Box {
                        ToolChip(Icons.Default.Add, "Insert") { insertMenu = true }
                        DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Blank page") },
                                leadingIcon = { Icon(imageVector = Icons.Default.NoteAdd, contentDescription = null) },
                                onClick = {
                                    insertMenu = false
                                    insertBlank()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pages from another PDF") },
                                leadingIcon = { Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null) },
                                onClick = {
                                    insertMenu = false
                                    pickInsertPdf()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Word, Excel, PowerPoint, image or text") },
                                leadingIcon = { Icon(imageVector = Icons.Default.Description, contentDescription = null) },
                                onClick = {
                                    insertMenu = false
                                    pickInsertOther()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan with the camera") },
                                leadingIcon = { Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null) },
                                onClick = {
                                    insertMenu = false
                                    insertFromCamera()
                                }
                            )
                        }
                    }
                }
                val hint = notice
                Text(
                    text = if (anySelected) selected.size.toString() + " selected" else (hint ?: ""),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        var hitUid: Int? = null
                                        for (info in gridState.layoutInfo.visibleItemsInfo) {
                                            val inX = offset.x >= info.offset.x && offset.x <= info.offset.x + info.size.width
                                            val inY = offset.y >= info.offset.y && offset.y <= info.offset.y + info.size.height
                                            if (inX && inY) {
                                                hitUid = pages.getOrNull(info.index)?.uid
                                                break
                                            }
                                        }
                                        if (hitUid != null) {
                                            snapshot()
                                            draggingUid = hitUid
                                            dragPos = offset
                                        }
                                    },
                                    onDragEnd = { draggingUid = null },
                                    onDragCancel = { draggingUid = null },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPos = dragPos + amount
                                        val uid = draggingUid
                                        if (uid != null) {
                                            var target = -1
                                            for (info in gridState.layoutInfo.visibleItemsInfo) {
                                                val inX = dragPos.x >= info.offset.x && dragPos.x <= info.offset.x + info.size.width
                                                val inY = dragPos.y >= info.offset.y && dragPos.y <= info.offset.y + info.size.height
                                                if (inX && inY) {
                                                    target = info.index
                                                    break
                                                }
                                            }
                                            val from = pages.indexOfFirst { it.uid == uid }
                                            if (target >= 0 && from >= 0 && target != from && target < pages.size) {
                                                val moved = pages.removeAt(from)
                                                pages.add(target, moved)
                                            }
                                            val edge = 110f
                                            if (dragPos.y < edge) {
                                                scope.launch { gridState.scrollBy(-26f) }
                                            } else if (dragPos.y > size.height - edge) {
                                                scope.launch { gridState.scrollBy(26f) }
                                            }
                                        }
                                    }
                                )
                            },
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.uid }) { index, page ->
                            PageCell(
                                source = sources[page.source],
                                page = page,
                                number = index + 1,
                                selected = selected.contains(page.uid),
                                dragging = draggingUid == page.uid,
                                onToggle = { toggle(page.uid) }
                            )
                        }
                    }
                    val dragged = pages.firstOrNull { it.uid == draggingUid }
                    if (dragged != null) {
                        val ghostW = 96.dp
                        val ghostH = 130.dp
                        val wPx = with(density) { ghostW.toPx() }
                        val hPx = with(density) { ghostH.toPx() }
                        val ghostShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((dragPos.x - wPx / 2f).roundToInt(), (dragPos.y - hPx / 2f).roundToInt()) }
                                .size(ghostW, ghostH)
                                .alpha(0.92f)
                                .clip(ghostShape)
                                .border(2.dp, CyanAccent, ghostShape)
                        ) {
                            PdfThumbnail(
                                file = sources[dragged.source].file,
                                pageIndex = dragged.page,
                                rotation = dragged.rotation,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    PrimaryButton(
                        text = "Save PDF (" + pages.size + (if (pages.size == 1) " page)" else " pages)"),
                        icon = Icons.Default.PictureAsPdf,
                        enabled = !busy && pages.isNotEmpty(),
                        onClick = { buildPdf(false) }
                    )
                }
            }
        }
    }
}
