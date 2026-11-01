package com.example.engine

import android.content.Context
import android.net.Uri

data class RecentFile(val uri: String, val name: String, val openedAt: Long)

/** The last few documents opened in the viewer. */
object RecentFiles {
    private const val PREFS = "pdfkit_recent"
    private const val KEY = "items"
    private const val MAX_ITEMS = 12

    fun list(context: Context): List<RecentFile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val out = ArrayList<RecentFile>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size >= 3) {
                out.add(RecentFile(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L))
            }
        }
        return out
    }

    fun add(context: Context, uri: Uri, name: String) {
        val clean = name.replace('\t', ' ').replace('\n', ' ')
        val key = uri.toString()
        val items = ArrayList<RecentFile>()
        items.add(RecentFile(key, clean, System.currentTimeMillis()))
        for (existing in list(context)) {
            if (existing.uri != key) items.add(existing)
        }
        save(context, items.take(MAX_ITEMS))
    }

    fun remove(context: Context, uri: String) {
        save(context, list(context).filter { it.uri != uri })
    }

    private fun save(context: Context, items: List<RecentFile>) {
        val sb = StringBuilder()
        for (item in items) {
            sb.append(item.uri).append('\t').append(item.name).append('\t').append(item.openedAt).append('\n')
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply()
    }
}

/** User bookmarks (page numbers) remembered per document. */
object BookmarkStore {
    private const val PREFS = "pdfkit_bookmarks"

    private fun keyFor(docKey: String): String = "b_" + Integer.toHexString(docKey.hashCode())

    fun get(context: Context, docKey: String): List<Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(keyFor(docKey), "") ?: ""
        val pages = ArrayList<Int>()
        for (part in raw.split(',')) {
            val n = part.trim().toIntOrNull()
            if (n != null) pages.add(n)
        }
        pages.sort()
        return pages
    }

    /** Returns true when the page is now bookmarked. */
    fun toggle(context: Context, docKey: String, page: Int): Boolean {
        val pages = ArrayList(get(context, docKey))
        val nowOn: Boolean
        if (pages.contains(page)) {
            pages.remove(page)
            nowOn = false
        } else {
            pages.add(page)
            nowOn = true
        }
        pages.sort()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFor(docKey), pages.joinToString(","))
            .apply()
        return nowOn
    }
}

/** Hand-over between "open this PDF" actions (home, other apps, search results) and the viewer. */
object ViewerLaunch {
    var uri: Uri? = null
    var startPage: Int = 0
    var query: String? = null

    fun open(target: Uri, page: Int = 0, searchFor: String? = null) {
        uri = target
        startPage = page
        query = searchFor
    }
}
