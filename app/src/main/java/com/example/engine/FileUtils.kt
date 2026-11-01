package com.example.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Small file helpers shared by every engine. All output lives in cache/pdf_toolkit (already FileProvider-shared). */
object FileUtils {

    fun workDir(context: Context): File {
        val dir = File(context.cacheDir, "pdf_toolkit")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseNameOf(name: String): String {
        val base = name.substringBeforeLast('.', name)
        return if (base.isBlank()) "document" else base
    }

    /** Removes only characters that are illegal in file names, so non-Latin names survive. */
    fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        return if (cleaned.isBlank()) "document" else cleaned
    }

    fun newFile(context: Context, base: String, ext: String): File {
        val dir = workDir(context)
        val safe = safeName(base).take(60)
        val stamp = System.currentTimeMillis()
        var file = File(dir, "${safe}_$stamp.$ext")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${safe}_${stamp}_$counter.$ext")
            counter++
        }
        return file
    }

    fun newDir(context: Context, base: String): File {
        val dir = File(workDir(context), "${safeName(base).take(40)}_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    /** Copies any content/file Uri into the cache so renderers and PDFBox get a seekable local file. */
    fun copyToCache(context: Context, uri: Uri, ext: String, prefix: String = "in"): File {
        val out = newFile(context, prefix, ext)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file.")
        input.use { source ->
            FileOutputStream(out).use { sink -> source.copyTo(sink) }
        }
        return out
    }

    /** Deletes cached work files older than [maxAgeMs]. Called once at app start. */
    fun pruneCache(context: Context, maxAgeMs: Long) {
        try {
            val now = System.currentTimeMillis()
            val children = workDir(context).listFiles() ?: return
            for (child in children) {
                if (now - child.lastModified() > maxAgeMs) {
                    child.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            // Cache cleanup is best effort only.
        }
    }
}

/** A finished file plus the name we suggest when the user saves it. */
data class OutputFile(val file: File, val name: String)
