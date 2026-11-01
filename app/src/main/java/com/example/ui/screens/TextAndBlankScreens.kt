package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.BlankPdfOptions
import com.example.engine.FileUtils
import com.example.engine.ImageInput
import com.example.engine.ImagePdfOptions
import com.example.engine.PdfCreators
import com.example.engine.TextPdfOptions
import com.example.model.BlankTemplate
import com.example.model.PageSizePreset
import com.example.model.ProcessState
import com.example.ui.components.AppTextField
import com.example.ui.components.ChoiceChips
import com.example.ui.components.HelperText
import com.example.ui.components.LabeledSlider
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProcessStatus
import com.example.ui.components.SectionCard
import com.example.ui.components.SwitchRow
import com.example.ui.components.ToolScreenFrame
import com.example.ui.components.rememberSingleFilePicker
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val textPageSizes = listOf(PageSizePreset.A4, PageSizePreset.LETTER, PageSizePreset.A5, PageSizePreset.LEGAL)

/** Type, paste or open text and save it as a PDF. Also turns a picture on the clipboard into a PDF. */
@Composable
fun TextPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var fontIndex by remember { mutableIntStateOf(0) }
    var sizePt by remember { mutableStateOf(12f) }
    var sizeIndex by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var margin by remember { mutableStateOf(54f) }
    var clipImage by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }

    val openText = rememberSingleFilePicker(arrayOf("text/*", "application/json", "application/xml")) { uri ->
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                }
                text = content
                notice = null
            } catch (e: Exception) {
                notice = "That file could not be read as text."
            }
        }
    }

    fun paste() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            notice = "The clipboard is empty."
            clipImage = null
            return
        }
        val item = clip.getItemAt(0)
        val itemUri = item.uri
        val type = if (itemUri != null) context.contentResolver.getType(itemUri) else null
        if (itemUri != null && type != null && type.startsWith("image/")) {
            clipImage = itemUri
            notice = "A picture is on the clipboard. You can turn it into a PDF below."
        } else {
            clipImage = null
            val pasted = item.coerceToText(context).toString()
            if (pasted.isBlank()) {
                notice = "There is no text on the clipboard."
            } else {
                text = if (text.isBlank()) pasted else text + "\n" + pasted
                notice = null
            }
        }
    }

    fun create() {
        scope.launch {
            state = ProcessState.Processing(0.3f, "Creating PDF")
            try {
                val family = when (fontIndex) {
                    0 -> "sans-serif"
                    1 -> "serif"
                    else -> "monospace"
                }
                val file = PdfCreators.textToPdf(
                    context,
                    text,
                    TextPdfOptions(
                        pageSize = textPageSizes[sizeIndex],
                        landscape = landscape,
                        fontFamily = family,
                        fontSizePt = sizePt,
                        marginPt = margin
                    ),
                    "Text"
                )
                state = ProcessState.Success(file, "Text.pdf", text.length.toString() + " characters")
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not create the PDF.")
            }
        }
    }

    fun createFromClipboardImage(uri: Uri) {
        scope.launch {
            state = ProcessState.Processing(0.3f, "Creating PDF from the picture")
            try {
                val file = PdfCreators.imagesToPdf(
                    context,
                    listOf(ImageInput(uri)),
                    ImagePdfOptions(PageSizePreset.A4, 18f, 88, 2000),
                    "Clipboard"
                )
                state = ProcessState.Success(file, "Clipboard.pdf", "Created from a picture on the clipboard")
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not read the picture.")
            }
        }
    }

    val busy = state is ProcessState.Processing
    val done = state is ProcessState.Success

    ToolScreenFrame(
        title = "Text to PDF",
        subtitle = "Type, paste or open a text file",
        onBack = onBack,
        bottomBar = {
            if (!busy && !done) {
                PrimaryButton(
                    text = "Create PDF",
                    icon = Icons.Default.PictureAsPdf,
                    enabled = text.isNotBlank(),
                    onClick = { create() }
                )
            }
        }
    ) {
        item {
            SectionCard(title = "Your text") {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Text",
                    singleLine = false,
                    minLines = 8,
                    placeholder = "Type or paste here"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        text = "Paste",
                        icon = Icons.Default.ContentPaste,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        onClick = { paste() }
                    )
                    PrimaryButton(
                        text = "Open file",
                        icon = Icons.Default.FolderOpen,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        onClick = { openText() }
                    )
                }
                val note = notice
                if (note != null) HelperText(note)
                val image = clipImage
                if (image != null) {
                    PrimaryButton(
                        text = "Create PDF from the clipboard picture",
                        icon = Icons.Default.PictureAsPdf,
                        enabled = !busy,
                        onClick = { createFromClipboardImage(image) }
                    )
                }
            }
        }
        item {
            SectionCard(title = "Page") {
                ChoiceChips(
                    options = listOf("Sans serif", "Serif", "Monospace"),
                    selectedIndex = fontIndex,
                    onSelect = { fontIndex = it }
                )
                LabeledSlider(
                    label = "Text size",
                    value = sizePt,
                    onValueChange = { sizePt = it.roundToInt().toFloat() },
                    valueRange = 8f..28f,
                    valueText = sizePt.roundToInt().toString() + " pt"
                )
                ChoiceChips(
                    options = textPageSizes.map { it.label },
                    selectedIndex = sizeIndex,
                    onSelect = { sizeIndex = it }
                )
                SwitchRow(label = "Landscape", checked = landscape, onChange = { landscape = it })
                LabeledSlider(
                    label = "Margins",
                    value = margin,
                    onValueChange = { margin = it.roundToInt().toFloat() },
                    valueRange = 18f..96f,
                    valueText = margin.roundToInt().toString() + " pt"
                )
            }
        }
        item {
            ProcessStatus(state = state, onReset = { state = ProcessState.Idle })
        }
    }
}

