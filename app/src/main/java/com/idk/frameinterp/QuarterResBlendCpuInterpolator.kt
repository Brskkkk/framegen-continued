package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * (CPU) Downscales both frames to 1/4 resolution (1/16th the pixels),
 * blends there, then scales the small result back up for display. All
 * the actual per-pixel work happens on a much smaller array - the
 * quality trade is blur, the payoff is a big cut in CPU work on weak
 * devices.
 */
class QuarterResBlendCpuInterpolator : FrameInterpolator {
    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val qw = max(1, w / 4)
        val qh = max(1, h / 4)

        val small1 = Bitmap.createScaledBitmap(frame1, qw, qh, true)
        val small2 = Bitmap.createScaledBitmap(frame2, qw, qh, true)
        val smallResult = Bitmap.createBitmap(qw, qh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(smallResult)
        canvas.drawBitmap(small1, 0f, 0f, null)
        val paint = Paint().apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt() }
        canvas.drawBitmap(small2, 0f, 0f, paint)
        small1.recycle()
        small2.recycle()

        val result = Bitmap.createScaledBitmap(smallResult, w, h, true)
        smallResult.recycle()
        return result
    }
    override fun release() {}
}
