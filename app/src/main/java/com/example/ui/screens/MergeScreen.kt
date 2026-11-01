package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.components.GlassTopBar
import com.example.ui.components.ProcessingCard
import com.example.ui.components.SuccessCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun MergeScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val selectedFiles = remember { mutableStateListOf<SelectedFile>() }
    var processState by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                uris.forEach { uri ->
                    val name = PdfEngine.getFileName(context, uri)
                    val size = PdfEngine.getFileSize(context, uri)
                    val pages = PdfEngine.getPageCount(context, uri)
                    selectedFiles.add(SelectedFile(uri, name, size, pages))
                }
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
                title = "Merge PDFs",
                subtitle = "Combine multiple documents into one",
                onBackClick = onNavigateBack
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    // Add Files Button / Card
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_pdf_files_card"),
                        onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                        backgroundColor = Color(0x251E293B),
                        borderColor = CyanAccent.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Files",
                                tint = CyanAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (selectedFiles.isEmpty()) "Select PDF Files to Merge" else "Add More PDF Files",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                        }
                    }
                }

                if (selectedFiles.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Documents to Merge (${selectedFiles.size})",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Text(
                                text = "Drag or use arrows to reorder",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    itemsIndexed(selectedFiles) { index, item ->
                        MergeItemCard(
                            file = item,
                            index = index,
                            isFirst = index == 0,
                            isLast = index == selectedFiles.size - 1,
                            onMoveUp = {
                                if (index > 0) {
                                    val temp = selectedFiles[index]
                                    selectedFiles[index] = selectedFiles[index - 1]
                                    selectedFiles[index - 1] = temp
                                }
                            },
                            onMoveDown = {
                                if (index < selectedFiles.size - 1) {
                                    val temp = selectedFiles[index]
                                    selectedFiles[index] = selectedFiles[index + 1]
                                    selectedFiles[index + 1] = temp
                                }
                            },
                            onRemove = {
                                selectedFiles.removeAt(index)
                            }
                        )
                    }
                }

                // Process States
                item {
                    when (val state = processState) {
                        is ProcessState.Processing -> {
                            ProcessingCard(
                                progress = state.progress,
                                message = state.message,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                        is ProcessState.Success -> {
                            SuccessCard(
                                state = state,
                                onReset = {
                                    processState = ProcessState.Idle
                                },
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                        is ProcessState.Error -> {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
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

            // Bottom Merge Action Button
            AnimatedVisibility(visible = selectedFiles.size >= 2 && processState !is ProcessState.Processing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    GlassButton(
                        onClick = {
                            coroutineScope.launch {
                                processState = ProcessState.Processing(0.05f, "Initializing merge...")
                                try {
                                    val outputFile = PdfEngine.mergePdfs(
                                        context = context,
                                        inputUris = selectedFiles.map { it.uri },
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    val totalPages = PdfEngine.getPageCount(context, Uri.fromFile(outputFile))
                                    val fileSizeFormatted = PdfEngine.formatFileSize(outputFile.length())
                                    processState = ProcessState.Success(
                                        outputFile = outputFile,
                                        outputName = "Merged_Document.pdf",
                                        details = "Successfully merged ${selectedFiles.size} PDFs into $totalPages pages ($fileSizeFormatted)"
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to merge PDFs")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_merge_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CallMerge,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Merge ${selectedFiles.size} PDFs",
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
private fun MergeItemCard(
    file: SelectedFile,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color(0x1C1E293B),
        borderColor = Color(0x30FFFFFF)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index Pill
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x25FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = CyanAccent
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${PdfEngine.formatFileSize(file.sizeBytes)}" +
                            if (file.pageCount > 0) " • ${file.pageCount} pages" else "",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )
            }

            // Move Up/Down Controls
            IconButton(
                onClick = onMoveUp,
                enabled = !isFirst,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Move Up",
                    tint = if (!isFirst) TextSecondary else Color(0x30FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onMoveDown,
                enabled = !isLast,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Move Down",
                    tint = if (!isLast) TextSecondary else Color(0x30FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
