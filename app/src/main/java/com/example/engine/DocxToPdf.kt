package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Converts a .docx file to PDF without any server or office suite.
 * Supports text with bold/italic/underline/size/colour, headings, alignment, bullet and numbered
 * lists, tables, embedded pictures, page size and margins. Complex layout (columns, floating
 * shapes, headers/footers) is simplified.
 */
internal object DocxToPdf {

    private class RunProps {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var underline: Boolean? = null
        var strike: Boolean? = null
        var sizePt: Float? = null
        var color: Int? = null
        var family: String? = null

        fun copyFrom(o: RunProps) {
            if (o.bold != null) bold = o.bold
            if (o.italic != null) italic = o.italic
            if (o.underline != null) underline = o.underline
            if (o.strike != null) strike = o.strike
            if (o.sizePt != null) sizePt = o.sizePt
            if (o.color != null) color = o.color
            if (o.family != null) family = o.family
        }
    }

    private class ParaProps {
        var align: String? = null
        var before: Float? = null
        var after: Float? = null
        var left: Float? = null
        var firstLine: Float? = null
        var hanging: Float? = null

        fun copyFrom(o: ParaProps) {
            if (o.align != null) align = o.align
            if (o.before != null) before = o.before
            if (o.after != null) after = o.after
            if (o.left != null) left = o.left
            if (o.firstLine != null) firstLine = o.firstLine
            if (o.hanging != null) hanging = o.hanging
        }
    }

    private class StyleDef(val basedOn: String?, val run: RunProps, val para: ParaProps)

    private class StyleSheet {
        val styles = HashMap<String, StyleDef>()
        var defaultParaId: String? = null
        val defRun = RunProps()
        val defPara = ParaProps()
    }

    private class NumLevel(val fmt: String, val text: String)

    private class Numbering {
        val numToAbstract = HashMap<String, String>()
        val abstracts = HashMap<String, HashMap<Int, NumLevel>>()
    }

    /** kind: 0 = text, 1 = page break, 2 = picture. */
    private class Run(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val sizePt: Float,
        val color: Int,
        val family: String?,
        val kind: Int,
        val imageRid: String?,
        val imageW: Float,
        val imageH: Float
    )

    private class ParaInfo {
        var styleId: String? = null
        var numId: String? = null
        var ilvl: Int = 0
        var pageBreakBefore: Boolean = false
        var sectionBreakAfter: Boolean = false
        val props = ParaProps()
    }

    private class Para(val runs: List<Run>, val info: ParaInfo, val resolved: ParaProps)

    private class Cell(val paras: List<Para>, val span: Int)

    private class Row(val cells: List<Cell>)

    private class Block(val para: Para?, val rows: List<Row>?, val grid: List<Float>?)

    private class Doc(
        val pkg: OoxmlPackage,
        val rels: Map<String, String>,
        val sheet: StyleSheet,
        val numbering: Numbering
    ) {
        var pageW = 595f
        var pageH = 842f
        var mTop = 56f
        var mBottom = 56f
        var mLeft = 56f
        var mRight = 56f
        var sectionSeen = false
        val blocks = ArrayList<Block>()
    }

    private class RenderState {
        val counters = HashMap<String, IntArray>()
        val bitmaps = HashMap<String, Bitmap?>()
        var pendingBreak = false
    }

    // ---------------------------------------------------------------- entry point

    fun convert(input: File, output: File) {
        OoxmlPackage(input).use { pkg ->
            if (!pkg.has("word/document.xml")) {
                throw IOException("This file is not a valid .docx document.")
            }
            val sheet = parseStyles(pkg)
            val numbering = parseNumbering(pkg)
            val rels = Ooxml.relationships(pkg, "word/_rels/document.xml.rels")
            val doc = Doc(pkg, rels, sheet, numbering)
            parseDocument(doc)
            writePdf(doc, output)
        }
    }

    // ---------------------------------------------------------------- parsing: styles

