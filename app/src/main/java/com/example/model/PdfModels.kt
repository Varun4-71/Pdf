package com.example.model

import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.GlowBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.RadiantRose
import com.example.ui.theme.SoftEmerald
import com.example.ui.theme.WarmAmber
import java.io.File

enum class PdfTool(
    val id: String,
    val title: String,
    val shortDescription: String,
    val accentColor: Color,
    val badge: String
) {
    MERGE(
        id = "merge",
        title = "Merge PDFs",
        shortDescription = "Combine multiple PDF documents into a single document",
        accentColor = CyanAccent,
        badge = "1"
    ),
    SPLIT(
        id = "split",
        title = "Split PDF",
        shortDescription = "Extract specific page ranges or split into parts",
        accentColor = NeonPurple,
        badge = "2"
    ),
    COMPRESS(
        id = "compress",
        title = "Compress PDF",
        shortDescription = "Reduce file size with optimized compression presets",
        accentColor = SoftEmerald,
        badge = "3"
    ),
    EDIT_METADATA(
        id = "metadata",
        title = "Edit PDF / Metadata",
        shortDescription = "View and modify Title, Author, Subject & Keywords",
        accentColor = WarmAmber,
        badge = "4"
    ),
    IMAGES_TO_PDF(
        id = "images_to_pdf",
        title = "Images to PDF",
        shortDescription = "Convert JPG & PNG images into a clean PDF document",
        accentColor = RadiantRose,
        badge = "5"
    ),
    PDF_TO_IMAGES(
        id = "pdf_to_images",
        title = "PDF to Images",
        shortDescription = "Render and export PDF pages as high-res images",
        accentColor = GlowBlue,
        badge = "6"
    ),
    ORGANIZE_PAGES(
        id = "organize",
        title = "Organize / Reorder",
        shortDescription = "Visually reorder, rotate 90°, or remove pages",
        accentColor = NeonCyan,
        badge = "7"
    ),
    ADD_PAGE_NUMBERS(
        id = "page_numbers",
        title = "Add Page Numbers",
        shortDescription = "Stamp custom page numbers with position and formatting",
        accentColor = ElectricViolet,
        badge = "8"
    )
}

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val pageCount: Int = 0
)

data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
    val pageCount: Int = 0,
    val fileSizeFormatted: String = ""
)

data class PageItem(
    val pageIndex: Int, // 0-based
    val originalPageNumber: Int, // 1-based display
    var rotationDegrees: Int = 0, // 0, 90, 180, 270
    var isSelected: Boolean = true
)

enum class CompressionLevel(val title: String, val description: String, val scale: Float, val quality: Int) {
    LIGHT("Light Compression", "High quality (150 DPI, 85% quality)", 1.0f, 85),
    STANDARD("Standard (Recommended)", "Balanced quality & size (120 DPI, 70% quality)", 0.8f, 70),
    STRONG("Strong Compression", "Smallest size for sharing (90 DPI, 50% quality)", 0.6f, 50)
}

enum class PageNumberPosition(val title: String) {
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right"),
    BOTTOM_LEFT("Bottom Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    TOP_LEFT("Top Left")
}

enum class PageNumberFormat(val template: String, val preview: String) {
    PAGE_N("Page {n}", "Page 1"),
    PAGE_N_OF_TOTAL("Page {n} of {total}", "Page 1 of 5"),
    N_SLASH_TOTAL("{n} / {total}", "1 / 5"),
    DASH_N_DASH("- {n} -", "- 1 -"),
    ONLY_N("{n}", "1")
}

sealed class ProcessState {
    object Idle : ProcessState()
    data class Processing(val progress: Float, val message: String) : ProcessState()
    data class Success(
        val outputFile: File,
        val outputName: String,
        val details: String,
        val isImageCollection: Boolean = false,
        val imageFiles: List<File> = emptyList()
    ) : ProcessState()
    data class Error(val message: String) : ProcessState()
}
