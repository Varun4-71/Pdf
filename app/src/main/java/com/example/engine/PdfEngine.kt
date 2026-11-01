package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.model.CompressionLevel
import com.example.model.PageItem
import com.example.model.PageNumberFormat
import com.example.model.PageNumberPosition
import com.example.model.PdfMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.DecimalFormat

object PdfEngine {

    private var isInitialized = false

    fun initialize(context: Context) {
        if (!isInitialized) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getOutputDir(context: Context): File {
        val dir = File(context.cacheDir, "pdf_toolkit")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result = "document.pdf"
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback
            }
        }
        if (result.isEmpty()) {
            result = uri.lastPathSegment ?: "document.pdf"
        }
        return result
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index != -1) {
                            size = cursor.getLong(index)
                        }
                    }
                }
            }
            if (size <= 0) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    suspend fun copyUriToTemp(context: Context, uri: Uri, prefix: String = "input"): File = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile(prefix, ".pdf", getOutputDir(context))
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    }

    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    count = renderer.pageCount
                }
            }
        } catch (e: Exception) {
            // fallback via PDFBox
            try {
                val temp = copyUriToTemp(context, uri)
                PDDocument.load(temp).use { doc ->
                    count = doc.numberOfPages
                }
                temp.delete()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        count
    }

    suspend fun renderPageThumbnail(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val scale = targetWidth.toFloat() / page.width.toFloat()
                            val targetHeight = (page.height * scale).toInt().coerceAtLeast(50)
                            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(AndroidColor.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun renderPageThumbnailFromFile(
        file: File,
        pageIndex: Int,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val scale = targetWidth.toFloat() / page.width.toFloat()
                            val targetHeight = (page.height * scale).toInt().coerceAtLeast(50)
                            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(AndroidColor.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    // 1. MERGE PDFs
    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        initialize(context)
        onProgress(0.1f, "Preparing PDF documents...")
        val outputFile = File(getOutputDir(context), "Merged_${System.currentTimeMillis()}.pdf")
        val merger = PDFMergerUtility()
        merger.destinationFileName = outputFile.absolutePath

        val tempFiles = mutableListOf<File>()
        try {
            inputUris.forEachIndexed { index, uri ->
                val progress = 0.1f + (index.toFloat() / inputUris.size) * 0.7f
                onProgress(progress, "Adding document ${index + 1} of ${inputUris.size}...")
                val tempFile = copyUriToTemp(context, uri, "merge_src_$index")
                tempFiles.add(tempFile)
                merger.addSource(tempFile)
            }
            onProgress(0.85f, "Merging all pages into unified document...")
            merger.mergeDocuments(null)
            onProgress(1.0f, "Merge complete!")
        } finally {
            tempFiles.forEach { it.delete() }
        }
        outputFile
    }

    // 2. SPLIT PDF
    suspend fun splitPdf(
        context: Context,
        inputUri: Uri,
        pageRangeString: String,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        initialize(context)
        onProgress(0.1f, "Reading document structure...")
        val tempSource = copyUriToTemp(context, inputUri, "split_src")
        val outputFile = File(getOutputDir(context), "Split_${System.currentTimeMillis()}.pdf")

        try {
            PDDocument.load(tempSource).use { sourceDoc ->
                val totalPages = sourceDoc.numberOfPages
                val selectedIndices = parsePageRanges(pageRangeString, totalPages)
                if (selectedIndices.isEmpty()) {
                    throw IllegalArgumentException("No valid pages specified for extraction.")
                }

                val newDoc = PDDocument()
                selectedIndices.forEachIndexed { i, pageIdx ->
                    val progress = 0.2f + (i.toFloat() / selectedIndices.size) * 0.7f
                    onProgress(progress, "Extracting page ${pageIdx + 1}...")
                    newDoc.addPage(sourceDoc.getPage(pageIdx))
                }

                onProgress(0.95f, "Writing split document...")
                newDoc.save(outputFile)
                newDoc.close()
                onProgress(1.0f, "Split finished!")
            }
        } finally {
            tempSource.delete()
        }
        outputFile
    }

    private fun parsePageRanges(rangeStr: String, totalPages: Int): List<Int> {
        val result = mutableSetOf<Int>()
        if (rangeStr.isBlank()) {
            return (0 until totalPages).toList()
        }
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val rangeBounds = trimmed.split("-")
                val start = (rangeBounds.getOrNull(0)?.trim()?.toIntOrNull() ?: 1) - 1
                val end = (rangeBounds.getOrNull(1)?.trim()?.toIntOrNull() ?: totalPages) - 1
                for (p in start.coerceAtLeast(0)..end.coerceAtMost(totalPages - 1)) {
                    result.add(p)
                }
            } else {
                trimmed.toIntOrNull()?.let { pageNum ->
                    val idx = pageNum - 1
                    if (idx in 0 until totalPages) {
                        result.add(idx)
                    }
                }
            }
        }
        return result.sorted()
    }

    // 3. COMPRESS PDF
    suspend fun compressPdf(
        context: Context,
        inputUri: Uri,
        level: CompressionLevel,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        onProgress(0.1f, "Analyzing pages for compression...")
        val outputFile = File(getOutputDir(context), "Compressed_${System.currentTimeMillis()}.pdf")

        context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                val newPdfDoc = PdfDocument()

                for (i in 0 until pageCount) {
                    val progress = 0.1f + (i.toFloat() / pageCount) * 0.8f
                    onProgress(progress, "Compressing page ${i + 1} of $pageCount...")

                    renderer.openPage(i).use { page ->
                        val origW = page.width
                        val origH = page.height
                        val renderW = (origW * level.scale * 1.5f).toInt().coerceAtLeast(200)
                        val renderH = (origH * level.scale * 1.5f).toInt().coerceAtLeast(200)

                        val renderedBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                        renderedBitmap.eraseColor(AndroidColor.WHITE)
                        page.render(renderedBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        // Compress via JPEG stream
                        val stream = java.io.ByteArrayOutputStream()
                        renderedBitmap.compress(Bitmap.CompressFormat.JPEG, level.quality, stream)
                        renderedBitmap.recycle()

                        val compressedBytes = stream.toByteArray()
                        val compressedBitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                        // Write to standard PDF page size
                        val pageInfo = PdfDocument.PageInfo.Builder(origW, origH, i + 1).create()
                        val newPage = newPdfDoc.startPage(pageInfo)
                        val destRect = Rect(0, 0, origW, origH)
                        newPage.canvas.drawBitmap(compressedBitmap, null, destRect, null)
                        newPdfDoc.finishPage(newPage)
                        compressedBitmap.recycle()
                    }
                }

                onProgress(0.95f, "Finalizing compressed file...")
                FileOutputStream(outputFile).use { out ->
                    newPdfDoc.writeTo(out)
                }
                newPdfDoc.close()
                onProgress(1.0f, "Compression complete!")
            }
        } ?: throw IllegalStateException("Could not open input PDF.")

        outputFile
    }

    // 4. READ & WRITE METADATA
    suspend fun readMetadata(context: Context, inputUri: Uri): PdfMetadata = withContext(Dispatchers.IO) {
        initialize(context)
        val tempSource = copyUriToTemp(context, inputUri, "meta_read")
        val sizeFormatted = formatFileSize(getFileSize(context, inputUri))
        var metadata = PdfMetadata(fileSizeFormatted = sizeFormatted)

        try {
            PDDocument.load(tempSource).use { doc ->
                val info: PDDocumentInformation? = doc.documentInformation
                metadata = PdfMetadata(
                    title = info?.title.orEmpty(),
                    author = info?.author.orEmpty(),
                    subject = info?.subject.orEmpty(),
                    keywords = info?.keywords.orEmpty(),
                    creator = info?.creator.orEmpty(),
                    producer = info?.producer.orEmpty(),
                    pageCount = doc.numberOfPages,
                    fileSizeFormatted = sizeFormatted
                )
            }
        } finally {
            tempSource.delete()
        }
        metadata
    }

    suspend fun writeMetadata(
        context: Context,
        inputUri: Uri,
        metadata: PdfMetadata,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        initialize(context)
        onProgress(0.2f, "Opening document...")
        val tempSource = copyUriToTemp(context, inputUri, "meta_write")
        val outputFile = File(getOutputDir(context), "Updated_Meta_${System.currentTimeMillis()}.pdf")

        try {
            PDDocument.load(tempSource).use { doc ->
                onProgress(0.5f, "Updating document properties...")
                val info = doc.documentInformation ?: PDDocumentInformation()
                info.title = metadata.title
                info.author = metadata.author
                info.subject = metadata.subject
                info.keywords = metadata.keywords
                info.creator = metadata.creator
                doc.documentInformation = info

                onProgress(0.85f, "Writing changes...")
                doc.save(outputFile)
                onProgress(1.0f, "Metadata updated successfully!")
            }
        } finally {
            tempSource.delete()
        }
        outputFile
    }

    // 5. CONVERT IMAGES TO PDF
    suspend fun convertImagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        pageFit: String, // "A4", "FitImage", "Letter"
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        onProgress(0.1f, "Preparing images for conversion...")
        val outputFile = File(getOutputDir(context), "Images_${System.currentTimeMillis()}.pdf")
        val pdfDoc = PdfDocument()

        val standardWidth = if (pageFit == "Letter") 612 else 595
        val standardHeight = if (pageFit == "Letter") 792 else 842

        imageUris.forEachIndexed { index, uri ->
            val progress = 0.1f + (index.toFloat() / imageUris.size) * 0.8f
            onProgress(progress, "Processing image ${index + 1} of ${imageUris.size}...")

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    val (pageW, pageH) = if (pageFit == "FitImage") {
                        Pair(bitmap.width, bitmap.height)
                    } else {
                        // Orient page based on image orientation
                        if (bitmap.width > bitmap.height) Pair(standardHeight, standardWidth)
                        else Pair(standardWidth, standardHeight)
                    }

                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas

                    val scale = minOf(
                        pageW.toFloat() / bitmap.width.toFloat(),
                        pageH.toFloat() / bitmap.height.toFloat()
                    )
                    val scaledW = bitmap.width * scale
                    val scaledH = bitmap.height * scale
                    val left = (pageW - scaledW) / 2f
                    val top = (pageH - scaledH) / 2f

                    val destRect = Rect(left.toInt(), top.toInt(), (left + scaledW).toInt(), (top + scaledH).toInt())
                    canvas.drawBitmap(bitmap, null, destRect, null)
                    pdfDoc.finishPage(page)
                    bitmap.recycle()
                }
            }
        }

        onProgress(0.95f, "Generating PDF document...")
        FileOutputStream(outputFile).use { out ->
            pdfDoc.writeTo(out)
        }
        pdfDoc.close()
        onProgress(1.0f, "PDF conversion complete!")
        outputFile
    }

    // 6. CONVERT PDF TO IMAGES
    suspend fun convertPdfToImages(
        context: Context,
        inputUri: Uri,
        format: String, // "PNG" or "JPG"
        dpiFactor: Float = 2.0f, // 2.0x for crisp ~150 DPI
        onProgress: (Float, String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        val outDir = File(getOutputDir(context), "pdf_images_${System.currentTimeMillis()}").apply { mkdirs() }
        val outputImages = mutableListOf<File>()

        context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                for (i in 0 until pageCount) {
                    val progress = 0.1f + (i.toFloat() / pageCount) * 0.85f
                    onProgress(progress, "Rendering page ${i + 1} of $pageCount...")

                    renderer.openPage(i).use { page ->
                        val targetW = (page.width * dpiFactor).toInt()
                        val targetH = (page.height * dpiFactor).toInt()
                        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val ext = if (format.uppercase() == "PNG") "png" else "jpg"
                        val compressFormat = if (format.uppercase() == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        val imgFile = File(outDir, "Page_${i + 1}.$ext")
                        FileOutputStream(imgFile).use { out ->
                            bitmap.compress(compressFormat, 92, out)
                        }
                        bitmap.recycle()
                        outputImages.add(imgFile)
                    }
                }
            }
        } ?: throw IllegalStateException("Could not open PDF file.")

        onProgress(1.0f, "Extracted ${outputImages.size} images!")
        outputImages
    }

    // 7. ORGANIZE / REORDER PAGES
    suspend fun organizePages(
        context: Context,
        inputUri: Uri,
        pageItems: List<PageItem>,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        initialize(context)
        onProgress(0.1f, "Loading pages...")
        val tempSource = copyUriToTemp(context, inputUri, "organize_src")
        val outputFile = File(getOutputDir(context), "Organized_${System.currentTimeMillis()}.pdf")

        try {
            PDDocument.load(tempSource).use { sourceDoc ->
                val newDoc = PDDocument()
                val selected = pageItems.filter { it.isSelected }

                if (selected.isEmpty()) {
                    throw IllegalArgumentException("At least one page must remain in the document.")
                }

                selected.forEachIndexed { index, item ->
                    val progress = 0.2f + (index.toFloat() / selected.size) * 0.7f
                    onProgress(progress, "Reordering page ${index + 1}...")

                    val page = sourceDoc.getPage(item.pageIndex)
                    if (item.rotationDegrees != 0) {
                        page.rotation = (page.rotation + item.rotationDegrees) % 360
                    }
                    newDoc.addPage(page)
                }

                onProgress(0.95f, "Saving organized document...")
                newDoc.save(outputFile)
                newDoc.close()
                onProgress(1.0f, "Document reorganized successfully!")
            }
        } finally {
            tempSource.delete()
        }
        outputFile
    }

    // 8. ADD PAGE NUMBERS
    suspend fun addPageNumbers(
        context: Context,
        inputUri: Uri,
        format: PageNumberFormat,
        position: PageNumberPosition,
        startFromPage: Int = 1,
        fontSizePt: Float = 11f,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        onProgress(0.1f, "Reading pages...")
        val outputFile = File(getOutputDir(context), "Numbered_${System.currentTimeMillis()}.pdf")

        context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val totalPages = renderer.pageCount
                val pdfDoc = PdfDocument()

                val paint = Paint().apply {
                    color = AndroidColor.DKGRAY
                    textSize = fontSizePt * 1.5f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }

                for (i in 0 until totalPages) {
                    val progress = 0.1f + (i.toFloat() / totalPages) * 0.85f
                    onProgress(progress, "Stamping page ${i + 1} of $totalPages...")

                    renderer.openPage(i).use { page ->
                        val origW = page.width
                        val origH = page.height

                        // Render original page at 2x for sharp background
                        val scale = 2.0f
                        val bitmap = Bitmap.createBitmap((origW * scale).toInt(), (origH * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val pageInfo = PdfDocument.PageInfo.Builder(origW, origH, i + 1).create()
                        val newPage = pdfDoc.startPage(pageInfo)
                        val canvas = newPage.canvas

                        // Draw base page
                        canvas.drawBitmap(bitmap, null, Rect(0, 0, origW, origH), null)
                        bitmap.recycle()

                        // Check if numbering applies to this page
                        val pageNumDisplay = (i + 1) - (startFromPage - 1)
                        if (pageNumDisplay >= 1) {
                            val text = format.template
                                .replace("{n}", pageNumDisplay.toString())
                                .replace("{total}", (totalPages - (startFromPage - 1)).toString())

                            val margin = 28f
                            var x = origW / 2f
                            var y = origH - margin

                            when (position) {
                                PageNumberPosition.BOTTOM_CENTER -> {
                                    paint.textAlign = Paint.Align.CENTER
                                    x = origW / 2f
                                    y = origH - margin
                                }
                                PageNumberPosition.BOTTOM_RIGHT -> {
                                    paint.textAlign = Paint.Align.RIGHT
                                    x = origW - margin
                                    y = origH - margin
                                }
                                PageNumberPosition.BOTTOM_LEFT -> {
                                    paint.textAlign = Paint.Align.LEFT
                                    x = margin
                                    y = origH - margin
                                }
                                PageNumberPosition.TOP_CENTER -> {
                                    paint.textAlign = Paint.Align.CENTER
                                    x = origW / 2f
                                    y = margin + paint.textSize
                                }
                                PageNumberPosition.TOP_RIGHT -> {
                                    paint.textAlign = Paint.Align.RIGHT
                                    x = origW - margin
                                    y = margin + paint.textSize
                                }
                                PageNumberPosition.TOP_LEFT -> {
                                    paint.textAlign = Paint.Align.LEFT
                                    x = margin
                                    y = margin + paint.textSize
                                }
                            }

                            canvas.drawText(text, x, y, paint)
                        }

                        pdfDoc.finishPage(newPage)
                    }
                }

                onProgress(0.96f, "Saving numbered document...")
                FileOutputStream(outputFile).use { out ->
                    pdfDoc.writeTo(out)
                }
                pdfDoc.close()
                onProgress(1.0f, "Page numbers added!")
            }
        } ?: throw IllegalStateException("Could not open input PDF.")

        outputFile
    }

    suspend fun saveFileToDestination(context: Context, sourceFile: File, destinationUri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destinationUri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }
}
