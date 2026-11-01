package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Marks the kinds of overlay content this app adds to pages so it can remove them again. */
enum class LayerKind(val tag: String) {
    BACKGROUND("background"),
    WATERMARK("watermark"),
    HEADER_FOOTER("headerfooter")
}

data class WatermarkSpec(
    val text: String = "",
    val imageFile: File? = null,
    val fontSize: Float = 64f,
    val colorRgb: Int = 0x808080,
    val opacity: Float = 0.25f,
    val angleDeg: Float = 45f,
    val imageScale: Float = 0.5f,
    val tiled: Boolean = false,
    val bold: Boolean = true
)

data class BackgroundSpec(
    val colorRgb: Int? = null,
    val imageFile: File? = null,
    val opacity: Float = 1f
)

data class HeaderFooterSpec(
    val headerLeft: String = "",
    val headerCenter: String = "",
    val headerRight: String = "",
    val footerLeft: String = "",
    val footerCenter: String = "",
    val footerRight: String = "",
    val fontSize: Float = 10f,
    val margin: Float = 28f,
    val colorRgb: Int = 0x333333,
    val skipFirstPage: Boolean = false,
    val bold: Boolean = false,
    val startNumber: Int = 1
)

private fun rgbParts(rgb: Int): IntArray {
    return intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}

/** Everything that changes a PDF's pages: order, size, content layers, transitions. */
object PageOps {

    private const val LAYER_KEY = "PDFKitLayer"

    /** One page of the output: which source file, which page in it, and extra rotation. */
    data class PageRef(val source: Int, val page: Int, val rotationDelta: Int = 0)

