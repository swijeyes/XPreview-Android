// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.io

/**
 * Removes metadata segments (EXIF incl. GPS location, XMP, IPTC, comments)
 * from a JPEG without touching the compressed image data (plan 13.1). ICC
 * profiles (APP2) and the Adobe colour-transform marker (APP14) are kept,
 * since dropping them would change the colours. Returns null if the data is
 * not a well-formed JPEG header, so callers can fall back to re-encoding.
 */
object JpegUtils {
    fun stripMetadata(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || u(jpeg, 0) != 0xFF || u(jpeg, 1) != 0xD8) return null
        val out = java.io.ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (u(jpeg, i) != 0xFF) return null
            val marker = u(jpeg, i + 1)
            if (marker == 0xFF) { i++; continue }  // fill byte
            if (marker == 0xDA) {  // start of scan: the rest is image data
                out.write(jpeg, i, jpeg.size - i)
                return out.toByteArray()
            }
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) return null
            val len = (u(jpeg, i + 2) shl 8) or u(jpeg, i + 3)
            if (len < 2 || i + 2 + len > jpeg.size) return null
            val drop = marker == 0xE1 || marker == 0xED || marker == 0xFE || marker in 0xE3..0xEC || marker == 0xEF
            if (!drop) out.write(jpeg, i, 2 + len)
            i += 2 + len
        }
        return null
    }

    /** Pixel size from the SOF marker, or null. */
    fun size(jpeg: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < jpeg.size) {
            if (u(jpeg, i) != 0xFF) return null
            val m = u(jpeg, i + 1)
            if (m == 0xFF) { i++; continue }
            val len = (u(jpeg, i + 2) shl 8) or u(jpeg, i + 3)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = (u(jpeg, i + 5) shl 8) or u(jpeg, i + 6)
                val w = (u(jpeg, i + 7) shl 8) or u(jpeg, i + 8)
                return if (w > 0 && h > 0) w to h else null
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff
}
