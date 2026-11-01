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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.engine.PdfEngine
import com.example.model.ProcessState
import com.example.ui.components.GlassTopBar
import com.example.ui.components.ProcessingCard
import com.example.ui.components.SuccessCard
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.RadiantRose
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ImagesToPdfScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val imageUris = remember { mutableStateListOf<Uri>() }
    var selectedPageFit by remember { mutableStateOf("A4") }
    var processState by remember { mutableStateOf<ProcessState>(ProcessState.Idle) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            imageUris.addAll(uris)
            processState = ProcessState.Idle
        }
    }

    MeshBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            GlassTopBar(
                title = "Images to PDF",
                subtitle = "Convert JPG & PNG photos into a clean document",
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
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_images_card"),
                        onClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
                        backgroundColor = Color(0x221E293B),
                        borderColor = RadiantRose.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Add Images",
                                tint = RadiantRose,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (imageUris.isEmpty()) "Select Photos / Images" else "Add More Images",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                        }
                    }
                }

                if (imageUris.isNotEmpty()) {
                    item {
                        // Page Size Format selector
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x201E293B),
                            borderColor = Color(0x35FFFFFF)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = "Page Format",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("A4", "Letter", "FitImage").forEach { format ->
                                        val isSelected = format == selectedPageFit
                                        val label = if (format == "FitImage") "Fit to Image" else format
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSelected) RadiantRose.copy(0.25f) else Color(0x15FFFFFF))
                                                .border(
                                                    1.dp,
                                                    if (isSelected) RadiantRose else Color(0x25FFFFFF),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable { selectedPageFit = format }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) TextPrimary else TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Selected Images (${imageUris.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // Grid preview of images
                    item {
                        Box(modifier = Modifier.height(260.dp)) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(imageUris) { index, uri ->
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = "Image $index",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )

                                        // Index Badge
                                        Box(
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xCC000000))
                                                .align(Alignment.TopStart),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }

                                        // Delete button
                                        IconButton(
                                            onClick = { imageUris.removeAt(index) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(26.dp)
                                                .padding(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xCCEF4444))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
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

            if (imageUris.isNotEmpty() && processState !is ProcessState.Processing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    GlassButton(
                        onClick = {
                            coroutineScope.launch {
                                processState = ProcessState.Processing(0.1f, "Compiling images to PDF...")
                                try {
                                    val out = PdfEngine.convertImagesToPdf(
                                        context = context,
                                        imageUris = imageUris.toList(),
                                        pageFit = selectedPageFit,
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    processState = ProcessState.Success(
                                        outputFile = out,
                                        outputName = "Converted_Images.pdf",
                                        details = "Created ${imageUris.size}-page PDF document (${PdfEngine.formatFileSize(out.length())})"
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to convert images")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_images_to_pdf_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Convert ${imageUris.size} Images to PDF",
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
