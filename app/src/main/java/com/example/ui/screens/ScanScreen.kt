package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.engine.FileUtils
import com.example.engine.ImageInput
import com.example.engine.ImagePdfOptions
import com.example.engine.PdfCreators
import com.example.model.PageSizePreset
import com.example.model.ProcessState
import com.example.model.ScanFilter
import com.example.ui.components.ChoiceChips
import com.example.ui.components.HelperText
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProcessStatus
import com.example.ui.components.SectionCard
import com.example.ui.components.ToolScreenFrame
import com.example.ui.components.rememberFilesPicker
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

private data class ScanPage(val id: Int, val uri: Uri, val rotation: Int, val filter: ScanFilter)

@Composable
private fun ScanThumb(page: ScanPage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(initialValue = null, page.uri, page.rotation, page.filter) {
        value = PdfCreators.previewBitmap(context, ImageInput(page.uri, page.rotation, page.filter), 360)
    }
    Box(modifier = modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val shown = preview
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Scanned page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanAccent)
        }
    }
}

/** Camera and gallery pages with simple clean-up filters, saved as one PDF. */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<ScanPage>() }
    var nextId by remember { mutableIntStateOf(1) }
    var filterIndex by remember { mutableIntStateOf(1) }
    var sizeIndex by remember { mutableIntStateOf(0) }
    var qualityIndex by remember { mutableIntStateOf(0) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var state by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }

    val filters = ScanFilter.values()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val file = pendingFile
        if (ok && file != null) {
            pages.add(ScanPage(nextId, Uri.fromFile(file), 0, filters[filterIndex]))
            nextId++
        }
    }
    val gallery = rememberFilesPicker(arrayOf("image/*")) { uris ->
        for (u in uris) {
            pages.add(ScanPage(nextId, u, 0, filters[filterIndex]))
            nextId++
        }
    }

    fun capture() {
        try {
            val dir = File(FileUtils.workDir(context), "scans")
            dir.mkdirs()
            val file = File(dir, "scan_" + System.currentTimeMillis() + ".jpg")
            pendingFile = file
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            camera.launch(uri)
        } catch (e: Exception) {
            state = ProcessState.Error("The camera could not be started.")
        }
    }

    fun create() {
        scope.launch {
            state = ProcessState.Processing(0.02f, "Preparing pages")
            try {
                val maxDim = when (qualityIndex) {
                    0 -> 2480
                    1 -> 1654
                    else -> 1240
                }
                val jpeg = when (qualityIndex) {
                    0 -> 88
                    1 -> 80
                    else -> 70
                }
                val preset: PageSizePreset? = when (sizeIndex) {
                    0 -> PageSizePreset.A4
                    1 -> PageSizePreset.LETTER
                    else -> null
                }
                val inputs = pages.map { ImageInput(it.uri, it.rotation, it.filter) }
                val file = PdfCreators.imagesToPdf(
                    context,
                    inputs,
                    ImagePdfOptions(preset, 0f, jpeg, maxDim),
                    "Scan"
                ) { p, message -> state = ProcessState.Processing(p, message) }
                state = ProcessState.Success(file, "Scan.pdf", pages.size.toString() + " pages scanned")
            } catch (e: Exception) {
                state = ProcessState.Error(e.localizedMessage ?: "Could not create the PDF.")
            }
        }
    }

    val busy = state is ProcessState.Processing
    val done = state is ProcessState.Success

    ToolScreenFrame(
        title = "Scan to PDF",
        subtitle = "Photograph pages or pick pictures",
        onBack = onBack,
        bottomBar = {
            if (!busy && !done) {
                PrimaryButton(
                    text = if (pages.size > 1) "Create PDF (" + pages.size + " pages)" else "Create PDF",
                    icon = Icons.Default.PictureAsPdf,
                    enabled = pages.isNotEmpty(),
                    onClick = { create() }
                )
            }
        }
    ) {
        item {
            SectionCard(title = "Pages") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        text = "Scan a page",
                        icon = Icons.Default.CameraAlt,
                        modifier = Modifier.weight(1f),
                        onClick = { capture() }
                    )
                    PrimaryButton(
                        text = "From gallery",
                        icon = Icons.Default.PhotoLibrary,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        onClick = { gallery() }
                    )
                }
                if (pages.isEmpty()) {
                    HelperText("Hold the phone straight above the page in good light. Each photo becomes one page.")
                }
            }
        }
        pages.forEachIndexed { index, page ->
            item(key = page.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x14FFFFFF))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScanThumb(
                        page = page,
                        modifier = Modifier
                            .width(78.dp)
                            .height(104.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Page " + (index + 1), color = TextPrimary, fontSize = 15.sp)
                        Text(text = page.filter.label, color = TextMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { pages[index] = page.copy(rotation = (page.rotation + 90) % 360) }) {
                        Icon(imageVector = Icons.Default.RotateRight, contentDescription = "Rotate page " + (index + 1), tint = TextSecondary)
                    }
                    if (index > 0) {
                        IconButton(onClick = {
                            val moved = pages.removeAt(index)
                            pages.add(index - 1, moved)
                        }) {
                            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = "Move up", tint = TextSecondary)
                        }
                    }
                    if (index < pages.size - 1) {
                        IconButton(onClick = {
                            val moved = pages.removeAt(index)
                            pages.add(index + 1, moved)
                        }) {
                            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Move down", tint = TextSecondary)
                        }
                    }
                    IconButton(onClick = { pages.removeAt(index) }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Remove page " + (index + 1), tint = TextSecondary)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Look") {
                ChoiceChips(
                    options = filters.map { it.label },
                    selectedIndex = filterIndex,
                    onSelect = { choice ->
                        filterIndex = choice
                        for (i in pages.indices) pages[i] = pages[i].copy(filter = filters[choice])
                    }
                )
                HelperText("Enhanced brightens paper and darkens text. Black and white suits printed text.")
                ChoiceChips(
                    options = listOf("A4", "Letter", "Match the photo"),
                    selectedIndex = sizeIndex,
                    onSelect = { sizeIndex = it }
                )
                ChoiceChips(
                    options = listOf("High quality", "Balanced", "Small file"),
                    selectedIndex = qualityIndex,
                    onSelect = { qualityIndex = it }
                )
            }
        }
        item {
            ProcessStatus(state = state, onReset = { state = ProcessState.Idle })
        }
    }
}
