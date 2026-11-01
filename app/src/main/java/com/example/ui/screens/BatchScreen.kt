package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.batch.BatchManager
import com.example.batch.BatchOperation
import com.example.batch.BatchOptions
import com.example.batch.JobStatus
import com.example.engine.HeaderFooterSpec
import com.example.engine.OutputFile
import com.example.engine.PdfEngine
import com.example.engine.WatermarkSpec
import com.example.model.CompressionLevel
import com.example.ui.components.AppTextField
import com.example.ui.components.ChoiceChips
import com.example.ui.components.FileRow
import com.example.ui.components.HelperText
import com.example.ui.components.LabeledSlider
import com.example.ui.components.OutputFilesCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SectionCard
import com.example.ui.components.ToolScreenFrame
import com.example.ui.components.rememberFilesPicker
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RadiantRose
import com.example.ui.theme.SoftEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Runs one task over many files in the background, with progress and a notification. */
@Composable
fun BatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val batch by BatchManager.state.collectAsState()
    val operations = BatchOperation.values()
    var opIndex by remember { mutableIntStateOf(0) }
    val files = remember { mutableStateListOf<Pair<Uri, String>>() }

    var levelIndex by remember { mutableIntStateOf(1) }
    var wmText by remember { mutableStateOf("CONFIDENTIAL") }
    var wmOpacity by remember { mutableFloatStateOf(30f) }
    var hfIndex by remember { mutableIntStateOf(0) }
    var hfCustom by remember { mutableStateOf("{name}  -  page {page}") }
    var rotateIndex by remember { mutableIntStateOf(0) }
    var pngImages by remember { mutableIntStateOf(0) }

    val operation = operations[opIndex]
    val levels = CompressionLevel.values()

    val picker = rememberFilesPicker(
        if (operation == BatchOperation.CONVERT) arrayOf("*/*") else arrayOf("application/pdf")
    ) { uris ->
        scope.launch {
            for (u in uris) {
                if (files.none { it.first == u }) {
                    val name = withContext(Dispatchers.IO) { PdfEngine.getFileName(context, u) }
                    files.add(Pair(u, name))
                }
            }
        }
    }

    fun buildOptions(): BatchOptions {
        val footer = when (hfIndex) {
            0 -> HeaderFooterSpec(footerCenter = "Page {page} of {total}")
            1 -> HeaderFooterSpec(footerRight = "{page}")
            2 -> HeaderFooterSpec(headerLeft = "{name}", headerRight = "{date}")
            else -> HeaderFooterSpec(footerCenter = hfCustom)
        }
        val degrees = when (rotateIndex) {
            0 -> 90
            1 -> 180
            else -> 270
        }
        return BatchOptions(
            compression = levels[levelIndex],
            watermark = WatermarkSpec(text = wmText, opacity = wmOpacity / 100f),
            headerFooter = footer,
            rotateDegrees = degrees,
            imagesAsPng = pngImages == 1
        )
    }

    fun begin() {
        BatchManager.start(context, operation, buildOptions(), files.toList())
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean ->
        begin()
    }

    fun startWithPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            begin()
        }
    }

    val hasJobs = batch.jobs.isNotEmpty()

    ToolScreenFrame(
        title = "Batch tasks",
        subtitle = if (hasJobs) (batch.operation?.title ?: "") else "Do the same job on many files",
        onBack = onBack,
        bottomBar = {
            if (!hasJobs) {
                PrimaryButton(
                    text = "Start (" + files.size + (if (files.size == 1) " file)" else " files)"),
                    icon = Icons.Default.PlayArrow,
                    enabled = files.isNotEmpty(),
                    onClick = { startWithPermission() }
                )
            } else if (batch.running) {
                PrimaryButton(
                    text = "Cancel",
                    icon = Icons.Default.Close,
                    isPrimary = false,
                    onClick = { BatchManager.cancel() }
                )
            }
        }
    ) {
        if (!hasJobs) {
            item {
                SectionCard(title = "What should happen to every file?") {
                    ChoiceChips(
                        options = operations.map { it.title },
                        selectedIndex = opIndex,
                        onSelect = {
                            if (it != opIndex) files.clear()
                            opIndex = it
                        }
                    )
                    HelperText(operation.description)
                }
            }
            item {
                SectionCard(title = "Files") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        files.forEachIndexed { index, f ->
                            FileRow(
                                name = f.second,
                                detail = "Ready",
                                icon = Icons.Default.PictureAsPdf,
                                onRemove = { files.removeAt(index) }
                            )
                        }
                    }
                    PrimaryButton(
                        text = if (files.isEmpty()) "Choose files" else "Add more files",
                        icon = Icons.Default.Add,
                        isPrimary = false,
                        onClick = { picker() }
                    )
                }
            }
            item {
                SectionCard(title = "Settings") {
                    when (operation) {
                        BatchOperation.CONVERT -> {
                            HelperText("Each Word, Excel, PowerPoint, image or text file becomes its own PDF. PDFs are copied as they are.")
                        }
                        BatchOperation.COMPRESS -> {
                            ChoiceChips(
                                options = levels.map { it.title },
                                selectedIndex = levelIndex,
                                onSelect = { levelIndex = it }
                            )
                            HelperText(levels[levelIndex].description)
                        }
                        BatchOperation.WATERMARK -> {
                            AppTextField(wmText, { wmText = it }, "Watermark text")
                            LabeledSlider("Strength", wmOpacity, { wmOpacity = it }, 5f..100f, wmOpacity.roundToInt().toString() + "%")
                        }
                        BatchOperation.HEADER_FOOTER -> {
                            ChoiceChips(
                                options = listOf("Page X of Y", "Page number", "Name and date", "Custom footer"),
                                selectedIndex = hfIndex,
                                onSelect = { hfIndex = it }
                            )
                            if (hfIndex == 3) {
                                AppTextField(hfCustom, { hfCustom = it }, "Footer text")
                                HelperText("Use {page}, {total}, {date} and {name}.")
                            }
                        }
                        BatchOperation.ROTATE -> {
                            ChoiceChips(
                                options = listOf("90\u00B0 clockwise", "180\u00B0", "90\u00B0 anticlockwise"),
                                selectedIndex = rotateIndex,
                                onSelect = { rotateIndex = it }
                            )
                        }
                        BatchOperation.TO_IMAGES -> {
                            ChoiceChips(
                                options = listOf("JPG (smaller)", "PNG (sharper)"),
                                selectedIndex = pngImages,
                                onSelect = { pngImages = it }
                            )
                        }
                    }
                }
            }
        } else {
            item {
                SectionCard(title = if (batch.running) "Working" else "Finished") {
                    LinearProgressIndicator(
                        progress = { batch.overallProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0x30FFFFFF)
                    )
                    Text(
                        text = batch.doneCount.toString() + " of " + batch.jobs.size + " done" +
                            (if (batch.failedCount > 0) ", " + batch.failedCount + " failed" else ""),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    if (batch.running) {
                        HelperText("You can leave this screen or the app. A notification shows progress.")
                    }
                }
            }
            for (job in batch.jobs) {
                item(key = job.id) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (job.status) {
                            JobStatus.DONE -> Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Done", tint = SoftEmerald)
                            JobStatus.FAILED -> Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = "Failed", tint = RadiantRose)
                            JobStatus.CANCELLED -> Icon(imageVector = Icons.Default.Close, contentDescription = "Cancelled", tint = TextMuted)
                            else -> Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = TextMuted)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = job.name,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val detail = when (job.status) {
                                JobStatus.QUEUED -> "Waiting"
                                JobStatus.RUNNING -> (job.progress * 100f).roundToInt().toString() + "%"
                                JobStatus.DONE -> job.outputs.size.toString() + (if (job.outputs.size == 1) " file" else " files")
                                JobStatus.FAILED -> job.error ?: "Failed"
                                JobStatus.CANCELLED -> "Cancelled"
                            }
                            Text(
                                text = detail,
                                color = if (job.status == JobStatus.FAILED) RadiantRose else TextMuted,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (!batch.running) {
                val results = ArrayList<OutputFile>()
                for (job in batch.jobs) results.addAll(job.outputs)
                if (results.isNotEmpty()) {
                    item {
                        OutputFilesCard(
                            title = results.size.toString() + (if (results.size == 1) " file ready" else " files ready"),
                            outputs = results,
                            onReset = {
                                BatchManager.clear()
                                files.clear()
                            }
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Start over",
                            color = CyanAccent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .clickable {
                                    BatchManager.clear()
                                    files.clear()
                                }
                        )
                    }
                }
            }
        }
    }
}
