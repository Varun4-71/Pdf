package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ConvertRouter
import com.example.engine.FileUtils
import com.example.engine.OutputFile
import com.example.engine.PageOps
import com.example.engine.PdfEngine
import com.example.model.FileKind
import com.example.model.ImagePdfOptionsHolder
import com.example.model.PageSizePreset
import com.example.model.ProcessState
import com.example.model.SourceItem
import com.example.ui.components.ChoiceChips
import com.example.ui.components.FileRow
import com.example.ui.components.HelperText
import com.example.ui.components.OutputFilesCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProcessStatus
import com.example.ui.components.SectionCard
import com.example.ui.components.SwitchRow
import com.example.ui.components.ToolScreenFrame
import com.example.ui.components.rememberFilesPicker
import com.example.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun iconFor(kind: FileKind): ImageVector {
    return when (kind) {
        FileKind.WORD -> Icons.Default.Description
        FileKind.EXCEL -> Icons.Default.TableChart
        FileKind.POWERPOINT -> Icons.Default.Slideshow
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.TEXT -> Icons.Default.TextFields
        else -> Icons.Default.PictureAsPdf
    }
}

/** Turns Word, Excel, PowerPoint, images, text and PDF files into PDFs, or joins them into one. */
@Composable
fun CreateFromFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<SourceItem>() }
    var combine by remember { mutableStateOf(true) }
    var imageSize by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }
    var outputs by remember { mutableStateOf<List<OutputFile>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val pickFiles = rememberFilesPicker(arrayOf("*/*")) { uris ->
        scope.launch {
            val skipped = ArrayList<String>()
            for (uri in uris) {
                val name = withContext(Dispatchers.IO) { PdfEngine.getFileName(context, uri) }
                val mime = context.contentResolver.getType(uri)
                val size = withContext(Dispatchers.IO) { PdfEngine.getFileSize(context, uri) }
                val kind = ConvertRouter.kindOf(name, mime)
                if (kind == FileKind.UNSUPPORTED) {
                    skipped.add(name + ": " + ConvertRouter.unsupportedReason(name))
                } else {
                    items.add(SourceItem(uri, name, kind, size))
                }
            }
            notice = if (skipped.isEmpty()) null else skipped.joinToString("\n")
        }
    }

    fun reset() {
        state = ProcessState.Idle
        outputs = emptyList()
    }

    fun startCreate() {
        scope.launch {
            outputs = emptyList()
            state = ProcessState.Processing(0.02f, "Starting")
            try {
                val holder = when (imageSize) {
                    0 -> ImagePdfOptionsHolder(PageSizePreset.A4)
                    1 -> ImagePdfOptionsHolder(PageSizePreset.LETTER)
                    else -> ImagePdfOptionsHolder(null)
                }
                val made = ArrayList<OutputFile>()
                for ((i, item) in items.withIndex()) {
                    state = ProcessState.Processing(0.05f + 0.8f * i / items.size, "Converting " + item.name)
                    val file = ConvertRouter.toPdf(context, item.uri, item.name, item.kind, holder)
                    made.add(OutputFile(file, FileUtils.safeName(FileUtils.baseNameOf(item.name)) + ".pdf"))
                }
                if (made.size == 1) {
                    val one = made[0]
                    state = ProcessState.Success(one.file, one.name, "Created from " + items[0].name)
                } else if (combine) {
                    state = ProcessState.Processing(0.9f, "Combining " + made.size + " files")
                    val merged = PageOps.mergeFiles(context, made.map { it.file }, "Combined")
                    val pages = PageOps.pageCountOf(merged)
                    state = ProcessState.Success(merged, "Combined.pdf", made.size.toString() + " files combined, " + pages + " pages")
                } else {
                    outputs = made
                    state = ProcessState.Idle
                }
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Something went wrong")
            }
        }
    }

    val busy = state is ProcessState.Processing
    val finished = state is ProcessState.Success || outputs.isNotEmpty()
    val hasImages = items.any { it.kind == FileKind.IMAGE }

    ToolScreenFrame(
        title = "Create PDF",
        subtitle = "Word, Excel, PowerPoint, images, text, PDF",
        onBack = onBack,
        bottomBar = {
            if (!busy && !finished) {
                PrimaryButton(
                    text = if (items.size > 1 && !combine) "Create " + items.size + " PDFs" else "Create PDF",
                    icon = Icons.Default.PictureAsPdf,
                    enabled = items.isNotEmpty(),
                    onClick = { startCreate() }
                )
            }
        }
    ) {
        item {
            SectionCard(title = "Files") {
                if (items.isEmpty()) {
                    HelperText("Choose the files to convert. You can mix types, for example a Word report and a few photos.")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items.forEachIndexed { index, item ->
                        FileRow(
                            name = item.name,
                            detail = item.kind.label + ", " + PdfEngine.formatFileSize(item.sizeBytes),
                            icon = iconFor(item.kind),
                            onRemove = { items.removeAt(index) },
                            onMoveUp = if (index > 0) {
                                {
                                    val moved = items.removeAt(index)
                                    items.add(index - 1, moved)
                                }
                            } else {
                                null
                            },
                            onMoveDown = if (index < items.size - 1) {
                                {
                                    val moved = items.removeAt(index)
                                    items.add(index + 1, moved)
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
                PrimaryButton(
                    text = if (items.isEmpty()) "Choose files" else "Add more files",
                    icon = Icons.Default.Add,
                    isPrimary = false,
                    onClick = { pickFiles() }
                )
                val note = notice
                if (note != null) {
                    Text(text = note, color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        if (items.size > 1) {
            item {
                SectionCard(title = "Output") {
                    SwitchRow(
                        label = "Combine into one PDF",
                        checked = combine,
                        onChange = { combine = it },
                        description = "Files are joined in the order shown above. Turn off to get one PDF per file."
                    )
                }
            }
        }
        if (hasImages) {
            item {
                SectionCard(title = "Image pages") {
                    ChoiceChips(
                        options = listOf("A4", "Letter", "Match the image"),
                        selectedIndex = imageSize,
                        onSelect = { imageSize = it }
                    )
                }
            }
        }
        item {
            HelperText(
                "Office files are converted on your phone. Text, tables, pictures and basic formatting are kept; " +
                    "complex layouts, charts and fonts that are not on your phone may look different."
            )
        }
        item {
            ProcessStatus(state = state, onReset = { reset() })
        }
        if (outputs.isNotEmpty()) {
            item {
                OutputFilesCard(title = outputs.size.toString() + " PDFs created", outputs = outputs, onReset = { reset() })
            }
        }
    }
}
