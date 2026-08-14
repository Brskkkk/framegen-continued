package com.idk.frameinterp

import android.graphics.Bitmap

/**
 * (CPU) The cheapest real blend there is: a fixed 50/50 average done with
 * a classic bit-trick instead of Canvas/Paint or floating-point math -
 * `(c1>>1 & 0x7f7f7f7f) + (c2>>1 & 0x7f7f7f7f)` averages all 4 ARGB
 * channels of two packed pixels in about 3 integer ops, no per-channel
 * unpacking at all. No motion search, no allocation beyond the output
 * array. Ignores `alpha` (always exactly halfway) - that's the trade for
 * being this cheap.
 */
class HalfBlendCpuInterpolator : FrameInterpolator {
    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in p1.indices) {
            p1[i] = ((p1[i] ushr 1) and 0x7F7F7F7F) + ((p2[i] ushr 1) and 0x7F7F7F7F)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(p1, 0, w, 0, 0, w, h)
        return result
    }
    override fun release() {}
}
