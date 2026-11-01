package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.BackgroundSpec
import com.example.engine.FileUtils
import com.example.engine.HeaderFooterSpec
import com.example.engine.LayerKind
import com.example.engine.OutputFile
import com.example.engine.PageOps
import com.example.engine.PageRanges
import com.example.engine.PdfEngine
import com.example.engine.WatermarkSpec
import com.example.model.PageSizePreset
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.components.AppTextField
import com.example.ui.components.ChoiceChips
import com.example.ui.components.FilePickerCard
import com.example.ui.components.HelperText
import com.example.ui.components.LabeledSlider
import com.example.ui.components.OutputFilesCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProcessStatus
import com.example.ui.components.ScrollFrame
import com.example.ui.components.SectionCard
import com.example.ui.components.SwitchRow
import com.example.ui.components.rememberPdfPicker
import com.example.ui.components.rememberSingleFilePicker
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private class ToolEnv(
    val context: Context,
    val file: File,
    val name: String,
    val pageCount: Int,
    val busy: Boolean,
    val start: (String, suspend () -> ProcessState.Success) -> Unit,
    val startMany: (String, suspend () -> List<OutputFile>) -> Unit
) {
    val baseName: String
        get() = FileUtils.baseNameOf(name)
}

/** Which pages an edit applies to: everything, or pages typed like "1-3, 7". */
private class PageScopeState {
    var all by mutableStateOf(true)
    var text by mutableStateOf("")

    fun resolve(total: Int): List<Int> {
        return if (all) (0 until total).toList() else PageRanges.parse(text, total)
    }

    fun first(total: Int): Int {
        if (all) return 0
        val r = PageRanges.parseOrEmpty(text, total)
        return if (r.isEmpty()) 0 else r[0]
    }
}

@Composable
private fun PageScopeCard(scope: PageScopeState, pageCount: Int) {
    SectionCard(title = "Pages to change") {
        ChoiceChips(
            options = listOf("All " + pageCount + (if (pageCount == 1) " page" else " pages"), "Choose pages"),
            selectedIndex = if (scope.all) 0 else 1,
            onSelect = { scope.all = it == 0 }
        )
        if (!scope.all) {
            AppTextField(
                value = scope.text,
                onValueChange = { scope.text = it },
                label = "Pages",
                placeholder = "For example 1-3, 7, 10-end"
            )
        }
    }
}

private val swatchColors = listOf(
    0xFFFFFF, 0xFFF8E7, 0xE9EDF2, 0xDCEBFF, 0xE3F6E8, 0xFDE4EC, 0xFFF3B0, 0x808080, 0xC62828, 0x1565C0, 0x1E293B, 0x000000
)

