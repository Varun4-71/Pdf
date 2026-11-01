package com.example.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Converts an .xlsx workbook to PDF. Every visible sheet becomes one or more landscape pages with
 * gridlines, column widths, bold text, cell fills, merged headers and common number/date formats.
 * Charts, images and conditional formatting are not drawn.
 */
internal object XlsxToPdf {

    private const val MAX_ROWS = 4000
    private const val MAX_COLS = 40

    private class Xf(val numFmtId: Int, val fontId: Int, val fillId: Int) {
        var halign: String? = null
    }

    private class Styles {
        val numFmts = HashMap<Int, String>()
        val xfs = ArrayList<Xf>()
        val boldFonts = HashSet<Int>()
        val fills = HashMap<Int, Int>()
    }

    private class XCell(val col: Int, val text: String, val numeric: Boolean, val style: Int)

    private class XRow(val index: Int, val height: Float?, val cells: List<XCell>)

    private class Sheet(val name: String) {
        val rows = ArrayList<XRow>()
        val colWidths = HashMap<Int, Float>()
        var defaultColWidth = 48f
        var defaultRowHeight = 15f
        val merges = HashMap<Long, IntArray>()
        var truncated = false
    }

    private class SheetRef(val name: String, val rid: String, val hidden: Boolean)

    private class Seg(val col: Int, val span: Int, val x: Float, val w: Float)

    private class Paints {
        val normal = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val bold = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val grid = Paint()
        val fill = Paint()

        init {
            normal.textSize = 9f
            normal.color = Color.BLACK
            bold.textSize = 9f
            bold.color = Color.BLACK
            bold.typeface = Typeface.DEFAULT_BOLD
            title.textSize = 13f
            title.color = Color.BLACK
            title.typeface = Typeface.DEFAULT_BOLD
            sub.textSize = 8f
            sub.color = Color.GRAY
            grid.style = Paint.Style.STROKE
            grid.strokeWidth = 0.4f
            grid.color = Color.rgb(205, 205, 205)
            fill.style = Paint.Style.FILL
        }
    }

    private class PageWriter(val pdf: PdfDocument) {
        val pageW = 842
        val pageH = 595
        val margin = 28f
        private var page: PdfDocument.Page? = null
        private var count = 0

