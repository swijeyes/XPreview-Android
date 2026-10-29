// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.vision

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScanFilter { Original, Document, BlackWhite, Grayscale, Color }

/** Releases every Mat created in [block] deterministically (plan 2.7). */
internal class MatScope : AutoCloseable {
    private val mats = ArrayList<Mat>()
    fun <T : Mat> T.track(): T = also { mats += it }
    fun mat() = Mat().track()
    override fun close() = mats.forEach { it.release() }
}

internal inline fun <R> mats(block: MatScope.() -> R): R = MatScope().use(block)

object Vision {
    @Volatile private var loaded = false

    fun init() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
                loaded = true
            }
        }
    }
}

object DocumentDetector {
    /** Longest side used for detection (B.1). */
    const val DETECT_SIDE = 480

    /**
     * Detects the page in an 8-bit gray image. Returns corners normalised to
     * the image, or null. Strategy A (Canny + contours) falls back to
     * strategy B (Otsu threshold + largest blob).
     */
    fun detect(gray: Mat): Quad? = mats {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        if (w < 16 || h < 16) return@mats null
        val blurred = mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val med = median(blurred)
        val edges = mat()
        Imgproc.Canny(blurred, edges, 0.66 * med, 1.33 * med)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).track()
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        best(edges, w, h)?.let { return@mats it }

        val bin = mat()
        Imgproc.threshold(blurred, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, kernel)
        best(bin, w, h)
    }

    private fun MatScope.best(binary: Mat, w: Double, h: Double): Quad? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = mat()
        Imgproc.findContours(binary.clone().track(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        try {
            val minArea = 0.15 * w * h
            var best: Quad? = null
            var bestScore = 0.0
            for (c in contours.sortedByDescending { Imgproc.contourArea(it) }.take(12)) {
                val area = Imgproc.contourArea(c)
                if (area < minArea) break
                val quad = approxQuad(c) ?: continue
                val norm = Geometry.orderCorners(quad.map { Pt(it.x / w, it.y / h) })
                if (!Geometry.isPlausible(norm, w, h)) continue
                // Score: area dominates; penalise quads hugging the frame edge
                // (often the table or the image border, not the page).
                val edgeHug = norm.points.count { it.x < 0.01 || it.y < 0.01 || it.x > 0.99 || it.y > 0.99 }
                val score = norm.area * (1.0 - 0.1 * edgeHug)
                if (score > bestScore) { bestScore = score; best = norm }
            }
            return best
        } finally {
            contours.forEach { it.release() }
        }
    }

    private fun approxQuad(c: MatOfPoint): List<Point>? {
        val c2f = MatOfPoint2f(*c.toArray())
        val hullIdx = MatOfInt()
        val approx = MatOfPoint2f()
        try {
            val peri = Imgproc.arcLength(c2f, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.05)) {
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                if (approx.total() == 4L) return approx.toList()
            }
            // Rounded or torn corners: try the convex hull.
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            try {
                val hp = Imgproc.arcLength(hull, true)
                for (eps in doubleArrayOf(0.02, 0.04, 0.06)) {
                    Imgproc.approxPolyDP(hull, approx, eps * hp, true)
                    if (approx.total() == 4L) return approx.toList()
                }
            } finally {
                hull.release()
            }
            return null
        } finally {
            c2f.release(); hullIdx.release(); approx.release()
        }
    }

    private fun median(gray: Mat): Double {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xff]++
        var acc = 0
        val half = bytes.size / 2
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) return i.toDouble().coerceIn(20.0, 235.0)
        }
        return 128.0
    }

    /** Variance of the Laplacian inside the quad's bounding box (B.2). */
    fun sharpness(gray: Mat, quad: Quad?): Double = mats {
        val w = gray.cols(); val h = gray.rows()
        val roi = if (quad == null) gray else {
            val xs = quad.points.map { it.x * w }; val ys = quad.points.map { it.y * h }
            val x0 = xs.min().toInt().coerceIn(0, w - 1); val y0 = ys.min().toInt().coerceIn(0, h - 1)
            val x1 = xs.max().toInt().coerceIn(x0 + 1, w); val y1 = ys.max().toInt().coerceIn(y0 + 1, h)
            gray.submat(Rect(x0, y0, x1 - x0, y1 - y0)).track()
        }
        val lap = mat()
        Imgproc.Laplacian(roi, lap, CvType.CV_64F)
        val mean = MatOfDouble().track(); val std = MatOfDouble().track()
        Core.meanStdDev(lap, mean, std)
        val s = std.toArray().firstOrNull() ?: 0.0
        s * s
    }

    /** Downscales and grays a bitmap for detection, then detects. */
    fun detect(bitmap: Bitmap): Quad? = mats {
        val gray = grayForDetection(bitmap)
        detect(gray)
    }

    internal fun MatScope.grayForDetection(bitmap: Bitmap): Mat {
        val rgba = mat()
        val s = DETECT_SIDE.toDouble() / max(bitmap.width, bitmap.height)
        val small = if (s < 1) Bitmap.createScaledBitmap(
            bitmap, max(1, (bitmap.width * s).roundToInt()), max(1, (bitmap.height * s).roundToInt()), true,
        ) else bitmap
        Utils.bitmapToMat(small, rgba)
        if (small !== bitmap) small.recycle()
        val gray = mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        return gray
    }

    /**
     * Builds an upright gray Mat from a camera Y plane (respecting row stride),
     * rotated by [rotationDegrees] and downscaled to [DETECT_SIDE] (B.1 steps 1-2).
     * The caller releases the result.
     */
    fun grayFromYPlane(y: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, rotationDegrees: Int): Mat {
        val full = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (r in 0 until height) {
            y.position(r * rowStride)
            y.get(row, 0, width)
            full.put(r, 0, row)
        }
        val s = DETECT_SIDE.toDouble() / max(width, height)
        val small = Mat()
        Imgproc.resize(full, small, Size(max(1.0, width * s), max(1.0, height * s)), 0.0, 0.0, Imgproc.INTER_AREA)
        full.release()
        val code = when (rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> -1
        }
        if (code >= 0) Core.rotate(small, small, code)
        return small
    }
}

