# Frame Interpolation - GPU Warp Edition

**Screen frame interpolation for Android, built entirely on the standard Android SDK** — `MediaProjection` for capture, `GLES2`/`EGL14` for compositing. No OpenCV, no custom native library, no per-ABI `.so` files, no risk of an ABI-mismatch crash on an unusual/low-end chipset (like Unisoc's T606, where the earlier OpenCV/LSFG-style native builds don't run). GLES2 itself is standard on essentially every Android device since ~2010, so this still runs anywhere Android does.

## How it works

1. **MediaProjection** captures screen frames at the device's own native resolution — see "Capture resolution" below.
2. **CPU: cheap motion search with occlusion detection** — each frame is downscaled to a small grayscale luma grid; a three-step search finds motion vectors between the two most recent real frames, and an independent *backward* search checks whether that vector is trustworthy (see `MotionField`). This part stays on the CPU on purpose — it's already cheap (a handful of small block comparisons) and isn't a good fit for the GPU.
3. **GPU: GLSL warp + occlusion-aware blend** — the vectors and per-block confidence are uploaded as a small RGBA texture, frame1/frame2 as full-res textures, and a fragment shader does the per-pixel motion-compensated cross-fade where confidence is high, or shows a single unwarped real frame where it's low (see `GlOffscreenPipeline`, `BlockMotionInterpolator`, `FrameBlendInterpolator`, `OpticalFlowWarpInterpolator`).
4. **Overlay compositing** displays interpolated + real frames full-screen, with a live FPS HUD.

Three interpolation modes, picked from the app:

- **Block Motion (GLSL warp) – balanced** — full per-block motion grid from step 2, sampled with `GL_LINEAR` filtering in the shader so the vector field is smooth *between* grid cells instead of stepped.
- **Optical Flow (GLSL warp) – sharpest** — the new mode. Same forward/backward-consistency search as Block Motion, but over roughly 3x as many, smaller blocks (a finer, less-downscaled search grid), plus a confidence-weighted smoothing pass over the resulting motion field before it's uploaded (`MotionField.smoothGrid`) to cancel out single-block search noise on flat/repetitive texture. Highest quality of the three, at roughly 2-3x Block Motion's CPU cost.
- **Frame Blend (GLSL warp) – fastest** — one single global motion vector (one cheap search over the whole frame, not a grid) instead of full per-block search, then GPU cross-fade shifted by that vector, with the same forward/backward confidence check applied globally. Meaningfully less ghosting than a blind cross-dissolve on the common case (panning/scrolling), while staying close to free.

All three fall back to the original motion-blind CPU `Canvas` cross-fade if GLES2 init fails for any reason, so a device without a working GLES2 context still shows a picture instead of crashing.

## Ghosting: what causes it, and how this eliminates it

"Ghosting" — the double-exposure smear on a generated frame — isn't primarily a motion-*accuracy* problem. It happens whenever two frames get cross-faded in a region where there is nothing correct to blend: content that appears or disappears between frame1 and frame2 (occlusion/disocclusion, a UI element popping in, something moving in front of something else) has no vector, however well-searched, that correctly explains it in *both* source frames.

All three modes now detect this directly with a **forward/backward consistency check** (`MotionField.searchWithConfidence`): search frame1→frame2 for a forward vector, then independently search frame2→frame1 at the same block for a backward vector. For genuine rigid motion, the two are (near) exact opposites; where they disagree, that's the signature of occlusion, and confidence drops. The GLSL shader in every mode uses that confidence to blend between:

- **high confidence** → the same motion-compensated warp+cross-fade as before, and
- **low confidence** → the single nearer *unwarped* real frame, with no blend at all.

A single real frame can't double-expose against itself — only a blend can. That's the structural difference from the previous version, which always blended regardless of whether the vectors made sense: this version only ever blends where it's actually safe to, and shows real, sharp, single-source content everywhere else. It's an honest trade-off, not a trick: occluded regions no longer *smoothly interpolate*, they briefly show the nearest real frame outright — but that's strictly better than a wrong blend, and it's visually much closer to "no interpolation happened here" than to "ghosting happened here."

Optical Flow's extra grid density and field smoothing further shrinks how much of the frame ever needs the fallback, since a denser, regularized field can correctly explain more genuinely-moving content instead of miscategorizing it as occlusion.

## Capture resolution: picker is back, 180p–1080p

`ScreenMetrics.getReal()` still reads the device's real physical screen size (via `WindowManager.maximumWindowMetrics` on API 30+, `getRealMetrics` below that — the *real* bounds, not the app window's inset-adjusted size), but capture no longer has to happen at that full size. `ScreenMetrics.getCaptureSpec(context, targetLongSide)` scales the real size down so its long side (portrait height on almost every phone) matches the picker's choice, keeping the device's real aspect ratio, then rounds to the nearest even pixel for `ImageReader`/encoder compatibility.

