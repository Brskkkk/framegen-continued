package com.idk.frameinterp

/** (CPU) Zero math, zero blend: just toggles between showing frame1 and frame2 unmodified, flipping a stateful flag each call. */
class AlternateFrameCpuInterpolator : FrameInterpolator {
    private var showFirst = true
    override fun interpolate(frame1: android.graphics.Bitmap, frame2: android.graphics.Bitmap, alpha: Float): android.graphics.Bitmap {
        val pick = if (showFirst) frame1 else frame2
        showFirst = !showFirst
        return pick.copy(pick.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
    }
    override fun release() {}
}
