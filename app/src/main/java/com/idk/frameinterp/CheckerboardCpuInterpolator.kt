package com.idk.frameinterp

import android.graphics.Bitmap

/**
 * (CPU) A 2x2 checkerboard pixel pick instead of a true blend - even
 * cheaper than the existing 4x4 Dither Blend mode since the pattern check
 * is just `(x+y) & 1` (a single bitwise AND) rather than a 4x4 matrix
 * lookup. Approximates a 50/50 blend visually at normal viewing distance.
 */
class CheckerboardCpuInterpolator : FrameInterpolator {
    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        var y = 0
        while (y < h) {
            var x = 0
            val rowParity = y and 1
            while (x < w) {
                val i = y * w + x
                out[i] = if (((x and 1) xor rowParity) == 0) p1[i] else p2[i]
                x++
            }
            y++
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
    override fun release() {}
}