The picker (`Prefs.CAPTURE_RESOLUTIONS`, shown in `MainActivity`) offers 1080p / 900p / 720p / 540p / 480p / 360p / 240p / 180p, defaulting to **720p**. Picking a preset larger than the device's native resolution is a no-op — `getCaptureSpec` clamps to native rather than upscaling.

Why this exists: every stage of the pipeline scales with capture width×height — the CPU luma search grid, the two full-res GPU texture uploads, the GLSL warp itself, and the `glReadPixels` readback in `GlOffscreenPipeline`. Capturing at a device's native 1080p/1440p resolution can make that whole per-frame round-trip too slow to keep up with real capture, so generated frames fall further and further behind (showing up as a low generated-FPS count on the frame graph HUD while real FPS stays smooth). Dropping the capture resolution is by far the biggest lever for that — a 1440p→720p drop alone is a 4x reduction in every one of those costs, not just one stage — which is why it defaults to 720p rather than to native. The overlay's `ImageView` still uses `FIT_XY` to stretch back to fullscreen, so a lower capture resolution costs some sharpness (more so at the low end of the range) in exchange for the interpolator being able to keep pace; the three interpolation *modes* (Frame Blend / Block Motion / Optical Flow) remain an independent, smaller speed/quality lever on top of whatever resolution is picked.

## Performance

- No native code to load, no ABI to mismatch — GLES2/EGL are core Android platform APIs, not a bundled library.
- Motion search runs on a downscaled luma grid with a cheap approximate search, not full-resolution pixels — this is what keeps it usable on low-end CPUs. Every mode now searches twice per block (forward + backward) for the occlusion confidence check, roughly doubling search cost versus the old forward-only version in exchange for structurally eliminating blend-based ghosting.
- The GPU side is one persistent `EGL`/GLES2 context reused for the whole session (`GlOffscreenPipeline`) — context creation only happens once, not per frame — with rendering into a same-size FBO+texture and a `glReadPixels` readback, not a display-attached surface.
- Capture resolution (180p–1080p, default 720p — see above) is now the single biggest lever over per-frame cost, since it scales the CPU search, both GPU texture uploads, the warp shader, and the `glReadPixels` readback all at once. Interpolation *mode* is a secondary lever on top of that: **Frame Blend** is the cheap option for devices that still can't keep up with a full grid search even at a low resolution, **Block Motion** is the balanced default, and **Optical Flow** is the highest-quality option for devices with headroom to spare.

## What to expect

- ✅ Motion-aware smoothing (not just duplication or plain cross-dissolve) in Block Motion and Optical Flow modes
- ✅ Cheap global motion compensation even in Frame Blend mode — no longer fully motion-blind
- ✅ GPU-side warp/blend removes block-edge seams/tearing (bilinear-filtered motion field)
- ✅ **Occlusion-aware blending in all three modes** — forward/backward consistency checking detects regions where blending would ghost, and falls back to a single unwarped real frame instead, structurally eliminating double-exposure ghosting rather than just reducing it
- ✅ **Optical Flow mode**: denser motion grid + confidence-weighted field smoothing, for the sharpest/most accurate warp of the three
- ✅ **Capture resolution picker (180p–1080p, default 720p)** — the app still fits whatever screen it's running on automatically (aspect ratio always preserved, never upscaled past native), but capture size is now a user-adjustable trade-off between sharpness and keeping generated frames caught up with real ones
- ✅ Works on effectively any Android device — no OpenCV/native dependency, and GLES2 is near-universal
- ✅ Smaller APK than an OpenCV build (no bundled per-ABI native libraries)
- ✅ Live frame graph HUD (real vs generated vs total FPS, scrolling timeline)
- ❌ Block-granularity *search*, not per-pixel — motion vectors are still found per block, only the sampling of them (and, in Optical Flow, the field itself) is smooth; won't handle complex non-rigid motion as cleanly as dense per-pixel optical flow from a neural model
- ❌ Approximate search, not exhaustive — occasional wrong-vector blocks on very fast or repetitive-texture motion (the confidence check catches most of these before they reach the blend, but not all)
- ❌ Occlusion handling means occluded regions momentarily show a real frame rather than an interpolated one, not that they're perfectly motion-smoothed too — there is still no true hole-filling/inpainting of disoccluded content, since that needs information that genuinely isn't in either source frame
- ❌ Frame Blend's global vector only helps uniform motion (panning/scrolling); independently-moving elements against a static background still fall back to the single-frame path more often than the grid-based modes would
- ❌ Still has a real CPU cost for motion search (now roughly double, from the forward+backward check), and drives the GPU every frame — battery impact from both
- ❌ Lower capture resolutions upscale visibly softer on the overlay (`FIT_XY` stretches a smaller frame back to fullscreen) — 1080p/900p stay close to native sharpness, but 240p/180p are a deliberately blurry, low-fidelity trade for devices that need every bit of headroom