@Composable
private fun Swatches(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        swatchColors.forEachIndexed { index, rgb ->
            val chosen = index == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF000000.toInt() or rgb))
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) CyanAccent else Color(0x66FFFFFF), CircleShape)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                if (chosen) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected colour",
                        tint = if (rgb == 0xFFFFFF || rgb == 0xFFF8E7 || rgb == 0xE9EDF2 || rgb == 0xFFF3B0) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PageToolsScreen(initialTool: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toolIds = listOf("crop", "resize", "split", "extract", "replace", "background", "watermark", "headerfooter", "transitions")
    val toolNames = listOf("Crop", "Resize", "Split", "Extract or delete", "Replace", "Background", "Watermark", "Header and footer", "Transitions")
    var toolIndex by remember { mutableIntStateOf(max(0, toolIds.indexOf(initialTool))) }
    var picked by remember { mutableStateOf<SelectedFile?>(null) }
    var local by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }
    var outputs by remember { mutableStateOf<List<OutputFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val pick = rememberPdfPicker { sel ->
        scope.launch {
            loading = true
            state = ProcessState.Idle
            outputs = emptyList()
            try {
                val copy = withContext(Dispatchers.IO) { FileUtils.copyToCache(context, sel.uri, "pdf", "tool") }
                val count = withContext(Dispatchers.IO) { PageOps.pageCountOf(copy) }
                if (count <= 0) {
                    picked = null
                    local = null
                    pageCount = 0
                    state = ProcessState.Error("This PDF can't be read. It may be password protected.")
                } else {
                    picked = sel
                    local = copy
                    pageCount = count
                }
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not open the file.")
            }
            loading = false
        }
    }

    val runner: (String, suspend () -> ProcessState.Success) -> Unit = { message, block ->
        scope.launch {
            outputs = emptyList()
            state = ProcessState.Processing(0.3f, message)
            try {
                state = block()
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Something went wrong")
            }
        }
    }
    val manyRunner: (String, suspend () -> List<OutputFile>) -> Unit = { message, block ->
        scope.launch {
            outputs = emptyList()
            state = ProcessState.Processing(0.3f, message)
            try {
                outputs = block()
                state = ProcessState.Idle
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Something went wrong")
            }
        }
    }

    ScrollFrame(title = "Page tools", subtitle = toolNames[toolIndex], onBack = onBack) {
        FilePickerCard(
            selectedFile = picked?.copy(pageCount = pageCount),
            onPickFile = { pick() },
            onRemoveFile = {
                picked = null
                local = null
                pageCount = 0
                state = ProcessState.Idle
                outputs = emptyList()
            },
            title = if (loading) "Opening the PDF" else "Select a PDF",
            helperText = "Everything is done on your phone"
        )
        ChoiceChips(
            options = toolNames,
            selectedIndex = toolIndex,
            onSelect = {
                toolIndex = it
                state = ProcessState.Idle
                outputs = emptyList()
            }
        )
        val file = local
        val sel = picked
        if (file != null && sel != null) {
            val env = ToolEnv(context, file, sel.name, pageCount, state is ProcessState.Processing, runner, manyRunner)
            when (toolIds[toolIndex]) {
                "crop" -> CropTool(env)
                "resize" -> ResizeTool(env)
                "split" -> SplitTool(env)
                "extract" -> ExtractTool(env)
                "replace" -> ReplaceTool(env)
                "background" -> BackgroundTool(env)
                "watermark" -> WatermarkTool(env)
                "headerfooter" -> HeaderFooterTool(env)
                else -> TransitionsTool(env)
            }
        } else {
            HelperText("Choose a PDF first, then pick what you want to do with its pages.")
        }
        ProcessStatus(state = state, onReset = { state = ProcessState.Idle })
        if (outputs.isNotEmpty()) {
            OutputFilesCard(
                title = outputs.size.toString() + " files created",
                outputs = outputs,
                onReset = { outputs = emptyList() }
            )
        }
    }
}

// -------------------------------------------------------------------- crop

@Composable
private fun CropPreview(file: File, pageIndex: Int, l: Float, t: Float, r: Float, b: Float) {
    var bmp by remember(file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, pageIndex) {
        bmp = PdfEngine.renderPageThumbnailFromFile(file, pageIndex, 640)
    }
    val shown = bmp
    if (shown == null) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyanAccent)
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(shown.width.toFloat() / shown.height.toFloat())
                .background(Color.White)
        ) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Preview of page " + (pageIndex + 1),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dim = Color(0xB3000000)
                val w = size.width
                val h = size.height
                drawRect(dim, Offset(0f, 0f), Size(w * l, h))
                drawRect(dim, Offset(w * (1f - r), 0f), Size(w * r, h))
                drawRect(dim, Offset(w * l, 0f), Size(w * (1f - l - r), h * t))
                drawRect(dim, Offset(w * l, h * (1f - b)), Size(w * (1f - l - r), h * b))
            }
        }
    }
}

