package com.idk.frameinterp

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared cheap block-motion search, used by all three GPU interpolators:
 * BlockMotionInterpolator and OpticalFlowWarpInterpolator run it per-block
 * over a grid to build a full motion field (at different block sizes),
 * FrameBlendInterpolator runs it once over the whole frame to get a single
 * global vector. Pulled out into one place so the search itself doesn't
 * fork into two-or-three copies that quietly drift apart.
 *
 * This is the same core algorithm the original all-CPU BlockMotionInterpolator
 * used end-to-end: downscale to grayscale luma, three-step search per
 * block. What happens *after* the vectors are found is in
 * BlockMotionInterpolator/FrameBlendInterpolator/OpticalFlowWarpInterpolator.
 *
 * ## Occlusion detection (the actual fix for ghosting)
 *
 * "Ghosting" in a motion-warped cross-fade isn't caused by inaccurate
 * vectors alone - it's caused by *blending two frames when there is nothing
 * correct to blend*. When content appears or disappears between frame1 and
 * frame2 (occlusion/disocclusion - something moved in front of / away from
 * something else), there is no vector that correctly explains that pixel in
 * both source frames, because the "true" source pixel only exists in one of
 * them. Blending anyway - even with a perfect vector everywhere else -
 * produces the classic double-exposure smear.
 *
 * [searchWithConfidence] detects this with a standard forward/backward
 * consistency check: search frame1->frame2 to get a forward vector, then
 * independently search frame2->frame1 at the same block to get a backward
 * vector. For genuine rigid motion the two searches describe the same
 * physical displacement, so they should be (near) exact opposites of each
 * other. Where they agree, confidence is high and it's safe to warp+blend.
 * Where they disagree (occlusion, disocclusion, a bad match on flat/
 * repetitive texture), confidence drops - and the interpolators use that to
 * fall back to showing a single real, unwarped frame for that region
 * instead of cross-fading mismatched content. A single real frame can never
 * ghost; only a bad blend can. That's what makes this a structural fix
 * rather than a search-accuracy tweak.
 */
object MotionField {

    /** Downscales to (sw x sh) and converts to a flat single-channel luma buffer. */
    fun luma(bitmap: Bitmap, sw: Int, sh: Int): IntArray {
        val scaled = if (bitmap.width == sw && bitmap.height == sh) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        }
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (scaled !== bitmap) scaled.recycle()

