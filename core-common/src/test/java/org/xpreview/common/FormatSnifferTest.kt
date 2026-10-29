// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSnifferTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun pdf() = assertEquals(SniffedFormat.Pdf, FormatSniffer.sniff("%PDF-1.7\n".toByteArray()))
    @Test fun pdfAfterJunk() =
        assertEquals(SniffedFormat.Pdf, FormatSniffer.sniff(("x".repeat(500) + "%PDF-1.4").toByteArray()))
    @Test fun png() = assertEquals(SniffedFormat.Png, FormatSniffer.sniff(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
    @Test fun jpeg() = assertEquals(SniffedFormat.Jpeg, FormatSniffer.sniff(b(0xFF, 0xD8, 0xFF, 0xE0)))
    @Test fun webp() = assertEquals(SniffedFormat.WebP, FormatSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    @Test fun tiff() = assertEquals(SniffedFormat.Tiff, FormatSniffer.sniff(b(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)))
    @Test fun heic() = assertEquals(SniffedFormat.Heif, FormatSniffer.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray()))
    @Test fun svg() = assertEquals(SniffedFormat.Svg, FormatSniffer.sniff("<?xml version=\"1.0\"?><svg xmlns=".toByteArray()))
    @Test fun extensionIgnored() =
        assertEquals(SniffedFormat.Unknown, FormatSniffer.sniff("hello world, this is text".toByteArray()))
}