@Composable
private fun ColumnScope.CropTool(env: ToolEnv) {
    var left by remember { mutableFloatStateOf(0f) }
    var top by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    var bottom by remember { mutableFloatStateOf(0f) }
    val pages = remember { PageScopeState() }
    SectionCard(title = "Preview") {
        CropPreview(env.file, pages.first(env.pageCount), left / 100f, top / 100f, right / 100f, bottom / 100f)
        HelperText("The darker area will be removed.")
    }
    SectionCard(title = "Trim margins") {
        LabeledSlider("Left", left, { left = it }, 0f..40f, left.roundToInt().toString() + "%")
        LabeledSlider("Top", top, { top = it }, 0f..40f, top.roundToInt().toString() + "%")
        LabeledSlider("Right", right, { right = it }, 0f..40f, right.roundToInt().toString() + "%")
        LabeledSlider("Bottom", bottom, { bottom = it }, 0f..40f, bottom.roundToInt().toString() + "%")
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = "Crop pages",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy && (left + top + right + bottom) > 0f,
        onClick = {
            env.start("Cropping pages") {
                val indices = pages.resolve(env.pageCount)
                val out = PageOps.cropPages(env.context, env.file, indices, left / 100f, top / 100f, right / 100f, bottom / 100f)
                ProcessState.Success(out, env.baseName + "_cropped.pdf", indices.size.toString() + " pages cropped")
            }
        }
    )
}

// -------------------------------------------------------------------- resize

@Composable
private fun ColumnScope.ResizeTool(env: ToolEnv) {
    val presets = PageSizePreset.values()
    var sizeIndex by remember { mutableIntStateOf(0) }
    var customW by remember { mutableStateOf("210") }
    var customH by remember { mutableStateOf("297") }
    val pages = remember { PageScopeState() }
    val custom = sizeIndex == presets.size
    SectionCard(title = "New page size") {
        ChoiceChips(
            options = presets.map { it.label } + "Custom",
            selectedIndex = sizeIndex,
            onSelect = { sizeIndex = it }
        )
        if (custom) {
            AppTextField(customW, { customW = it }, "Width (mm)", keyboardType = KeyboardType.Number)
            AppTextField(customH, { customH = it }, "Height (mm)", keyboardType = KeyboardType.Number)
        }
        HelperText("Content is scaled to fit and centred. Landscape pages stay landscape.")
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = "Resize pages",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy,
        onClick = {
            env.start("Resizing pages") {
                val widthPt: Float
                val heightPt: Float
                if (custom) {
                    val wMm = customW.toFloatOrNull()
                    val hMm = customH.toFloatOrNull()
                    if (wMm == null || hMm == null || wMm < 20f || hMm < 20f) {
                        throw IllegalArgumentException("Enter a width and height of at least 20 mm.")
                    }
                    widthPt = wMm * 72f / 25.4f
                    heightPt = hMm * 72f / 25.4f
                } else {
                    widthPt = presets[sizeIndex].widthPt.toFloat()
                    heightPt = presets[sizeIndex].heightPt.toFloat()
                }
                val indices = pages.resolve(env.pageCount)
                val out = PageOps.resizePages(env.context, env.file, indices, widthPt, heightPt)
                ProcessState.Success(out, env.baseName + "_resized.pdf", indices.size.toString() + " pages resized")
            }
        }
    )
}

// -------------------------------------------------------------------- split

@Composable
private fun ColumnScope.SplitTool(env: ToolEnv) {
    var mode by remember { mutableIntStateOf(0) }
    var every by remember { mutableStateOf("5") }
    var ranges by remember { mutableStateOf("1-3, 4-6") }
    SectionCard(title = "Split into several PDFs") {
        ChoiceChips(
            options = listOf("Every N pages", "Custom ranges", "Each page"),
            selectedIndex = mode,
            onSelect = { mode = it }
        )
        if (mode == 0) {
            AppTextField(every, { every = it.filter { c -> c.isDigit() }.take(4) }, "Pages in each file", keyboardType = KeyboardType.Number)
        } else if (mode == 1) {
            AppTextField(ranges, { ranges = it }, "Ranges, one file each", placeholder = "For example 1-3, 4-6, 7-end")
        }
        HelperText("This document has " + env.pageCount + (if (env.pageCount == 1) " page." else " pages."))
    }
    PrimaryButton(
        text = "Split PDF",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy,
        onClick = {
            env.startMany("Splitting the PDF") {
                val total = env.pageCount
                val groups: List<List<Int>> = when (mode) {
                    0 -> {
                        val n = every.toIntOrNull()
                        if (n == null || n < 1) throw IllegalArgumentException("Enter how many pages each file should have.")
                        (0 until total).toList().chunked(n)
                    }
                    1 -> PageRanges.parseGroups(ranges, total)
                    else -> (0 until total).map { listOf(it) }
                }
                if (groups.size > 300) {
                    throw IllegalArgumentException("That would make " + groups.size + " files. Use larger parts.")
                }
                PageOps.splitPages(env.context, env.file, groups, env.baseName)
            }
        }
    )
}

