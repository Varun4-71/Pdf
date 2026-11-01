package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class OutlineEntry(val title: String, val level: Int, val pageIndex: Int)

/** A clickable area on a page, in 0..1 coordinates of the page as it is displayed. */
data class PageLink(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val targetPage: Int,
    val uri: String?
)

data class AttachmentInfo(val name: String, val sizeBytes: Long)

data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class SearchHit(val page: Int, val rects: List<NormRect>, val snippet: String)

data class PageTransition(val style: String?, val durationSec: Float, val autoAdvanceSec: Float?)

class WordBox(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

class PageWords(val width: Float, val height: Float, val words: List<WordBox>)

sealed class OpenResult {
    class Ok(val session: ViewerSession) : OpenResult()
    object NeedsPassword : OpenResult()
    object WrongPassword : OpenResult()
    class Failed(val message: String) : OpenResult()
}

/** Rotates a 0..1 rectangle the same way the page image is rotated (clockwise degrees). */
fun rotateNorm(r: NormRect, degrees: Int): NormRect {
    return when (((degrees % 360) + 360) % 360) {
        90 -> NormRect(1f - r.bottom, r.left, 1f - r.top, r.right)
        180 -> NormRect(1f - r.right, 1f - r.bottom, 1f - r.left, 1f - r.top)
        270 -> NormRect(r.top, 1f - r.right, r.bottom, 1f - r.left)
        else -> r
    }
}

/** Collects every word on a page together with its box (in points, origin top-left). */
private class WordCollector : PDFTextStripper() {
    val words = ArrayList<WordBox>()
    var pageW = 0f
    var pageH = 0f

    override fun startPage(page: PDPage) {
        super.startPage(page)
        val cb = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        pageW = if (sideways) cb.height else cb.width
        pageH = if (sideways) cb.width else cb.height
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty() || text.isBlank()) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = 0f
        var maxY = 0f
        for (tp in textPositions) {
            val x = tp.getXDirAdj()
            val y = tp.getYDirAdj()
            val w = tp.getWidthDirAdj()
            val h = tp.getHeightDir()
            minX = min(minX, x)
            maxX = max(maxX, x + w)
            minY = min(minY, y - h)
            maxY = max(maxY, y)
        }
        if (maxX > minX && maxY > minY) {
            words.add(WordBox(text.trim(), minX, minY, maxX - minX, maxY - minY))
        }
    }
}

/**
 * One open document. Pages are drawn with Android's PdfRenderer (fast, low memory); outline,
 * links, attachments, text and transitions come from PDFBox, which is loaded lazily on first use.
 */
