package com.example.engine

/** Parses user-typed page selections such as "1-3, 5, 8-" or "2-last" into 0-based indices. */
object PageRanges {

    /**
     * Keeps the order the user typed and drops duplicates.
     * Throws IllegalArgumentException with a readable message when the text is not understood.
     */
    fun parse(text: String, total: Int): List<Int> {
        if (total <= 0) return emptyList()
        if (text.isBlank()) return (0 until total).toList()
        val result = LinkedHashSet<Int>()
        for (raw in text.split(',', ';')) {
            val part = raw.replace(" ", "").lowercase()
            if (part.isEmpty()) continue
            if (part.contains('-')) {
                val startText = part.substringBefore('-')
                val endText = part.substringAfter('-')
                val start = toPage(startText, total, 1)
                val end = toPage(endText, total, total)
                if (start == null || end == null) {
                    throw IllegalArgumentException("\"$raw\" is not a valid page range.")
                }
                if (start > total && end > total) {
                    throw IllegalArgumentException("Pages $start-$end are outside this document (1-$total).")
                }
                val from = start.coerceIn(1, total)
                val to = end.coerceIn(1, total)
                if (from <= to) {
                    for (p in from..to) result.add(p - 1)
                } else {
                    for (p in from downTo to) result.add(p - 1)
                }
            } else {
                val page = toPage(part, total, 1)
                    ?: throw IllegalArgumentException("\"$raw\" is not a valid page number.")
                if (page < 1 || page > total) {
                    throw IllegalArgumentException("Page $page is not in this document (1-$total).")
                }
                result.add(page - 1)
            }
        }
        if (result.isEmpty()) {
            throw IllegalArgumentException("Enter at least one page.")
        }
        return result.toList()
    }

    /** Same as [parse] but returns an empty list instead of throwing. */
    fun parseOrEmpty(text: String, total: Int): List<Int> {
        return try {
            parse(text, total)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Splits a typed list such as "1-3, 4-6, 7-" into separate groups (one output file each). */
    fun parseGroups(text: String, total: Int): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        for (raw in text.split(',', ';')) {
            if (raw.isBlank()) continue
            groups.add(parse(raw, total))
        }
        if (groups.isEmpty()) throw IllegalArgumentException("Enter at least one range.")
        return groups
    }

    private fun toPage(text: String, total: Int, ifEmpty: Int): Int? {
        if (text.isEmpty()) return ifEmpty
        if (text == "end" || text == "last") return total
        if (text == "first") return 1
        return text.toIntOrNull()
    }

    /** "1-3, 5" style summary for status messages. */
    fun describe(indices: List<Int>): String {
        if (indices.isEmpty()) return "none"
        val sorted = indices.sorted()
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur == prev + 1) {
                prev = cur
            } else {
                parts.add(if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}")
                start = cur
                prev = cur
            }
        }
        parts.add(if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}")
        return parts.joinToString(", ")
    }
}
