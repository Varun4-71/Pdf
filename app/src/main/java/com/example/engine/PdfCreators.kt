package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import com.example.model.BlankTemplate
import com.example.model.PageSizePreset
import com.example.model.ScanFilter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TextPdfOptions(
    val pageSize: PageSizePreset = PageSizePreset.A4,
    val landscape: Boolean = false,
    val fontFamily: String = "sans-serif",
    val fontSizePt: Float = 12f,
    val marginPt: Float = 54f,
    val lineSpacing: Float = 1.2f
)

data class BlankPdfOptions(
    val pageSize: PageSizePreset = PageSizePreset.A4,
    val landscape: Boolean = false,
    val pages: Int = 1,
    val template: BlankTemplate = BlankTemplate.PLAIN
)

/** One picture destined for a PDF page. [rotation] is clockwise degrees chosen by the user. */
data class ImageInput(
    val uri: Uri,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.ORIGINAL
)

data class ImagePdfOptions(
    /** null = every page takes the size of its image. */
    val pageSize: PageSizePreset? = PageSizePreset.A4,
    val marginPt: Float = 0f,
    val jpegQuality: Int = 85,
    val maxDimension: Int = 2000
)

/** Creates PDFs from text, from nothing, and from pictures or camera scans. */
object PdfCreators {

    // ---------------------------------------------------------------- text

