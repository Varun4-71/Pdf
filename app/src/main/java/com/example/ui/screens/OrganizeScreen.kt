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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.model.PageItem
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
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun OrganizeScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    val pageItems = remember { mutableStateListOf<PageItem>() }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    var isLoadingThumbnails by remember { mutableStateOf(false) }
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
                pageItems.clear()
                pageBitmaps.clear()
                for (i in 0 until pages) {
                    pageItems.add(PageItem(pageIndex = i, originalPageNumber = i + 1))
                }
                processState = ProcessState.Idle

                // Load thumbnails in background
                isLoadingThumbnails = true
                for (i in 0 until pages) {
                    val bmp = PdfEngine.renderPageThumbnail(context, uri, i, targetWidth = 250)
                    if (bmp != null) {
                        pageBitmaps[i] = bmp
                    }
                }
                isLoadingThumbnails = false
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
                title = "Organize / Reorder Pages",
                subtitle = "Reorder, rotate, or remove pages visually",
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
                            pageItems.clear()
                            pageBitmaps.clear()
                            processState = ProcessState.Idle
                        },
                        title = "Select PDF to Organize Pages"
                    )
                }

                if (selectedFile != null && pageItems.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Document Pages (${pageItems.count { it.isSelected }} of ${pageItems.size} active)",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            if (isLoadingThumbnails) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = NeonCyan,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Rendering...", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }

                    // Pages grid
                    item {
                        Box(modifier = Modifier.height(380.dp)) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(pageItems) { index, item ->
                                    PageCard(
                                        item = item,
                                        bitmap = pageBitmaps[item.pageIndex],
                                        currentIndex = index,
                                        isFirst = index == 0,
                                        isLast = index == pageItems.size - 1,
                                        onMoveLeft = {
                                            if (index > 0) {
                                                val tmp = pageItems[index]
                                                pageItems[index] = pageItems[index - 1]
                                                pageItems[index - 1] = tmp
                                            }
                                        },
                                        onMoveRight = {
                                            if (index < pageItems.size - 1) {
                                                val tmp = pageItems[index]
                                                pageItems[index] = pageItems[index + 1]
                                                pageItems[index + 1] = tmp
                                            }
                                        },
                                        onRotate = {
                                            val currentRot = item.rotationDegrees
                                            item.rotationDegrees = (currentRot + 90) % 360
                                            // trigger recompose
                                            pageItems[index] = item.copy(rotationDegrees = (currentRot + 90) % 360)
                                        },
                                        onToggleDelete = {
                                            val currentSel = item.isSelected
                                            pageItems[index] = item.copy(isSelected = !currentSel)
                                        }
                                    )
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

            if (selectedFile != null && pageItems.any { it.isSelected } && processState !is ProcessState.Processing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    GlassButton(
                        onClick = {
                            coroutineScope.launch {
                                processState = ProcessState.Processing(0.1f, "Applying reordering and rotations...")
                                try {
                                    val out = PdfEngine.organizePages(
                                        context = context,
                                        inputUri = selectedFile!!.uri,
                                        pageItems = pageItems.toList(),
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    val newCount = PdfEngine.getPageCount(context, Uri.fromFile(out))
                                    processState = ProcessState.Success(
                                        outputFile = out,
                                        outputName = "Organized_${selectedFile!!.name}",
                                        details = "Successfully saved reorganized document ($newCount pages, ${PdfEngine.formatFileSize(out.length())})"
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to organize PDF")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_organize_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Export Organized PDF (${pageItems.count { it.isSelected }} Pages)",
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
private fun PageCard(
    item: PageItem,
    bitmap: Bitmap?,
    currentIndex: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRotate: () -> Unit,
    onToggleDelete: () -> Unit
) {
    val isDeleted = !item.isSelected

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        backgroundColor = if (isDeleted) Color(0x353E1515) else Color(0x221E293B),
        borderColor = if (isDeleted) Color(0x60EF4444) else Color(0x35FFFFFF)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header bar of card: Index & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x30FFFFFF))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Page ${item.originalPageNumber}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDeleted) Color(0xFFF87171) else CyanAccent
                    )
                }

                IconButton(
                    onClick = onToggleDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove page",
                        tint = if (isDeleted) Color(0xFFEF4444) else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Thumbnail preview with rotation applied
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(item.rotationDegrees.toFloat()),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanAccent)
                }

                if (item.rotationDegrees != 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("${item.rotationDegrees}°", fontSize = 9.sp, color = Color.White)
                    }
                }
            }

            // Controls footer: Move Left, Rotate, Move Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMoveLeft,
                    enabled = !isFirst,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Move Left",
                        tint = if (!isFirst) TextPrimary else Color(0x30FFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onRotate,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateRight,
                        contentDescription = "Rotate 90",
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onMoveRight,
                    enabled = !isLast,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Move Right",
                        tint = if (!isLast) TextPrimary else Color(0x30FFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
