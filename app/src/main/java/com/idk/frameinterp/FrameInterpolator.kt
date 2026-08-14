package com.idk.frameinterp

import android.graphics.Bitmap

interface FrameInterpolator {
    /** Interpolate a frame between frame1 and frame2. alpha: 0=frame1, 1=frame2. */
    fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float = 0.5f): Bitmap
    fun release()
}
