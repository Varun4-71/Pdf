package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout

/**
 * Writes content top-to-bottom onto fixed-size PDF pages, starting a new page whenever the
 * current one is full. Used by the Word and text converters.
 */
internal class PdfFlow(
    val doc: PdfDocument,
    val pageW: Int,
    val pageH: Int,
    val marginL: Float,
    val marginT: Float,
    val marginR: Float,
    val marginB: Float
) {
    private var page: PdfDocument.Page? = null
    private var currentCanvas: Canvas? = null
    private var pages = 0
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Vertical cursor in page points. */
    var y: Float = 0f

    val contentWidth: Float
        get() = pageW - marginL - marginR

    val contentHeight: Float
        get() = pageH - marginT - marginB

    val pageCount: Int
        get() = pages

    fun canvas(): Canvas {
        ensurePage()
        return currentCanvas ?: throw IllegalStateException("No active page")
    }

    fun newPage() {
        val old = page
        if (old != null) doc.finishPage(old)
        pages++
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pages).create()
        val fresh = doc.startPage(info)
        page = fresh
        currentCanvas = fresh.canvas
        y = marginT
    }

    fun ensurePage() {
        if (page == null) newPage()
    }

    fun atTop(): Boolean = page == null || y <= marginT + 0.5f

    fun ensureSpace(height: Float) {
        ensurePage()
        if (y + height > pageH - marginB && y > marginT + 0.5f) newPage()
    }

    fun finish() {
        val old = page
        if (old != null) doc.finishPage(old)
        page = null
        currentCanvas = null
    }

    /** Draws a laid-out paragraph, splitting it across pages line by line when needed. */
    fun drawLayout(layout: StaticLayout, x: Float) {
        var line = 0
        val total = layout.lineCount
        while (line < total) {
            ensurePage()
            val startTop = layout.getLineTop(line)
            val available = pageH - marginB - y
            var end = line
            while (end < total && layout.getLineBottom(end) - startTop <= available) {
                end++
            }
            if (end == line) {
                if (!atTop()) {
                    newPage()
                    continue
                }
                end = line + 1
            }
            val c = currentCanvas ?: return
            c.save()
            c.translate(x, y - startTop)
            c.clipRect(-2f, startTop.toFloat(), layout.width + 2f, layout.getLineBottom(end - 1).toFloat())
            layout.draw(c)
            c.restore()
            y += (layout.getLineBottom(end - 1) - startTop).toFloat()
            line = end
            if (line < total) newPage()
        }
    }

    fun drawBitmap(bitmap: Bitmap, w: Float, h: Float, x: Float) {
        ensureSpace(h)
        val c = currentCanvas ?: return
        c.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), bitmapPaint)
        y += h + 4f
    }
}