/** Empty pages with an optional lined, grid or dotted background. */
@Composable
fun BlankPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeIndex by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var pages by remember { mutableIntStateOf(1) }
    var templateIndex by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }
    val sizes = PageSizePreset.values()
    val templates = BlankTemplate.values()

    fun create() {
        scope.launch {
            state = ProcessState.Processing(0.4f, "Creating pages")
            try {
                val file = PdfCreators.blankPdf(
                    context,
                    BlankPdfOptions(sizes[sizeIndex], landscape, pages, templates[templateIndex])
                )
                state = ProcessState.Success(file, "Blank.pdf", pages.toString() + (if (pages == 1) " page" else " pages") + ", " + sizes[sizeIndex].label)
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not create the PDF.")
            }
        }
    }

    val busy = state is ProcessState.Processing
    val done = state is ProcessState.Success

    ToolScreenFrame(
        title = "Blank PDF",
        subtitle = "Plain, lined, grid or dotted pages",
        onBack = onBack,
        bottomBar = {
            if (!busy && !done) {
                PrimaryButton(text = "Create PDF", icon = Icons.Default.PictureAsPdf, onClick = { create() })
            }
        }
    ) {
        item {
            SectionCard(title = "Page") {
                ChoiceChips(
                    options = sizes.map { it.label },
                    selectedIndex = sizeIndex,
                    onSelect = { sizeIndex = it }
                )
                SwitchRow(label = "Landscape", checked = landscape, onChange = { landscape = it })
                ChoiceChips(
                    options = templates.map { it.label },
                    selectedIndex = templateIndex,
                    onSelect = { templateIndex = it }
                )
            }
        }
        item {
            SectionCard(title = "Number of pages") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = { if (pages > 1) pages-- }) {
                        Icon(imageVector = Icons.Default.Remove, contentDescription = "Fewer pages", tint = CyanAccent)
                    }
                    Text(text = pages.toString(), color = TextPrimary, fontSize = 22.sp)
                    IconButton(onClick = { if (pages < 200) pages++ }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "More pages", tint = CyanAccent)
                    }
                }
            }
        }
        item {
            ProcessStatus(state = state, onReset = { state = ProcessState.Idle })
        }
    }
}
