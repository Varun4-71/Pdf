package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts a .pptx presentation to PDF: one page per slide, using the slide's own size.
 * Draws shapes with fills and outlines, text (with auto-shrink), bullets, pictures and tables,
 * plus the non-placeholder artwork from the slide layout and master. Charts, SmartArt, video and
 * animations are not rendered.
 */
internal object PptxToPdf {

    private class Env(
        val pkg: OoxmlPackage,
        val theme: Map<String, Int>,
        val clrMap: Map<String, String>
    ) {
        fun schemeColor(name: String?): Int? {
            if (name == null) return null
            val mapped = clrMap[name] ?: when (name) {
                "bg1" -> "lt1"
                "tx1" -> "dk1"
                "bg2" -> "lt2"
                "tx2" -> "dk2"
                else -> name
            }
            return theme[mapped]
        }
    }

    private class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    private class T(val tx: Float, val ty: Float, val sx: Float, val sy: Float, val cox: Float, val coy: Float) {
        fun mapX(x: Float): Float = tx + (x - cox) * sx
        fun mapY(y: Float): Float = ty + (y - coy) * sy
    }

    private val IDENTITY = T(0f, 0f, 1f, 1f, 0f, 0f)

    private class Xfrm {
        var offX = 0f
        var offY = 0f
        var extW = 0f
        var extH = 0f
        var chOffX = 0f
        var chOffY = 0f
        var chExtW = 0f
        var chExtH = 0f
        var rot = 0f
    }

    private class PRun(
        val text: String,
        val bold: Boolean?,
        val italic: Boolean?,
        val underline: Boolean,
        val sizePt: Float?,
        val color: Int?
    )

    private class PPara(
        val runs: List<PRun>,
        val align: String?,
        val level: Int,
        val bullet: String?,
        val autoNum: Boolean,
        val noBullet: Boolean,
        val marL: Float?,
        val indent: Float?
    )

    private class TableCell(val paras: List<PPara>, val fill: Int?, val span: Int, val merged: Boolean)

    private class TableData(val colW: List<Float>, val rowH: List<Float>, val rows: List<List<TableCell>>)

    /** kind: 0 = shape/text, 1 = picture, 2 = table. */
    private class Shape(val kind: Int) {
        var box: Box? = null
        var rot = 0f
        var fill: Int? = null
        var fillNone = false
        var line: Int? = null
        var lineNone = false
        var lineW = 0.75f
        var geom: String? = null
        var paras: List<PPara> = emptyList()
        var anchor: String? = null
        var hasPh = false
        var phType: String? = null
        var phIdx: String? = null
        var imageRid: String? = null
        var table: TableData? = null
        var insetL = 7.2f
        var insetT = 3.6f
        var insetR = 7.2f
        var insetB = 3.6f
        var styleFill: Int? = null
        var styleLine: Int? = null
        var styleFont: Int? = null
    }

    private class PartData(val path: String, val rels: Map<String, String>) {
        var bg: Int? = null
        var showMasterSp = true
        val shapes = ArrayList<Shape>()
    }

    // ---------------------------------------------------------------- entry point