    private fun parseStyles(pkg: OoxmlPackage): StyleSheet {
        val sheet = StyleSheet()
        val stream = pkg.open("word/styles.xml") ?: return sheet
        stream.use { s ->
            val p = Ooxml.parser(s)
            var inDefaults = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = p.name
                    if (name == "w:docDefaults") {
                        inDefaults = true
                    } else if (name == "w:rPr" && inDefaults) {
                        parseRunProps(p, sheet.defRun)
                    } else if (name == "w:pPr" && inDefaults) {
                        val tmp = ParaInfo()
                        parsePPr(p, tmp, null)
                        sheet.defPara.copyFrom(tmp.props)
                    } else if (name == "w:style") {
                        parseStyle(p, sheet)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "w:docDefaults") {
                    inDefaults = false
                }
                ev = p.next()
            }
        }
        return sheet
    }

    private fun parseStyle(p: XmlPullParser, sheet: StyleSheet) {
        val id = Ooxml.attr(p, "w:styleId") ?: ""
        val type = Ooxml.attr(p, "w:type") ?: "paragraph"
        val isDefault = Ooxml.attr(p, "w:default") == "1"
        var basedOn: String? = null
        val run = RunProps()
        val info = ParaInfo()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:basedOn" -> basedOn = Ooxml.attr(p, "w:val")
                "w:rPr" -> parseRunProps(p, run)
                "w:pPr" -> parsePPr(p, info, null)
            }
        }
        if (id.isNotEmpty()) {
            sheet.styles[id] = StyleDef(basedOn, run, info.props)
            if (type == "paragraph" && isDefault) sheet.defaultParaId = id
        }
    }

    private fun parseRunProps(p: XmlPullParser, into: RunProps) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:b" -> into.bold = Ooxml.onOff(p)
                "w:i" -> into.italic = Ooxml.onOff(p)
                "w:strike", "w:dstrike" -> into.strike = Ooxml.onOff(p)
                "w:u" -> {
                    val v = Ooxml.attr(p, "w:val")
                    into.underline = v != null && v != "none"
                }
                "w:sz" -> {
                    val half = Ooxml.floatAttr(p, "w:val")
                    if (half != null) into.sizePt = half / 2f
                }
                "w:color" -> {
                    val v = Ooxml.attr(p, "w:val")
                    if (v != null && v != "auto") into.color = Ooxml.hexColor(v)
                }
                "w:rFonts" -> {
                    val f = Ooxml.attr(p, "w:ascii") ?: Ooxml.attr(p, "w:hAnsi")
                    if (f != null) into.family = familyFor(f)
                }
            }
        }
    }

    private fun familyFor(font: String): String? {
        val f = font.lowercase()
        if (f.contains("courier") || f.contains("consolas") || f.contains("mono") || f.contains("menlo")) {
            return "monospace"
        }
        val serif = f.contains("times") || f.contains("georgia") || f.contains("cambria") ||
            f.contains("garamond") || f.contains("book") || (f.contains("serif") && !f.contains("sans"))
        return if (serif) "serif" else null
    }

    private fun parsePPr(p: XmlPullParser, info: ParaInfo, doc: Doc?) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:pStyle" -> info.styleId = Ooxml.attr(p, "w:val")
                "w:jc" -> info.props.align = Ooxml.attr(p, "w:val")
                "w:pageBreakBefore" -> info.pageBreakBefore = Ooxml.onOff(p)
                "w:spacing" -> {
                    val before = Ooxml.floatAttr(p, "w:before")
                    val after = Ooxml.floatAttr(p, "w:after")
                    if (before != null) info.props.before = before / 20f
                    if (after != null) info.props.after = after / 20f
                }
                "w:ind" -> {
                    val left = Ooxml.floatAttr(p, "w:left") ?: Ooxml.floatAttr(p, "w:start")
                    val first = Ooxml.floatAttr(p, "w:firstLine")
                    val hang = Ooxml.floatAttr(p, "w:hanging")
                    if (left != null) info.props.left = left / 20f
                    if (first != null) info.props.firstLine = first / 20f
                    if (hang != null) info.props.hanging = hang / 20f
                }
                "w:numId" -> info.numId = Ooxml.attr(p, "w:val")
                "w:ilvl" -> info.ilvl = Ooxml.intAttr(p, "w:val") ?: 0
                "w:rPr" -> Ooxml.skip(p)
                "w:sectPr" -> parseSectPr(p, doc, info)
            }
        }
    }

    private fun parseSectPr(p: XmlPullParser, doc: Doc?, info: ParaInfo?) {
        val depth = p.depth
        var continuous = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:pgSz" -> {
                    if (doc != null && !doc.sectionSeen) {
                        val w = Ooxml.floatAttr(p, "w:w")
                        val h = Ooxml.floatAttr(p, "w:h")
                        if (w != null && h != null && w > 0f && h > 0f) {
                            var pw = w / 20f
                            var ph = h / 20f
                            if (Ooxml.attr(p, "w:orient") == "landscape" && pw < ph) {
                                val t = pw
                                pw = ph
                                ph = t
                            }
                            doc.pageW = pw
                            doc.pageH = ph
                        }
                    }
                }
                "w:pgMar" -> {
                    if (doc != null && !doc.sectionSeen) {
                        val top = Ooxml.floatAttr(p, "w:top")
                        val bottom = Ooxml.floatAttr(p, "w:bottom")
                        val left = Ooxml.floatAttr(p, "w:left")
                        val right = Ooxml.floatAttr(p, "w:right")
                        if (top != null) doc.mTop = (top / 20f).coerceIn(18f, 160f)
                        if (bottom != null) doc.mBottom = (bottom / 20f).coerceIn(18f, 160f)
                        if (left != null) doc.mLeft = (left / 20f).coerceIn(18f, 160f)
                        if (right != null) doc.mRight = (right / 20f).coerceIn(18f, 160f)
                    }
                }
                "w:type" -> {
                    if (Ooxml.attr(p, "w:val") == "continuous") continuous = true
                }
            }
        }
        if (doc != null) doc.sectionSeen = true
        if (info != null && !continuous) info.sectionBreakAfter = true
    }

    private fun parseNumbering(pkg: OoxmlPackage): Numbering {
        val n = Numbering()
        val stream = pkg.open("word/numbering.xml") ?: return n
        stream.use { s ->
            val p = Ooxml.parser(s)
            var currentAbstract: String? = null
            var currentNum: String? = null
            var currentLevel = -1
            var currentFmt = "decimal"
            var currentText = "%1."
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "w:abstractNum" -> {
                            val id = Ooxml.attr(p, "w:abstractNumId")
                            currentAbstract = id
                            currentNum = null
                            if (id != null) n.abstracts[id] = HashMap()
                        }
                        "w:lvl" -> {
                            currentLevel = Ooxml.intAttr(p, "w:ilvl") ?: 0
                            currentFmt = "decimal"
                            currentText = "%1."
                        }
                        "w:numFmt" -> currentFmt = Ooxml.attr(p, "w:val") ?: "decimal"
                        "w:lvlText" -> currentText = Ooxml.attr(p, "w:val") ?: ""
                        "w:num" -> {
                            currentNum = Ooxml.attr(p, "w:numId")
                            currentAbstract = null
                        }
                        "w:abstractNumId" -> {
                            val absId = Ooxml.attr(p, "w:val")
                            val numId = currentNum
                            if (numId != null && absId != null) n.numToAbstract[numId] = absId
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "w:lvl") {
                    val abs = currentAbstract
                    if (abs != null && currentLevel >= 0) {
                        n.abstracts[abs]?.put(currentLevel, NumLevel(currentFmt, currentText))
                    }
                }
                ev = p.next()
            }
        }
        return n
    }

    // ---------------------------------------------------------------- parsing: document body

    private fun parseDocument(d: Doc) {
        val stream = d.pkg.open("word/document.xml") ?: throw IOException("This Word file has no readable content.")
        stream.use { s ->
            val p = Ooxml.parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "w:p" -> parseParagraph(p, d, d.blocks)
                        "w:tbl" -> parseTable(p, d, d.blocks)
                        "w:sectPr" -> parseSectPr(p, d, null)
                        "mc:Fallback" -> Ooxml.skip(p)
                    }
                }
                ev = p.next()
            }
        }
    }

    private fun parseParagraph(p: XmlPullParser, d: Doc, out: MutableList<Block>) {
        val depth = p.depth
        val info = ParaInfo()
        val runs = ArrayList<Run>()
        val extras = ArrayList<Block>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:pPr" -> parsePPr(p, info, d)
                "w:r" -> parseRun(p, d, info, runs, extras)
                "mc:Fallback" -> Ooxml.skip(p)
                "w:txbxContent" -> parseNested(p, d, extras)
            }
        }
        out.add(Block(Para(runs, info, resolveParaProps(info, d)), null, null))
        out.addAll(extras)
    }

    private fun parseNested(p: XmlPullParser, d: Doc, out: MutableList<Block>) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:p" -> parseParagraph(p, d, out)
                "w:tbl" -> parseTable(p, d, out)
            }
        }
    }

    private fun parseRun(
        p: XmlPullParser,
        d: Doc,
        info: ParaInfo,
        runs: MutableList<Run>,
        extras: MutableList<Block>
    ) {
        val depth = p.depth
        val own = RunProps()
        val text = StringBuilder()

        fun emitText() {
            if (text.isEmpty()) return
            val eff = effectiveRun(d, info, own)
            runs.add(
                Run(
                    text.toString(),
                    eff.bold == true,
                    eff.italic == true,
                    eff.underline == true,
                    eff.strike == true,
                    eff.sizePt ?: 11f,
                    eff.color ?: Color.BLACK,
                    eff.family,
                    0, null, 0f, 0f
                )
            )
            text.setLength(0)
        }

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:rPr" -> parseRunProps(p, own)
                "w:t" -> text.append(p.nextText())
                "w:tab" -> text.append("    ")
                "w:noBreakHyphen" -> text.append('-')
                "w:br" -> {
                    if (Ooxml.attr(p, "w:type") == "page") {
                        emitText()
                        runs.add(Run("", false, false, false, false, 11f, Color.BLACK, null, 1, null, 0f, 0f))
                    } else {
                        text.append('\n')
                    }
                }
                "w:cr" -> text.append('\n')
                "w:drawing" -> {
                    emitText()
                    parseDrawing(p, d, runs, extras)
                }
                "mc:Fallback" -> Ooxml.skip(p)
                "w:txbxContent" -> parseNested(p, d, extras)
            }
        }
        emitText()
    }

    private fun parseDrawing(p: XmlPullParser, d: Doc, runs: MutableList<Run>, extras: MutableList<Block>) {
        val depth = p.depth
        var cx = 0L
        var cy = 0L
        var rid: String? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "wp:extent" -> {
                    cx = Ooxml.longAttr(p, "cx") ?: 0L
                    cy = Ooxml.longAttr(p, "cy") ?: 0L
                }
                "a:blip" -> {
                    if (rid == null) rid = Ooxml.attr(p, "r:embed")
                }
                "w:txbxContent" -> parseNested(p, d, extras)
                "mc:Fallback" -> Ooxml.skip(p)
            }
        }
        if (rid != null) {
            runs.add(
                Run("", false, false, false, false, 11f, Color.BLACK, null, 2, rid, Ooxml.emuToPt(cx), Ooxml.emuToPt(cy))
            )
        }
    }

    private fun parseTable(p: XmlPullParser, d: Doc, out: MutableList<Block>) {
        val depth = p.depth
        val grid = ArrayList<Float>()
        val rows = ArrayList<Row>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:gridCol" -> {
                    val w = Ooxml.floatAttr(p, "w:w")
                    if (w != null) grid.add(w / 20f)
                }
                "w:tr" -> rows.add(parseRow(p, d))
            }
        }
        out.add(Block(null, rows, grid))
    }

    private fun parseRow(p: XmlPullParser, d: Doc): Row {
        val depth = p.depth
        val cells = ArrayList<Cell>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            if (p.name == "w:tc") cells.add(parseCell(p, d))
        }
        return Row(cells)
    }

    private fun parseCell(p: XmlPullParser, d: Doc): Cell {
        val depth = p.depth
        var span = 1
        val blocks = ArrayList<Block>()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "w:gridSpan" -> span = Ooxml.intAttr(p, "w:val") ?: 1
                "w:p" -> parseParagraph(p, d, blocks)
                "w:tbl" -> parseTable(p, d, blocks)
            }
        }
        val paras = ArrayList<Para>()
        for (b in blocks) {
            val para = b.para
            if (para != null) paras.add(para)
            val nested = b.rows
            if (nested != null) {
                for (r in nested) {
                    for (c in r.cells) paras.addAll(c.paras)
                }
            }
        }
        return Cell(paras, span.coerceAtLeast(1))
    }

    // ---------------------------------------------------------------- style resolution

    private fun styleChain(d: Doc, id: String?): List<StyleDef> {
        val chain = ArrayList<StyleDef>()
        var current = id
        var guard = 0
        while (current != null && guard < 12) {
            val def = d.sheet.styles[current] ?: break
            chain.add(0, def)
            current = def.basedOn
            guard++
        }
        return chain
    }

    private fun effectiveRun(d: Doc, info: ParaInfo, own: RunProps): RunProps {
        val eff = RunProps()
        eff.copyFrom(d.sheet.defRun)
        for (def in styleChain(d, info.styleId ?: d.sheet.defaultParaId)) {
            eff.copyFrom(def.run)
        }
        eff.copyFrom(own)
        return eff
    }

    private fun resolveParaProps(info: ParaInfo, d: Doc): ParaProps {
        val out = ParaProps()
        out.copyFrom(d.sheet.defPara)
        for (def in styleChain(d, info.styleId ?: d.sheet.defaultParaId)) {
            out.copyFrom(def.para)
        }
        out.copyFrom(info.props)
        return out
    }

    // ---------------------------------------------------------------- rendering

    private fun writePdf(d: Doc, output: File) {
        val pdf = PdfDocument()
        try {
            val flow = PdfFlow(
                pdf,
                d.pageW.roundToInt(),
                d.pageH.roundToInt(),
                d.mLeft,
                d.mTop,
                d.mRight,
                d.mBottom
            )
            val state = RenderState()
            flow.newPage()
            for (block in d.blocks) {
                val para = block.para
                if (para != null) {
                    renderParagraph(d, para, flow, state)
                } else {
                    renderTable(d, block, flow, state)
                }
            }
            flow.finish()
            FileOutputStream(output).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun basePaint(): TextPaint {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = 11f
        tp.color = Color.BLACK
        return tp
    }

    private fun alignmentOf(v: String?): Layout.Alignment {
        return when (v) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
    }

    private fun applyPendingBreak(flow: PdfFlow, state: RenderState) {
        if (state.pendingBreak) {
            state.pendingBreak = false
            if (!flow.atTop()) flow.newPage()
        }
    }

    private fun appendRun(sb: SpannableStringBuilder, r: Run) {
        if (r.text.isEmpty()) return
        val start = sb.length
        sb.append(r.text)
        val end = sb.length
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(AbsoluteSizeSpan(r.sizePt.roundToInt().coerceAtLeast(4), false), start, end, flags)
        val style = if (r.bold && r.italic) {
            Typeface.BOLD_ITALIC
        } else if (r.bold) {
            Typeface.BOLD
        } else if (r.italic) {
            Typeface.ITALIC
        } else {
            Typeface.NORMAL
        }
        if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), start, end, flags)
        if (r.underline) sb.setSpan(UnderlineSpan(), start, end, flags)
        if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, flags)
        sb.setSpan(ForegroundColorSpan(r.color), start, end, flags)
        val family = r.family
        if (family != null) sb.setSpan(TypefaceSpan(family), start, end, flags)
    }

    private fun renderParagraph(d: Doc, para: Para, flow: PdfFlow, state: RenderState) {
        val props = para.resolved
        val info = para.info
        val align = alignmentOf(props.align)
        val numId = info.numId
        var label: String? = null
        if (numId != null && numId != "0") {
            label = nextLabel(d, numId, info.ilvl, state)
        }
        var left = props.left ?: 0f
        var hanging = props.hanging ?: 0f
        if (label != null && left <= 0f) {
            left = 20f * (info.ilvl + 1)
            hanging = 16f
        }
        left = left.coerceIn(0f, flow.contentWidth * 0.6f)
        val before = props.before ?: 0f
        val after = props.after ?: 0f

        if (info.pageBreakBefore) state.pendingBreak = true
        applyPendingBreak(flow, state)
        if (!flow.atTop()) flow.y += before

        val sb = SpannableStringBuilder()
        val width = (flow.contentWidth - left).roundToInt().coerceAtLeast(48)
        var labelPending: String? = label
        var hasContent = false

        fun flush() {
            if (sb.isEmpty()) return
            applyPendingBreak(flow, state)
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, basePaint(), width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .build()
            flow.ensureSpace(layout.getLineBottom(0).toFloat())
            val lab = labelPending
            if (lab != null) {
                drawLabel(flow, lab, flow.marginL + left - hanging, layout)
                labelPending = null
            }
            flow.drawLayout(layout, flow.marginL + left)
            hasContent = true
            sb.clear()
        }

        for (run in para.runs) {
            if (run.kind == 0) {
                appendRun(sb, run)
            } else if (run.kind == 1) {
                flush()
                state.pendingBreak = true
            } else if (run.kind == 2) {
                flush()
                applyPendingBreak(flow, state)
                drawImage(d, run, flow, state, left)
                hasContent = true
            }
        }
        flush()

        if (!hasContent && para.runs.isEmpty()) {
            applyPendingBreak(flow, state)
            val gap = 11f * 1.2f
            flow.ensureSpace(gap)
            flow.y += gap
        }
        flow.y += after
        if (info.sectionBreakAfter) state.pendingBreak = true
    }

    private fun drawLabel(flow: PdfFlow, label: String, x: Float, layout: StaticLayout) {
        val paint = basePaint()
        val baseline = flow.y + layout.getLineBaseline(0)
        flow.canvas().drawText(label, x, baseline, paint)
    }

    private fun drawImage(d: Doc, run: Run, flow: PdfFlow, state: RenderState, left: Float) {
        val rid = run.imageRid ?: return
        val bmp = loadBitmap(d, rid, state) ?: return
        var w = run.imageW
        var h = run.imageH
        if (w <= 0f || h <= 0f) {
            w = bmp.width.toFloat()
            h = bmp.height.toFloat()
        }
        val maxW = flow.contentWidth - left
        if (w > maxW) {
            h *= maxW / w
            w = maxW
        }
        val maxH = flow.contentHeight
        if (h > maxH) {
            w *= maxH / h
            h = maxH
        }
        flow.drawBitmap(bmp, w, h, flow.marginL + left)
    }

    private fun loadBitmap(d: Doc, rid: String, state: RenderState): Bitmap? {
        if (state.bitmaps.containsKey(rid)) return state.bitmaps[rid]
        var bmp: Bitmap? = null
        val target = d.rels[rid]
        if (target != null) {
            val path = Ooxml.resolve("word/document.xml", target)
            val bytes = d.pkg.bytes(path)
            if (bytes != null) bmp = Ooxml.decodeBitmap(bytes, 1600)
        }
        state.bitmaps[rid] = bmp
        return bmp
    }

    private fun layoutPlain(para: Para, width: Int): StaticLayout? {
        val sb = SpannableStringBuilder()
        for (r in para.runs) {
            if (r.kind == 0) appendRun(sb, r)
        }
        if (sb.isEmpty()) return null
        return StaticLayout.Builder.obtain(sb, 0, sb.length, basePaint(), width)
            .setAlignment(alignmentOf(para.resolved.align))
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun renderTable(d: Doc, block: Block, flow: PdfFlow, state: RenderState) {
        val rows = block.rows ?: return
        applyPendingBreak(flow, state)
        var cols = 0
        for (r in rows) {
            var c = 0
            for (cell in r.cells) c += cell.span
            if (c > cols) cols = c
        }
        if (cols == 0) return
        val grid = block.grid ?: emptyList()
        val avail = flow.contentWidth
        val widths = FloatArray(cols)
        var total = 0f
        for (i in 0 until cols) {
            widths[i] = if (i < grid.size && grid[i] > 0f) grid[i] else avail / cols
            total += widths[i]
        }
        if (total > avail) {
            val scale = avail / total
            for (i in 0 until cols) widths[i] = widths[i] * scale
        }
        val pad = 3f
        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.style = Paint.Style.STROKE
        border.strokeWidth = 0.6f
        border.color = Color.rgb(150, 150, 150)
        flow.y += 2f

        for (row in rows) {
            val layouts = ArrayList<List<StaticLayout>>()
            val cellWidths = ArrayList<Float>()
            var col = 0
            var rowH = 14f
            for (cell in row.cells) {
                var w = 0f
                val last = (col + cell.span).coerceAtMost(cols)
                for (k in col until last) w += widths[k]
                col += cell.span
                cellWidths.add(w)
                val list = ArrayList<StaticLayout>()
                var h = pad * 2
                for (para in cell.paras) {
                    val layout = layoutPlain(para, (w - pad * 2).roundToInt().coerceAtLeast(16))
                    if (layout != null) {
                        list.add(layout)
                        h += layout.height
                    }
                }
                layouts.add(list)
                if (h > rowH) rowH = h
            }
            flow.ensureSpace(rowH.coerceAtMost(flow.contentHeight))
            val c = flow.canvas()
            var x = flow.marginL
            for (i in row.cells.indices) {
                val w = cellWidths[i]
                c.drawRect(x, flow.y, x + w, flow.y + rowH, border)
                var yy = flow.y + pad
                for (layout in layouts[i]) {
                    c.save()
                    c.translate(x + pad, yy)
                    layout.draw(c)
                    c.restore()
                    yy += layout.height
                }
                x += w
            }
            flow.y += rowH
        }
        flow.y += 6f
    }

    // ---------------------------------------------------------------- numbering labels

    private fun nextLabel(d: Doc, numId: String, ilvl: Int, state: RenderState): String {
        val abstractId = d.numbering.numToAbstract[numId]
        val levels = if (abstractId != null) d.numbering.abstracts[abstractId] else null
        val lv = ilvl.coerceIn(0, 8)
        val counters = state.counters.getOrPut(numId) { IntArray(9) }
        counters[lv] = counters[lv] + 1
        for (k in lv + 1 until 9) counters[k] = 0
        val level = levels?.get(lv)
        if (level == null || level.fmt == "bullet") {
            return if (lv == 0) "\u2022" else "\u2013"
        }
        var text = level.text
        for (k in 0..lv) {
            val fmt = levels.get(k)?.fmt ?: "decimal"
            text = text.replace("%" + (k + 1), formatNumber(counters[k], fmt))
        }
        return text
    }

    private fun formatNumber(n: Int, fmt: String): String {
        return when (fmt) {
            "lowerLetter" -> letters(n, false)
            "upperLetter" -> letters(n, true)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            else -> n.toString()
        }
    }

    private fun letters(n: Int, upper: Boolean): String {
        var v = n
        val sb = StringBuilder()
        while (v > 0) {
            v--
            sb.insert(0, 'a' + (v % 26))
            v /= 26
        }
        val s = sb.toString()
        return if (upper) s.uppercase() else s
    }

    private fun roman(n: Int): String {
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var v = n.coerceAtLeast(1)
        val sb = StringBuilder()
        for (i in values.indices) {
            while (v >= values[i]) {
                sb.append(symbols[i])
                v -= values[i]
            }
        }
        return sb.toString()
    }
}
