package com.example.model

/** Paper sizes in PDF points (1/72 inch). */
enum class PageSizePreset(val label: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842),
    A3("A3", 842, 1191),
    A5("A5", 420, 595),
    LETTER("Letter", 612, 792),
    LEGAL("Legal", 612, 1008),
    TABLOID("Tabloid", 792, 1224)
}

enum class BlankTemplate(val label: String) {
    PLAIN("Plain"),
    LINED("Lined"),
    GRID("Grid"),
    DOTTED("Dotted")
}

enum class ScanFilter(val label: String) {
    ORIGINAL("Original"),
    ENHANCED("Enhanced"),
    GRAYSCALE("Grayscale"),
    BLACK_WHITE("Black & white")
}

/** Layout for images placed on PDF pages. */
enum class ImageFit(val label: String) {
    FIT_PAGE("Fit on page"),
    FIT_IMAGE("Page matches image")
}

/** The kinds of files the converters understand. */
enum class FileKind(val label: String) {
    PDF("PDF"),
    WORD("Word"),
    EXCEL("Excel"),
    POWERPOINT("PowerPoint"),
    IMAGE("Image"),
    TEXT("Text"),
    UNSUPPORTED("Unsupported")
}

/** A picked file waiting to be converted or combined. */
data class SourceItem(
    val uri: android.net.Uri,
    val name: String,
    val kind: FileKind,
    val sizeBytes: Long
)

/** Image placement settings the converters share (kept in the model package for UI use). */
data class ImagePdfOptionsHolder(
    val pageSize: PageSizePreset? = PageSizePreset.A4,
    val marginPt: Float = 18f,
    val jpegQuality: Int = 85,
    val maxDimension: Int = 2000
)
