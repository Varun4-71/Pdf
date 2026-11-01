package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes several result files into a folder the person picked (Storage Access Framework). */
object FolderSaver {

    private fun mimeFor(name: String): String {
        return when (FileUtils.extensionOf(name)) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** Returns how many files were saved. */
    suspend fun saveAll(context: Context, treeUri: Uri, files: List<OutputFile>): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            var saved = 0
            for (item in files) {
                val target = DocumentsContract.createDocument(resolver, parent, mimeFor(item.name), item.name)
                if (target != null) {
                    resolver.openOutputStream(target)?.use { out ->
                        item.file.inputStream().use { input -> input.copyTo(out) }
                    }
                    saved++
                }
            }
            saved
        }
}
