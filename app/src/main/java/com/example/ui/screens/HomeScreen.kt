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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.PdfEngine
import com.example.engine.RecentFile
import com.example.engine.RecentFiles
import com.example.engine.ViewerLaunch
import com.example.model.PdfTool
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GlassCard
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.SoftEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmAmber

private class Tile(
    val route: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color
)

private val createTiles = listOf(
    Tile("create_files", "Create from files", "Word, Excel, PowerPoint, images, text", Icons.Default.Description, CyanAccent),
    Tile("scan", "Scan to PDF", "Camera or gallery pages", Icons.Default.CameraAlt, SoftEmerald),
    Tile("text_pdf", "Text to PDF", "Type, paste or open text", Icons.Default.TextFields, NeonPurple),
    Tile("blank_pdf", "Blank PDF", "Plain, lined, grid or dotted", Icons.Default.NoteAdd, WarmAmber)
)

private val pageTiles = listOf(
    Tile("organizer", "Organize pages", "Drag to reorder, rotate, delete, insert", Icons.Default.GridView, CyanAccent),
    Tile("page_tools/crop", "Crop and resize", "Trim margins or change paper size", Icons.Default.Crop, NeonPurple),
    Tile("page_tools/split", "Split and extract", "Into parts, or pick pages", Icons.Default.CallSplit, SoftEmerald),
    Tile("page_tools/replace", "Replace pages", "Swap in pages from another PDF", Icons.Default.SwapVert, WarmAmber),
    Tile("page_tools/watermark", "Watermark", "Text or picture on every page", Icons.Default.Layers, CyanAccent),
    Tile("page_tools/headerfooter", "Headers and footers", "Page numbers, dates, titles", Icons.Default.FormatListNumbered, NeonPurple),
    Tile("page_tools/transitions", "Slide transitions", "For presentations", Icons.Default.Slideshow, SoftEmerald),
    Tile("page_tools/background", "Page background", "Colour or picture behind pages", Icons.Default.Image, WarmAmber)
)

private val workTiles = listOf(
    Tile("batch", "Batch tasks", "Convert, compress or stamp many files", Icons.Default.Speed, CyanAccent),
    Tile("search_pdfs", "Search in PDFs", "Find text across several files", Icons.Default.Search, NeonPurple)
)

private fun iconFor(tool: PdfTool): ImageVector {
    return when (tool) {
        PdfTool.MERGE -> Icons.Default.CallMerge
        PdfTool.SPLIT -> Icons.Default.CallSplit
        PdfTool.COMPRESS -> Icons.Default.Compress
        PdfTool.EDIT_METADATA -> Icons.Default.EditNote
        PdfTool.IMAGES_TO_PDF -> Icons.Default.PhotoLibrary
        PdfTool.PDF_TO_IMAGES -> Icons.Default.Image
        PdfTool.ORGANIZE_PAGES -> Icons.Default.GridView
        PdfTool.ADD_PAGE_NUMBERS -> Icons.Default.FormatListNumbered
    }
}

@Composable
fun HomeScreen(
    onToolSelected: (PdfTool) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val recents = remember { RecentFiles.list(context) }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            ViewerLaunch.open(uri)
            onNavigate("viewer")
        }
    }
    val quickTools = listOf(
        PdfTool.MERGE,
        PdfTool.COMPRESS,
        PdfTool.EDIT_METADATA,
        PdfTool.IMAGES_TO_PDF,
        PdfTool.PDF_TO_IMAGES,
        PdfTool.ADD_PAGE_NUMBERS
    )

    MeshBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HomeHeader() }
            item {
                OpenCard(onClick = { openPdf.launch(arrayOf("application/pdf")) })
            }
            if (recents.isNotEmpty()) {
                item { SectionLabel("Recent") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recents) { recent ->
                            RecentCard(recent = recent, onClick = {
                                ViewerLaunch.open(Uri.parse(recent.uri))
                                onNavigate("viewer")
                            })
                        }
                    }
                }
            }
            item { SectionLabel("Create") }
            item { TileRows(createTiles) { onNavigate(it.route) } }
            item { SectionLabel("Edit pages") }
            item { TileRows(pageTiles) { onNavigate(it.route) } }
            item { SectionLabel("Many files at once") }
            item { TileRows(workTiles) { onNavigate(it.route) } }
            item { SectionLabel("More tools") }
            item {
                val tiles = quickTools.map { Tile(it.id, it.title, it.shortDescription, iconFor(it), it.accentColor) }
                TileRows(tiles) { tile ->
                    val tool = quickTools.firstOrNull { it.id == tile.route }
                    if (tool != null) onToolSelected(tool)
                }
            }
            item { PrivacyNote() }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_pdf_toolkit_icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
        )
        Column {
            Text(text = "PDF Toolkit Pro", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(text = "Read, create and edit PDFs on your phone", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun OpenCard(onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        backgroundColor = Color(0x2238BDF8),
        borderColor = CyanAccent.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(CyanAccent, NeonPurple))),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFF070B14), modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Open a PDF", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Read, search, zoom, print and more",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = CyanAccent)
        }
    }
}

@Composable
private fun RecentCard(recent: RecentFile, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, recent.uri) {
        value = try {
            PdfEngine.renderPageThumbnail(context, Uri.parse(recent.uri), 0, 240)
        } catch (e: Exception) {
            null
        }
    }
    GlassCard(modifier = Modifier.width(120.dp), onClick = onClick) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1FFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                val shown = thumb
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
                }
            }
            Text(
                text = recent.name,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun TileRows(tiles: List<Tile>, onClick: (Tile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                for (tile in pair) {
                    ToolTile(tile = tile, modifier = Modifier.weight(1f), onClick = { onClick(tile) })
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolTile(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier = modifier.height(88.dp), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(tile.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = tile.icon, contentDescription = null, tint = tile.accent, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tile.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tile.description,
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "Your files stay on this phone. Nothing is uploaded.", color = TextMuted, fontSize = 12.sp)
    }
}
