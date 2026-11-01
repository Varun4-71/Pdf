package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.model.FileKind
import com.example.model.ImagePdfOptionsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Decides how each picked file becomes a PDF and runs the matching converter. */
object ConvertRouter {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    private val textExtensions = setOf("txt", "md", "log", "csv", "json", "xml", "html", "htm", "ini", "yaml", "yml", "tsv")

    fun kindOf(name: String, mime: String?): FileKind {
        val ext = FileUtils.extensionOf(name)
        if (ext == "pdf" || mime == "application/pdf") return FileKind.PDF
        if (ext == "docx" || ext == "docm" || ext == "dotx") return FileKind.WORD
        if (ext == "xlsx" || ext == "xlsm" || ext == "xltx") return FileKind.EXCEL
        if (ext == "pptx" || ext == "pptm" || ext == "potx" || ext == "ppsx") return FileKind.POWERPOINT
        if (ext in imageExtensions || (mime != null && mime.startsWith("image/"))) return FileKind.IMAGE
        if (ext in textExtensions || (mime != null && mime.startsWith("text/"))) return FileKind.TEXT
        return FileKind.UNSUPPORTED
    }

    /** Friendly explanation for formats we cannot read, such as the old binary Office files. */
    fun unsupportedReason(name: String): String {
        return when (FileUtils.extensionOf(name)) {
            "doc" -> "Old .doc files are not supported. Open it in Word and save as .docx."
            "xls" -> "Old .xls files are not supported. Open it in Excel and save as .xlsx."
            "ppt" -> "Old .ppt files are not supported. Open it in PowerPoint and save as .pptx."
            "odt", "ods", "odp" -> "OpenDocument files are not supported yet. Export to .docx, .xlsx or .pptx first."
            "rtf" -> "RTF files are not supported yet. Save the document as .docx first."
            else -> "This file type cannot be converted."
        }
    }

    /**
     * Converts one file to a PDF in the cache and returns it. PDFs are copied unchanged.
     * Runs off the main thread.
     */
    suspend fun toPdf(
        context: Context,
        uri: Uri,
        name: String,
        kind: FileKind,
        imageOptions: ImagePdfOptionsHolder = ImagePdfOptionsHolder()
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val base = FileUtils.baseNameOf(name)
        when (kind) {
            FileKind.PDF -> FileUtils.copyToCache(context, uri, "pdf", base)
            FileKind.WORD -> {
                val src = FileUtils.copyToCache(context, uri, "docx", "word")
                val out = FileUtils.newFile(context, base, "pdf")
                try {
                    DocxToPdf.convert(src, out)
                } catch (e: Exception) {
                    throw IOException("\"$name\" could not be read as a Word document. ${e.message ?: ""}".trim())
                } finally {
                    src.delete()
                }
                out
            }
            FileKind.EXCEL -> {
                val src = FileUtils.copyToCache(context, uri, "xlsx", "sheet")
                val out = FileUtils.newFile(context, base, "pdf")
                try {
                    XlsxToPdf.convert(src, out)
                } catch (e: Exception) {
                    throw IOException("\"$name\" could not be read as an Excel workbook. ${e.message ?: ""}".trim())
                } finally {
                    src.delete()
                }
                out
            }
            FileKind.POWERPOINT -> {
                val src = FileUtils.copyToCache(context, uri, "pptx", "slides")
                val out = FileUtils.newFile(context, base, "pdf")
                try {
                    PptxToPdf.convert(src, out)
                } catch (e: Exception) {
                    throw IOException("\"$name\" could not be read as a PowerPoint file. ${e.message ?: ""}".trim())
                } finally {
                    src.delete()
                }
                out
            }
            FileKind.IMAGE -> {
                PdfCreators.imagesToPdf(
                    context,
                    listOf(ImageInput(uri)),
                    ImagePdfOptions(
                        pageSize = imageOptions.pageSize,
                        marginPt = imageOptions.marginPt,
                        jpegQuality = imageOptions.jpegQuality,
                        maxDimension = imageOptions.maxDimension
                    ),
                    base
                )
            }
            FileKind.TEXT -> {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IOException("Could not read \"$name\".")
                val mono = FileUtils.extensionOf(name) != "txt" && FileUtils.extensionOf(name) != "md"
                PdfCreators.textToPdf(
                    context,
                    text,
                    TextPdfOptions(fontFamily = if (mono) "monospace" else "sans-serif", fontSizePt = if (mono) 10f else 12f),
                    base
                )
            }
            FileKind.UNSUPPORTED -> throw IOException(unsupportedReason(name))
        }
    }
}
