// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCodeTest {
    @Test fun valid() {
        listOf("eng", "fra", "chi_sim", "deu_latf", "Latin").forEach { assertTrue(it, LanguageManager.isValidCode(it)) }
    }

    @Test fun invalid() {
        listOf("", "en", "../eng", "eng.traineddata", "eng/../x", "ENG", "e ng").forEach {
            assertFalse(it, LanguageManager.isValidCode(it))
        }
    }
}
