package com.idk.frameinterp

import android.graphics.Bitmap

/**
 * CPU-only ordered-dither temporal blend - no motion search at all (like
 * Frame Blend), but unlike Frame Blend it never does a true alpha blend
 * (a multiply+add per color channel, per pixel). Instead, each pixel is
 * picked *outright* from frame1 or frame2 based on a classic 4x4 Bayer
 * dither matrix compared against `alpha` - a single comparison and a
 * plain array copy per pixel, no floating-point math, no Canvas/Paint
 * compositing pass at all.
 *
 * The dithered pattern of frame1/frame2 pixels approximates the same
 * intermediate brightness/motion a true blend would give (this is the
 * same principle temporal dithering uses on displays to fake bit depth),
 * so it still looks like a cross-fade at normal viewing distance, just
 * computed far more cheaply than Frame Blend's Canvas/Paint alpha
 * compositing.
 */
class DitherBlendInterpolator : FrameInterpolator {

    private companion object {
        // Classic 4x4 Bayer matrix, values 0..15 - compared directly
        // against alpha*16 to decide frame1 vs frame2 per pixel.
        val BAYER_4X4 = intArrayOf(
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
        )
    }

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val threshold = (alpha.coerceIn(0f, 1f) * 16f).toInt().coerceIn(0, 16)

        val pixels1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }
        val pixels2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)

        var y = 0
        while (y < h) {
            val rowBase = y * w
            val bayerRow = (y and 3) * 4
            var x = 0
            while (x < w) {
                val bayerValue = BAYER_4X4[bayerRow + (x and 3)]
                out[rowBase + x] = if (bayerValue < threshold) pixels2[rowBase + x] else pixels1[rowBase + x]
                x++
            }
            y++
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    override fun release() {
        // Nothing to release - no GPU/search state at all.
    }
}
