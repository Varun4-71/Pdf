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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.model.PageNumberFormat
import com.example.model.PageNumberPosition
import com.example.model.ProcessState
import com.example.model.SelectedFile
import com.example.ui.components.FilePickerCard
import com.example.ui.components.GlassTopBar
import com.example.ui.components.ProcessingCard
import com.example.ui.components.SuccessCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.GlassButton
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PageNumbersScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    var selectedFormat by remember { mutableStateOf(PageNumberFormat.PAGE_N_OF_TOTAL) }
    var selectedPosition by remember { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    var startFromPage by remember { mutableIntStateOf(1) }
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
                title = "Add Page Numbers",
                subtitle = "Stamp customizable numbering onto PDF pages",
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
                        title = "Select PDF Document"
                    )
                }

                if (selectedFile != null) {
                    item {
                        // Stamp Configuration Card
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x221E293B),
                            borderColor = ElectricViolet.copy(alpha = 0.4f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "Numbering Format",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                // Formats
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PageNumberFormat.values().forEach { fmt ->
                                        val isSel = fmt == selectedFormat
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) ElectricViolet.copy(0.25f) else Color(0x12FFFFFF))
                                                .border(1.dp, if (isSel) ElectricViolet else Color(0x20FFFFFF), RoundedCornerShape(8.dp))
                                                .clickable { selectedFormat = fmt }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = fmt.template,
                                                fontSize = 13.sp,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "Preview: ${fmt.preview}",
                                                fontSize = 11.sp,
                                                color = if (isSel) ElectricViolet else TextMuted
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "Stamp Position",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                // Position 2x3 Grid
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val topPositions = listOf(PageNumberPosition.TOP_LEFT, PageNumberPosition.TOP_CENTER, PageNumberPosition.TOP_RIGHT)
                                    val bottomPositions = listOf(PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT)

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        topPositions.forEach { pos ->
                                            PositionChip(
                                                pos = pos,
                                                isSelected = pos == selectedPosition,
                                                onSelect = { selectedPosition = pos },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        bottomPositions.forEach { pos ->
                                            PositionChip(
                                                pos = pos,
                                                isSelected = pos == selectedPosition,
                                                onSelect = { selectedPosition = pos },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Skip Cover Page Option
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x15FFFFFF))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Start from Page",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = TextPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        Text(
                                            text = if (startFromPage == 1) "Number every page" else "Skip page 1 (cover page)",
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(1, 2).forEach { pageNum ->
                                            val isSel = startFromPage == pageNum
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSel) ElectricViolet.copy(0.3f) else Color(0x20FFFFFF))
                                                    .border(1.dp, if (isSel) ElectricViolet else Color.Transparent, RoundedCornerShape(6.dp))
                                                    .clickable { startFromPage = pageNum }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = if (pageNum == 1) "Page 1" else "Page 2",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSel) TextPrimary else TextSecondary
                                                )
                                            }
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
                                processState = ProcessState.Processing(0.1f, "Stamping page numbers...")
                                try {
                                    val out = PdfEngine.addPageNumbers(
                                        context = context,
                                        inputUri = selectedFile!!.uri,
                                        format = selectedFormat,
                                        position = selectedPosition,
                                        startFromPage = startFromPage,
                                        onProgress = { p, msg ->
                                            processState = ProcessState.Processing(p, msg)
                                        }
                                    )
                                    processState = ProcessState.Success(
                                        outputFile = out,
                                        outputName = "Numbered_${selectedFile!!.name}",
                                        details = "Numbered ${selectedFile!!.pageCount} pages using ${selectedFormat.preview} at ${selectedPosition.title} (${PdfEngine.formatFileSize(out.length())})"
                                    )
                                } catch (e: Exception) {
                                    processState = ProcessState.Error(e.localizedMessage ?: "Failed to add page numbers")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("execute_add_page_numbers_button"),
                        isPrimary = true
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FormatListNumbered,
                                contentDescription = null,
                                tint = Color(0xFF070B14)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stamp Page Numbers",
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
private fun PositionChip(
    pos: PageNumberPosition,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shortName = when (pos) {
        PageNumberPosition.TOP_LEFT -> "Top L"
        PageNumberPosition.TOP_CENTER -> "Top C"
        PageNumberPosition.TOP_RIGHT -> "Top R"
        PageNumberPosition.BOTTOM_LEFT -> "Btm L"
        PageNumberPosition.BOTTOM_CENTER -> "Btm C"
        PageNumberPosition.BOTTOM_RIGHT -> "Btm R"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) ElectricViolet.copy(0.3f) else Color(0x12FFFFFF))
            .border(1.dp, if (isSelected) ElectricViolet else Color(0x20FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = shortName,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) TextPrimary else TextSecondary
        )
    }
}
