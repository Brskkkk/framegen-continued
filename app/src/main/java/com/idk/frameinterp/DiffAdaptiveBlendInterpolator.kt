package com.idk.frameinterp

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * (CPU) Content-adaptive blend - completely different approach from
 * every other mode in this app. There is no motion search, no block
 * matching, no warp, no grid, no dither pattern - just one pass per pixel
 * comparing how much it actually changed between the two frames:
 *
 *  - Nearly identical brightness in both frames (static content: UI,
 *    background, anything not moving) -> a normal alpha cross-fade,
 *    which is perfectly smooth and artifact-free since nothing moved.
 *  - A big brightness difference (something moved through that pixel,
 *    a flash, a cut) -> show the single nearest real frame instead of
 *    blending, since blending two very different values there would
 *    just be ghosting - the same failure mode every warp-based mode is
 *    trying to avoid by moving pixels around, except this mode avoids
 *    it by simply declining to blend there at all.
 *
 * The tradeoff: fast-moving content doesn't get smoothed (it snaps
 * between real frames like Frame Blend/Nearest Frame would), but nothing
 * ever warps or ghosts, and there's no search loop of any kind - just one
 * subtraction and one comparison per pixel.
 */
class DiffAdaptiveBlendInterpolator : FrameInterpolator {

    private companion object {
        // Luma difference (0-255 scale) above which a pixel is treated as
        // "changed" and blending is skipped in favor of showing one frame.
        const val CHANGE_THRESHOLD = 24
    }

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)

        for (i in p1.indices) {
            val c1 = p1[i]
            val c2 = p2[i]

            val luma1 = lumaOf(c1)
            val luma2 = lumaOf(c2)

            out[i] = if (abs(luma1 - luma2) > CHANGE_THRESHOLD) {
                // Changed a lot here - don't blend, just show the nearer
                // real frame so this pixel never ghosts.
                if (a < 0.5f) c1 else c2
            } else {
                blend(c1, c2, a)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun lumaOf(c: Int): Int {
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 150 + b * 29) ushr 8
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

    override fun release() {
        // No GPU/search state to release.
    }
}