// -------------------------------------------------------------------- extract / delete

@Composable
private fun ColumnScope.ExtractTool(env: ToolEnv) {
    var mode by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("1") }
    SectionCard(title = "Choose pages") {
        ChoiceChips(
            options = listOf("Extract these pages", "Delete these pages"),
            selectedIndex = mode,
            onSelect = { mode = it }
        )
        AppTextField(text, { text = it }, "Pages", placeholder = "For example 1-3, 7, 10-end")
        HelperText(
            if (mode == 0) {
                "The chosen pages are copied into a new PDF. The original is not changed."
            } else {
                "The chosen pages are removed in a new copy. The original is not changed."
            }
        )
    }
    PrimaryButton(
        text = if (mode == 0) "Extract pages" else "Delete pages",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy,
        onClick = {
            env.start(if (mode == 0) "Extracting pages" else "Deleting pages") {
                val indices = PageRanges.parse(text, env.pageCount)
                if (mode == 0) {
                    val out = PageOps.extractPages(env.context, env.file, indices)
                    ProcessState.Success(out, env.baseName + "_extracted.pdf", indices.size.toString() + " pages extracted")
                } else {
                    val out = PageOps.deletePages(env.context, env.file, indices)
                    ProcessState.Success(out, env.baseName + "_trimmed.pdf", indices.size.toString() + " pages deleted")
                }
            }
        }
    )
}

// -------------------------------------------------------------------- replace

@Composable
private fun ColumnScope.ReplaceTool(env: ToolEnv) {
    val scope = rememberCoroutineScope()
    var other by remember { mutableStateOf<File?>(null) }
    var otherName by remember { mutableStateOf("") }
    var otherPages by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf("1") }
    var source by remember { mutableStateOf("1") }
    var problem by remember { mutableStateOf<String?>(null) }
    val pickOther = rememberPdfPicker { sel ->
        scope.launch {
            try {
                val copy = withContext(Dispatchers.IO) { FileUtils.copyToCache(env.context, sel.uri, "pdf", "replace") }
                val count = withContext(Dispatchers.IO) { PageOps.pageCountOf(copy) }
                if (count <= 0) {
                    problem = "That PDF can't be read. It may be password protected."
                } else {
                    other = copy
                    otherName = sel.name
                    otherPages = count
                    problem = null
                }
            } catch (e: Exception) {
                problem = e.localizedMessage ?: "Could not open that file."
            }
        }
    }
    SectionCard(title = "Replacement pages") {
        PrimaryButton(
            text = if (other == null) "Choose the other PDF" else "Using " + otherName + " (" + otherPages + " pages)",
            icon = Icons.Default.PictureAsPdf,
            isPrimary = false,
            onClick = { pickOther() }
        )
        AppTextField(target, { target = it }, "Pages to replace in this PDF", placeholder = "For example 2-3")
        AppTextField(source, { source = it }, "Use these pages from the other PDF", placeholder = "For example 1-2")
        val note = problem
        if (note != null) Text(text = note, color = TextMuted, fontSize = 13.sp)
        HelperText("The new pages are placed where the first replaced page was. The number of pages may change.")
    }
    PrimaryButton(
        text = "Replace pages",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy && other != null,
        onClick = {
            val second = other
            if (second != null) {
                env.start("Replacing pages") {
                    val targetIdx = PageRanges.parse(target, env.pageCount)
                    val sourceIdx = PageRanges.parse(source, otherPages)
                    val out = PageOps.replacePages(env.context, env.file, env.pageCount, targetIdx, second, sourceIdx)
                    ProcessState.Success(out, env.baseName + "_replaced.pdf", targetIdx.size.toString() + " pages replaced with " + sourceIdx.size)
                }
            }
        }
    )
}

// -------------------------------------------------------------------- background

