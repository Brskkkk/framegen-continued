package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU-only block motion compensation - the original, basic form of this
 * mode (no GLSL/GPU involved anywhere in it).
 *
 * Downscales both frames to grayscale luma, runs a cheap forward/backward
 * consistency-checked block search per block (MotionField.searchWithConfidence,
 * shared with the other modes), then for each block either:
 *  - draws a single unwarped real frame's block (occlusion / low
 *    confidence - nothing correct to blend), or
 *  - draws frame1's block shifted by -motion*alpha, then frame2's block
 *    shifted by +motion*(1-alpha) on top at `alpha` opacity, via plain
 *    Canvas.drawBitmap src/dst Rects.
 *
 * Because this is a hard-edged per-block blit (not a per-pixel-sampled
 * shader), neighboring blocks that pick different vectors can show a
 * visible seam at fast motion boundaries - that's the known trade-off of
 * the basic/CPU form vs. a GPU-sampled continuous field (see the new
 * GLSL Shader mode for that trade-off in the other direction).
 */
class BlockMotionInterpolator : FrameInterpolator {

    private companion object {
        const val SEARCH_DOWNSCALE = 6
        const val BLOCK_DS = 10
        const val MAX_STEP = 4
        const val MV_RANGE_PX = 64
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

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val blendPaint = Paint().apply { isFilterBitmap = true; this.alpha = (a * 255).roundToInt() }

        var by = 0
        while (by < sh) {
            val blockH = min(BLOCK_DS, sh - by)
            var bx = 0
            while (bx < sw) {
                val blockW = min(BLOCK_DS, sw - bx)
                val m = MotionField.searchWithConfidence(luma1, luma2, sw, sh, bx, by, blockW, blockH, MAX_STEP)

                val dst = Rect(
                    bx * SEARCH_DOWNSCALE,
                    by * SEARCH_DOWNSCALE,
                    min(w, (bx + blockW) * SEARCH_DOWNSCALE),
                    min(h, (by + blockH) * SEARCH_DOWNSCALE)
                )

                if (m.confidence < CONFIDENCE_FLOOR || dst.isEmpty) {
                    // Occlusion fallback: nothing correct to blend for this
                    // block, so show a single unwarped real frame instead -
                    // that can never ghost, only a bad blend can.
                    val nearest = if (a < 0.5f) frame1 else frame2
                    canvas.drawBitmap(nearest, dst, dst, null)
                } else {
                    val fmx = (m.dx * SEARCH_DOWNSCALE).coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
                    val fmy = (m.dy * SEARCH_DOWNSCALE).coerceIn(-MV_RANGE_PX, MV_RANGE_PX)

                    val src1 = Rect(dst).apply { offset((-fmx * a).roundToInt(), (-fmy * a).roundToInt()) }
                    val src2 = Rect(dst).apply { offset((fmx * (1f - a)).roundToInt(), (fmy * (1f - a)).roundToInt()) }
                    clampRectTo(src1, w, h)
                    clampRectTo(src2, w, h)

                    canvas.drawBitmap(frame1, src1, dst, null)
                    canvas.drawBitmap(frame2, src2, dst, blendPaint)
                }

                bx += BLOCK_DS
            }
            by += BLOCK_DS
        }

        return result
    }

    /** Translates [rect] (preserving its size) so it stays fully within [0,w) x [0,h). */
    private fun clampRectTo(rect: Rect, w: Int, h: Int) {
        if (rect.left < 0) rect.offset(-rect.left, 0)
        if (rect.top < 0) rect.offset(0, -rect.top)
        if (rect.right > w) rect.offset(w - rect.right, 0)
        if (rect.bottom > h) rect.offset(0, h - rect.bottom)
    }

    override fun release() {
        // No GPU resources to release in the CPU-only form.
    }
}