## Frame graph HUD

A small overlay panel in the top-left shows, live:

- **Real** — frames captured directly from the screen
- **Gen** — frames synthesized by the interpolator
- **Total** — real + generated
- A scrolling timeline strip (green = real, blue = generated) of the last 60 frames

Useful for confirming the interpolator is keeping pace with capture, and for eyeballing the real:generated ratio.

## Limitations

- **Captured output only** — uses MediaProjection, so only works with screen capture
- **Motion artifacts** — block search can still misfire on occlusions, fast motion, or large uniform/repetitive areas (little texture to match against); the confidence check catches most resulting ghosting but a misfire that happens to look forward/backward-consistent can still slip through
- **Android 8.0+** required (minSdk 26)
- **Google Play policy** — sideload only (overlay + capture violates ToS)

## Build

```bash
cd FrameInterp
./gradlew :app:assembleDebug
```

APK in `app/build/outputs/apk/debug/app-debug.apk`.

## How to use

1. Install APK
2. Open app → pick an interpolation mode and a capture resolution (720p by default; go lower if generated frames are lagging behind real ones on the frame graph HUD, higher for more sharpness if your device has headroom)
3. Tap "Start Frame Interpolation"
4. Grant overlay + MediaProjection permissions when prompted
5. Interpolation runs as overlay on top of current screen, captured at your chosen resolution and upscaled to fill the display
6. Tap notification to stop

## Next steps

Done since the initial skeleton:
- ✅ VirtualDisplay + ImageReader frame capture wired up
- ✅ Fullscreen overlay across OEM skins (real display bounds, not inset-adjusted `displayMetrics`) + rotation handling
- ✅ Overlay permission checked/requested at runtime, not just declared in the manifest
- ✅ Live rolling-window FPS HUD (real / generated / total), not a lifetime counter
- ✅ Replaced OpenCV/DIS optical flow with a native-free block motion interpolator
- ✅ Frame Blend fallback mode for devices that still can't keep up
- ✅ Moved all interpolators from CPU `Canvas` blits to GPU GLES2/GLSL warp+blend (`GlOffscreenPipeline`), fixing block-edge seams via bilinear-filtered motion field sampling
- ✅ Frame Blend gained cheap single-vector global motion compensation instead of being fully motion-blind
- ✅ Added forward/backward consistency-based occlusion detection (`MotionField.searchWithConfidence`) to all three interpolators, so regions where blending would ghost fall back to a single unwarped real frame instead
- ✅ Added a third mode, **Optical Flow (GLSL warp)** — a denser motion grid plus confidence-weighted field smoothing (`MotionField.smoothGrid`) for the sharpest result of the three
- ✅ Removed, then restored, the capture-resolution picker: capture briefly went always-native-1:1, but native resolution alone (especially 1080p+) was too much per-frame GPU/readback cost on some devices for generated frames to keep pace with real capture, so the picker is back — 180p to 1080p, defaulting to 720p (`ScreenMetrics.getCaptureSpec(context, targetLongSide)`, `Prefs.CAPTURE_RESOLUTIONS`)

Still open:
- Auto-picking a starting capture resolution from a quick on-device benchmark (or just watching the real:generated ratio for the first few seconds and stepping the resolution down automatically) instead of a fixed 720p default for every device
- Tune block size / search range / step count per mode for the speed-vs-accuracy sweet spot (currently fixed constants in `BlockMotionInterpolator` / `FrameBlendInterpolator` / `OpticalFlowWarpInterpolator`)
- Not yet compiled/run on a real device — motion direction/sign, GL texture orientation, block alignment, and the new occlusion-confidence math should all be sanity-checked against actual on-screen motion (see the "Note" in `GlOffscreenPipeline`'s history and `BlockMotionInterpolator` for what to check first)
- True hole-filling/inpainting for disoccluded content — the current occlusion handling avoids ghosting by falling back to a single real frame, but doesn't synthesize plausible content for the disoccluded region the way a neural interpolator could
- Adaptive alpha (currently a single fixed midpoint frame between each real pair; could generate more than one intermediate frame per real pair for higher multipliers)
- In-app tuning UI for motion search parameters, rather than editing constants in code
- Motion search is still CPU-only and now runs twice per block (forward + backward); profiling whether it or the GPU warp/blend is the bottleneck on native-resolution capture would tell you where to spend further optimization effort
- Possible further win: skip the backward search (and its CPU cost) for blocks whose forward search already converged with a very low SAD cost, since a strong match is unlikely to be occluded — currently every block pays for both searches unconditionally
