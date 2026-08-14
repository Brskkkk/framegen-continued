package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * CPU-only motion-blind cross-fade - the original, basic form of this
 * mode (no GLSL/GPU, no motion search at all).
 *
 * Just draws frame1, then frame2 on top at `alpha` opacity. This is the
 * cheapest of the three basic modes since there's no luma downscale or
 * block search of any kind - and consequently the one most prone to
 * double-exposure ("ghosting") on anything that's moving between the two
 * frames. Use Block Motion or Optical Flow if that's visible and
 * objectionable; this mode is for when raw cost matters more than that.
 */
class FrameBlendInterpolator : FrameInterpolator {

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val result = Bitmap.createBitmap(frame1.width, frame1.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(frame1, 0f, 0f, null)
        val paint = Paint().apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt() }
        canvas.drawBitmap(frame2, 0f, 0f, paint)
        return result
    }

    override fun release() {
        // No GPU resources to release in the CPU-only form.
    }
}
