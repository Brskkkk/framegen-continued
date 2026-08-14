package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Genuine native Vulkan compute interpolator - our own single-global-
 * motion-vector warp+blend algorithm (the same one GlslShaderInterpolator
 * uses via GLES), just dispatched as a Vulkan compute shader on the GPU
 * instead of a GLES fragment shader on a quad.
 *
 * This is NOT lsfg-vk/Lossless Scaling's algorithm - that comes from a
 * paid, closed-source Windows DLL whose shaders lsfg-vk extracts and
 * translates, and it's built to hook a game's DirectX/Vulkan swapchain,
 * which doesn't apply to a screen-capture app like this one. What's here
 * is a from-scratch implementation using Vulkan as the API, restricted to
 * core Vulkan 1.0 with zero instance/device extensions and zero
 * validation layers (see vk_interp.cpp) - deliberately so it doesn't
 * require any "special" Vulkan feature/extension a given device might
 * lack, only the baseline every Vulkan-capable device supports.
 *
 * Native init can fail for entirely normal reasons (no Vulkan driver on
 * this device/emulator at all, or a compute-incapable one) - that's
 * handled as a graceful, silent fallback to a plain CPU cross-fade, the
 * same contract every other GPU mode in this codebase follows.
 */
class VulkanWarpInterpolator : FrameInterpolator {

    private companion object {
        const val TAG = "VulkanWarpInterpolator"
        const val SEARCH_DOWNSCALE = 12
        const val MAX_STEP = 4
        const val MV_RANGE_PX = 96f
        const val CONFIDENCE_FLOOR = 0.15f

        private var nativeLibLoaded = false
        private var nativeLibLoadFailed = false

        private fun ensureLibLoaded(): Boolean {
            if (nativeLibLoaded) return true
            if (nativeLibLoadFailed) return false
            return try {
                System.loadLibrary("vkinterp")
                nativeLibLoaded = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "libvkinterp.so unavailable on this build/device", t)
                nativeLibLoadFailed = true
                false
            }
        }
    }

    private external fun nativeInit(): Boolean
    private external fun nativeInterpolate(
        bitmap1: Bitmap, bitmap2: Bitmap, outBitmap: Bitmap,
        mvx: Float, mvy: Float, alpha: Float
    ): Boolean
    private external fun nativeRelease()

    private var vkReady = false
    private var outputBitmap: Bitmap? = null

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        return interpolateVulkan(frame1, frame2, alpha)
    }

    private fun interpolateVulkan(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        if (!ensureLibLoaded()) {
            throw IllegalStateException("libvkinterp.so unavailable - this device/build can't run the Vulkan mode")
        }
        if (!vkReady) {
            vkReady = nativeInit()
            if (!vkReady) {
                throw IllegalStateException("nativeInit() reported no usable Vulkan compute device on this hardware")
            }
        }

        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        // Single global motion vector, same cheap one-block search the
        // GLSL Shader mode uses - the actual per-pixel warp+blend work is
        // what's on the GPU here (via Vulkan), not the search.
        val sw = max(1, w / SEARCH_DOWNSCALE)
        val sh = max(1, h / SEARCH_DOWNSCALE)
        val luma1 = MotionField.luma(frame1, sw, sh)
        val luma2 = MotionField.luma(frame2, sw, sh)
        val m = MotionField.searchWithConfidence(luma1, luma2, sw, sh, 0, 0, sw, sh, MAX_STEP)

        val mvx: Float
        val mvy: Float
        if (m.confidence < CONFIDENCE_FLOOR) {
            mvx = 0f
            mvy = 0f
        } else {
            mvx = (m.dx * SEARCH_DOWNSCALE).toFloat().coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
            mvy = (m.dy * SEARCH_DOWNSCALE).toFloat().coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
        }

        var out = outputBitmap
        if (out == null || out.width != w || out.height != h) {
            out?.recycle()
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outputBitmap = out
        }

        val success = nativeInterpolate(frame1, frame2, out, mvx, mvy, a)
        if (!success) {
            throw IllegalStateException("nativeInterpolate() failed - Vulkan compute dispatch did not complete")
        }

        // Native writes directly into `out`'s pixels - the interpolator
        // contract expects a fresh Bitmap per call (see the recycling
        // note in NearestFrameInterpolator), so hand back a cheap copy
        // rather than the reused buffer itself.
        return out.copy(out.config ?: Bitmap.Config.ARGB_8888, false)
    }

    override fun release() {
        outputBitmap?.recycle()
        outputBitmap = null
        if (vkReady) {
            try {
                nativeRelease()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeRelease() failed", t)
            }
            vkReady = false
        }
    }
}
