package com.idk.frameinterp

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

data class ScreenSpec(val width: Int, val height: Int, val densityDpi: Int)

object ScreenMetrics {

    /**
     * The *real* full physical display size, including status bar / nav bar /
     * cutouts - not the app window's inset-adjusted size. Using
     * resources.displayMetrics here is what caused the overlay to be smaller
     * than the actual screen (OEM skins like MIUI report a smaller "usable"
     * area from that API).
     */
    fun getReal(context: Context): ScreenSpec {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            ScreenSpec(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            ScreenSpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    /**
     * The spec to actually capture at: the real screen size, 1:1, evenified.
     * Kept around for callers that genuinely want native (none currently do,
     * now that the resolution picker is back - see getCaptureSpec(context, targetLongSide)
     * below), and as the "no downscale requested" building block that
     * function is written on top of.
     */
    fun getCaptureSpec(context: Context): ScreenSpec {
        val real = getReal(context)
        return ScreenSpec(evenify(real.width), evenify(real.height), real.densityDpi)
    }

    /**
     * The spec to capture at when the user has picked a target resolution
     * from the picker (Prefs.KEY_CAPTURE_LONG_SIDE / Prefs.CAPTURE_RESOLUTIONS).
     *
     * [targetLongSide] is the desired size of the screen's *long* side
     * (portrait height on almost every phone, landscape width after a
     * rotation) - e.g. 720 for "720p". The short side is scaled by the same
     * factor to preserve the device's real aspect ratio, so there's no
     * stretching - just a smaller frame that gets upscaled by the overlay's
     * FIT_XY ImageView back to fullscreen.
     *
     * Never upscales: if the device's native long side is already smaller
     * than [targetLongSide] (e.g. a 720p device with 1080p selected), this
     * clamps to native instead, so picking a high preset on a low-res device
     * is a harmless no-op rather than a manufactured upscale.
     *
     * This is the main lever for keeping generated frames caught up with
     * real ones: every downstream cost (CPU motion search's luma grid, the
     * two full-res GPU texture uploads, the GLSL warp itself, and the
     * glReadPixels readback in GlOffscreenPipeline) scales with capture
     * width*height, so e.g. dropping from a 1440-long-side device down to a
     * 720p capture is a 4x reduction in *every one* of those, not just one
     * stage of the pipeline.
     */
    fun getCaptureSpec(context: Context, targetLongSide: Int): ScreenSpec {
        val real = getReal(context)
        val realLong = maxOf(real.width, real.height)
        val realShort = minOf(real.width, real.height)

        val clampedLong = targetLongSide.coerceIn(1, realLong)
        val scale = clampedLong.toFloat() / realLong.toFloat()
        val scaledShort = maxOf(1, (realShort * scale).roundToInt())

        val (w, h) = if (real.width >= real.height) {
            clampedLong to scaledShort
        } else {
            scaledShort to clampedLong
        }
        return ScreenSpec(evenify(w), evenify(h), real.densityDpi)
    }

    private fun evenify(v: Int): Int = if (v % 2 == 0) v else v + 1
}
