package com.idk.frameinterp

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * (CPU) Velocity-vector motion blur - instead of generating a sharp new
 * frame, this smears the real frame along the direction the camera is
 * moving, the same way a camera's own motion blur masks fast panning.
 *
 * One cheap whole-frame motion search (same one GLSL Shader/Global Shift
 * use) gives a single global velocity. If it's slow or unreliable, this
 * just falls back to a plain cross-fade (nothing to blur). If it's fast
 * and confident (a quick camera pan/spin), it averages a handful of
 * samples of frame1 shifted progressively along that vector, producing a
 * directional blur - there's no second frame to precisely time against,
 * so there's nothing here that can produce the vsync-mismatch stutter a
 * strict two-frame alternation can.
 */
class MotionBlurInterpolator : FrameInterpolator {

    private companion object {
        const val SEARCH_DOWNSCALE = 8
        const val MAX_STEP = 6
        const val CONFIDENCE_FLOOR = 0.3f
        const val BLUR_TRIGGER_PX = 6f
        const val SAMPLES = 3
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

        val fmx = (m.dx * SEARCH_DOWNSCALE).toFloat()
        val fmy = (m.dy * SEARCH_DOWNSCALE).toFloat()
        val mag = sqrt(fmx * fmx + fmy * fmy)

        val p1 = IntArray(w * h).also { frame1.getPixels(it, 0, w, 0, 0, w, h) }

        if (m.confidence < CONFIDENCE_FLOOR || mag < BLUR_TRIGGER_PX) {
            // Not a fast camera pan - nothing to smear, just a plain cross-fade.
            val p2 = IntArray(w * h).also { frame2.getPixels(it, 0, w, 0, 0, w, h) }
            val out = IntArray(w * h)
            for (i in p1.indices) out[i] = blend(p1[i], p2[i], a)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(out, 0, w, 0, 0, w, h)
            return result
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rs = 0; var gs = 0; var bs = 0
                for (s in 0 until SAMPLES) {
                    val t = (s.toFloat() / (SAMPLES - 1)) * a
                    val sx = (x - fmx * t).roundToInt().coerceIn(0, w - 1)
                    val sy = (y - fmy * t).roundToInt().coerceIn(0, h - 1)
                    val c = p1[sy * w + sx]
                    rs += (c ushr 16) and 0xFF
                    gs += (c ushr 8) and 0xFF
                    bs += c and 0xFF
                }
                out[y * w + x] = (0xFF shl 24) or ((rs / SAMPLES) shl 16) or ((gs / SAMPLES) shl 8) or (bs / SAMPLES)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
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
