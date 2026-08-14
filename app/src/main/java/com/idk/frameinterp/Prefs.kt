package com.idk.frameinterp

/** Shared SharedPreferences keys so MainActivity's pickers and the service agree on names. */
object Prefs {
    const val NAME = "frameinterp_prefs"
    const val KEY_LAST_TARGET_PACKAGE = "last_target_package"

    const val KEY_INTERP_MODE = "interp_mode"
    const val MODE_BLOCK_MOTION = "block_motion"
    const val MODE_FRAME_BLEND = "frame_blend"
    const val MODE_OPTICAL_FLOW = "optical_flow"
    const val MODE_GLSL_SHADER = "glsl_shader"
    const val MODE_LITE_BLEND = "lite_blend"
    const val MODE_GRID_WARP = "grid_warp"
    const val MODE_NEAREST_FRAME = "nearest_frame"
    const val MODE_GLOBAL_SHIFT = "global_shift"
    const val MODE_DITHER_BLEND = "dither_blend"
    const val MODE_DOWNSCALED_BLEND = "downscaled_blend"
    const val MODE_VULKAN_WARP = "vulkan_warp"
    const val MODE_HALF_BLEND_CPU = "half_blend_cpu"
    const val MODE_HALF_BLEND_GPU = "half_blend_gpu"
    const val MODE_ALTERNATE_FRAME_CPU = "alternate_frame_cpu"
    const val MODE_ALTERNATE_FRAME_GPU = "alternate_frame_gpu"
    const val MODE_CHECKERBOARD_CPU = "checkerboard_cpu"
    const val MODE_CHECKERBOARD_GPU = "checkerboard_gpu"
    const val MODE_QUARTER_RES_CPU = "quarter_res_cpu"
    const val MODE_QUARTER_RES_GPU = "quarter_res_gpu"
    const val MODE_SCANLINE_CPU = "scanline_cpu"
    const val MODE_SCANLINE_GPU = "scanline_gpu"
    const val MODE_DIFF_ADAPTIVE = "diff_adaptive"
    const val MODE_EDGE_WARP = "edge_warp"
    const val MODE_MOTION_BLUR = "motion_blur"
    const val DEFAULT_MODE = MODE_BLOCK_MOTION

    // Overlay display strategy - separate from which interpolation
    // ALGORITHM is used above. This controls HOW/WHEN computed frames get
    // painted to the screen.
    const val KEY_OVERLAY_MODE = "overlay_mode"
    const val OVERLAY_MODE_ALTERNATING = "alternating"
    const val OVERLAY_MODE_ASYNC_REPROJECTION = "async_reprojection"
    const val DEFAULT_OVERLAY_MODE = OVERLAY_MODE_ALTERNATING

    /**
     * Capture resolution picker, back by popular demand. Stored as the
     * target *long-side* pixel count (portrait height / landscape width) -
     * see ScreenMetrics.getCaptureSpec(). Every stage of the pipeline
     * (luma search grid, full-res texture upload, GPU warp, glReadPixels
     * readback) scales with capture size, so this is the main lever for
     * "generated frames can't keep up" on devices where native resolution
     * is too much work per frame - much bigger effect than anything else
     * tunable in the interpolators.
     */
    const val KEY_CAPTURE_LONG_SIDE = "capture_long_side"

    // Label to target long-side px. "Native" isn't in the list on purpose -
    // getCaptureSpec() already clamps any of these to the device's real
    // resolution if it's smaller, so picking 1080p on a 1080p (or lower)
    // device is effectively native with zero extra code path.
    val CAPTURE_RESOLUTIONS: List<Pair<String, Int>> = listOf(
        "1080p" to 1080,
        "900p" to 900,
        "720p" to 720,
        "540p" to 540,
        "480p" to 480,
        "360p" to 360,
        "240p" to 240,
        "180p" to 180
    )

    // Defaulting to 720p rather than the max: most "generated frames lag
    // behind real frames" complaints are a native-1080p+-capture problem,
    // so a lighter-than-max default actually keeps up out of the box. Users
    // on strong hardware can bump this back up to 1080p/900p from the picker.
    const val DEFAULT_CAPTURE_LONG_SIDE = 720
}
