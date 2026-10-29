// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.common

enum class SniffedFormat { Unknown, Pdf, Svg, Png, Jpeg, Gif, WebP, Bmp, Heif, Tiff, Odf, Rtf }

val SniffedFormat.isRasterImage: Boolean
    get() = this in setOf(
        SniffedFormat.Png, SniffedFormat.Jpeg, SniffedFormat.Gif,
        SniffedFormat.WebP, SniffedFormat.Bmp, SniffedFormat.Heif,
    )

val SniffedFormat.isImage: Boolean get() = isRasterImage || this == SniffedFormat.Svg

/**
 * Content-based format detection, as on desktop: the extension and MIME type
 * supplied by the sender are never trusted.
 */
object FormatSniffer {
    const val HEAD_BYTES = 4096

    fun sniff(head: ByteArray): SniffedFormat {
        fun at(off: Int, vararg b: Int) =
            head.size >= off + b.size && b.indices.all { head[off + it].toInt() and 0xff == b[it] }
        fun ascii(off: Int, s: String) = at(off, *s.map { it.code }.toIntArray())

        // PDF header may be preceded by junk; desktop looks in the first 1 KB.
        if (indexOf(head, "%PDF-".toByteArray(), 1024) >= 0) return SniffedFormat.Pdf
        if (at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return SniffedFormat.Png
        if (at(0, 0xFF, 0xD8, 0xFF)) return SniffedFormat.Jpeg
        if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return SniffedFormat.Gif
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return SniffedFormat.WebP
        if (ascii(0, "BM") && head.size >= 26) return SniffedFormat.Bmp
        if (at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A)) return SniffedFormat.Tiff
        if (ascii(4, "ftyp")) {
            val brand = String(head, 8, 4.coerceAtMost(head.size - 8), Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif"))
                return SniffedFormat.Heif
        }
        var start = 0
        if (at(0, 0xEF, 0xBB, 0xBF)) start = 3
        if (ascii(start, "{\\rtf")) return SniffedFormat.Rtf
        if (ascii(0, "PK\u0003\u0004") && ascii(30, "mimetype")) return SniffedFormat.Odf

        val text = String(head, Charsets.ISO_8859_1)
        if (text.contains("<svg") &&
            (text.startsWith("<?xml") || text.trimStart().startsWith("<svg") || text.contains("<!DOCTYPE svg"))
        ) return SniffedFormat.Svg
        return SniffedFormat.Unknown
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, limit: Int): Int {
        val end = minOf(hay.size, limit) - needle.size
        outer@ for (i in 0..end) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
