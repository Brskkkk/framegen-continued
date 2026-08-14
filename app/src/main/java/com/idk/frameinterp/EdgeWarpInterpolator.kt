package com.idk.frameinterp

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * (CPU) Edge-aware interpolation - a cheap 1-pass Sobel edge-detection
 * over the downscaled luma decides, per region, whether to blend or just
 * repeat the frame:
 *
 *  - Near an edge (where motion/jaggedness is most visible to the eye) ->
 *    a normal alpha cross-fade there.
 *  - Flat, textureless regions (sky, road, walls - most of a typical
 *    scene) -> skip blending entirely and just repeat frame1, since there
 *    is nothing there for the eye to notice moving.
 *
 * The Sobel pass runs on a small downscaled grid (not full resolution),
 * so the "extra" cost over a plain cross-fade is one cheap low-res
 * gradient pass, not a full-resolution edge detector.
 */
class EdgeWarpInterpolator : FrameInterpolator {

    private companion object {
        const val EDGE_DOWNSCALE = 6
        const val EDGE_THRESHOLD = 40
    }

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        val ew = max(1, w / EDGE_DOWNSCALE)
        val eh = max(1, h / EDGE_DOWNSCALE)
        val luma = MotionField.luma(frame1, ew, eh)

        val edge = IntArray(ew * eh)
        for (y in 0 until eh) {
            for (x in 0 until ew) {
                val gx = sobel(luma, ew, eh, x, y, horizontal = true)
                val gy = sobel(luma, ew, eh, x, y, horizontal = false)
                edge[y * ew + x] = abs(gx) + abs(gy)
            }
        }

        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)

        for (y in 0 until h) {
            val ey = (y / EDGE_DOWNSCALE).coerceAtMost(eh - 1)
            val rowBase = y * w
            val edgeRowBase = ey * ew
            for (x in 0 until w) {
                val ex = (x / EDGE_DOWNSCALE).coerceAtMost(ew - 1)
                val i = rowBase + x
                out[i] = if (edge[edgeRowBase + ex] > EDGE_THRESHOLD) blend(p1[i], p2[i], a) else p1[i]
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun sobel(l: IntArray, w: Int, h: Int, x: Int, y: Int, horizontal: Boolean): Int {
        fun px(xx: Int, yy: Int) = l[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
        return if (horizontal) {
            (px(x + 1, y - 1) + 2 * px(x + 1, y) + px(x + 1, y + 1)) -
                (px(x - 1, y - 1) + 2 * px(x - 1, y) + px(x - 1, y + 1))
        } else {
            (px(x - 1, y + 1) + 2 * px(x, y + 1) + px(x + 1, y + 1)) -
                (px(x - 1, y - 1) + 2 * px(x, y - 1) + px(x + 1, y - 1))
        }
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val a1 = (c1 ushr 24) and 0xFF; val r1 = (c1 ushr 16) and 0xFF; val g1 = (c1 ushr 8) and 0xFF; val b1 = c1 and 0xFF
        val a2 = (c2 ushr 24) and 0xFF; val r2 = (c2 ushr 16) and 0xFF; val g2 = (c2 ushr 8) and 0xFF; val b2 = c2 and 0xFF
        val a = (a1 + (a2 - a1) * t).toInt().coerceIn(0, 255)
        val r = (r1 + (r2 - r1) * t).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * t).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * t).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun release() {}
}
