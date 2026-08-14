package com.idk.frameinterp

import android.graphics.Bitmap

/**
 * (CPU) Alternates whole scanlines between frame1 and frame2 instead of
 * blending every pixel - even-numbered rows come from frame1, odd rows
 * from frame2 (or vice versa each call, so it doesn't always favor one
 * frame). Each row is a single array copy (System.arraycopy), no
 * per-pixel math at all - touches every pixel exactly once, but never
 * computes anything for it.
 */
class ScanlineBlendCpuInterpolator : FrameInterpolator {
    private var flip = false

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)

        val firstIsEven = flip
        flip = !flip

        var y = 0
        while (y < h) {
            val fromFirst = ((y and 1) == 0) == firstIsEven
            val src = if (fromFirst) p1 else p2
            System.arraycopy(src, y * w, out, y * w, w)
            y++
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
    override fun release() {}
}
