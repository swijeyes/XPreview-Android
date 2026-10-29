// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The native layer's limits.h and this object must agree (plan 2.3). */
class LimitsParityTest {
    @Test fun nativeHeaderMatches() {
        val header = File("../core-pdf/src/main/cpp/limits.h").readText()
        fun value(name: String): String =
            Regex("""$name\s*=\s*([^;]+);""").find(header)?.groupValues?.get(1)?.trim()
                ?: error("$name missing from limits.h")
        assertEquals(Limits.MAX_IMAGE_DIMENSION.toString(), value("kMaxImageDimension"))
        assertEquals("64ll * 1024 * 1024", value("kMaxRenderPixels"))
        assertEquals(Limits.MAX_RENDER_PIXELS, 64L * 1024 * 1024)
        assertEquals("14400.0 * 4", value("kMaxPageSide"))
        assertEquals(Limits.MAX_PAGE_SIDE, 14400.0 * 4, 0.0)
    }
}
