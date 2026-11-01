package com.example.engine

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Sends a PDF (or selected pages of it) to Android's print system. */
object PrintHelper {

    private class FilePrintAdapter(
        private val file: File,
        private val jobName: String,
        private val pageCount: Int
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal != null && cancellationSignal.isCanceled) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback?
        ) {
            if (destination == null) {
                callback?.onWriteFailed("No destination")
                return
            }
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }

    /**
     * [pages] null prints the whole document. When annotations are excluded or only some pages are
     * chosen, a temporary copy is prepared first.
     */
    suspend fun print(
        context: Context,
        session: ViewerSession,
        pages: List<Int>?,
        includeAnnotations: Boolean
    ) {
        val jobName = session.displayName
        val prepared: File = withContext(Dispatchers.IO) {
            if (pages == null && includeAnnotations) {
                session.file
            } else {
                PageOps.extractForPrint(context, session.file, pages, includeAnnotations)
            }
        }
        val count = pages?.size ?: session.pageCount
        withContext(Dispatchers.Main) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(jobName, FilePrintAdapter(prepared, jobName, count), null)
        }
    }
}