    fun convert(input: File, output: File) {
        OoxmlPackage(input).use { pkg ->
            if (!pkg.has("ppt/presentation.xml")) {
                throw IOException("This file is not a valid .pptx presentation.")
            }
            var slideW = 720f
            var slideH = 540f
            val slideRids = ArrayList<String>()
            val presStream = pkg.open("ppt/presentation.xml") ?: throw IOException("Missing presentation.xml")
            presStream.use { s ->
                val p = Ooxml.parser(s)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "p:sldSz") {
                            val cx = Ooxml.longAttr(p, "cx")
                            val cy = Ooxml.longAttr(p, "cy")
                            if (cx != null && cy != null && cx > 0L && cy > 0L) {
                                slideW = Ooxml.emuToPt(cx)
                                slideH = Ooxml.emuToPt(cy)
                            }
                        } else if (p.name == "p:sldId") {
                            val rid = Ooxml.attr(p, "r:id")
                            if (rid != null) slideRids.add(rid)
                        }
                    }
                    ev = p.next()
                }
            }
            val presRels = Ooxml.relationships(pkg, "ppt/_rels/presentation.xml.rels")
            val theme = parseTheme(pkg)
            val pdf = PdfDocument()
            try {
                var pageNumber = 0
                for (rid in slideRids) {
                    val target = presRels[rid] ?: continue
                    val slidePath = Ooxml.resolve("ppt/presentation.xml", target)
                    if (!pkg.has(slidePath)) continue
                    pageNumber++
                    renderSlideFile(pdf, pkg, theme, slidePath, pageNumber, slideW, slideH)
                }
                if (pageNumber == 0) throw IOException("This presentation has no slides.")
                FileOutputStream(output).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
        }
    }

    private fun relsPathFor(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val file = part.substringAfterLast('/')
        return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    private fun renderSlideFile(
        pdf: PdfDocument,
        pkg: OoxmlPackage,
        theme: Map<String, Int>,
        slidePath: String,
        pageNumber: Int,
        w: Float,
        h: Float
    ) {
        val slideRels = Ooxml.relationships(pkg, relsPathFor(slidePath))
        var layoutPath: String? = null
        for (target in slideRels.values) {
            if (target.contains("slideLayouts/")) {
                layoutPath = Ooxml.resolve(slidePath, target)
                break
            }
        }
        var layoutRels: Map<String, String> = emptyMap()
        var masterPath: String? = null
        if (layoutPath != null) {
            layoutRels = Ooxml.relationships(pkg, relsPathFor(layoutPath))
            for (target in layoutRels.values) {
                if (target.contains("slideMasters/")) {
                    masterPath = Ooxml.resolve(layoutPath, target)
                    break
                }
            }
        }
        val masterRels: Map<String, String> = if (masterPath != null) {
            Ooxml.relationships(pkg, relsPathFor(masterPath))
        } else {
            emptyMap()
        }
        val clrMap = if (masterPath != null) parseClrMap(pkg, masterPath) else emptyMap()
        val env = Env(pkg, theme, clrMap)
        val slide = parsePart(env, slidePath, slideRels)
        val layout = if (layoutPath != null) parsePart(env, layoutPath, layoutRels) else null
        val master = if (masterPath != null) parsePart(env, masterPath, masterRels) else null

        val page = pdf.startPage(PdfDocument.PageInfo.Builder(w.roundToInt(), h.roundToInt(), pageNumber).create())
        try {
            val canvas = page.canvas
            val bg = slide.bg ?: layout?.bg ?: master?.bg ?: Color.WHITE
            canvas.drawColor(bg)
            val showMaster = slide.showMasterSp && (layout?.showMasterSp ?: true)
            if (showMaster && master != null) {
                for (s in master.shapes) {
                    val box = s.box
                    if (!s.hasPh && box != null) drawShape(canvas, s, box, env, master)
                }
            }
            if (layout != null) {
                for (s in layout.shapes) {
                    val box = s.box
                    if (!s.hasPh && box != null) drawShape(canvas, s, box, env, layout)
                }
            }
            for (s in slide.shapes) {
                var box = s.box
                if (box == null && s.hasPh) box = findPlaceholder(s, layout, master)
                if (box == null && s.hasPh) box = defaultBox(s, w, h)
                if (box != null) drawShape(canvas, s, box, env, slide)
            }
        } finally {
            pdf.finishPage(page)
        }
    }

    // ---------------------------------------------------------------- theme and parts

    private fun parseTheme(pkg: OoxmlPackage): Map<String, Int> {
        val map = HashMap<String, Int>()
        var path: String? = null
        for (n in 1..4) {
            val candidate = "ppt/theme/theme$n.xml"
            if (pkg.has(candidate)) {
                path = candidate
                break
            }
        }
        if (path == null) return map
        val stream = pkg.open(path) ?: return map
        val names = setOf(
            "a:dk1", "a:lt1", "a:dk2", "a:lt2", "a:accent1", "a:accent2",
            "a:accent3", "a:accent4", "a:accent5", "a:accent6", "a:hlink", "a:folHlink"
        )
        stream.use { s ->
            val p = Ooxml.parser(s)
            var inScheme = false
            var current: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = p.name
                    val cur = current
                    if (name == "a:clrScheme") {
                        inScheme = true
                    } else if (inScheme && names.contains(name)) {
                        current = name.substring(2)
                    } else if (inScheme && cur != null && name == "a:srgbClr") {
                        val c = Ooxml.hexColor(Ooxml.attr(p, "val"))
                        if (c != null) map[cur] = c
                    } else if (inScheme && cur != null && name == "a:sysClr") {
                        val c = Ooxml.hexColor(Ooxml.attr(p, "lastClr"))
                        if (c != null) map[cur] = c
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "a:clrScheme") {
                    inScheme = false
                }
                ev = p.next()
            }
        }
        return map
    }

    private fun parseClrMap(pkg: OoxmlPackage, masterPath: String): Map<String, String> {
        val map = HashMap<String, String>()
        val stream = pkg.open(masterPath) ?: return map
        stream.use { s ->
            val p = Ooxml.parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "p:clrMap") {
                    for (i in 0 until p.attributeCount) {
                        map[p.getAttributeName(i)] = p.getAttributeValue(i)
                    }
                    break
                }
                ev = p.next()
            }
        }
        return map
    }

    private fun parsePart(env: Env, path: String, rels: Map<String, String>): PartData {
        val data = PartData(path, rels)
        val stream = env.pkg.open(path) ?: return data
        stream.use { s ->
            val p = Ooxml.parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "p:sld", "p:sldLayout" -> {
                            if (Ooxml.attr(p, "showMasterSp") == "0") data.showMasterSp = false
                        }
                        "p:bg" -> data.bg = parseBackground(p, env)
                        "p:spTree" -> parseTree(p, env, IDENTITY, data.shapes)
                    }
                }
                ev = p.next()
            }
        }
        return data
    }

    private fun parseBackground(p: XmlPullParser, env: Env): Int? {
        val depth = p.depth
        var color: Int? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "a:solidFill" || p.name == "p:bgRef") {
                val c = parseColorContainer(p, env)
                if (color == null) color = c
            }
        }
        return color
    }

    // ---------------------------------------------------------------- colours

    private fun presetColor(name: String?): Int? {
        return when (name) {
            "black" -> Color.BLACK
            "white" -> Color.WHITE
            "red" -> Color.RED
            "green" -> Color.GREEN
            "blue" -> Color.BLUE
            "yellow" -> Color.YELLOW
            "gray", "grey" -> Color.GRAY
            else -> null
        }
    }

    private fun applyLum(color: Int, mod: Float, off: Float): Int {
        val hsv = FloatArray(3)
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        var l = (maxC + minC) / 2f
        val d = maxC - minC
        var s = 0f
        var hue = 0f
        if (d > 0f) {
            s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
            hue = when (maxC) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
                g -> ((b - r) / d + 2f) * 60f
                else -> ((r - g) / d + 4f) * 60f
            }
        }
        l = (l * mod + off).coerceIn(0f, 1f)
        // HSL -> RGB
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val pp = 2f * l - q
        fun channel(tIn: Float): Float {
            var t = tIn
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> pp + (q - pp) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> pp + (q - pp) * (2f / 3f - t) * 6f
                else -> pp
            }
        }
        hsv[0] = hue
        val h = hue / 360f
        val rr = if (s == 0f) l else channel(h + 1f / 3f)
        val gg = if (s == 0f) l else channel(h)
        val bb = if (s == 0f) l else channel(h - 1f / 3f)
        return Color.rgb((rr * 255f).roundToInt().coerceIn(0, 255), (gg * 255f).roundToInt().coerceIn(0, 255), (bb * 255f).roundToInt().coerceIn(0, 255))
    }

    /** Reads the first colour found under the current element (solidFill, bgRef, gs, fillRef...). */
    private fun parseColorContainer(p: XmlPullParser, env: Env): Int? {
        val depth = p.depth
        var base: Int? = null
        var lumMod = 1f
        var lumOff = 0f
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:srgbClr" -> if (base == null) base = Ooxml.hexColor(Ooxml.attr(p, "val"))
                "a:schemeClr" -> if (base == null) base = env.schemeColor(Ooxml.attr(p, "val"))
                "a:sysClr" -> if (base == null) base = Ooxml.hexColor(Ooxml.attr(p, "lastClr"))
                "a:prstClr" -> if (base == null) base = presetColor(Ooxml.attr(p, "val"))
                "a:lumMod" -> lumMod = (Ooxml.intAttr(p, "val") ?: 100000) / 100000f
                "a:lumOff" -> lumOff = (Ooxml.intAttr(p, "val") ?: 0) / 100000f
            }
        }
        val b = base ?: return null
        return if (lumMod != 1f || lumOff != 0f) applyLum(b, lumMod, lumOff) else b
    }

    // ---------------------------------------------------------------- shapes

    private fun emu(p: XmlPullParser, name: String): Float = (Ooxml.longAttr(p, name) ?: 0L) / 12700f

    private fun parseXfrm(p: XmlPullParser): Xfrm {
        val x = Xfrm()
        x.rot = (Ooxml.floatAttr(p, "rot") ?: 0f) / 60000f
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:off" -> {
                    x.offX = emu(p, "x")
                    x.offY = emu(p, "y")
                }
                "a:ext" -> {
                    x.extW = emu(p, "cx")
                    x.extH = emu(p, "cy")
                }
                "a:chOff" -> {
                    x.chOffX = emu(p, "x")
                    x.chOffY = emu(p, "y")
                }
                "a:chExt" -> {
                    x.chExtW = emu(p, "cx")
                    x.chExtH = emu(p, "cy")
                }
            }
        }
        return x
    }

    private fun parseTree(p: XmlPullParser, env: Env, t: T, out: MutableList<Shape>) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "p:sp" -> out.add(parseShape(p, env, t))
                "p:pic" -> out.add(parsePicture(p, env, t))
                "p:graphicFrame" -> {
                    val frame = parseFrame(p, env, t)
                    if (frame != null) out.add(frame)
                }
                "p:grpSp" -> parseGroup(p, env, t, out)
                "p:cxnSp" -> Ooxml.skip(p)
                "mc:Fallback" -> Ooxml.skip(p)
            }
        }
    }

    private fun parseGroup(p: XmlPullParser, env: Env, parent: T, out: MutableList<Shape>) {
        val depth = p.depth
        var t = parent
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "p:grpSpPr" -> t = parseGroupProps(p, parent)
                "p:sp" -> out.add(parseShape(p, env, t))
                "p:pic" -> out.add(parsePicture(p, env, t))
                "p:graphicFrame" -> {
                    val frame = parseFrame(p, env, t)
                    if (frame != null) out.add(frame)
                }
                "p:grpSp" -> parseGroup(p, env, t, out)
                "p:cxnSp" -> Ooxml.skip(p)
                "mc:Fallback" -> Ooxml.skip(p)
            }
        }
    }

    private fun parseGroupProps(p: XmlPullParser, parent: T): T {
        val depth = p.depth
        var result = parent
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "a:xfrm") {
                val xf = parseXfrm(p)
                val chW = if (xf.chExtW > 0f) xf.chExtW else xf.extW
                val chH = if (xf.chExtH > 0f) xf.chExtH else xf.extH
                val ax = parent.mapX(xf.offX)
                val ay = parent.mapY(xf.offY)
                val gw = xf.extW * parent.sx
                val gh = xf.extH * parent.sy
                val sx = if (chW > 0f) gw / chW else parent.sx
                val sy = if (chH > 0f) gh / chH else parent.sy
                result = T(ax, ay, sx, sy, xf.chOffX, xf.chOffY)
            }
        }
        return result
    }

    private fun applyXfrm(s: Shape, xf: Xfrm, t: T) {
        s.box = Box(t.mapX(xf.offX), t.mapY(xf.offY), xf.extW * t.sx, xf.extH * t.sy)
        s.rot = xf.rot
    }

    private fun parseShape(p: XmlPullParser, env: Env, t: T): Shape {
        val s = Shape(0)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "p:ph" -> {
                    s.hasPh = true
                    s.phType = Ooxml.attr(p, "type")
                    s.phIdx = Ooxml.attr(p, "idx")
                }
                "p:spPr" -> parseSpPr(p, env, t, s)
                "p:txBody" -> parseTxBody(p, env, s)
                "p:style" -> parseStyleRefs(p, env, s)
            }
        }
        return s
    }

    private fun parseStyleRefs(p: XmlPullParser, env: Env, s: Shape) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:fillRef" -> {
                    val idx = Ooxml.intAttr(p, "idx") ?: 0
                    val c = parseColorContainer(p, env)
                    if (idx > 0) s.styleFill = c
                }
                "a:lnRef" -> {
                    val idx = Ooxml.intAttr(p, "idx") ?: 0
                    val c = parseColorContainer(p, env)
                    if (idx > 0) s.styleLine = c
                }
                "a:fontRef" -> s.styleFont = parseColorContainer(p, env)
            }
        }
    }

    private fun parseSpPr(p: XmlPullParser, env: Env, t: T, s: Shape) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:xfrm" -> applyXfrm(s, parseXfrm(p), t)
                "a:prstGeom" -> s.geom = Ooxml.attr(p, "prst")
                "a:solidFill" -> s.fill = parseColorContainer(p, env)
                "a:noFill" -> s.fillNone = true
                "a:gradFill" -> s.fill = parseGradientStart(p, env)
                "a:ln" -> parseLine(p, env, s)
                "a:blipFill" -> Ooxml.skip(p)
                "a:custGeom" -> Ooxml.skip(p)
            }
        }
    }

    private fun parseGradientStart(p: XmlPullParser, env: Env): Int? {
        val depth = p.depth
        var color: Int? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "a:gs") {
                val c = parseColorContainer(p, env)
                if (color == null) color = c
            }
        }
        return color
    }

    private fun parseLine(p: XmlPullParser, env: Env, s: Shape) {
        val w = Ooxml.longAttr(p, "w")
        if (w != null) s.lineW = (w / 12700f).coerceIn(0.25f, 12f)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "a:noFill") {
                s.lineNone = true
            } else if (p.name == "a:solidFill") {
                s.line = parseColorContainer(p, env)
            }
        }
    }

    private fun parseTxBody(p: XmlPullParser, env: Env, s: Shape) {
        val depth = p.depth
        val paras = ArrayList<PPara>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:bodyPr" -> {
                    s.anchor = Ooxml.attr(p, "anchor")
                    val l = Ooxml.longAttr(p, "lIns")
                    val tt = Ooxml.longAttr(p, "tIns")
                    val r = Ooxml.longAttr(p, "rIns")
                    val b = Ooxml.longAttr(p, "bIns")
                    if (l != null) s.insetL = l / 12700f
                    if (tt != null) s.insetT = tt / 12700f
                    if (r != null) s.insetR = r / 12700f
                    if (b != null) s.insetB = b / 12700f
                }
                "a:p" -> paras.add(parsePara(p, env))
            }
        }
        s.paras = paras
    }

    private fun parsePara(p: XmlPullParser, env: Env): PPara {
        val depth = p.depth
        var align: String? = null
        var level = 0
        var bullet: String? = null
        var autoNum = false
        var noBullet = false
        var marL: Float? = null
        var indent: Float? = null
        val runs = ArrayList<PRun>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:pPr" -> {
                    align = Ooxml.attr(p, "algn")
                    level = Ooxml.intAttr(p, "lvl") ?: 0
                    val ml = Ooxml.longAttr(p, "marL")
                    val ind = Ooxml.longAttr(p, "indent")
                    if (ml != null) marL = ml / 12700f
                    if (ind != null) indent = ind / 12700f
                }
                "a:buChar" -> bullet = Ooxml.attr(p, "char") ?: "\u2022"
                "a:buAutoNum" -> autoNum = true
                "a:buNone" -> noBullet = true
                "a:r", "a:fld" -> {
                    val run = parseRun(p, env)
                    if (run != null) runs.add(run)
                }
                "a:br" -> runs.add(PRun("\n", null, null, false, null, null))
            }
        }
        return PPara(runs, align, level, bullet, autoNum, noBullet, marL, indent)
    }

    private fun parseRun(p: XmlPullParser, env: Env): PRun? {
        val depth = p.depth
        var bold: Boolean? = null
        var italic: Boolean? = null
        var underline = false
        var size: Float? = null
        var color: Int? = null
        val text = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:rPr" -> {
                    val sz = Ooxml.floatAttr(p, "sz")
                    if (sz != null && sz > 0f) size = sz / 100f
                    val b = Ooxml.attr(p, "b")
                    if (b != null) bold = b == "1" || b == "true"
                    val i = Ooxml.attr(p, "i")
                    if (i != null) italic = i == "1" || i == "true"
                    val u = Ooxml.attr(p, "u")
                    underline = u != null && u != "none"
                }
                "a:solidFill" -> {
                    val c = parseColorContainer(p, env)
                    if (color == null) color = c
                }
                "a:t" -> text.append(p.nextText())
            }
        }
        if (text.isEmpty()) return null
        return PRun(text.toString(), bold, italic, underline, size, color)
    }

    private fun parsePicture(p: XmlPullParser, env: Env, t: T): Shape {
        val s = Shape(1)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:blip" -> s.imageRid = Ooxml.attr(p, "r:embed")
                "p:spPr" -> parseSpPr(p, env, t, s)
            }
        }
        return s
    }

    private fun parseFrame(p: XmlPullParser, env: Env, t: T): Shape? {
        val s = Shape(2)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "p:xfrm" -> applyXfrm(s, parseXfrm(p), t)
                "a:tbl" -> s.table = parseTable(p, env)
                "mc:Fallback" -> Ooxml.skip(p)
            }
        }
        return if (s.table != null && s.box != null) s else null
    }

    private fun parseTable(p: XmlPullParser, env: Env): TableData {
        val depth = p.depth
        val colW = ArrayList<Float>()
        val rowH = ArrayList<Float>()
        val rows = ArrayList<List<TableCell>>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:gridCol" -> colW.add(emu(p, "w"))
                "a:tr" -> {
                    rowH.add(emu(p, "h"))
                    rows.add(parseTableRow(p, env))
                }
            }
        }
        return TableData(colW, rowH, rows)
    }

    private fun parseTableRow(p: XmlPullParser, env: Env): List<TableCell> {
        val depth = p.depth
        val cells = ArrayList<TableCell>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "a:tc") cells.add(parseTableCell(p, env))
        }
        return cells
    }

    private fun parseTableCell(p: XmlPullParser, env: Env): TableCell {
        val span = Ooxml.intAttr(p, "gridSpan") ?: 1
        val merged = Ooxml.attr(p, "hMerge") == "1"
        val depth = p.depth
        val paras = ArrayList<PPara>()
        var fill: Int? = null
        var tcPrDepth = -1
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.END_TAG && p.name == "a:tcPr") tcPrDepth = -1
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "a:p" -> paras.add(parsePara(p, env))
                "a:tcPr" -> tcPrDepth = p.depth
                "a:solidFill" -> {
                    if (tcPrDepth > 0 && p.depth == tcPrDepth + 1) {
                        fill = parseColorContainer(p, env)
                    }
                }
            }
        }
        return TableCell(paras, fill, span.coerceAtLeast(1), merged)
    }

    // ---------------------------------------------------------------- placeholders

    private fun normType(t: String?): String {
        return when (t) {
            null, "obj" -> "body"
            "ctrTitle" -> "title"
            "subTitle" -> "body"
            else -> t
        }
    }

    private fun findPlaceholder(shape: Shape, layout: PartData?, master: PartData?): Box? {
        val lists = ArrayList<List<Shape>>()
        if (layout != null) lists.add(layout.shapes)
        if (master != null) lists.add(master.shapes)
        val type = normType(shape.phType)
        for (list in lists) {
            if (shape.phIdx != null) {
                for (candidate in list) {
                    if (candidate.hasPh && candidate.phIdx == shape.phIdx && candidate.box != null) {
                        return candidate.box
                    }
                }
            }
            for (candidate in list) {
                if (candidate.hasPh && normType(candidate.phType) == type && candidate.box != null) {
                    return candidate.box
                }
            }
        }
        return null
    }

    private fun defaultBox(s: Shape, w: Float, h: Float): Box {
        return when (normType(s.phType)) {
            "title" -> Box(w * 0.08f, h * 0.05f, w * 0.84f, h * 0.2f)
            "body" -> Box(w * 0.08f, h * 0.27f, w * 0.84f, h * 0.62f)
            else -> Box(w * 0.08f, h * 0.9f, w * 0.84f, h * 0.07f)
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun defaultSize(s: Shape, level: Int): Float {
        if (s.hasPh) {
            return when (s.phType) {
                "title", "ctrTitle" -> 40f
                "subTitle" -> 26f
                "ftr", "dt", "sldNum" -> 12f
                else -> {
                    val sizes = floatArrayOf(26f, 22f, 20f, 18f, 16f)
                    sizes[level.coerceIn(0, 4)]
                }
            }
        }
        return 18f
    }

    private fun isBodyLike(s: Shape): Boolean {
        if (!s.hasPh) return false
        return when (s.phType) {
            null, "body", "obj" -> true
            else -> false
        }
    }

    private fun drawShape(canvas: Canvas, s: Shape, box: Box, env: Env, part: PartData) {
        canvas.save()
        if (s.rot != 0f) canvas.rotate(s.rot, box.x + box.w / 2f, box.y + box.h / 2f)
        try {
            when (s.kind) {
                1 -> drawPicture(canvas, s, box, env, part)
                2 -> drawTable(canvas, s, box, env)
                else -> drawTextShape(canvas, s, box, env)
            }
        } finally {
            canvas.restore()
        }
    }

    private fun drawPicture(canvas: Canvas, s: Shape, box: Box, env: Env, part: PartData) {
        val rid = s.imageRid ?: return
        val target = part.rels[rid] ?: return
        val bytes = env.pkg.bytes(Ooxml.resolve(part.path, target)) ?: return
        val bmp: Bitmap = Ooxml.decodeBitmap(bytes, 1600) ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bmp, null, RectF(box.x, box.y, box.x + box.w, box.y + box.h), paint)
    }

    private fun drawTextShape(canvas: Canvas, s: Shape, box: Box, env: Env) {
        val rect = RectF(box.x, box.y, box.x + box.w, box.y + box.h)
        val fillColor: Int? = if (s.fillNone) null else (s.fill ?: s.styleFill)
        val lineColor: Int? = if (s.lineNone) null else (s.line ?: s.styleLine)
        if (fillColor != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = fillColor
            when (s.geom) {
                "ellipse" -> canvas.drawOval(rect, paint)
                "roundRect" -> {
                    val r = min(box.w, box.h) * 0.12f
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                else -> canvas.drawRect(rect, paint)
            }
        }
        if (lineColor != null && (s.line != null || fillColor != null)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s.lineW
            paint.color = lineColor
            when (s.geom) {
                "ellipse" -> canvas.drawOval(rect, paint)
                "roundRect" -> {
                    val r = min(box.w, box.h) * 0.12f
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                else -> canvas.drawRect(rect, paint)
            }
        }
        if (s.paras.isEmpty()) return

        val defaultColor = s.styleFont ?: env.schemeColor("tx1") ?: Color.BLACK
        val innerX = box.x + s.insetL
        val innerY = box.y + s.insetT
        val innerW = (box.w - s.insetL - s.insetR).coerceAtLeast(10f)
        val innerH = (box.h - s.insetT - s.insetB).coerceAtLeast(10f)

        var scale = 1f
        var built = buildLayouts(s, innerW, scale, defaultColor)
        while (built.totalHeight > innerH && scale > 0.5f) {
            scale -= 0.05f
            built = buildLayouts(s, innerW, scale, defaultColor)
        }
        var y = when (s.anchor) {
            "ctr" -> innerY + (innerH - built.totalHeight) / 2f
            "b" -> innerY + innerH - built.totalHeight
            else -> innerY
        }
        if (y < innerY) y = innerY
        for (item in built.items) {
            val layout = item.layout
            if (layout != null) {
                canvas.save()
                canvas.translate(innerX + item.left, y)
                layout.draw(canvas)
                canvas.restore()
                val label = item.label
                if (label != null) {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                    paint.textSize = item.labelSize
                    paint.color = item.labelColor
                    canvas.drawText(label, innerX + item.left + item.labelOffset, y + layout.getLineBaseline(0), paint)
                }
                y += layout.height + item.gapAfter
            } else {
                y += item.gapAfter
            }
        }
    }

    private class Item(
        val layout: StaticLayout?,
        val left: Float,
        val label: String?,
        val labelOffset: Float,
        val labelSize: Float,
        val labelColor: Int,
        val gapAfter: Float
    )

    private class Built(val items: List<Item>, val totalHeight: Float)

    private fun alignOf(v: String?): Layout.Alignment {
        return when (v) {
            "ctr" -> Layout.Alignment.ALIGN_CENTER
            "r" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
    }

    private fun buildLayouts(s: Shape, innerW: Float, scale: Float, defaultColor: Int): Built {
        val items = ArrayList<Item>()
        var total = 0f
        var counter = 0
        var lastLevel = -1
        val bodyLike = isBodyLike(s)
        for (para in s.paras) {
            val baseSize = defaultSize(s, para.level)
            val size = baseSize * scale
            val sb = SpannableStringBuilder()
            for (run in para.runs) {
                val start = sb.length
                sb.append(run.text)
                val end = sb.length
                if (end <= start) continue
                val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                val runSize = (run.sizePt ?: baseSize) * scale
                sb.setSpan(AbsoluteSizeSpan(runSize.roundToInt().coerceAtLeast(4), false), start, end, flags)
                sb.setSpan(ForegroundColorSpan(run.color ?: defaultColor), start, end, flags)
                val bold = run.bold == true || (run.bold == null && s.hasPh && (s.phType == "title" || s.phType == "ctrTitle"))
                val italic = run.italic == true
                val style = if (bold && italic) {
                    Typeface.BOLD_ITALIC
                } else if (bold) {
                    Typeface.BOLD
                } else if (italic) {
                    Typeface.ITALIC
                } else {
                    Typeface.NORMAL
                }
                if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), start, end, flags)
                if (run.underline) sb.setSpan(UnderlineSpan(), start, end, flags)
            }
            val bulletOn = !para.noBullet && (para.bullet != null || para.autoNum || bodyLike)
            var label: String? = null
            var left = para.marL ?: 0f
            var labelOffset = 0f
            if (bulletOn && sb.isNotEmpty()) {
                if (left <= 0f) left = 24f * (para.level + 1)
                val ind = para.indent ?: -18f
                labelOffset = if (ind < 0f) ind else -18f
                if (para.level == lastLevel) counter++ else counter = 1
                label = if (para.autoNum) "$counter." else (para.bullet ?: "\u2022")
            } else {
                counter = 0
            }
            lastLevel = para.level
            val width = (innerW - left).roundToInt().coerceAtLeast(20)
            if (sb.isEmpty()) {
                val gap = size * 1.1f
                items.add(Item(null, left, null, 0f, size, defaultColor, gap))
                total += gap
            } else {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                paint.textSize = size
                paint.color = defaultColor
                val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width)
                    .setAlignment(alignOf(para.align))
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(false)
                    .build()
                val gap = size * 0.25f
                items.add(Item(layout, left, label, labelOffset, size, defaultColor, gap))
                total += layout.height + gap
            }
        }
        return Built(items, total)
    }

    private fun drawTable(canvas: Canvas, s: Shape, box: Box, env: Env) {
        val table = s.table ?: return
        if (table.rows.isEmpty() || table.colW.isEmpty()) return
        var totalW = 0f
        for (w in table.colW) totalW += w
        val scaleX = if (totalW > 0f) box.w / totalW else 1f
        val defaultColor = env.schemeColor("tx1") ?: Color.BLACK
        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.style = Paint.Style.STROKE
        border.strokeWidth = 0.6f
        border.color = Color.rgb(140, 140, 140)
        val fillPaint = Paint()
        fillPaint.style = Paint.Style.FILL
        var y = box.y
        for ((r, row) in table.rows.withIndex()) {
            val declared = if (r < table.rowH.size) table.rowH[r] else 20f
            val layouts = ArrayList<StaticLayout?>()
            var rowH = declared
            var x = box.x
            var col = 0
            val xs = ArrayList<Float>()
            val ws = ArrayList<Float>()
            for (cell in row) {
                var cw = 0f
                for (k in col until min(col + cell.span, table.colW.size)) cw += table.colW[k] * scaleX
                col += cell.span
                xs.add(x)
                ws.add(cw)
                x += cw
                val sb = SpannableStringBuilder()
                for (para in cell.paras) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    for (run in para.runs) {
                        val start = sb.length
                        sb.append(run.text)
                        val end = sb.length
                        if (end <= start) continue
                        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        val size = (run.sizePt ?: 12f).coerceAtMost(14f)
                        sb.setSpan(AbsoluteSizeSpan(size.roundToInt(), false), start, end, flags)
                        sb.setSpan(ForegroundColorSpan(run.color ?: defaultColor), start, end, flags)
                        if (run.bold == true) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                    }
                }
                if (sb.isEmpty() || cell.merged) {
                    layouts.add(null)
                } else {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                    paint.textSize = 12f
                    paint.color = defaultColor
                    val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, (cw - 8f).roundToInt().coerceAtLeast(16))
                        .setIncludePad(false)
                        .build()
                    layouts.add(layout)
                    if (layout.height + 8f > rowH) rowH = layout.height + 8f
                }
            }
            for (i in row.indices) {
                val cell = row[i]
                val rect = RectF(xs[i], y, xs[i] + ws[i], y + rowH)
                val fill = cell.fill
                if (fill != null) {
                    fillPaint.color = fill
                    canvas.drawRect(rect, fillPaint)
                }
                canvas.drawRect(rect, border)
                val layout = layouts[i]
                if (layout != null) {
                    canvas.save()
                    canvas.translate(xs[i] + 4f, y + 4f)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
            y += rowH
        }
    }
}
