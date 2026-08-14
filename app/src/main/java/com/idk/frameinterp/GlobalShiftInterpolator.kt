package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CPU-only single global motion vector, whole-frame shift+blend.
 *
 * Runs exactly ONE forward/backward consistency-checked search
 * (MotionField.searchWithConfidence, treating the entire downscaled frame
 * as one block) - not a grid of searches like Block Motion, not a dense
 * per-pixel field like Optical Flow. That makes it the cheapest CPU mode
 * that still does *any* motion compensation at all: one search, one
 * Canvas.drawBitmap shift, one alpha blend.
 *
 * Best suited to content that pans/scrolls as a whole (e.g. a scrolling
 * feed or a side-scrolling game) rather than content with multiple things
 * moving independently, since there's only one vector for the whole
 * frame - independently-moving elements will still ghost like Frame
 * Blend would. Falls back to showing a single unwarped frame if the one
 * global search has low confidence (occlusion/chaotic scene).
 */
class GlobalShiftInterpolator : FrameInterpolator {

    private companion object {
        const val SEARCH_DOWNSCALE = 8
        const val MAX_STEP = 6
        const val MV_RANGE_PX = 96
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

        val m = MotionField.searchWithConfidence(luma1, luma2, sw, sh, 0, 0, sw, sh, MAX_STEP)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val full = Rect(0, 0, w, h)

        if (m.confidence < CONFIDENCE_FLOOR) {
            val nearest = if (a < 0.5f) frame1 else frame2
            canvas.drawBitmap(nearest, full, full, null)
            return result
        }

        val fmx = (m.dx * SEARCH_DOWNSCALE).coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
        val fmy = (m.dy * SEARCH_DOWNSCALE).coerceIn(-MV_RANGE_PX, MV_RANGE_PX)

        val src1 = Rect(full).apply { offset((-fmx * a).roundToInt(), (-fmy * a).roundToInt()) }
        val src2 = Rect(full).apply { offset((fmx * (1f - a)).roundToInt(), (fmy * (1f - a)).roundToInt()) }
        clampRectTo(src1, w, h)
        clampRectTo(src2, w, h)

        val blendPaint = Paint().apply { isFilterBitmap = true; this.alpha = (a * 255).roundToInt() }
        canvas.drawBitmap(frame1, src1, full, null)
        canvas.drawBitmap(frame2, src2, full, blendPaint)
        return result
    }

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
