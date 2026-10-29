// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xpreview.vision.Pt
import org.xpreview.vision.Quad

class LiveTrackerTest {
    private val page = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))

    private fun LiveTracker.feed(q: Quad?, n: Int, sharp: Double = 200.0, t0: Long = 0): List<LiveState> =
        (0 until n).map { update(q, sharp, 128.0, 0.75f, autoCapture = true, now = t0 + it * 100L) }

    @Test fun capturesOnceWhenSteadyAndSharp() {
        val t = LiveTracker()
        val states = t.feed(page, 20)
        assertEquals(1, states.count { it.captureNow })
        assertEquals(Hint.Captured, states.last().hint)
    }

    @Test fun noCaptureWhenBlurry() {
        val t = LiveTracker()
        val states = t.feed(page, 20, sharp = 5.0)
        assertFalse(states.any { it.captureNow })
        assertEquals(Hint.Blurry, states.last().hint)
    }

    @Test fun rearmsAfterDocumentLeaves() {
        val t = LiveTracker()
        t.feed(page, 20)
        t.feed(null, 4, t0 = 2000)
        val again = t.feed(page, 20, t0 = 3000)
        assertTrue(again.any { it.captureNow })
    }

    @Test fun singleDroppedFrameIgnored() {
        val t = LiveTracker()
        t.feed(page, 3)
        val s = t.update(null, 200.0, 128.0, 0.75f, autoCapture = false, now = 400)
        assertTrue(s.quad != null)
    }

    @Test fun smallPageAsksToMoveCloser() {
        val small = Quad(Pt(0.4, 0.4), Pt(0.6, 0.4), Pt(0.6, 0.6), Pt(0.4, 0.6))
        assertEquals(Hint.MoveCloser, LiveTracker().feed(small, 10).last().hint)
    }
}
