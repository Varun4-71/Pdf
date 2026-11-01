package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.engine.FileUtils
import com.example.engine.FolderSaver
import com.example.engine.OutputFile
import com.example.engine.PdfEngine
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.RadiantRose
import com.example.ui.theme.SoftEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Standard layout for every tool screen: glass top bar, scrolling content, optional pinned action. */
@Composable
fun ToolScreenFrame(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    topActions: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    MeshBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = title, subtitle = subtitle, onBackClick = onBack, actions = topActions)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                content()
            }
            if (bottomBar != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    bottomBar()
                }
            } else {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

/** Like [ToolScreenFrame] but with an ordinary scrolling column, so option state is never discarded. */
@Composable
fun ScrollFrame(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    MeshBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(title = title, subtitle = subtitle, onBackClick = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null) {
                Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun HelperText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)
}

@Composable
fun ChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) CyanAccent.copy(alpha = 0.22f) else Color(0x14FFFFFF))
                    .border(1.dp, if (selected) CyanAccent else Color(0x30FFFFFF), shape)
                    .selectable(selected = selected, onClick = { onSelect(index) }, role = Role.RadioButton)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    color = if (selected) TextPrimary else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = TextSecondary, fontSize = 13.sp)
            Text(text = valueText, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = CyanAccent,
                activeTrackColor = CyanAccent,
                inactiveTrackColor = Color(0x30FFFFFF)
            )
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextPrimary, fontSize = 14.sp)
            if (description != null) {
                Text(text = description, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CyanAccent,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color(0x30FFFFFF)
            )
        )
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { hint -> { Text(hint) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = CyanAccent,
            unfocusedBorderColor = Color(0x40FFFFFF),
            focusedLabelColor = CyanAccent,
            unfocusedLabelColor = TextMuted,
            cursorColor = CyanAccent,
            focusedPlaceholderColor = TextMuted,
            unfocusedPlaceholderColor = TextMuted
        )
    )
}

@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isPrimary: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        enabled = enabled,
        isPrimary = isPrimary
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(text = text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = Color(0x26F43F5E),
        borderColor = RadiantRose.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = RadiantRose)
            Text(text = message, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary)
            }
        }
    }
}

/** Shows progress, the single-file result card, or an error for a [ProcessState]. */
@Composable
fun ProcessStatus(state: ProcessState, onReset: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is ProcessState.Idle -> {
        }
        is ProcessState.Processing -> ProcessingCard(progress = state.progress, message = state.message, modifier = modifier)
        is ProcessState.Success -> SuccessCard(state = state, onReset = onReset, modifier = modifier)
        is ProcessState.Error -> ErrorBanner(message = state.message, onDismiss = onReset, modifier = modifier)
    }
}

private fun mimeOf(name: String): String {
    return when (FileUtils.extensionOf(name)) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "*/*"
    }
}

/** Result card for jobs that produce several files: save them all to a folder or share them. */
@Composable
fun OutputFilesCard(
    title: String,
    outputs: List<OutputFile>,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            scope.launch {
                message = try {
                    val saved = FolderSaver.saveAll(context, tree, outputs)
                    "Saved $saved of ${outputs.size} files to the folder you chose."
                } catch (e: Exception) {
                    "Could not save: " + (e.localizedMessage ?: "unknown error")
                }
            }
        }
    }
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = Color(0x2610B981),
        borderColor = SoftEmerald.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = SoftEmerald)
                Text(text = title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            val shown = outputs.take(8)
            for (item in shown) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    Text(
                        text = item.name,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = PdfEngine.formatFileSize(item.file.length()), color = TextMuted, fontSize = 12.sp)
                }
            }
            if (outputs.size > shown.size) {
                Text(text = "and ${outputs.size - shown.size} more", color = TextMuted, fontSize = 12.sp)
            }
            val note = message
            if (note != null) {
                Text(text = note, color = SoftEmerald, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = "Save to folder",
                    icon = Icons.Default.FolderOpen,
                    modifier = Modifier.weight(1f),
                    onClick = { folderLauncher.launch(null) }
                )
                PrimaryButton(
                    text = "Share",
                    icon = Icons.Default.Share,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { shareAll(context, outputs) }
                )
            }
            Text(
                text = "Start over",
                color = CyanAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onReset)
                    .padding(vertical = 6.dp)
            )
        }
    }
}

private fun shareAll(context: Context, outputs: List<OutputFile>) {
    try {
        val uris = ArrayList<Uri>()
        var allPdf = true
        for (o in outputs) {
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", o.file))
            if (mimeOf(o.name) != "application/pdf") allPdf = false
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = if (allPdf) "application/pdf" else "*/*"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share files"))
    } catch (e: Exception) {
        // nothing to share
    }
}

// -------------------------------------------------------------------- pickers

suspend fun describePdf(context: Context, uri: Uri): SelectedFile = withContext(Dispatchers.IO) {
    val name = PdfEngine.getFileName(context, uri)
    val size = PdfEngine.getFileSize(context, uri)
    val pages = try {
        PdfEngine.getPageCount(context, uri)
    } catch (e: Exception) {
        0
    }
    SelectedFile(uri, name, size, pages)
}

/** Returns a function that opens the system picker for one PDF. */
@Composable
fun rememberPdfPicker(onPicked: (SelectedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch { onPicked(describePdf(context, uri)) }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/pdf")) } }
}

/** Opens the system picker for several files of the given types. */
@Composable
fun rememberFilesPicker(mimeTypes: Array<String>, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val typesKey = mimeTypes.joinToString("|")
    return remember(launcher, typesKey) { { launcher.launch(mimeTypes) } }
}

/** Opens the system picker for one file of the given types. */
@Composable
fun rememberSingleFilePicker(mimeTypes: Array<String>, onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onPicked(uri)
    }
    val typesKey = mimeTypes.joinToString("|")
    return remember(launcher, typesKey) { { launcher.launch(mimeTypes) } }
}

// -------------------------------------------------------------------- list rows and thumbnails

/** A file in a list, with optional remove and reorder controls. */
@Composable
fun FileRow(
    name: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = detail, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = "Move up", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Move down", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** One page of a local PDF file as a small picture, optionally turned by [rotation] degrees. */
@Composable
fun PdfThumbnail(
    file: File,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    widthPx: Int = 260,
    rotation: Int = 0
) {
    val turn = ((rotation % 360) + 360) % 360
    val key = file.absolutePath + "#" + pageIndex + "@" + widthPx + "r" + turn
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(ThumbCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            val base = PdfEngine.renderPageThumbnailFromFile(file, pageIndex, widthPx)
            if (base != null) {
                var result: Bitmap = base
                if (turn != 0) {
                    val m = Matrix()
                    m.postRotate(turn.toFloat())
                    result = Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
                }
                ThumbCache.put(key, result)
                bitmap = result
            }
        }
    }
    Box(modifier = modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanAccent)
        }
    }
}