@Composable
private fun ColumnScope.BackgroundTool(env: ToolEnv) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var colorIndex by remember { mutableIntStateOf(1) }
    var opacity by remember { mutableFloatStateOf(100f) }
    var image by remember { mutableStateOf<File?>(null) }
    val pages = remember { PageScopeState() }
    val pickImage = rememberSingleFilePicker(arrayOf("image/*")) { uri ->
        scope.launch {
            image = withContext(Dispatchers.IO) { FileUtils.copyToCache(env.context, uri, "img", "background") }
        }
    }
    SectionCard(title = "Background") {
        ChoiceChips(options = listOf("Colour", "Picture"), selectedIndex = mode, onSelect = { mode = it })
        if (mode == 0) {
            Swatches(selected = colorIndex, onSelect = { colorIndex = it })
        } else {
            PrimaryButton(
                text = if (image == null) "Choose a picture" else "Picture chosen. Tap to change",
                icon = Icons.Default.Image,
                isPrimary = false,
                onClick = { pickImage() }
            )
        }
        LabeledSlider("Strength", opacity, { opacity = it }, 10f..100f, opacity.roundToInt().toString() + "%")
        HelperText("The background goes behind the existing content of each page.")
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = "Add background",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy && (mode == 0 || image != null),
        onClick = {
            env.start("Adding the background") {
                val indices = pages.resolve(env.pageCount)
                val spec = if (mode == 0) {
                    BackgroundSpec(colorRgb = swatchColors[colorIndex], opacity = opacity / 100f)
                } else {
                    BackgroundSpec(imageFile = image, opacity = opacity / 100f)
                }
                val out = PageOps.addBackground(env.context, env.file, indices, spec)
                ProcessState.Success(out, env.baseName + "_background.pdf", "Background added to " + indices.size + " pages")
            }
        }
    )
    PrimaryButton(
        text = "Remove backgrounds",
        isPrimary = false,
        enabled = !env.busy,
        onClick = {
            env.start("Removing backgrounds") {
                val indices = pages.resolve(env.pageCount)
                val result = PageOps.removeLayers(env.context, env.file, indices, setOf(LayerKind.BACKGROUND), false)
                if (result.second == 0) {
                    throw IllegalArgumentException("No backgrounds added by this app were found on those pages.")
                }
                ProcessState.Success(result.first, env.baseName + "_no_background.pdf", result.second.toString() + " backgrounds removed")
            }
        }
    )
}

// -------------------------------------------------------------------- watermark

@Composable
private fun ColumnScope.WatermarkTool(env: ToolEnv) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var fontSize by remember { mutableFloatStateOf(64f) }
    var colorIndex by remember { mutableIntStateOf(7) }
    var opacity by remember { mutableFloatStateOf(30f) }
    var angleIndex by remember { mutableIntStateOf(2) }
    var tiled by remember { mutableStateOf(false) }
    var imageScale by remember { mutableFloatStateOf(50f) }
    var image by remember { mutableStateOf<File?>(null) }
    val pages = remember { PageScopeState() }
    val angles = listOf(0f, 30f, 45f, 90f, -45f)
    val pickImage = rememberSingleFilePicker(arrayOf("image/*")) { uri ->
        scope.launch {
            image = withContext(Dispatchers.IO) { FileUtils.copyToCache(env.context, uri, "img", "watermark") }
        }
    }
    SectionCard(title = "Watermark") {
        ChoiceChips(options = listOf("Text", "Picture"), selectedIndex = mode, onSelect = { mode = it })
        if (mode == 0) {
            AppTextField(text, { text = it }, "Text")
            LabeledSlider("Text size", fontSize, { fontSize = it }, 16f..140f, fontSize.roundToInt().toString() + " pt")
            Swatches(selected = colorIndex, onSelect = { colorIndex = it })
            SwitchRow(label = "Repeat across the page", checked = tiled, onChange = { tiled = it })
        } else {
            PrimaryButton(
                text = if (image == null) "Choose a picture" else "Picture chosen. Tap to change",
                icon = Icons.Default.Image,
                isPrimary = false,
                onClick = { pickImage() }
            )
            LabeledSlider("Picture width", imageScale, { imageScale = it }, 10f..100f, imageScale.roundToInt().toString() + "% of the page")
        }
        LabeledSlider("Strength", opacity, { opacity = it }, 5f..100f, opacity.roundToInt().toString() + "%")
        ChoiceChips(
            options = angles.map { it.roundToInt().toString() + "\u00B0" },
            selectedIndex = angleIndex,
            onSelect = { angleIndex = it }
        )
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = "Add watermark",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy && (if (mode == 0) text.isNotBlank() else image != null),
        onClick = {
            env.start("Adding the watermark") {
                val indices = pages.resolve(env.pageCount)
                val spec = if (mode == 0) {
                    WatermarkSpec(
                        text = text,
                        fontSize = fontSize,
                        colorRgb = swatchColors[colorIndex],
                        opacity = opacity / 100f,
                        angleDeg = angles[angleIndex],
                        tiled = tiled
                    )
                } else {
                    WatermarkSpec(
                        imageFile = image,
                        opacity = opacity / 100f,
                        angleDeg = angles[angleIndex],
                        imageScale = imageScale / 100f
                    )
                }
                val out = PageOps.addWatermark(env.context, env.file, indices, spec)
                ProcessState.Success(out, env.baseName + "_watermarked.pdf", "Watermark added to " + indices.size + " pages")
            }
        }
    )
    PrimaryButton(
        text = "Remove watermarks",
        isPrimary = false,
        enabled = !env.busy,
        onClick = {
            env.start("Removing watermarks") {
                val indices = pages.resolve(env.pageCount)
                val result = PageOps.removeLayers(env.context, env.file, indices, setOf(LayerKind.WATERMARK), true)
                if (result.second == 0) {
                    throw IllegalArgumentException(
                        "No removable watermark was found. This app removes watermarks it added and standard watermark marks. " +
                            "A watermark that is part of a page picture can't be removed."
                    )
                }
                ProcessState.Success(result.first, env.baseName + "_no_watermark.pdf", result.second.toString() + " watermarks removed")
            }
        }
    )
}

