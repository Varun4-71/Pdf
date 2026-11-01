package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Random-access view of a .docx / .xlsx / .pptx file (they are all zip packages). */
internal class OoxmlPackage(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun open(path: String): InputStream? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry)
    }

    fun has(path: String): Boolean = zip.getEntry(path) != null

    fun bytes(path: String): ByteArray? {
        val stream = open(path) ?: return null
        return stream.use { it.readBytes() }
    }

    override fun close() {
        zip.close()
    }
}

/** Helpers shared by the Word, Excel and PowerPoint readers. */
internal object Ooxml {

    fun parser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        return p
    }

    fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    fun intAttr(p: XmlPullParser, name: String): Int? = attr(p, name)?.trim()?.toIntOrNull()

    fun longAttr(p: XmlPullParser, name: String): Long? = attr(p, name)?.trim()?.toLongOrNull()

    fun floatAttr(p: XmlPullParser, name: String): Float? = attr(p, name)?.trim()?.toFloatOrNull()

    /** Reads on/off style toggles such as <w:b/> or <w:b w:val="0"/>. */
    fun onOff(p: XmlPullParser): Boolean {
        val v = attr(p, "w:val") ?: return true
        return !(v == "0" || v.equals("false", true) || v.equals("off", true) || v.equals("none", true))
    }

    /** Call while positioned on a START_TAG: moves to the matching END_TAG. */
    fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            val ev = p.next()
            if (ev == XmlPullParser.START_TAG) {
                depth++
            } else if (ev == XmlPullParser.END_TAG) {
                depth--
            } else if (ev == XmlPullParser.END_DOCUMENT) {
                return
            }
        }
    }

    /** id -> target path (relative to the part that owns the .rels file). External links are ignored. */
    fun relationships(pkg: OoxmlPackage, relsPath: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val stream = pkg.open(relsPath) ?: return map
        stream.use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = attr(p, "Id")
                    val target = attr(p, "Target")
                    val mode = attr(p, "TargetMode")
                    if (id != null && target != null && mode != "External") {
                        map[id] = target
                    }
                }
                ev = p.next()
            }
        }
        return map
    }

    /** Resolves a relationship target against the part that referenced it. */
    fun resolve(basePart: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = ArrayList<String>()
        val baseDir = basePart.substringBeforeLast('/', "")
        for (seg in baseDir.split('/')) {
            if (seg.isNotEmpty()) parts.add(seg)
        }
        for (seg in target.split('/')) {
            if (seg.isEmpty() || seg == ".") {
                continue
            } else if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            } else {
                parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Decodes an embedded picture, sub-sampling very large images so a phone does not run out of memory. */
    fun decodeBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun emuToPt(emu: Long): Float = emu / 12700f

    /** "RRGGBB" (or "AARRGGBB") -> opaque ARGB int. */
    fun hexColor(hex: String?): Int? {
        if (hex == null) return null
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        return try {
            val rgb = if (h.length == 8) h.substring(2) else h
            0xFF000000.toInt() or rgb.toInt(16)
        } catch (e: NumberFormatException) {
            null
        }
    }
}
