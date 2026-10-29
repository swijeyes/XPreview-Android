// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.common

/**
 * Hard limits that keep hostile or pathological files bounded.
 *
 * Mirrors the image, geometry and rendering constants of the desktop
 * src/core/Limits.h. That header is Qt-free, so the native layer includes the
 * same values (core-pdf/src/main/cpp/limits.h); LimitsParityTest keeps the
 * two from drifting. ZIP/XML/RTF limits are desktop-only.
 */
object Limits {
    // Images
    const val MAX_IMAGE_DIMENSION = 30000
    const val IMAGE_ALLOCATION_LIMIT_MB = 1024
    const val MAX_SVG_BYTES = 64L * 1024 * 1024

    /** Pixel budget for one decoded image shown on screen (Android-specific). */
    const val MAX_DISPLAY_IMAGE_PIXELS = 24L * 1000 * 1000

    // Geometry (points)
    const val MAX_PAGE_SIDE = 14400.0 * 4
    const val MIN_PAGE_SIDE = 1.0

    // Rendering
    const val MAX_RENDER_PIXELS = 64L * 1024 * 1024
    const val MAX_THUMBNAIL_ASPECT = 4

    // Input files (Android-specific): largest PDF/image we will open.
    const val MAX_INPUT_FILE_BYTES = 2L * 1024 * 1024 * 1024

    // OCR language models imported by the user.
    const val MAX_TRAINEDDATA_BYTES = 128L * 1024 * 1024

    // Scanner output, roughly A4 at 300 DPI.
    const val MAX_SCAN_SIDE = 3508
}