// -------------------------------------------------------------------- header and footer

@Composable
private fun ColumnScope.HeaderFooterTool(env: ToolEnv) {
    var headerLeft by remember { mutableStateOf("") }
    var headerCenter by remember { mutableStateOf("") }
    var headerRight by remember { mutableStateOf("") }
    var footerLeft by remember { mutableStateOf("") }
    var footerCenter by remember { mutableStateOf("Page {page} of {total}") }
    var footerRight by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(10f) }
    var margin by remember { mutableFloatStateOf(28f) }
    var colorIndex by remember { mutableIntStateOf(10) }
    var skipFirst by remember { mutableStateOf(false) }
    var startNumber by remember { mutableStateOf("1") }
    val pages = remember { PageScopeState() }
    SectionCard(title = "Quick start") {
        ChoiceChips(
            options = listOf("Page X of Y", "Page number", "Date and file name"),
            selectedIndex = -1,
            onSelect = { choice ->
                headerLeft = ""
                headerCenter = ""
                headerRight = ""
                footerLeft = ""
                footerCenter = ""
                footerRight = ""
                when (choice) {
                    0 -> footerCenter = "Page {page} of {total}"
                    1 -> footerRight = "{page}"
                    else -> {
                        headerLeft = "{name}"
                        headerRight = "{date}"
                    }
                }
            }
        )
        HelperText("In your own text, {page} is the page number, {total} the page count, {date} today's date and {name} the file name.")
    }
    SectionCard(title = "Header") {
        AppTextField(headerLeft, { headerLeft = it }, "Left")
        AppTextField(headerCenter, { headerCenter = it }, "Centre")
        AppTextField(headerRight, { headerRight = it }, "Right")
    }
    SectionCard(title = "Footer") {
        AppTextField(footerLeft, { footerLeft = it }, "Left")
        AppTextField(footerCenter, { footerCenter = it }, "Centre")
        AppTextField(footerRight, { footerRight = it }, "Right")
    }
    SectionCard(title = "Style") {
        LabeledSlider("Text size", fontSize, { fontSize = it }, 7f..18f, fontSize.roundToInt().toString() + " pt")
        LabeledSlider("Distance from the edge", margin, { margin = it }, 12f..72f, margin.roundToInt().toString() + " pt")
        Swatches(selected = colorIndex, onSelect = { colorIndex = it })
        SwitchRow(label = "Skip the first page", checked = skipFirst, onChange = { skipFirst = it })
        AppTextField(startNumber, { startNumber = it.filter { c -> c.isDigit() }.take(5) }, "First page number", keyboardType = KeyboardType.Number)
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = "Add header and footer",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy,
        onClick = {
            env.start("Adding header and footer") {
                val indices = pages.resolve(env.pageCount)
                val spec = HeaderFooterSpec(
                    headerLeft = headerLeft,
                    headerCenter = headerCenter,
                    headerRight = headerRight,
                    footerLeft = footerLeft,
                    footerCenter = footerCenter,
                    footerRight = footerRight,
                    fontSize = fontSize,
                    margin = margin,
                    colorRgb = swatchColors[colorIndex],
                    skipFirstPage = skipFirst,
                    startNumber = startNumber.toIntOrNull() ?: 1
                )
                val out = PageOps.addHeaderFooter(env.context, env.file, indices, spec, env.baseName)
                ProcessState.Success(out, env.baseName + "_stamped.pdf", "Added to " + indices.size + " pages")
            }
        }
    )
    PrimaryButton(
        text = "Remove headers and footers",
        isPrimary = false,
        enabled = !env.busy,
        onClick = {
            env.start("Removing headers and footers") {
                val indices = pages.resolve(env.pageCount)
                val result = PageOps.removeLayers(env.context, env.file, indices, setOf(LayerKind.HEADER_FOOTER), false)
                if (result.second == 0) {
                    throw IllegalArgumentException("No headers or footers added by this app were found on those pages.")
                }
                ProcessState.Success(result.first, env.baseName + "_clean.pdf", result.second.toString() + " items removed")
            }
        }
    )
}

