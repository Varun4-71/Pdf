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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.engine.PdfEngine
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.components.FilePickerCard
import com.example.ui.components.GlassTopBar
import com.example.ui.components.ProcessingCard
import com.example.ui.components.SuccessCard
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlowBlue
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PdfToImagesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    var selectedFormat by remember { mutableStateOf("PNG") }
    var selectedDpi by remember { mutableStateOf("Standard") } // Standard (150 DPI) or High (300 DPI)
    var extractedImages = remember { mutableStateListOf<File>() }
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
                extractedImages.clear()
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
                title = "PDF to Images",
                subtitle = "Render and export PDF pages as high-res images",
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
                            extractedImages.clear()
                            processState = ProcessState.Idle
                        },
                        title = "Select PDF to Extract Images"
                    )
                }

                if (selectedFile != null) {
                    item {
                        // Options Card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x221E293B),
                            borderColor = GlowBlue.copy(alpha = 0.4f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Export Settings",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                // Format row
                                Text("Image Format", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    listOf("PNG", "JPG").forEach { fmt ->
                                        val isSel = fmt == selectedFormat
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSel) GlowBlue.copy(0.25f) else Color(0x15FFFFFF))
                                                .border(1.dp, if (isSel) GlowBlue else Color(0x25FFFFFF), RoundedCornerShape(10.dp))
                                                .clickable { selectedFormat = fmt }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (fmt == "PNG") "PNG (Lossless)" else "JPG (Compact)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSel) TextPrimary else TextSecondary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Resolution row
                                Text("Resolution / DPI", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    listOf("Standard", "High").forEach { res ->
                                        val isSel = res == selectedDpi
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSel) GlowBlue.copy(0.25f) else Color(0x15FFFFFF))
                                                .border(1.dp, if (isSel) GlowBlue else Color(0x25FFFFFF), RoundedCornerShape(10.dp))
                                                .clickable { selectedDpi = res }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (res == "Standard") "Standard (150 DPI)" else "Ultra (300 DPI)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSel) TextPrimary else TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Extracted images preview grid
                if (extractedImages.isNotEmpty()) {
                    item {
                        Text(
                            text = "Rendered Pages (${extractedImages.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    item {
                        Box(modifier = Modifier.height(280.dp)) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(extractedImages) { index, imgFile ->
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(0.75f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White)
                                            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                                    ) {
                                        AsyncImage(
                                            model = imgFile,
                                            contentDescription = "Page ${index + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )

                                        Box(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xCC000000))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .align(Alignment.BottomCenter)
                                        ) {
                                            Text(
                                                text = "Page ${index + 1}",
                                                fontSize = 10.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
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
                                processState = ProcessState.Processing(0.1f, "Rendering PDF pages...")
                                try {
                                    val factor = if (selectedDpi == "High") 3.0f else 1.8f
                                    val images = PdfEngine.convertPdfToImages(
                                        context = context,
                                        inputUri = selectedFile!!.uri,
                                        format = selectedFormat,
                                        dpiFactor = factor,
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    extractedImages.clear()
                                    extractedImages.addAll(images)

                                    val firstImage = images.firstOrNull() ?: File(context.cacheDir, "sample.png")
                                    processState = ProcessState.Success(
                                        outputFile = firstImage,
                                        outputName = "Page_1.${if (selectedFormat == "PNG") "png" else "jpg"}",
                                        details = "Successfully converted all ${images.size} pages into $selectedFormat images!",
                                        isImageCollection = true,
                                        imageFiles = images
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to convert PDF to images")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_pdf_to_images_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Extract ${selectedFile!!.pageCount} Pages as Images",
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
