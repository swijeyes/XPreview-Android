// SPDX-License-Identifier: AGPL-3.0-or-later
package org.xpreview.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val square = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))

    @Test fun orderCornersFromShuffled() {
        val q = Geometry.orderCorners(listOf(square.br, square.tl, square.bl, square.tr))
        assertEquals(square, q)
    }

    @Test fun orderCornersTiltedPage() {
        val tl = Pt(0.3, 0.1); val tr = Pt(0.9, 0.3); val br = Pt(0.7, 0.9); val bl = Pt(0.1, 0.7)
        assertEquals(Quad(tl, tr, br, bl), Geometry.orderCorners(listOf(br, bl, tr, tl)))
    }

    @Test fun area() = assertEquals(0.64, square.area, 1e-9)

    @Test fun plausibility() {
        assertTrue(Geometry.isPlausible(square, 480.0, 640.0))
        val sliver = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.12), Pt(0.1, 0.12))
        assertFalse(Geometry.isPlausible(sliver, 480.0, 640.0))
        val bowtie = Quad(Pt(0.1, 0.1), Pt(0.9, 0.9), Pt(0.9, 0.1), Pt(0.1, 0.9))
        assertFalse(Geometry.isConvex(bowtie))
    }

    @Test fun outputSizeCapped() {
        val (w, h) = Geometry.outputSize(Quad.full(0.0), 4000, 3000, 3508)
        assertEquals(3508, w)
        assertEquals(2631, h)
    }

    @Test fun paperSnap() {
        assertEquals(Geometry.PaperSize.A4, Geometry.snapPaper(2480, 3508))
        assertEquals(Geometry.PaperSize.Letter, Geometry.snapPaper(2550, 3300))
        assertNull(Geometry.snapPaper(1000, 1000))
    }

    @Test fun rotateKeepsOrder() {
        val r = Geometry.rotate(square, 1)
        assertEquals(4, r.points.size)
        assertEquals(square.area, r.area, 1e-9)
        assertEquals(Geometry.orderCorners(r.points), r)
    }

    @Test fun cornerShift() {
        val moved = square.mapPoints { Pt(it.x + 0.01, it.y) }
        assertEquals(0.01 / Math.sqrt(2.0), Geometry.maxCornerShift(square, moved), 1e-9)
    }
}