// -------------------------------------------------------------------- transitions

@Composable
private fun ColumnScope.TransitionsTool(env: ToolEnv) {
    val styles = listOf("None", "Fade", "Dissolve", "Wipe", "Push", "Cover", "Uncover", "Split", "Blinds", "Box", "Fly", "Glitter")
    var styleIndex by remember { mutableIntStateOf(1) }
    var duration by remember { mutableFloatStateOf(1f) }
    var auto by remember { mutableStateOf(false) }
    var autoSeconds by remember { mutableFloatStateOf(5f) }
    val pages = remember { PageScopeState() }
    SectionCard(title = "Transition between pages") {
        ChoiceChips(options = styles, selectedIndex = styleIndex, onSelect = { styleIndex = it })
        if (styleIndex > 0) {
            LabeledSlider("Speed", duration, { duration = (it * 10f).roundToInt() / 10f }, 0.3f..3f, "%.1f s".format(duration))
        }
        SwitchRow(
            label = "Move on automatically",
            checked = auto,
            onChange = { auto = it },
            description = "Each page stays on screen for a set time during a slide show."
        )
        if (auto) {
            LabeledSlider("Time on each page", autoSeconds, { autoSeconds = it.roundToInt().toFloat() }, 1f..60f, autoSeconds.roundToInt().toString() + " s")
        }
        HelperText(
            "Transitions play in full-screen presentation mode, in this app's slide show and in viewers such as Adobe Acrobat. " +
                "Choose None to remove them."
        )
    }
    PageScopeCard(pages, env.pageCount)
    PrimaryButton(
        text = if (styleIndex == 0) "Remove transitions" else "Apply transitions",
        icon = Icons.Default.PictureAsPdf,
        enabled = !env.busy,
        onClick = {
            env.start("Updating pages") {
                val indices = pages.resolve(env.pageCount)
                val style: String? = if (styleIndex == 0) null else styles[styleIndex]
                val wait: Float? = if (auto && styleIndex >= 0) autoSeconds else null
                val out = PageOps.setTransitions(env.context, env.file, indices, style, duration, wait)
                ProcessState.Success(
                    out,
                    env.baseName + "_slides.pdf",
                    if (style == null) "Transitions removed from " + indices.size + " pages" else style + " transition set on " + indices.size + " pages"
                )
            }
        }
    )
    HelperText("Open the result in this app and choose More, then Start slide show, to preview it.")
}

