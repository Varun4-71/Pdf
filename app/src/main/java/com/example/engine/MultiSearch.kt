package com.example.engine

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

sealed class MultiSearchEvent {
    class FileStarted(val index: Int, val name: String) : MultiSearchEvent()
    class Hit(val fileIndex: Int, val page: Int, val snippet: String) : MultiSearchEvent()
    class FileDone(val index: Int, val pages: Int, val hits: Int) : MultiSearchEvent()
    class FileFailed(val index: Int, val message: String) : MultiSearchEvent()
}

/** Searches the text of several PDFs one after another and reports matches as it goes. */
object MultiSearch {

    private const val MAX_HITS_PER_FILE = 80

    fun searchFiles(context: Context, files: List<Uri>, query: String): Flow<MultiSearchEvent> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        return flow {
            PdfEngine.initialize(context)
            for ((index, uri) in files.withIndex()) {
                val name = PdfEngine.getFileName(context, uri)
                emit(MultiSearchEvent.FileStarted(index, name))
                var hits = 0
                var pages = 0
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("This file could not be opened.")
                    stream.use { input ->
                        PDDocument.load(input, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L)).use { doc ->
                            pages = doc.numberOfPages
                            val stripper = PDFTextStripper()
                            stripper.setSortByPosition(true)
                            for (p in 0 until pages) {
                                if (hits >= MAX_HITS_PER_FILE) break
                                stripper.setStartPage(p + 1)
                                stripper.setEndPage(p + 1)
                                val text = stripper.getText(doc).replace(Regex("\\s+"), " ")
                                var from = 0
                                while (from < text.length && hits < MAX_HITS_PER_FILE) {
                                    val at = text.indexOf(q, from, true)
                                    if (at < 0) break
                                    val end = at + q.length
                                    val s = maxOf(0, at - 30)
                                    val e = minOf(text.length, end + 50)
                                    emit(MultiSearchEvent.Hit(index, p, text.substring(s, e).trim()))
                                    hits++
                                    from = end
                                }
                            }
                        }
                    }
                    emit(MultiSearchEvent.FileDone(index, pages, hits))
                } catch (e: Exception) {
                    val message = if (e.javaClass.simpleName.contains("Password")) {
                        "Password protected"
                    } else {
                        e.localizedMessage ?: "Could not read this file"
                    }
                    emit(MultiSearchEvent.FileFailed(index, message))
                }
            }
        }.flowOn(Dispatchers.IO)
    }
}
