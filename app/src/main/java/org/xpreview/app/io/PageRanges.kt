// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.io

/** Page-range parsing for split/extract ("1-3, 5, 8-"), 1-based in, 0-based out. */
object PageRanges {
    /** Each comma-separated group becomes one output; null if the text is invalid. */
    fun parseGroups(text: String, pageCount: Int): List<List<Int>>? {
        val groups = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (groups.isEmpty()) return null
        return groups.map { g ->
            val parts = g.split('-', '–').map { it.trim() }
            val (a, b) = when (parts.size) {
                1 -> parts[0].toIntOrNull().let { it to it }
                2 -> (parts[0].ifEmpty { "1" }.toIntOrNull()) to (parts[1].ifEmpty { pageCount.toString() }.toIntOrNull())
                else -> null to null
            }
            if (a == null || b == null || a < 1 || b > pageCount || a > b) return null
            (a - 1 until b).toList()
        }
    }

    fun everyN(n: Int, pageCount: Int): List<List<Int>> =
        if (n <= 0) emptyList() else (0 until pageCount).chunked(n)
}