object Enhancer {
    /** Perspective-corrects [src] to the quad (plan 6.5), long side capped at [maxSide]. */
    fun warp(src: Bitmap, quad: Quad, maxSide: Int): Bitmap = mats {
        val w = src.width; val h = src.height
        val (ow, oh) = Geometry.outputSize(quad, w, h, maxSide)
        val srcMat = mat()
        Utils.bitmapToMat(src, srcMat)
        val from = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray()).track()
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(ow.toDouble(), 0.0), Point(ow.toDouble(), oh.toDouble()), Point(0.0, oh.toDouble()),
        ).track()
        val m = Imgproc.getPerspectiveTransform(from, to).track()
        val out = mat()
        Imgproc.warpPerspective(srcMat, out, m, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        toBitmap(out)
    }

    /**
     * Applies an enhancement filter (plan 6.6, B.4) plus brightness (-100..100)
     * and contrast (0.5..2.0). Returns a new bitmap.
     */
    fun filter(src: Bitmap, filter: ScanFilter, brightness: Int = 0, contrast: Float = 1f): Bitmap = mats {
        val rgba = mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val out: Mat = when (filter) {
            ScanFilter.Original -> rgb
            ScanFilter.Grayscale -> mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2GRAY) }
            ScanFilter.Document -> document(rgb)
            ScanFilter.BlackWhite -> blackWhite(rgb)
            ScanFilter.Color -> colorEnhance(rgb)
        }
        if (brightness != 0 || contrast != 1f) {
            // Contrast pivots around mid-gray so it does not also brighten.
            out.convertTo(out, -1, contrast.toDouble(), brightness * 1.28 + 128 * (1 - contrast))
        }
        toBitmap(out)
    }

    /** Shadow removal: divide by a heavily blurred background, then stretch and sharpen. */
    private fun MatScope.document(rgb: Mat): Mat {
        val channels = ArrayList<Mat>()
        Core.split(rgb, channels)
        channels.forEach { it.track() }
        for (c in channels) normalizeIllumination(c)
        val merged = mat()
        Core.merge(channels, merged)
        val blur = mat()
        Imgproc.GaussianBlur(merged, blur, Size(0.0, 0.0), 2.0)
        Core.addWeighted(merged, 1.5, blur, -0.5, 0.0, merged)
        return merged
    }

    private fun MatScope.normalizeIllumination(gray: Mat) {
        val k = max(15, (max(gray.cols(), gray.rows()) / 40) or 1)
        val bg = mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())).track()
        Imgproc.morphologyEx(gray, bg, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), k / 2.0)
        Core.divide(gray, bg, gray, 255.0)
        // Contrast stretch between the 1st and 99th percentile-ish via min/max of a blurred copy.
        val mm = Core.minMaxLoc(gray)
        if (mm.maxVal - mm.minVal > 16) {
            gray.convertTo(gray, -1, 255.0 / (mm.maxVal - mm.minVal), -mm.minVal * 255.0 / (mm.maxVal - mm.minVal))
        }
    }

    private fun MatScope.blackWhite(rgb: Mat): Mat {
        val gray = mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        normalizeIllumination(gray)
        val out = mat()
        val block = (max(31, max(gray.cols(), gray.rows()) / 60) or 1).coerceAtMost(75)
        Imgproc.adaptiveThreshold(gray, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        return out
    }

    private fun MatScope.colorEnhance(rgb: Mat): Mat {
        val lab = mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        ch.forEach { it.track() }
        val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(ch[0], ch[0])
        // Mild chroma boost around the neutral point 128.
        for (i in 1..2) ch[i].convertTo(ch[i], -1, 1.15, -128 * 0.15)
        Core.merge(ch, lab)
        val out = mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        return out
    }

    fun rotate(src: Bitmap, quarterTurns: Int): Bitmap {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(90f * q) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun toBitmap(m: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        when (m.channels()) {
            1 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_RGB2RGBA)
            else -> m.copyTo(rgba)
        }
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    @Suppress("unused") private val white = Scalar(255.0, 255.0, 255.0, 255.0)
}
