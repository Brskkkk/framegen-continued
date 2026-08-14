package com.idk.frameinterp

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU-only dense optical-flow-style warp - the original, basic form of
 * this mode (no GLSL/GPU involved anywhere in it). The highest-quality of
 * the three CPU modes, at the highest CPU cost.
 *
 * 1. Same forward/backward-consistency-checked block search as
 *    BlockMotionInterpolator (MotionField.searchWithConfidence), but over
 *    a denser grid (smaller blocks, less-downscaled luma), then
 *    MotionField.smoothGrid() regularizes it - identical setup to before.
 * 2. Unlike BlockMotionInterpolator's hard-edged per-block blit, this
 *    samples the motion grid *per output pixel* with manual bilinear
 *    interpolation between the 4 nearest grid cells (see [sampleGrid]),
 *    which is what a GPU's GL_LINEAR texture sampling was doing for free -
 *    done by hand here since there's no GPU involved. That removes the
 *    block-seam artifact Block Motion has at fast motion boundaries.
 * 3. Color sampling itself uses nearest-neighbor (not bilinear) to keep
 *    the per-pixel CPU cost from doubling again - a reasonable trade-off
 *    since the motion *field* is already smooth; only individual source
 *    pixels are picked without sub-pixel blending.
 *
 * This is real per-pixel work in a Kotlin loop (no SIMD/GPU), so it is
 * meaningfully heavier than the other two CPU modes - that's the intended
 * trade-off: quality over speed.
 */
class OpticalFlowWarpInterpolator : FrameInterpolator {

    private companion object {
        const val SEARCH_DOWNSCALE = 4
        const val BLOCK_DS = 6
        const val MAX_STEP = 5
        const val MV_RANGE_PX = 64f
        const val CONFIDENCE_FLOOR = 0.15f
    }

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        val sw = max(1, w / SEARCH_DOWNSCALE)
        val sh = max(1, h / SEARCH_DOWNSCALE)
        val luma1 = MotionField.luma(frame1, sw, sh)
        val luma2 = MotionField.luma(frame2, sw, sh)

        val gw = (sw + BLOCK_DS - 1) / BLOCK_DS
        val gh = (sh + BLOCK_DS - 1) / BLOCK_DS

        val raw = arrayOfNulls<MotionField.Motion>(gw * gh)
        var by = 0
        var gy = 0
        while (by < sh) {
            val blockH = min(BLOCK_DS, sh - by)
            var bx = 0
            var gx = 0
            while (bx < sw) {
                val blockW = min(BLOCK_DS, sw - bx)
                raw[gy * gw + gx] = MotionField.searchWithConfidence(luma1, luma2, sw, sh, bx, by, blockW, blockH, MAX_STEP)
                bx += BLOCK_DS
                gx++
            }
            by += BLOCK_DS
            gy++
        }
        @Suppress("UNCHECKED_CAST")
        val grid = MotionField.smoothGrid(raw as Array<MotionField.Motion>, gw, gh)

        val pixels1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val pixels2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)

        // Full-res size of one grid cell, used to map an output pixel to
        // its (fractional) grid coordinate for bilinear interpolation.
        val cellPx = (BLOCK_DS * SEARCH_DOWNSCALE).toFloat().coerceAtLeast(1f)

        var y = 0
        while (y < h) {
            val gyF = (y / cellPx - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            var x = 0
            while (x < w) {
                val gxF = (x / cellPx - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val (mvx, mvy, confidence) = sampleGrid(grid, gw, gh, gxF, gyF)

                val fmx = mvx.coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
                val fmy = mvy.coerceIn(-MV_RANGE_PX, MV_RANGE_PX)

                val x1 = (x - fmx * a).roundToInt().coerceIn(0, w - 1)
                val y1 = (y - fmy * a).roundToInt().coerceIn(0, h - 1)
                val x2 = (x + fmx * (1f - a)).roundToInt().coerceIn(0, w - 1)
                val y2 = (y + fmy * (1f - a)).roundToInt().coerceIn(0, h - 1)

                val warped = blendArgb(pixels1[y1 * w + x1], pixels2[y2 * w + x2], a)
                val nearest = if (a < 0.5f) pixels1[y * w + x] else pixels2[y * w + x]

                out[y * w + x] = blendArgb(nearest, warped, confidence.coerceIn(0f, 1f))
                x++
            }
            y++
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Bilinearly interpolates (dx, dy, confidence) between the 4 grid cells nearest (gxF, gyF). */
    private fun sampleGrid(grid: Array<MotionField.Motion>, gw: Int, gh: Int, gxF: Float, gyF: Float): Triple<Float, Float, Float> {
        val x0 = gxF.toInt().coerceIn(0, gw - 1)
        val y0 = gyF.toInt().coerceIn(0, gh - 1)
        val x1 = (x0 + 1).coerceIn(0, gw - 1)
        val y1 = (y0 + 1).coerceIn(0, gh - 1)
        val tx = gxF - x0
        val ty = gyF - y0

        val m00 = grid[y0 * gw + x0]
        val m10 = grid[y0 * gw + x1]
        val m01 = grid[y1 * gw + x0]
        val m11 = grid[y1 * gw + x1]

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        val dxTop = lerp(m00.dx.toFloat(), m10.dx.toFloat(), tx)
        val dxBot = lerp(m01.dx.toFloat(), m11.dx.toFloat(), tx)
        val dx = lerp(dxTop, dxBot, ty) * SEARCH_DOWNSCALE

        val dyTop = lerp(m00.dy.toFloat(), m10.dy.toFloat(), tx)
        val dyBot = lerp(m01.dy.toFloat(), m11.dy.toFloat(), tx)
        val dy = lerp(dyTop, dyBot, ty) * SEARCH_DOWNSCALE

        val cTop = lerp(m00.confidence, m10.confidence, tx)
        val cBot = lerp(m01.confidence, m11.confidence, tx)
        val confidence = lerp(cTop, cBot, ty)

        return Triple(dx, dy, confidence)
    }

    private fun blendArgb(c1: Int, c2: Int, t: Float): Int {
        val a1 = (c1 ushr 24) and 0xFF; val r1 = (c1 ushr 16) and 0xFF; val g1 = (c1 ushr 8) and 0xFF; val b1 = c1 and 0xFF
        val a2 = (c2 ushr 24) and 0xFF; val r2 = (c2 ushr 16) and 0xFF; val g2 = (c2 ushr 8) and 0xFF; val b2 = c2 and 0xFF
        val a = (a1 + (a2 - a1) * t).roundToInt().coerceIn(0, 255)
        val r = (r1 + (r2 - r1) * t).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * t).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun release() {
        // No GPU resources to release in the CPU-only form.
    }
}