        val out = IntArray(sw * sh)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    /**
     * Classic three-step search: try the 8 neighbors of the current best
     * offset at a large step, keep whichever improves the match, halve the
     * step, repeat. ~27 block comparisons instead of an exhaustive search's
     * (2*maxStep+1)^2, which is what keeps this usable on low-end CPUs.
     */
    fun threeStepSearch(
        l1: IntArray, l2: IntArray, w: Int, h: Int,
        bx: Int, by: Int, blockW: Int, blockH: Int, maxStep: Int
    ): Pair<Int, Int> {
        var cx = 0
        var cy = 0
        var bestCost = sad(l1, l2, w, h, bx, by, blockW, blockH, cx, cy)
        var step = maxStep

        while (step >= 1) {
            var bestDx = cx
            var bestDy = cy
            var improved = false
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val ndx = cx + dx * step
                    val ndy = cy + dy * step
                    val cost = sad(l1, l2, w, h, bx, by, blockW, blockH, ndx, ndy)
                    if (cost < bestCost) {
                        bestCost = cost
                        bestDx = ndx
                        bestDy = ndy
                        improved = true
                    }
                }
            }
            cx = bestDx
            cy = bestDy
            if (!improved && step == 1) break
            step /= 2
        }
        return cx to cy
    }

    /**
     * Result of a confidence-checked search: [dx],[dy] is the forward
     * (l1->l2) motion vector, and [confidence] is 1.0 when the independent
     * backward (l2->l1) search at the same block agrees with it (safe to
     * warp+blend) down to 0.0 when they disagree entirely (likely
     * occlusion/disocclusion - callers should fall back to a single
     * unwarped source frame rather than blend).
     */
    data class Motion(val dx: Int, val dy: Int, val confidence: Float)

    /**
     * Forward/backward consistency search - see the occlusion-detection
     * doc on [MotionField]. [consistencyTolerance] is the max forward+
     * backward vector-sum magnitude (in downscaled px) that still counts as
     * fully consistent; confidence falls off linearly to 0 by 3x that.
     */
    fun searchWithConfidence(
        l1: IntArray, l2: IntArray, w: Int, h: Int,
        bx: Int, by: Int, blockW: Int, blockH: Int, maxStep: Int,
        consistencyTolerance: Float = 1.5f
    ): Motion {
        val (fdx, fdy) = threeStepSearch(l1, l2, w, h, bx, by, blockW, blockH, maxStep)
        // Backward search re-centered on where the forward vector says the
        // content ended up, so it's checking the same physical patch rather
        // than a block that may have moved out from under it.
        val backBx = (bx + fdx).coerceIn(0, max(0, w - blockW))
        val backBy = (by + fdy).coerceIn(0, max(0, h - blockH))
        val (bdx, bdy) = threeStepSearch(l2, l1, w, h, backBx, backBy, blockW, blockH, maxStep)

        val errX = fdx + bdx
        val errY = fdy + bdy
        val err = kotlin.math.sqrt((errX * errX + errY * errY).toFloat())
        val confidence = (1f - (err - consistencyTolerance) / (2f * consistencyTolerance)).coerceIn(0f, 1f)

        return Motion(fdx, fdy, confidence)
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b

    /**
     * Confidence-weighted 3x3 smoothing pass over a (gw x gh) motion grid -
     * used only by OpticalFlowWarpInterpolator, which has a dense enough
     * grid for single-block search noise (a block picking a slightly wrong
     * vector on flat/repetitive texture) to be visible as a small
     * discontinuity even with bilinear sampling. Neighbors are weighted by
     * their own confidence, so a low-confidence (likely occluded) neighbor
     * doesn't drag a good vector towards a bad one - this regularizes the
     * field without smearing real motion boundaries. Confidence itself is
     * left untouched (each block keeps its own, unaveraged): a genuinely
     * occluded block must stay flagged as occluded even after its vector
     * is smoothed, so the occlusion fallback in the shader still kicks in
     * for it.
     */
    fun smoothGrid(grid: Array<Motion>, gw: Int, gh: Int): Array<Motion> {
        val out = arrayOfNulls<Motion>(grid.size)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val center = grid[gy * gw + gx]
                // Own vector counts double, so a confident block still
                // mostly trusts its own search result rather than being
                // averaged away by neighbors.
                var sumW = 0.05f + center.confidence * 2f
                var sumX = center.dx * sumW
                var sumY = center.dy * sumW
                for (ny in -1..1) {
                    for (nx in -1..1) {
                        if (nx == 0 && ny == 0) continue
                        val sx = gx + nx
                        val sy = gy + ny
                        if (sx < 0 || sx >= gw || sy < 0 || sy >= gh) continue
                        val m = grid[sy * gw + sx]
                        val w = 0.05f + m.confidence
                        sumW += w
                        sumX += m.dx * w
                        sumY += m.dy * w
                    }
                }
                // Confidence itself is NOT smoothed/averaged - a genuinely
                // occluded block must stay flagged as low-confidence even
                // next to confident neighbors, so the shader's occlusion
                // fallback still applies there.
                out[gy * gw + gx] = Motion((sumX / sumW).roundToInt(), (sumY / sumW).roundToInt(), center.confidence)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Motion>
    }

    /** Sum of absolute differences between an l1 block at (bx,by) and an l2 block offset by (dx,dy). */
    fun sad(
        l1: IntArray, l2: IntArray, w: Int, h: Int,
        bx: Int, by: Int, blockW: Int, blockH: Int, dx: Int, dy: Int
    ): Int {
        var sum = 0
        var y = 0
        while (y < blockH) {
            val sy = by + y
            if (sy >= h) { y++; continue }
            val ty = (sy + dy).coerceIn(0, h - 1)
            val rowBase1 = sy * w
            val rowBase2 = ty * w
            var x = 0
            while (x < blockW) {
                val sx = bx + x
                if (sx < w) {
                    val tx = (sx + dx).coerceIn(0, w - 1)
                    sum += abs(l1[rowBase1 + sx] - l2[rowBase2 + tx])
                }
                x++
            }
            y++
        }
        return sum
    }
}