        fun newPage(): Canvas {
            finish()
            count++
            val fresh = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, count).create())
            page = fresh
            return fresh.canvas
        }

        fun finish() {
            val pg = page
            if (pg != null) pdf.finishPage(pg)
            page = null
        }

        val pages: Int
            get() = count
    }

    // ---------------------------------------------------------------- entry point

    fun convert(input: File, output: File) {
        OoxmlPackage(input).use { pkg ->
            if (!pkg.has("xl/workbook.xml")) {
                throw IOException("This file is not a valid .xlsx workbook.")
            }
            val shared = parseSharedStrings(pkg)
            val styles = parseStyles(pkg)
            val refs = parseWorkbook(pkg)
            val rels = Ooxml.relationships(pkg, "xl/_rels/workbook.xml.rels")
            val pdf = PdfDocument()
            try {
                val writer = PageWriter(pdf)
                val paints = Paints()
                for (ref in refs) {
                    if (ref.hidden) continue
                    val target = rels[ref.rid] ?: continue
                    val path = Ooxml.resolve("xl/workbook.xml", target)
                    val sheet = parseSheet(pkg, path, ref.name, shared, styles)
                    if (sheet.rows.isEmpty()) continue
                    renderSheet(writer, sheet, styles, paints)
                }
                if (writer.pages == 0) {
                    val canvas = writer.newPage()
                    canvas.drawText("This workbook has no data to show.", writer.margin, writer.margin + 20f, paints.normal)
                }
                writer.finish()
                FileOutputStream(output).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseWorkbook(pkg: OoxmlPackage): List<SheetRef> {
        val list = ArrayList<SheetRef>()
        val stream = pkg.open("xl/workbook.xml") ?: return list
        stream.use { s ->
            val p = Ooxml.parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = Ooxml.attr(p, "name") ?: "Sheet"
                    val rid = Ooxml.attr(p, "r:id")
                    val state = Ooxml.attr(p, "state")
                    if (rid != null) {
                        list.add(SheetRef(name, rid, state == "hidden" || state == "veryHidden"))
                    }
                }
                ev = p.next()
            }
        }
        return list
    }

    private fun parseSharedStrings(pkg: OoxmlPackage): List<String> {
        val list = ArrayList<String>()
        val stream = pkg.open("xl/sharedStrings.xml") ?: return list
        stream.use { s ->
            val p = Ooxml.parser(s)
            var inSi = false
            var inPhonetic = false
            val sb = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = p.name
                    if (name == "si") {
                        inSi = true
                        sb.setLength(0)
                    } else if (name == "rPh") {
                        inPhonetic = true
                    } else if (name == "t" && inSi && !inPhonetic) {
                        sb.append(p.nextText())
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val name = p.name
                    if (name == "rPh") {
                        inPhonetic = false
                    } else if (name == "si") {
                        list.add(sb.toString())
                        inSi = false
                    }
                }
                ev = p.next()
            }
        }
        return list
    }

    private fun parseStyles(pkg: OoxmlPackage): Styles {
        val st = Styles()
        val stream = pkg.open("xl/styles.xml") ?: return st
        stream.use { s ->
            val p = Ooxml.parser(s)
            var section = ""
            var fontIndex = -1
            var fillIndex = -1
            var pattern: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "numFmts" -> section = "numFmts"
                        "fonts" -> section = "fonts"
                        "fills" -> section = "fills"
                        "cellXfs" -> section = "cellXfs"
                        "cellStyleXfs", "borders", "cellStyles", "dxfs" -> section = "other"
                        "numFmt" -> {
                            if (section == "numFmts") {
                                val id = Ooxml.intAttr(p, "numFmtId")
                                val code = Ooxml.attr(p, "formatCode")
                                if (id != null && code != null) st.numFmts[id] = code
                            }
                        }
                        "font" -> {
                            if (section == "fonts") fontIndex++
                        }
                        "b" -> {
                            if (section == "fonts" && fontIndex >= 0) {
                                val v = Ooxml.attr(p, "val")
                                if (v == null || v == "1" || v == "true") st.boldFonts.add(fontIndex)
                            }
                        }
                        "fill" -> {
                            if (section == "fills") {
                                fillIndex++
                                pattern = null
                            }
                        }
                        "patternFill" -> {
                            if (section == "fills") pattern = Ooxml.attr(p, "patternType")
                        }
                        "fgColor" -> {
                            if (section == "fills" && fillIndex >= 0 && pattern == "solid") {
                                val rgb = Ooxml.hexColor(Ooxml.attr(p, "rgb"))
                                if (rgb != null) st.fills[fillIndex] = rgb
                            }
                        }
                        "xf" -> {
                            if (section == "cellXfs") {
                                st.xfs.add(
                                    Xf(
                                        Ooxml.intAttr(p, "numFmtId") ?: 0,
                                        Ooxml.intAttr(p, "fontId") ?: 0,
                                        Ooxml.intAttr(p, "fillId") ?: 0
                                    )
                                )
                            }
                        }
                        "alignment" -> {
                            if (section == "cellXfs" && st.xfs.isNotEmpty()) {
                                st.xfs[st.xfs.size - 1].halign = Ooxml.attr(p, "horizontal")
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }
        return st
    }

    private fun parseSheet(
        pkg: OoxmlPackage,
        path: String,
        name: String,
        shared: List<String>,
        styles: Styles
    ): Sheet {
        val sheet = Sheet(name)
        val stream = pkg.open(path) ?: return sheet
        stream.use { s ->
            val p = Ooxml.parser(s)
            var rowIndex = 0
            var rowHeight: Float? = null
            var cells = ArrayList<XCell>()
            var inRow = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "sheetFormatPr" -> {
                            val dcw = Ooxml.floatAttr(p, "defaultColWidth")
                            val drh = Ooxml.floatAttr(p, "defaultRowHeight")
                            if (dcw != null && dcw > 0f) sheet.defaultColWidth = charsToPt(dcw)
                            if (drh != null && drh > 0f) sheet.defaultRowHeight = drh
                        }
                        "col" -> {
                            val min = Ooxml.intAttr(p, "min") ?: 1
                            val max = (Ooxml.intAttr(p, "max") ?: min).coerceAtMost(MAX_COLS)
                            val width = Ooxml.floatAttr(p, "width")
                            val hidden = Ooxml.attr(p, "hidden") == "1"
                            for (i in min..max) {
                                if (hidden) {
                                    sheet.colWidths[i - 1] = 0f
                                } else if (width != null) {
                                    sheet.colWidths[i - 1] = charsToPt(width)
                                }
                            }
                        }
                        "row" -> {
                            rowIndex = Ooxml.intAttr(p, "r") ?: (rowIndex + 1)
                            rowHeight = Ooxml.floatAttr(p, "ht")
                            cells = ArrayList()
                            val hidden = Ooxml.attr(p, "hidden") == "1"
                            if (sheet.rows.size >= MAX_ROWS) {
                                sheet.truncated = true
                                Ooxml.skip(p)
                            } else if (hidden) {
                                Ooxml.skip(p)
                            } else {
                                inRow = true
                            }
                        }
                        "c" -> {
                            if (inRow) {
                                val cell = parseCell(p, shared, styles, cells.size)
                                if (cell != null) cells.add(cell)
                            }
                        }
                        "mergeCell" -> parseMerge(p, sheet)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "row" && inRow) {
                    sheet.rows.add(XRow(rowIndex, rowHeight, cells))
                    inRow = false
                }
                ev = p.next()
            }
        }
        return sheet
    }

    private fun charsToPt(chars: Float): Float = (chars * 7f + 5f) * 0.75f

    private fun mergeKey(row: Int, col: Int): Long = row.toLong() * 100000L + col

    private fun parseMerge(p: XmlPullParser, sheet: Sheet) {
        val ref = Ooxml.attr(p, "ref") ?: return
        val parts = ref.split(':')
        if (parts.size != 2) return
        val a = splitRef(parts[0]) ?: return
        val b = splitRef(parts[1]) ?: return
        val rowSpan = b.second - a.second + 1
        val colSpan = b.first - a.first + 1
        if (rowSpan > 1 || colSpan > 1) {
            sheet.merges[mergeKey(a.second, a.first)] = intArrayOf(rowSpan, colSpan)
        }
    }

    /** "B12" -> (col 1, row 12) */
    private fun splitRef(ref: String): Pair<Int, Int>? {
        var i = 0
        var col = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        val row = ref.substring(i).toIntOrNull() ?: return null
        if (col == 0) return null
        return Pair(col - 1, row)
    }

    private fun parseCell(p: XmlPullParser, shared: List<String>, styles: Styles, fallbackCol: Int): XCell? {
        val ref = Ooxml.attr(p, "r")
        val type = Ooxml.attr(p, "t")
        val styleIdx = Ooxml.intAttr(p, "s") ?: 0
        val col = if (ref != null) (splitRef(ref)?.first ?: fallbackCol) else fallbackCol
        val depth = p.depth
        var v: String? = null
        var inline: String? = null
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "v" -> v = p.nextText()
                "is" -> inline = readInlineString(p)
                "f" -> Ooxml.skip(p)
            }
        }
        if (col >= MAX_COLS) return null
        val xf = styles.xfs.getOrNull(styleIdx)
        var numeric = false
        val text: String = when (type) {
            "s" -> {
                val idx = v?.trim()?.toIntOrNull()
                if (idx != null && idx >= 0 && idx < shared.size) shared[idx] else ""
            }
            "str", "inlineStr" -> inline ?: (v ?: "")
            "b" -> if (v == "1") "TRUE" else "FALSE"
            "e", "d" -> v ?: ""
            else -> {
                numeric = true
                val raw = v
                if (raw == null || raw.isBlank()) "" else formatNumeric(raw.trim(), xf, styles)
            }
        }
        val hasFill = xf != null && styles.fills.containsKey(xf.fillId)
        if (text.isEmpty() && !hasFill) return null
        return XCell(col, text, numeric, styleIdx)
    }

    private fun readInlineString(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var inPhonetic = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "rPh") {
                    inPhonetic = true
                } else if (p.name == "t" && !inPhonetic) {
                    sb.append(p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "rPh") {
                inPhonetic = false
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- number formatting

    private fun general(raw: String): String {
        return try {
            val rounded = BigDecimal(raw.trim()).round(MathContext(10))
            val stripped = if (rounded.signum() == 0) BigDecimal.ZERO else rounded.stripTrailingZeros()
            stripped.toPlainString()
        } catch (e: NumberFormatException) {
            raw
        }
    }

    private fun formatNumeric(raw: String, xf: Xf?, styles: Styles): String {
        val value = raw.toDoubleOrNull() ?: return raw
        val id = xf?.numFmtId ?: 0
        val code = styles.numFmts[id]
        if (isDateFormat(id, code)) return formatDate(value, id, code)
        if (code != null && code.lowercase() == "general") return general(raw)
        if (id == 0 && code == null) return general(raw)
        val percent = id == 9 || id == 10 || (code != null && code.contains('%'))
        val decimals = decimalsFor(id, code)
        if (decimals < 0 && !percent) return general(raw)
        val thousands = id == 3 || id == 4 ||
            (code != null && (code.contains("#,#") || code.contains("#,0") || code.contains("0,0")))
        var v = value
        if (percent) v *= 100.0
        val pattern = StringBuilder(if (thousands) "#,##0" else "0")
        val d = if (decimals < 0) 0 else decimals
        if (d > 0) {
            pattern.append('.')
            for (k in 0 until d) pattern.append('0')
        }
        val fmt = DecimalFormat(pattern.toString(), DecimalFormatSymbols(Locale.US))
        var text = fmt.format(v)
        if (percent) text += "%"
        return currencyPrefix(code) + text
    }

    private fun currencyPrefix(code: String?): String {
        if (code == null) return ""
        val symbols = "$\u20AC\u00A3\u00A5\u20B9"
        for (ch in code) {
            if (symbols.indexOf(ch) >= 0) return ch.toString()
        }
        return ""
    }

    private fun decimalsFor(id: Int, code: String?): Int {
        when (id) {
            1, 3, 9, 37, 38 -> return 0
            2, 4, 10, 39, 40 -> return 2
        }
        if (code == null) return -1
        val first = code.substringBefore(';')
        val cleaned = first.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*\\]"), "")
        val dot = cleaned.indexOf('.')
        if (dot < 0) {
            return if (cleaned.contains('0') || cleaned.contains('#')) 0 else -1
        }
        var n = 0
        var i = dot + 1
        while (i < cleaned.length && (cleaned[i] == '0' || cleaned[i] == '#')) {
            n++
            i++
        }
        return n
    }

    private fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 45..47) return true
        if (code == null) return false
        val cleaned = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\\\."), "")
            .lowercase()
        if (cleaned.contains("general")) return false
        return cleaned.contains("yy") || cleaned.contains("dd") || cleaned.contains("hh") ||
            cleaned.contains("h:") || cleaned.contains("ss") ||
            (cleaned.contains("d") && cleaned.contains("m") && !cleaned.contains("0"))
    }

    private fun formatDate(serial: Double, id: Int, code: String?): String {
        val pattern = when (id) {
            14 -> "M/d/yyyy"
            15 -> "d-MMM-yy"
            16 -> "d-MMM"
            17 -> "MMM-yy"
            18 -> "h:mm a"
            19 -> "h:mm:ss a"
            20 -> "H:mm"
            21 -> "H:mm:ss"
            22 -> "M/d/yyyy H:mm"
            else -> if (code != null) excelToJavaPattern(code) else "yyyy-MM-dd"
        }
        val millis = Math.round((serial - 25569.0) * 86400000.0)
        return try {
            val f = SimpleDateFormat(pattern, Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.format(Date(millis))
        } catch (e: IllegalArgumentException) {
            general(serial.toString())
        }
    }

    private fun excelToJavaPattern(code: String): String {
        val first = code.substringBefore(';').replace(Regex("\\[[^\\]]*\\]"), "")
        val twelveHour = first.contains("am/pm", true) || first.contains("a/p", true)
        val sb = StringBuilder()
        var i = 0
        var lastWasHour = false
        while (i < first.length) {
            val ch = first[i]
            if (ch == '"') {
                val end = first.indexOf('"', i + 1)
                val literal = if (end > i) first.substring(i + 1, end) else ""
                if (literal.isNotEmpty()) {
                    sb.append('\'').append(literal.replace("'", "''")).append('\'')
                }
                i = if (end > i) end + 1 else first.length
                continue
            }
            if (ch == '\\' && i + 1 < first.length) {
                sb.append('\'').append(first[i + 1]).append('\'')
                i += 2
                continue
            }
            if (first.regionMatches(i, "AM/PM", 0, 5, true)) {
                sb.append('a')
                i += 5
                continue
            }
            if (first.regionMatches(i, "A/P", 0, 3, true)) {
                sb.append('a')
                i += 3
                continue
            }
            val lower = ch.lowercaseChar()
            var n = 1
            while (i + n < first.length && first[i + n].lowercaseChar() == lower) n++
            if (lower == 'y') {
                sb.append(if (n >= 3) "yyyy" else "yy")
                lastWasHour = false
            } else if (lower == 'm') {
                val after = first.substring(i + n).trimStart()
                val nextIsSeconds = after.startsWith(":") && after.drop(1).trimStart().firstOrNull()?.lowercaseChar() == 's'
                if (lastWasHour || nextIsSeconds) {
                    sb.append(if (n >= 2) "mm" else "m")
                } else {
                    sb.append(
                        when (n) {
                            1 -> "M"
                            2 -> "MM"
                            3 -> "MMM"
                            else -> "MMMM"
                        }
                    )
                }
                lastWasHour = false
            } else if (lower == 'd') {
                sb.append(
                    when (n) {
                        1 -> "d"
                        2 -> "dd"
                        3 -> "EEE"
                        else -> "EEEE"
                    }
                )
                lastWasHour = false
            } else if (lower == 'h') {
                if (twelveHour) {
                    sb.append(if (n >= 2) "hh" else "h")
                } else {
                    sb.append(if (n >= 2) "HH" else "H")
                }
                lastWasHour = true
            } else if (lower == 's') {
                sb.append(if (n >= 2) "ss" else "s")
                lastWasHour = false
            } else if (ch.isLetter()) {
                for (k in 0 until n) sb.append('\'').append(ch).append('\'')
            } else {
                sb.append(ch)
                n = 1
            }
            i += n
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- rendering

    private fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.insert(0, 'A' + (n % 26))
            n /= 26
        }
        return sb.toString()
    }

    private fun renderSheet(w: PageWriter, sheet: Sheet, styles: Styles, paints: Paints) {
        var maxCol = 0
        for (row in sheet.rows) {
            for (c in row.cells) {
                if (c.col > maxCol) maxCol = c.col
            }
        }
        val colCount = (maxCol + 1).coerceAtMost(MAX_COLS)
        val widths = FloatArray(colCount)
        for (i in 0 until colCount) {
            widths[i] = sheet.colWidths[i] ?: sheet.defaultColWidth
        }
        val avail = w.pageW - 2f * w.margin
        val strips = ArrayList<IntArray>()
        var start = 0
        var acc = 0f
        for (i in 0 until colCount) {
            val cw = widths[i]
            if (i > start && acc + cw > avail) {
                strips.add(intArrayOf(start, i))
                start = i
                acc = 0f
            }
            acc += cw
        }
        strips.add(intArrayOf(start, colCount))
        for (strip in strips) {
            renderStrip(w, sheet, styles, paints, widths, strip[0], strip[1], strips.size > 1)
        }
    }

    private fun drawTitle(canvas: Canvas, w: PageWriter, paints: Paints, name: String, c0: Int, c1: Int, multi: Boolean): Float {
        var y = w.margin + 12f
        canvas.drawText(name, w.margin, y, paints.title)
        y += 10f
        if (multi) {
            canvas.drawText("Columns " + colName(c0) + " to " + colName(c1 - 1), w.margin, y + 2f, paints.sub)
            y += 8f
        }
        return y + 6f
    }

    private fun renderStrip(
        w: PageWriter,
        sheet: Sheet,
        styles: Styles,
        paints: Paints,
        widths: FloatArray,
        c0: Int,
        c1: Int,
        multi: Boolean
    ) {
        var canvas = w.newPage()
        var y = drawTitle(canvas, w, paints, sheet.name, c0, c1, multi)
        var prevRow = 0
        for (row in sheet.rows) {
            val gap = row.index - prevRow - 1
            if (gap > 0) y += gap.coerceAtMost(2) * sheet.defaultRowHeight
            prevRow = row.index
            val h = row.height ?: sheet.defaultRowHeight
            if (y + h > w.pageH - w.margin) {
                canvas = w.newPage()
                y = drawTitle(canvas, w, paints, sheet.name, c0, c1, multi)
            }
            drawRow(canvas, sheet, styles, paints, row, widths, c0, c1, w.margin, y, h)
            y += h
        }
        if (sheet.truncated) {
            if (y + 14f > w.pageH - w.margin) {
                canvas = w.newPage()
                y = w.margin + 12f
            }
            canvas.drawText("Only the first $MAX_ROWS rows are shown.", w.margin, y + 12f, paints.sub)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        sheet: Sheet,
        styles: Styles,
        paints: Paints,
        row: XRow,
        widths: FloatArray,
        c0: Int,
        c1: Int,
        left: Float,
        y: Float,
        h: Float
    ) {
        val byCol = HashMap<Int, XCell>()
        for (c in row.cells) byCol[c.col] = c

        val segs = ArrayList<Seg>()
        var col = c0
        var x = left
        while (col < c1) {
            val merge = sheet.merges[mergeKey(row.index, col)]
            var span = if (merge != null) merge[1] else 1
            if (col + span > c1) span = c1 - col
            if (span < 1) span = 1
            var segW = 0f
            for (k in col until col + span) segW += widths[k]
            if (segW > 0f) segs.add(Seg(col, span, x, segW))
            x += segW
            col += span
        }

        for (seg in segs) {
            val cell = byCol[seg.col]
            val xf = if (cell != null) styles.xfs.getOrNull(cell.style) else null
            val fill = if (xf != null) styles.fills[xf.fillId] else null
            if (fill != null) {
                paints.fill.color = fill
                canvas.drawRect(seg.x, y, seg.x + seg.w, y + h, paints.fill)
            }
            canvas.drawRect(seg.x, y, seg.x + seg.w, y + h, paints.grid)
        }

        for (idx in segs.indices) {
            val seg = segs[idx]
            val cell = byCol[seg.col] ?: continue
            if (cell.text.isEmpty()) continue
            val xf = styles.xfs.getOrNull(cell.style)
            val bold = xf != null && styles.boldFonts.contains(xf.fontId)
            val paint = if (bold) paints.bold else paints.normal
            val align = xf?.halign ?: (if (cell.numeric) "right" else "left")
            var room = seg.w
            if (align == "left" && !cell.numeric) {
                var next = idx + 1
                while (next < segs.size) {
                    val nc = byCol[segs[next].col]
                    if (nc != null && nc.text.isNotEmpty()) break
                    room += segs[next].w
                    next++
                }
            }
            val text = TextUtils.ellipsize(
                cell.text.replace('\n', ' '),
                paint,
                (room - 6f).coerceAtLeast(1f),
                TextUtils.TruncateAt.END
            ).toString()
            val tw = paint.measureText(text)
            val tx = when (align) {
                "center" -> seg.x + (seg.w - tw) / 2f
                "right" -> seg.x + seg.w - 3f - tw
                else -> seg.x + 3f
            }
            val ty = y + (h + paint.textSize) / 2f - 2f
            canvas.drawText(text, tx, ty, paint)
        }
    }
}
