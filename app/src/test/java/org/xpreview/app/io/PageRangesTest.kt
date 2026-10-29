// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.app.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangesTest {
    @Test fun groups() = assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(7, 8, 9)), PageRanges.parseGroups("1-3, 5, 8-", 10))
    @Test fun openStart() = assertEquals(listOf(listOf(0, 1)), PageRanges.parseGroups("-2", 10))
    @Test fun outOfRange() = assertNull(PageRanges.parseGroups("1-11", 10))
    @Test fun reversed() = assertNull(PageRanges.parseGroups("5-3", 10))
    @Test fun garbage() = assertNull(PageRanges.parseGroups("a-b", 10))
    @Test fun everyN() = assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PageRanges.everyN(2, 5))
}