    private fun open(file: File): PDDocument {
        return PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L))
    }

    private fun normRot(v: Int): Int = ((v % 360) + 360) % 360

    fun pageCountOf(file: File): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r -> r.pageCount }
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun <T> withDoc(input: File, out: File, block: (PDDocument) -> T): T {
        val doc = open(input)
        try {
            val result = block(doc)
            doc.save(out)
            return result
        } finally {
            doc.close()
        }
    }

    // ---------------------------------------------------------------- rebuild (reorder, insert, replace, extract)

    suspend fun rebuild(
        context: Context,
        sources: List<File>,
        order: List<PageRef>,
        baseName: String = "Edited",
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        if (order.isEmpty()) throw IllegalArgumentException("The document needs at least one page.")
        val out = FileUtils.newFile(context, baseName, "pdf")
        val docs = ArrayList<PDDocument>()
        val result = PDDocument()
        try {
            for (f in sources) docs.add(open(f))
            for ((i, ref) in order.withIndex()) {
                if (i % 4 == 0) onProgress(0.05f + 0.85f * i / order.size, "Adding page ${i + 1} of ${order.size}")
                val src = docs[ref.source]
                val imported = result.importPage(src.getPage(ref.page))
                if (ref.rotationDelta != 0) imported.rotation = normRot(imported.rotation + ref.rotationDelta)
            }
            try {
                if (docs.isNotEmpty()) result.documentInformation = docs[0].documentInformation
            } catch (e: Exception) {
                // metadata is optional
            }
            onProgress(0.95f, "Saving")
            result.save(out)
        } finally {
            try {
                result.close()
            } catch (e: Exception) {
                // ignore
            }
            for (d in docs) {
                try {
                    d.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        out
    }

    /** Joins whole PDF files, in the order given, into one PDF. */
    suspend fun mergeFiles(
        context: Context,
        files: List<File>,
        baseName: String = "Combined",
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File {
        val order = ArrayList<PageRef>()
        for ((si, f) in files.withIndex()) {
            val n = pageCountOf(f)
            if (n <= 0) {
                throw IllegalArgumentException("File ${si + 1} has no readable pages. Password-protected PDFs can't be combined.")
            }
            for (p in 0 until n) order.add(PageRef(si, p))
        }
        return rebuild(context, files, order, baseName, onProgress)
    }

    suspend fun extractPages(context: Context, input: File, indices: List<Int>, baseName: String = "Extracted"): File {
        return rebuild(context, listOf(input), indices.map { PageRef(0, it) }, baseName)
    }

    suspend fun insertPages(
        context: Context,
        target: File,
        targetPages: Int,
        atIndex: Int,
        source: File,
        sourceIndices: List<Int>
    ): File {
        val at = atIndex.coerceIn(0, targetPages)
        val order = ArrayList<PageRef>()
        for (i in 0 until at) order.add(PageRef(0, i))
        for (p in sourceIndices) order.add(PageRef(1, p))
        for (i in at until targetPages) order.add(PageRef(0, i))
        return rebuild(context, listOf(target, source), order, "Inserted")
    }

    /** Replaces the target pages with the chosen source pages, placed where the first replaced page was. */
    suspend fun replacePages(
        context: Context,
        target: File,
        targetPages: Int,
        replaceIndices: Collection<Int>,
        source: File,
        sourceIndices: List<Int>
    ): File {
        val replaced = replaceIndices.toSet()
        if (replaced.isEmpty()) throw IllegalArgumentException("Choose the pages to replace.")
        val firstReplaced = replaced.minOrNull() ?: 0
        val order = ArrayList<PageRef>()
        for (i in 0 until targetPages) {
            if (i == firstReplaced) {
                for (p in sourceIndices) order.add(PageRef(1, p))
            }
            if (!replaced.contains(i)) order.add(PageRef(0, i))
        }
        return rebuild(context, listOf(target, source), order, "Replaced")
    }

    suspend fun splitPages(
        context: Context,
        input: File,
        groups: List<List<Int>>,
        baseName: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): List<OutputFile> = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val outputs = ArrayList<OutputFile>()
        val src = open(input)
        try {
            for ((gi, group) in groups.withIndex()) {
                onProgress(gi.toFloat() / groups.size, "Creating file ${gi + 1} of ${groups.size}")
                if (group.isEmpty()) continue
                val doc = PDDocument()
                try {
                    for (p in group) doc.importPage(src.getPage(p))
                    val label = if (group.size == 1) {
                        "page" + (group[0] + 1)
                    } else {
                        "pages" + (group.minOrNull()!! + 1) + "-" + (group.maxOrNull()!! + 1)
                    }
                    val file = FileUtils.newFile(context, "${baseName}_$label", "pdf")
                    doc.save(file)
                    outputs.add(OutputFile(file, FileUtils.safeName("${baseName}_$label") + ".pdf"))
                } finally {
                    doc.close()
                }
            }
        } finally {
            src.close()
        }
        outputs
    }

    // ---------------------------------------------------------------- in-place page edits

    suspend fun deletePages(context: Context, input: File, indices: Collection<Int>): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "Edited", "pdf")
        withDoc(input, out) { doc ->
            val unique = indices.distinct().sortedDescending()
            if (unique.size >= doc.numberOfPages) throw IllegalArgumentException("You can't delete every page.")
            for (i in unique) doc.removePage(i)
        }
        out
    }

    suspend fun rotatePages(context: Context, input: File, indices: Collection<Int>, degrees: Int): File =
        withContext(Dispatchers.IO) {
            PdfEngine.initialize(context)
            val out = FileUtils.newFile(context, "Rotated", "pdf")
            withDoc(input, out) { doc ->
                for (i in indices) {
                    val page = doc.getPage(i)
                    page.rotation = normRot(page.rotation + degrees)
                }
            }
            out
        }

    /** Margins are fractions (0..0.45) of the page as it appears on screen. */
    suspend fun cropPages(
        context: Context,
        input: File,
        indices: Collection<Int>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "Cropped", "pdf")
        withDoc(input, out) { doc ->
            for (i in indices) {
                val page = doc.getPage(i)
                val cb = page.cropBox
                val rot = normRot(page.rotation)
                val w = cb.width
                val h = cb.height
                val visW = if (rot == 90 || rot == 270) h else w
                val visH = if (rot == 90 || rot == 270) w else h
                val ml = left * visW
                val mt = top * visH
                val mr = right * visW
                val mb = bottom * visH
                val uL: Float
                val uR: Float
                val uT: Float
                val uB: Float
                when (rot) {
                    90 -> {
                        uB = ml; uT = mr; uL = mt; uR = mb
                    }
                    180 -> {
                        uR = ml; uL = mr; uB = mt; uT = mb
                    }
                    270 -> {
                        uT = ml; uB = mr; uR = mt; uL = mb
                    }
                    else -> {
                        uL = ml; uR = mr; uT = mt; uB = mb
                    }
                }
                val nw = w - uL - uR
                val nh = h - uT - uB
                if (nw < 20f || nh < 20f) {
                    throw IllegalArgumentException("The crop is too large. Nothing would be left of page ${i + 1}.")
                }
                page.cropBox = PDRectangle(cb.lowerLeftX + uL, cb.lowerLeftY + uB, nw, nh)
            }
        }
        out
    }

    /** Scales page content to fit a new paper size (kept in the page's current orientation). */
    suspend fun resizePages(
        context: Context,
        input: File,
        indices: Collection<Int>,
        targetWidthPt: Float,
        targetHeightPt: Float
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "Resized", "pdf")
        withDoc(input, out) { doc ->
            for (i in indices) {
                val page = doc.getPage(i)
                val cb = page.cropBox
                val rot = normRot(page.rotation)
                val userW = cb.width
                val userH = cb.height
                val sideways = rot == 90 || rot == 270
                val visW = if (sideways) userH else userW
                val visH = if (sideways) userW else userH
                var tW = targetWidthPt
                var tH = targetHeightPt
                if ((visW > visH) != (tW > tH)) {
                    val t = tW
                    tW = tH
                    tH = t
                }
                val s = min(tW / visW, tH / visH)
                val boxW = if (sideways) tH else tW
                val boxH = if (sideways) tW else tH
                val tx = (boxW - userW * s) / 2f - cb.lowerLeftX * s
                val ty = (boxH - userH * s) / 2f - cb.lowerLeftY * s
                val m = PdfMatrix(s, 0f, 0f, s, tx, ty)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(m)
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                    cs.restoreGraphicsState()
                }
                try {
                    for (a in page.annotations) {
                        val r = a.rectangle
                        if (r != null) {
                            a.rectangle = PDRectangle(
                                r.lowerLeftX * s + tx,
                                r.lowerLeftY * s + ty,
                                r.width * s,
                                r.height * s
                            )
                        }
                    }
                } catch (e: Exception) {
                    // annotations are best effort
                }
                page.mediaBox = PDRectangle(0f, 0f, boxW, boxH)
                page.cropBox = PDRectangle(0f, 0f, boxW, boxH)
            }
        }
        out
    }

    suspend fun extractForPrint(
        context: Context,
        input: File,
        pages: List<Int>?,
        includeAnnotations: Boolean
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "print", "pdf")
        val src = open(input)
        val dst = PDDocument()
        try {
            val list = pages ?: (0 until src.numberOfPages).toList()
            for (p in list) {
                val imported = dst.importPage(src.getPage(p))
                if (!includeAnnotations) {
                    try {
                        imported.annotations = ArrayList<PDAnnotation>()
                    } catch (e: Exception) {
                        // keep annotations if they cannot be removed
                    }
                }
            }
            dst.save(out)
        } finally {
            dst.close()
            src.close()
        }
        out
    }

    // ---------------------------------------------------------------- transitions

    suspend fun setTransitions(
        context: Context,
        input: File,
        indices: Collection<Int>,
        style: String?,
        durationSec: Float,
        autoAdvanceSec: Float?
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "Presentation", "pdf")
        val transKey = COSName.getPDFName("Trans")
        val durKey = COSName.getPDFName("Dur")
        withDoc(input, out) { doc ->
            for (i in indices) {
                val dict = doc.getPage(i).cosObject
                if (style == null) {
                    dict.removeItem(transKey)
                    dict.removeItem(durKey)
                } else {
                    val t = COSDictionary()
                    t.setItem(COSName.getPDFName("Type"), COSName.getPDFName("Trans"))
                    t.setItem(COSName.getPDFName("S"), COSName.getPDFName(style))
                    t.setFloat(COSName.getPDFName("D"), durationSec)
                    dict.setItem(transKey, t)
                    if (autoAdvanceSec != null && autoAdvanceSec > 0f) {
                        dict.setFloat(durKey, autoAdvanceSec)
                    } else {
                        dict.removeItem(durKey)
                    }
                }
            }
        }
        out
    }

    // ---------------------------------------------------------------- overlay layers

    private class PageGeo(
        val w: Float,
        val h: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val e: Float,
        val f: Float
    )

    /** Describes the page as the reader sees it and how to map that view back to PDF user space. */
    private fun geometry(page: PDPage): PageGeo {
        val cb = page.cropBox
        val rot = normRot(page.rotation)
        val cw = cb.width
        val ch = cb.height
        val ox = cb.lowerLeftX
        val oy = cb.lowerLeftY
        return when (rot) {
            90 -> PageGeo(ch, cw, 0f, 1f, -1f, 0f, cw + ox, oy)
            180 -> PageGeo(cw, ch, -1f, 0f, 0f, -1f, cw + ox, ch + oy)
            270 -> PageGeo(ch, cw, 0f, -1f, 1f, 0f, ox, ch + oy)
            else -> PageGeo(cw, ch, 1f, 0f, 0f, 1f, ox, oy)
        }
    }

    private fun streamsOf(page: PDPage): List<COSBase> {
        val list = ArrayList<COSBase>()
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        if (contents is COSArray) {
            for (i in 0 until contents.size()) {
                val item = contents.getObject(i)
                if (item != null) list.add(item)
            }
        } else if (contents != null) {
            list.add(contents)
        }
        return list
    }

    /** Draws into a fresh content stream that is tagged so it can be found and removed later. */
    private fun addLayer(
        doc: PDDocument,
        page: PDPage,
        prepend: Boolean,
        kind: LayerKind,
        draw: (PDPageContentStream, PageGeo) -> Unit
    ) {
        val before = streamsOf(page)
        val geo = geometry(page)
        val mode = if (prepend) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
        val cs = PDPageContentStream(doc, page, mode, true, !prepend)
        try {
            cs.saveGraphicsState()
            cs.transform(PdfMatrix(geo.a, geo.b, geo.c, geo.d, geo.e, geo.f))
            draw(cs, geo)
            cs.restoreGraphicsState()
        } finally {
            cs.close()
        }
        for (s in streamsOf(page)) {
            var known = false
            for (b in before) {
                if (b === s) known = true
            }
            if (!known && s is COSStream) {
                s.setString(COSName.getPDFName(LAYER_KEY), kind.tag)
            }
        }
    }

    private fun applyOpacity(cs: PDPageContentStream, opacity: Float) {
        if (opacity >= 0.995f) return
        val gs = PDExtendedGraphicsState()
        gs.setStrokingAlphaConstant(opacity.coerceIn(0.02f, 1f))
        gs.setNonStrokingAlphaConstant(opacity.coerceIn(0.02f, 1f))
        cs.setGraphicsStateParameters(gs)
    }

    /** Text that is drawn as real PDF text when possible and as a picture for non-Latin scripts. */
    private class Ink(
        private val doc: PDDocument,
        rawText: String,
        val size: Float,
        private val rgb: Int,
        bold: Boolean
    ) {
        val text: String = rawText.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        private val isBold = bold
        private val font: PDType1Font = if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        private val vectorWidth: Float? = try {
            font.getStringWidth(text) / 1000f * size
        } catch (e: Exception) {
            null
        }
        private var image: PDImageXObject? = null
        private var imgW = 0f
        private var imgH = 0f
        private var imgBase = 0f

        val width: Float
            get() {
                val v = vectorWidth
                if (v != null) return v
                ensureRaster()
                return imgW
            }

        private fun ensureRaster() {
            if (image != null) return
            val scale = 3f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = size * scale
            paint.color = 0xFF000000.toInt() or rgb
            paint.typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fm = paint.fontMetrics
            val wpx = ceil(paint.measureText(text)).toInt() + 8
            val hpx = ceil(fm.descent - fm.ascent).toInt() + 8
            val bmp = Bitmap.createBitmap(max(wpx, 8), max(hpx, 8), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawText(text, 4f, 4f - fm.ascent, paint)
            image = LosslessFactory.createFromImage(doc, bmp)
            imgW = bmp.width / scale
            imgH = bmp.height / scale
            imgBase = (bmp.height - (4f - fm.ascent)) / scale
            bmp.recycle()
        }

        fun draw(cs: PDPageContentStream, x: Float, y: Float, angleDeg: Float) {
            if (text.isBlank()) return
            val v = vectorWidth
            if (v != null) {
                val c = rgbParts(rgb)
                cs.beginText()
                cs.setFont(font, size)
                cs.setNonStrokingColor(c[0], c[1], c[2])
                if (angleDeg == 0f) {
                    cs.newLineAtOffset(x, y)
                } else {
                    cs.setTextMatrix(PdfMatrix.getRotateInstance(Math.toRadians(angleDeg.toDouble()), x, y))
                }
                cs.showText(text)
                cs.endText()
            } else {
                ensureRaster()
                val img = image ?: return
                cs.saveGraphicsState()
                cs.transform(PdfMatrix.getRotateInstance(Math.toRadians(angleDeg.toDouble()), x, y))
                cs.drawImage(img, 0f, -imgBase, imgW, imgH)
                cs.restoreGraphicsState()
            }
        }
    }

    private fun loadBitmapFile(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun jpegImage(doc: PDDocument, bmp: Bitmap): PDImageXObject {
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
        return JPEGFactory.createFromStream(doc, ByteArrayInputStream(bytes.toByteArray()))
    }

    suspend fun addBackground(context: Context, input: File, indices: Collection<Int>, spec: BackgroundSpec): File =
        withContext(Dispatchers.IO) {
            PdfEngine.initialize(context)
            if (spec.colorRgb == null && spec.imageFile == null) {
                throw IllegalArgumentException("Choose a colour or a picture for the background.")
            }
            val out = FileUtils.newFile(context, "Background", "pdf")
            withDoc(input, out) { doc ->
                val color = spec.colorRgb
                val imageFile = spec.imageFile
                var image: PDImageXObject? = null
                var imageRatio = 1f
                if (imageFile != null) {
                    val bmp = loadBitmapFile(imageFile, 2000)
                        ?: throw IllegalArgumentException("The background picture could not be read.")
                    imageRatio = bmp.height.toFloat() / bmp.width.toFloat()
                    image = jpegImage(doc, bmp)
                    bmp.recycle()
                }
                val bgImage: PDImageXObject? = image
                val bgRatio = imageRatio
                for (i in indices) {
                    val page = doc.getPage(i)
                    addLayer(doc, page, true, LayerKind.BACKGROUND) { cs, geo ->
                        applyOpacity(cs, spec.opacity)
                        if (color != null) {
                            val c = rgbParts(color)
                            cs.setNonStrokingColor(c[0], c[1], c[2])
                            cs.addRect(0f, 0f, geo.w, geo.h)
                            cs.fill()
                        }
                        if (bgImage != null) {
                            // cover the page, cropping the overflow
                            var dw = geo.w
                            var dh = dw * bgRatio
                            if (dh < geo.h) {
                                dh = geo.h
                                dw = dh / bgRatio
                            }
                            cs.drawImage(bgImage, (geo.w - dw) / 2f, (geo.h - dh) / 2f, dw, dh)
                        }
                    }
                }
            }
            out
        }

    suspend fun addWatermark(context: Context, input: File, indices: Collection<Int>, spec: WatermarkSpec): File =
        withContext(Dispatchers.IO) {
            PdfEngine.initialize(context)
            if (spec.text.isBlank() && spec.imageFile == null) {
                throw IllegalArgumentException("Type the watermark text or choose a picture.")
            }
            val out = FileUtils.newFile(context, "Watermarked", "pdf")
            withDoc(input, out) { doc ->
                val ink = if (spec.text.isNotBlank()) Ink(doc, spec.text, spec.fontSize, spec.colorRgb, spec.bold) else null
                val imageFile = spec.imageFile
                var image: PDImageXObject? = null
                var imageRatio = 1f
                if (imageFile != null) {
                    val bmp = loadBitmapFile(imageFile, 1400)
                        ?: throw IllegalArgumentException("The watermark picture could not be read.")
                    imageRatio = bmp.height.toFloat() / bmp.width.toFloat()
                    image = LosslessFactory.createFromImage(doc, bmp)
                    bmp.recycle()
                }
                val wmImage: PDImageXObject? = image
                val wmRatio = imageRatio
                val rad = Math.toRadians(spec.angleDeg.toDouble())
                for (i in indices) {
                    val page = doc.getPage(i)
                    addLayer(doc, page, false, LayerKind.WATERMARK) { cs, geo ->
                        applyOpacity(cs, spec.opacity)
                        val cx = geo.w / 2f
                        val cy = geo.h / 2f
                        if (ink != null) {
                            val w = ink.width
                            if (spec.tiled) {
                                val stepX = w * 1.8f + 40f
                                val stepY = ink.size * 6f
                                val diag = hypot(geo.w, geo.h)
                                cs.saveGraphicsState()
                                cs.transform(PdfMatrix.getRotateInstance(rad, cx, cy))
                                var yy = -diag / 2f
                                var row = 0
                                while (yy <= diag / 2f) {
                                    var xx = -diag / 2f + (if (row % 2 == 0) 0f else stepX / 2f)
                                    while (xx <= diag / 2f) {
                                        ink.draw(cs, cx + xx, cy + yy, 0f)
                                        xx += stepX
                                    }
                                    yy += stepY
                                    row++
                                }
                                cs.restoreGraphicsState()
                            } else {
                                val cosA = Math.cos(rad).toFloat()
                                val sinA = Math.sin(rad).toFloat()
                                val sx = cx - (w / 2f) * cosA + 0.35f * ink.size * sinA
                                val sy = cy - (w / 2f) * sinA - 0.35f * ink.size * cosA
                                ink.draw(cs, sx, sy, spec.angleDeg)
                            }
                        }
                        if (wmImage != null) {
                            val w = geo.w * spec.imageScale.coerceIn(0.05f, 1f)
                            val h = w * wmRatio
                            cs.saveGraphicsState()
                            cs.transform(PdfMatrix.getRotateInstance(rad, cx, cy))
                            cs.drawImage(wmImage, -w / 2f, -h / 2f, w, h)
                            cs.restoreGraphicsState()
                        }
                    }
                }
            }
            out
        }

    suspend fun addHeaderFooter(
        context: Context,
        input: File,
        indices: Collection<Int>,
        spec: HeaderFooterSpec,
        docName: String
    ): File = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val any = spec.headerLeft.isNotBlank() || spec.headerCenter.isNotBlank() || spec.headerRight.isNotBlank() ||
            spec.footerLeft.isNotBlank() || spec.footerCenter.isNotBlank() || spec.footerRight.isNotBlank()
        if (!any) throw IllegalArgumentException("Type at least one header or footer text.")
        val out = FileUtils.newFile(context, "Header_footer", "pdf")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        withDoc(input, out) { doc ->
            val total = doc.numberOfPages
            for (i in indices) {
                if (spec.skipFirstPage && i == 0) continue
                val page = doc.getPage(i)
                val number = i + spec.startNumber
                addLayer(doc, page, false, LayerKind.HEADER_FOOTER) { cs, geo ->
                    fun resolve(t: String): String {
                        return t.replace("{page}", number.toString())
                            .replace("{total}", total.toString())
                            .replace("{date}", date)
                            .replace("{name}", docName)
                    }

                    fun place(text: String, align: Int, y: Float) {
                        val resolved = resolve(text)
                        if (resolved.isBlank()) return
                        val ink = Ink(doc, resolved, spec.fontSize, spec.colorRgb, spec.bold)
                        val w = ink.width
                        val x = when (align) {
                            0 -> spec.margin
                            1 -> (geo.w - w) / 2f
                            else -> geo.w - spec.margin - w
                        }
                        ink.draw(cs, x, y, 0f)
                    }

                    val topY = geo.h - spec.margin - spec.fontSize * 0.8f
                    val bottomY = spec.margin
                    place(spec.headerLeft, 0, topY)
                    place(spec.headerCenter, 1, topY)
                    place(spec.headerRight, 2, topY)
                    place(spec.footerLeft, 0, bottomY)
                    place(spec.footerCenter, 1, bottomY)
                    place(spec.footerRight, 2, bottomY)
                }
            }
        }
        out
    }

    // ---------------------------------------------------------------- removing layers

    private fun isTagged(stream: COSStream, tags: Set<String>): Boolean {
        val value = stream.getString(COSName.getPDFName(LAYER_KEY))
        return value != null && tags.contains(value)
    }

    private fun removeTaggedStreams(page: PDPage, tags: Set<String>): Int {
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        if (contents is COSArray) {
            val keep = COSArray()
            var removed = 0
            for (i in 0 until contents.size()) {
                val resolved = contents.getObject(i)
                if (resolved is COSStream && isTagged(resolved, tags)) {
                    removed++
                } else {
                    keep.add(contents.get(i))
                }
            }
            if (removed > 0) page.cosObject.setItem(COSName.CONTENTS, keep)
            return removed
        }
        if (contents is COSStream && isTagged(contents, tags)) {
            page.cosObject.removeItem(COSName.CONTENTS)
            return 1
        }
        return 0
    }

    private fun isWatermarkProps(props: COSDictionary): Boolean {
        val subtype = props.getNameAsString(COSName.getPDFName("Subtype"))
        return subtype == "Watermark"
    }

    /** Removes standard "Artifact / Watermark" marked content written by other PDF tools. */
    private fun stripWatermarkArtifacts(doc: PDDocument, page: PDPage): Int {
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens: List<Any> = parser.tokens
        val kept = ArrayList<Any>(tokens.size)
        var removed = 0
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Operator && t.name == "BDC" && i >= 2) {
                val tag = tokens[i - 2]
                val props = tokens[i - 1]
                if (tag is COSName && tag.name == "Artifact" && props is COSDictionary && isWatermarkProps(props)) {
                    kept.removeAt(kept.size - 1)
                    kept.removeAt(kept.size - 1)
                    var depth = 1
                    i++
                    while (i < tokens.size && depth > 0) {
                        val u = tokens[i]
                        if (u is Operator) {
                            if (u.name == "BDC" || u.name == "BMC") {
                                depth++
                            } else if (u.name == "EMC") {
                                depth--
                            }
                        }
                        i++
                    }
                    removed++
                    continue
                }
            }
            kept.add(t)
            i++
        }
        if (removed > 0) {
            val replacement = PDStream(doc)
            replacement.createOutputStream(COSName.FLATE_DECODE).use { os ->
                ContentStreamWriter(os).writeTokens(kept)
            }
            page.setContents(replacement)
        }
        return removed
    }

    /**
     * Removes layers this app added, and optionally standard watermark marks from other tools.
     * Returns the cleaned file and how many items were removed.
     */
    suspend fun removeLayers(
        context: Context,
        input: File,
        indices: Collection<Int>,
        kinds: Set<LayerKind>,
        includeForeignWatermarks: Boolean
    ): Pair<File, Int> = withContext(Dispatchers.IO) {
        PdfEngine.initialize(context)
        val out = FileUtils.newFile(context, "Cleaned", "pdf")
        val tags = HashSet<String>()
        for (k in kinds) tags.add(k.tag)
        var removedTotal = 0
        withDoc(input, out) { doc ->
            for (i in indices) {
                val page = doc.getPage(i)
                removedTotal += removeTaggedStreams(page, tags)
                if (includeForeignWatermarks) {
                    try {
                        removedTotal += stripWatermarkArtifacts(doc, page)
                    } catch (e: Exception) {
                        // skip pages whose content cannot be parsed
                    }
                    try {
                        val all = page.annotations
                        val keep = ArrayList<PDAnnotation>()
                        for (a in all) {
                            if (a.subtype == "Watermark") removedTotal++ else keep.add(a)
                        }
                        if (keep.size != all.size) page.annotations = keep
                    } catch (e: Exception) {
                        // ignore annotation problems
                    }
                }
            }
        }
        Pair(out, removedTotal)
    }
}
