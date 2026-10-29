// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.app.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegUtilsTest {
    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = seg(0xE0, "JFIF\u0000".toByteArray())
    private val exif = seg(0xE1, "Exif\u0000\u0000GPS-secret".toByteArray())
    private val icc = seg(0xE2, "ICC_PROFILE\u0000".toByteArray())
    private val sof = seg(0xC0, byteArrayOf(8, 0, 20, 0, 10, 3))
    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Test fun removesExifKeepsIcc() {
        val src = soi + jfif + exif + icc + sof + sos
        assertArrayEquals(soi + jfif + icc + sof + sos, JpegUtils.stripMetadata(src))
    }

    @Test fun rejectsNonJpeg() = assertNull(JpegUtils.stripMetadata("%PDF-1.7".toByteArray()))

    @Test fun rejectsTruncatedSegment() = assertNull(JpegUtils.stripMetadata(soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0)))

    @Test fun readsSize() = assertEquals(10 to 20, JpegUtils.size(soi + jfif + sof + sos))
}
