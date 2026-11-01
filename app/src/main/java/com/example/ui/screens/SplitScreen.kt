package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.components.FilePickerCard
import com.example.ui.components.GlassTopBar
import com.example.ui.components.ProcessingCard
import com.example.ui.components.SuccessCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun SplitScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageRangeText by remember { mutableStateOf("1") }
    var processState by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val name = PdfEngine.getFileName(context, uri)
                val size = PdfEngine.getFileSize(context, uri)
                val pages = PdfEngine.getPageCount(context, uri)
                selectedFile = SelectedFile(uri, name, size, pages)
                previewBitmap = PdfEngine.renderPageThumbnail(context, uri, 0, 300)
                pageRangeText = if (pages > 1) "1-${pages / 2}" else "1"
            }
        }
    }

    MeshBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            GlassTopBar(
                title = "Split PDF",
                subtitle = "Extract specific page ranges into a new document",
                onBackClick = onNavigateBack
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    FilePickerCard(
                        selectedFile = selectedFile,
                        onPickFile = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                        onRemoveFile = {
                            selectedFile = null
                            previewBitmap = null
                            processState = ProcessState.Idle
                        },
                        title = "Select PDF to Split"
                    )
                }

                if (selectedFile != null) {
                    item {
                        // Document details & Thumbnail card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x221E293B),
                            borderColor = NeonPurple.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (previewBitmap != null) {
                                    Image(
                                        bitmap = previewBitmap!!.asImageBitmap(),
                                        contentDescription = "Cover",
                                        modifier = Modifier
                                            .size(70.dp, 90.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                }

                                Column {
                                    Text(
                                        text = selectedFile!!.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Total Pages: ${selectedFile!!.pageCount}",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = NeonPurple)
                                    )
                                    Text(
                                        text = "Size: ${PdfEngine.formatFileSize(selectedFile!!.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        // Split Range Settings Card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x201E293B),
                            borderColor = Color(0x35FFFFFF)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Specify Page Range",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Example: 1-3, 5, 8-10 (Valid: 1 to ${selectedFile!!.pageCount})",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = pageRangeText,
                                    onValueChange = { pageRangeText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("split_range_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NeonPurple,
                                        unfocusedBorderColor = Color(0x35FFFFFF),
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        focusedContainerColor = Color(0x18FFFFFF),
                                        unfocusedContainerColor = Color(0x12FFFFFF)
                                    ),
                                    placeholder = { Text("e.g. 1-2, 4", color = TextMuted) }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // Quick preset chips
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val total = selectedFile!!.pageCount
                                    QuickPresetChip(
                                        text = "Page 1",
                                        onClick = { pageRangeText = "1" }
                                    )
                                    if (total > 2) {
                                        QuickPresetChip(
                                            text = "1-${total / 2}",
                                            onClick = { pageRangeText = "1-${total / 2}" }
                                        )
                                        QuickPresetChip(
                                            text = "${(total / 2) + 1}-$total",
                                            onClick = { pageRangeText = "${(total / 2) + 1}-$total" }
                                        )
                                    }
                                    QuickPresetChip(
                                        text = "All (1-$total)",
                                        onClick = { pageRangeText = "1-$total" }
                                    )
                                }
                            }
                        }
                    }
                }

                // Status or Success
                item {
                    when (val state = processState) {
                        is ProcessState.Processing -> {
                            ProcessingCard(progress = state.progress, message = state.message)
                        }
                        is ProcessState.Success -> {
                            SuccessCard(
                                state = state,
                                onReset = { processState = ProcessState.Idle }
                            )
                        }
                        is ProcessState.Error -> {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color(0x303E1515),
                                borderColor = Color(0xFFEF4444)
                            ) {
                                Text(
                                    text = "Error: ${state.message}",
                                    color = Color(0xFFFCA5A5),
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }
            }

            if (selectedFile != null && processState !is ProcessState.Processing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    GlassButton(
                        onClick = {
                            coroutineScope.launch {
                                processState = ProcessState.Processing(0.1f, "Extracting pages...")
                                try {
                                    val out = PdfEngine.splitPdf(
                                        context = context,
                                        inputUri = selectedFile!!.uri,
                                        pageRangeString = pageRangeText,
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    val pagesCount = PdfEngine.getPageCount(context, Uri.fromFile(out))
                                    processState = ProcessState.Success(
                                        outputFile = out,
                                        outputName = "Split_${selectedFile!!.name}",
                                        details = "Extracted $pagesCount pages based on range '$pageRangeText' (${PdfEngine.formatFileSize(out.length())})"
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to split document")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_split_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CallSplit,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Extract Pages",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF070B14)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPresetChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x20FFFFFF))
            .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
    }
}
