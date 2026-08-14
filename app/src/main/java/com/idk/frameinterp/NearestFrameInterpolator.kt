package com.idk.frameinterp

import android.graphics.Bitmap

/**
 * The lightest possible mode, full stop: no motion search, no blend math,
 * no GPU - just a plain copy of whichever real frame (frame1 or frame2)
 * is temporally nearest to `alpha`.
 *
 * This is the classic "frame duplication" the other docs in this codebase
 * warn is the *bad* default - it's offered here as an explicit, clearly-
 * labeled opt-in for when raw CPU/battery cost matters more than
 * perceived smoothness (e.g. thermal-throttled sessions), not as a
 * silent fallback anywhere else.
 *
 * Must return a genuine copy rather than frame1/frame2 by reference:
 * FrameInterpService recycles `previous` right after this call returns,
 * and returning the same Bitmap object back out would hand back an
 * about-to-be-recycled instance.
 */
class NearestFrameInterpolator : FrameInterpolator {

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val nearest = if (alpha.coerceIn(0f, 1f) < 0.5f) frame1 else frame2
        return nearest.copy(nearest.config ?: Bitmap.Config.ARGB_8888, false)
    }

    override fun release() {
        // Nothing to release - no GPU/search state at all.
    }
}