    suspend fun textToPdf(
        context: Context,
        text: String,
        options: TextPdfOptions,
        baseName: String
    ): File = withContext(Dispatchers.IO) {
        if (text.isBlank()) throw IllegalArgumentException("There is no text to convert.")
        val out = FileUtils.newFile(context, baseName, "pdf")
        val w = if (options.landscape) options.pageSize.heightPt else options.pageSize.widthPt
        val h = if (options.landscape) options.pageSize.widthPt else options.pageSize.heightPt
        val pdf = PdfDocument()
        try {
            val m = options.marginPt
            val flow = PdfFlow(pdf, w, h, m, m, m, m)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = options.fontSizePt
            paint.color = Color.BLACK
            paint.typeface = Typeface.create(options.fontFamily, Typeface.NORMAL)
            val width = flow.contentWidth.roundToInt().coerceAtLeast(60)
            val lineHeight = options.fontSizePt * options.lineSpacing
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
            flow.newPage()
            for (line in normalized.split('\n')) {
                if (line.isEmpty()) {
                    flow.ensureSpace(lineHeight)
                    flow.y += lineHeight
                    continue
                }
                val layout = StaticLayout.Builder.obtain(line, 0, line.length, paint, width)
                    .setLineSpacing(0f, options.lineSpacing)
                    .setIncludePad(false)
                    .build()
                flow.drawLayout(layout, flow.marginL)
            }
            flow.finish()
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        out
    }

    // ---------------------------------------------------------------- blank

    suspend fun blankPdf(context: Context, options: BlankPdfOptions): File = withContext(Dispatchers.IO) {
        val count = options.pages.coerceIn(1, 500)
        val out = FileUtils.newFile(context, "Blank", "pdf")
        val w = if (options.landscape) options.pageSize.heightPt else options.pageSize.widthPt
        val h = if (options.landscape) options.pageSize.widthPt else options.pageSize.heightPt
        val pdf = PdfDocument()
        try {
            for (i in 1..count) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i).create())
                drawTemplate(page.canvas, w, h, options.template)
                pdf.finishPage(page)
            }
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        out
    }

    private fun drawTemplate(c: Canvas, w: Int, h: Int, template: BlankTemplate) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(190, 198, 212)
        paint.strokeWidth = 0.6f
        when (template) {
            BlankTemplate.PLAIN -> {
            }
            BlankTemplate.LINED -> {
                var y = 72f
                while (y < h - 40f) {
                    c.drawLine(40f, y, w - 40f, y, paint)
                    y += 26f
                }
            }
            BlankTemplate.GRID -> {
                var x = 20f
                while (x < w) {
                    c.drawLine(x, 0f, x, h.toFloat(), paint)
                    x += 20f
                }
                var y = 20f
                while (y < h) {
                    c.drawLine(0f, y, w.toFloat(), y, paint)
                    y += 20f
                }
            }
            BlankTemplate.DOTTED -> {
                paint.style = Paint.Style.FILL
                var y = 30f
                while (y < h - 20f) {
                    var x = 30f
                    while (x < w - 20f) {
                        c.drawCircle(x, y, 0.9f, paint)
                        x += 20f
                    }
                    y += 20f
                }
            }
        }
    }

    // ---------------------------------------------------------------- images and scans

    suspend fun imagesToPdf(
        context: Context,
        images: List<ImageInput>,
        options: ImagePdfOptions,
        baseName: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        if (images.isEmpty()) throw IllegalArgumentException("Add at least one image.")
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, baseName, "pdf")
        val doc = PDDocument()
        try {
            for ((index, input) in images.withIndex()) {
                onProgress(index.toFloat() / images.size, "Adding image ${index + 1} of ${images.size}")
                val bmp = loadPrepared(context, input, options.maxDimension)
                    ?: throw IOException("Image ${index + 1} could not be read.")
                try {
                    addImagePage(doc, bmp, options)
                } finally {
                    bmp.recycle()
                }
            }
            onProgress(0.95f, "Saving PDF")
            doc.save(out)
        } finally {
            doc.close()
        }
        out
    }

    private fun addImagePage(doc: PDDocument, bmp: Bitmap, options: ImagePdfOptions) {
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality.coerceIn(30, 100), bytes)
        val image = JPEGFactory.createFromStream(doc, ByteArrayInputStream(bytes.toByteArray()))
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val preset = options.pageSize
        val pageW: Float
        val pageH: Float
        if (preset == null) {
            val scale = min(1f, 842f / max(iw, ih))
            pageW = iw * scale
            pageH = ih * scale
        } else {
            val landscape = iw > ih
            pageW = (if (landscape) preset.heightPt else preset.widthPt).toFloat()
            pageH = (if (landscape) preset.widthPt else preset.heightPt).toFloat()
        }
        val margin = if (preset == null) 0f else options.marginPt
        val availW = (pageW - 2f * margin).coerceAtLeast(10f)
        val availH = (pageH - 2f * margin).coerceAtLeast(10f)
        val scale = min(availW / iw, availH / ih)
        val drawW = iw * scale
        val drawH = ih * scale
        val x = (pageW - drawW) / 2f
        val y = (pageH - drawH) / 2f
        val page = PDPage(PDRectangle(pageW, pageH))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(image, x, y, drawW, drawH)
        }
    }

    /** Decodes, rotates (EXIF + user), filters and flattens a picture onto white. */
    private fun loadPrepared(context: Context, input: ImageInput, maxDim: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        resolver.openInputStream(input.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var decodedOrNull: Bitmap? = null
        try {
            decodedOrNull = resolver.openInputStream(input.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: OutOfMemoryError) {
            decodedOrNull = null
        }
        val decoded: Bitmap = decodedOrNull ?: return null

        var exifRotation = 0
        try {
            resolver.openInputStream(input.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                exifRotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            exifRotation = 0
        }
        val totalRotation = ((exifRotation + input.rotation) % 360 + 360) % 360
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f

        val matrix = Matrix()
        matrix.postRotate(totalRotation.toFloat())
        matrix.postScale(scale, scale)
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()

        val flat = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val filter = colorFilterFor(input.filter)
        if (filter != null) paint.colorFilter = filter
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        rotated.recycle()
        return flat
    }

    private fun contrastMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val t = 128f * (1f - contrast) + brightness
        return ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun colorFilterFor(filter: ScanFilter): ColorMatrixColorFilter? {
        return when (filter) {
            ScanFilter.ORIGINAL -> null
            ScanFilter.ENHANCED -> ColorMatrixColorFilter(contrastMatrix(1.3f, 12f))
            ScanFilter.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                ColorMatrixColorFilter(cm)
            }
            ScanFilter.BLACK_WHITE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                cm.postConcat(contrastMatrix(2.6f, 30f))
                ColorMatrixColorFilter(cm)
            }
        }
    }

    /** Small preview used by the scan screen so filters can be judged before creating the PDF. */
    suspend fun previewBitmap(context: Context, input: ImageInput, maxDim: Int = 480): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                loadPrepared(context, input, maxDim)
            } catch (e: Exception) {
                null
            }
        }
}