class ViewerSession private constructor(
    val appContext: Context,
    val sourceUri: Uri,
    val displayName: String,
    val file: File,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val pageCount: Int = renderer.pageCount

    /** Bumped whenever page sizes become known so the UI can re-layout. */
    var ratioVersion by mutableIntStateOf(0)
        private set

    private val ratios = FloatArray(pageCount)
    private var defaultRatio = 1.4142f
    private val renderLock = Mutex()
    private val boxLock = Mutex()
    private var boxDoc: PDDocument? = null
    private var boxFailed = false
    @Volatile
    private var closed = false

    private val linkCache = ConcurrentHashMap<Int, List<PageLink>>()
    private val wordCache = ConcurrentHashMap<Int, PageWords>()
    private val textCache = ConcurrentHashMap<Int, String>()

    private val cache: LruCache<String, Bitmap>

    init {
        val maxBytes = (Runtime.getRuntime().maxMemory() / 6L).toInt().coerceIn(16 * 1024 * 1024, 160 * 1024 * 1024)
        cache = object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    // ---------------------------------------------------------------- page sizes and rendering

    /** height / width of the page as displayed after [rotation] clockwise degrees. */
    fun ratioOf(index: Int, rotation: Int): Float {
        val version = ratioVersion
        if (version < 0) return 1f
        val known = if (index in 0 until pageCount && ratios[index] > 0f) ratios[index] else defaultRatio
        val sideways = ((rotation % 360) + 360) % 360 % 180 != 0
        return if (sideways) 1f / known else known
    }

    suspend fun loadPageRatios() {
        withContext(Dispatchers.IO) {
            var done = 0
            for (i in 0 until pageCount) {
                if (closed) break
                if (ratios[i] <= 0f) {
                    renderLock.withLock {
                        if (!closed) {
                            try {
                                renderer.openPage(i).use { p ->
                                    if (p.width > 0) ratios[i] = p.height.toFloat() / p.width.toFloat()
                                }
                            } catch (e: Exception) {
                                // leave the default ratio for this page
                            }
                        }
                    }
                }
                if (i == 0 && ratios[0] > 0f) defaultRatio = ratios[0]
                done++
                if (done % 40 == 0) ratioVersion++
            }
            ratioVersion++
        }
    }

    suspend fun renderPage(index: Int, widthPx: Int, rotation: Int): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        val rot = ((rotation % 360) + 360) % 360
        val w0 = widthPx.coerceIn(64, MAX_RENDER_WIDTH)
        val key = "$index@$w0@$rot"
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) return cached
        return withContext(Dispatchers.IO) {
            renderLock.withLock {
                if (closed) {
                    null
                } else {
                    val again = cache.get(key)
                    if (again != null && !again.isRecycled) {
                        again
                    } else {
                        try {
                            renderer.openPage(index).use { page ->
                                val pw = page.width.toFloat()
                                val ph = page.height.toFloat()
                                if (pw <= 0f || ph <= 0f) {
                                    null
                                } else {
                                    ratios[index] = ph / pw
                                    var w = w0
                                    var h = (w * ph / pw).roundToInt().coerceAtLeast(1)
                                    val maxPixels = 9000000L
                                    if (w.toLong() * h.toLong() > maxPixels) {
                                        val s = Math.sqrt(maxPixels.toDouble() / (w.toDouble() * h.toDouble()))
                                        w = (w * s).toInt().coerceAtLeast(1)
                                        h = (h * s).toInt().coerceAtLeast(1)
                                    }
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val result = if (rot != 0) rotateBitmap(bmp, rot) else bmp
                                    cache.put(key, result)
                                    result
                                }
                            }
                        } catch (e: OutOfMemoryError) {
                            cache.evictAll()
                            null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    suspend fun thumbnail(index: Int, widthPx: Int = 240): Bitmap? = renderPage(index, widthPx, 0)

    fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderLock.withLock {
                    try {
                        renderer.close()
                    } catch (e: Exception) {
                        // already closed
                    }
                    try {
                        pfd.close()
                    } catch (e: Exception) {
                        // already closed
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            try {
                boxLock.withLock {
                    try {
                        boxDoc?.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                    boxDoc = null
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------------- PDFBox access

    private suspend fun <T> withBox(block: (PDDocument) -> T): T? {
        return withContext(Dispatchers.IO) {
            boxLock.withLock {
                if (closed || boxFailed) {
                    null
                } else {
                    val existing = boxDoc
                    val doc: PDDocument? = if (existing != null) {
                        existing
                    } else {
                        val loaded: PDDocument? = try {
                            PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L))
                        } catch (e: Throwable) {
                            null
                        }
                        if (loaded == null) boxFailed = true else boxDoc = loaded
                        loaded
                    }
                    if (doc == null) {
                        null
                    } else {
                        try {
                            block(doc)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- outline

    suspend fun loadOutline(): List<OutlineEntry> {
        val result = withBox { doc ->
            val out = ArrayList<OutlineEntry>()
            val outline = doc.documentCatalog.documentOutline
            if (outline != null) walkOutline(doc, outline, 0, out)
            out
        }
        return result ?: emptyList()
    }

    private fun walkOutline(doc: PDDocument, node: PDOutlineNode, level: Int, out: MutableList<OutlineEntry>) {
        var item: PDOutlineItem? = node.firstChild
        var guard = 0
        while (item != null && guard < 4000 && out.size < 4000) {
            guard++
            val page = try {
                outlinePage(doc, item)
            } catch (e: Exception) {
                -1
            }
            out.add(OutlineEntry(item.title ?: "Untitled", level, page))
            if (item.hasChildren() && level < 6) walkOutline(doc, item, level + 1, out)
            item = item.nextSibling
        }
    }

    private fun outlinePage(doc: PDDocument, item: PDOutlineItem): Int {
        var dest: PDDestination? = item.destination
        if (dest == null) {
            val action = item.action
            if (action is PDActionGoTo) dest = action.destination
        }
        return destinationPage(doc, dest)
    }

    private fun destinationPage(doc: PDDocument, destination: PDDestination?): Int {
        var dest: PDDestination? = destination
        if (dest is PDNamedDestination) {
            dest = doc.documentCatalog.findNamedDestinationPage(dest)
        }
        if (dest is PDPageDestination) {
            return dest.retrievePageNumber()
        }
        return -1
    }

    // ---------------------------------------------------------------- links

    suspend fun loadLinks(index: Int): List<PageLink> {
        val cached = linkCache[index]
        if (cached != null) return cached
        val links = withBox { doc -> extractLinks(doc, index) } ?: emptyList()
        linkCache[index] = links
        return links
    }

    private fun extractLinks(doc: PDDocument, index: Int): List<PageLink> {
        if (index < 0 || index >= doc.numberOfPages) return emptyList()
        val page = doc.getPage(index)
        val cb = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val cw = cb.width
        val ch = cb.height
        if (cw <= 0f || ch <= 0f) return emptyList()
        val sideways = rot == 90 || rot == 270
        val dispW = if (sideways) ch else cw
        val dispH = if (sideways) cw else ch
        val result = ArrayList<PageLink>()
        for (annot in page.annotations) {
            if (annot !is PDAnnotationLink) continue
            val r = annot.rectangle ?: continue
            val u1 = min(r.lowerLeftX, r.upperRightX) - cb.lowerLeftX
            val u2 = max(r.lowerLeftX, r.upperRightX) - cb.lowerLeftX
            val v1 = min(r.lowerLeftY, r.upperRightY) - cb.lowerLeftY
            val v2 = max(r.lowerLeftY, r.upperRightY) - cb.lowerLeftY
            val ax: Float
            val ay: Float
            val bx: Float
            val by: Float
            when (rot) {
                90 -> {
                    ax = v1; ay = u1; bx = v2; by = u2
                }
                180 -> {
                    ax = cw - u1; ay = v1; bx = cw - u2; by = v2
                }
                270 -> {
                    ax = ch - v1; ay = cw - u1; bx = ch - v2; by = cw - u2
                }
                else -> {
                    ax = u1; ay = ch - v1; bx = u2; by = ch - v2
                }
            }
            var target = -1
            var uri: String? = null
            val action = annot.action
            if (action is PDActionURI) {
                uri = action.getURI()
            } else if (action is PDActionGoTo) {
                target = destinationPage(doc, action.destination)
            }
            if (target < 0 && uri == null) {
                val d = annot.destination
                if (d != null) target = destinationPage(doc, d)
            }
            if (target < 0 && uri == null) continue
            val left = (min(ax, bx) / dispW).coerceIn(0f, 1f)
            val right = (max(ax, bx) / dispW).coerceIn(0f, 1f)
            val top = (min(ay, by) / dispH).coerceIn(0f, 1f)
            val bottom = (max(ay, by) / dispH).coerceIn(0f, 1f)
            result.add(PageLink(left, top, right, bottom, target, uri))
        }
        return result
    }

    // ---------------------------------------------------------------- attachments

    suspend fun loadAttachments(): List<AttachmentInfo> {
        val result = withBox { doc ->
            val list = ArrayList<AttachmentInfo>()
            val tree = doc.documentCatalog.names?.embeddedFiles
            if (tree != null) collectEmbedded(tree, list, null)
            list
        }
        return result ?: emptyList()
    }

    suspend fun readAttachment(name: String): ByteArray? {
        return withBox { doc ->
            val tree = doc.documentCatalog.names?.embeddedFiles
            val holder = ArrayList<AttachmentInfo>()
            val bytesHolder = ArrayList<ByteArray>()
            if (tree != null) collectEmbedded(tree, holder, Pair(name, bytesHolder))
            if (bytesHolder.isEmpty()) null else bytesHolder[0]
        }
    }

    private fun collectEmbedded(
        node: PDNameTreeNode<PDComplexFileSpecification>,
        list: MutableList<AttachmentInfo>,
        wanted: Pair<String, MutableList<ByteArray>>?
    ) {
        val names = node.names
        if (names != null) {
            for ((key, spec) in names) {
                val embedded = spec.embeddedFile ?: continue
                val label = spec.fileUnicode ?: spec.file ?: key
                if (wanted == null) {
                    list.add(AttachmentInfo(label, embedded.size.toLong()))
                } else if (wanted.first == label || wanted.first == key) {
                    wanted.second.add(embedded.toByteArray())
                }
            }
        }
        val kids = node.kids
        if (kids != null) {
            for (kid in kids) collectEmbedded(kid, list, wanted)
        }
    }

    // ---------------------------------------------------------------- text, search and reflow

    suspend fun pageWords(index: Int): PageWords? {
        val cached = wordCache[index]
        if (cached != null) return cached
        val result = withBox { doc ->
            val stripper = WordCollector()
            stripper.setSortByPosition(true)
            stripper.setStartPage(index + 1)
            stripper.setEndPage(index + 1)
            stripper.getText(doc)
            PageWords(stripper.pageW, stripper.pageH, stripper.words)
        }
        if (result != null) wordCache[index] = result
        return result
    }

    suspend fun pageText(index: Int): String {
        val cached = textCache[index]
        if (cached != null) return cached
        val text = withBox { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setStartPage(index + 1)
            stripper.setEndPage(index + 1)
            reflowClean(stripper.getText(doc))
        } ?: ""
        textCache[index] = text
        return text
    }

    private fun reflowClean(raw: String): String {
        val lines = raw.replace("\r", "").split('\n')
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                val lastIdx = sb.length - 1
                if (sb[lastIdx] == '-' && lastIdx > 0 && sb[lastIdx - 1].isLetter()) {
                    sb.setLength(lastIdx)
                } else {
                    sb.append(' ')
                }
            }
            sb.append(line)
            val next = if (i + 1 < lines.size) lines[i + 1].trim() else ""
            val nextContinues = next.isNotEmpty() && next[0].isLowerCase()
            if (line.length < 48 && !nextContinues) sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** Streams matches page by page; callbacks run on the caller's dispatcher. */
    suspend fun search(
        query: String,
        onHit: suspend (SearchHit) -> Unit,
        onProgress: suspend (Int, Int) -> Unit
    ) {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return
        for (i in 0 until pageCount) {
            yield()
            val words = pageWords(i)
            if (words != null) {
                for (hit in findHits(i, words, q)) onHit(hit)
            }
            if (i % 4 == 0 || i == pageCount - 1) onProgress(i + 1, pageCount)
        }
    }

    // ---------------------------------------------------------------- transitions

    suspend fun loadTransitions(): Map<Int, PageTransition> {
        val result = withBox { doc ->
            val map = HashMap<Int, PageTransition>()
            val transKey = COSName.getPDFName("Trans")
            val durKey = COSName.getPDFName("Dur")
            val styleKey = COSName.getPDFName("S")
            val durationKey = COSName.getPDFName("D")
            for (i in 0 until doc.numberOfPages) {
                val dict = doc.getPage(i).cosObject
                val trans = dict.getDictionaryObject(transKey) as? COSDictionary
                val dur = dict.getFloat(durKey, -1f)
                if (trans != null || dur > 0f) {
                    val style = trans?.getNameAsString(styleKey)
                    val d = trans?.getFloat(durationKey, 1f) ?: 1f
                    map[i] = PageTransition(style, d, if (dur > 0f) dur else null)
                }
            }
            map
        }
        return result ?: emptyMap()
    }

    companion object {
        private const val MAX_RENDER_WIDTH = 3200

        suspend fun open(context: Context, uri: Uri, password: String? = null): OpenResult {
            return withContext(Dispatchers.IO) {
                try {
                    PdfEngine.initialize(context)
                    val appContext = context.applicationContext
                    val name = PdfEngine.getFileName(context, uri)
                    val local = FileUtils.copyToCache(appContext, uri, "pdf", "view")
                    try {
                        OpenResult.Ok(create(appContext, uri, name, local))
                    } catch (e: SecurityException) {
                        if (password == null) {
                            OpenResult.NeedsPassword
                        } else {
                            val decrypted = FileUtils.newFile(appContext, "view_dec", "pdf")
                            var wrong = false
                            try {
                                PDDocument.load(local, password).use { d ->
                                    d.setAllSecurityToBeRemoved(true)
                                    d.save(decrypted)
                                }
                            } catch (ex: InvalidPasswordException) {
                                wrong = true
                            }
                            if (wrong) {
                                decrypted.delete()
                                OpenResult.WrongPassword
                            } else {
                                OpenResult.Ok(create(appContext, uri, name, decrypted))
                            }
                        }
                    }
                } catch (e: Exception) {
                    OpenResult.Failed(e.localizedMessage ?: "This file could not be opened.")
                }
            }
        }

        private fun create(context: Context, uri: Uri, name: String, file: File): ViewerSession {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer: PdfRenderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                try {
                    pfd.close()
                } catch (ignored: Exception) {
                    // nothing more to do
                }
                throw e
            }
            return ViewerSession(context, uri, name, file, pfd, renderer)
        }
    }
}

/** Finds every occurrence of [query] on one page and returns highlight rectangles for each. */
internal fun findHits(pageIndex: Int, page: PageWords, query: String): List<SearchHit> {
    if (page.words.isEmpty() || page.width <= 0f || page.height <= 0f) return emptyList()
    val sb = StringBuilder()
    val starts = IntArray(page.words.size)
    for (i in page.words.indices) {
        starts[i] = sb.length
        sb.append(page.words[i].text)
        sb.append(' ')
    }
    val haystack = sb.toString()
    val hits = ArrayList<SearchHit>()
    var from = 0
    while (from < haystack.length && hits.size < 500) {
        val at = haystack.indexOf(query, from, true)
        if (at < 0) break
        val end = at + query.length
        val rects = ArrayList<NormRect>()
        for (i in page.words.indices) {
            val ws = starts[i]
            val we = ws + page.words[i].text.length
            if (we > at && ws < end) {
                val w = page.words[i]
                rects.add(
                    NormRect(
                        ((w.x - 1f) / page.width).coerceIn(0f, 1f),
                        ((w.y - 1f) / page.height).coerceIn(0f, 1f),
                        ((w.x + w.w + 1f) / page.width).coerceIn(0f, 1f),
                        ((w.y + w.h + 1f) / page.height).coerceIn(0f, 1f)
                    )
                )
            }
        }
        if (rects.isNotEmpty()) {
            val s = max(0, at - 28)
            val e = min(haystack.length, end + 44)
            hits.add(SearchHit(pageIndex, rects, haystack.substring(s, e).trim()))
        }
        from = end
    }
    return hits
}
