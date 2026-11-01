package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.model.CompressionLevel
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
import com.example.ui.theme.SoftEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun CompressScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    var selectedLevel by remember { mutableStateOf(CompressionLevel.STANDARD) }
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
                processState = ProcessState.Idle
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
                title = "Compress PDF",
                subtitle = "Reduce document size with smart image optimization",
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
                            processState = ProcessState.Idle
                        },
                        title = "Select PDF to Compress"
                    )
                }

                if (selectedFile != null) {
                    item {
                        // Compression presets
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x221E293B),
                            borderColor = SoftEmerald.copy(alpha = 0.4f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Choose Compression Level",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                CompressionLevel.values().forEach { level ->
                                    val isSelected = level == selectedLevel
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) SoftEmerald.copy(alpha = 0.15f) else Color(0x10FFFFFF))
                                            .border(
                                                1.dp,
                                                if (isSelected) SoftEmerald else Color(0x20FFFFFF),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable { selectedLevel = level }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) SoftEmerald else TextMuted,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = level.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                            )
                                            Text(
                                                text = level.description,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = TextSecondary,
                                                    fontSize = 12.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Process State
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
                                processState = ProcessState.Processing(0.05f, "Optimizing PDF pages...")
                                try {
                                    val out = PdfEngine.compressPdf(
                                        context = context,
                                        inputUri = selectedFile!!.uri,
                                        level = selectedLevel,
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    val origSize = selectedFile!!.sizeBytes
                                    val newSize = out.length()
                                    val savedPct = if (origSize > 0) {
                                        (((origSize - newSize).toFloat() / origSize) * 100).toInt().coerceAtLeast(0)
                                    } else 0

                                    val details = if (savedPct > 0) {
                                        "Reduced from ${PdfEngine.formatFileSize(origSize)} to ${PdfEngine.formatFileSize(newSize)} (Saved $savedPct%!)"
                                    } else {
                                        "Optimized document (${PdfEngine.formatFileSize(newSize)})"
                                    }

                                    processState = ProcessState.Success(
                                        outputFile = out,
                                        outputName = "Compressed_${selectedFile!!.name}",
                                        details = details
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to compress PDF")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_compress_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Compress,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Compress PDF (${selectedLevel.title.split(" ")[0]})",
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
