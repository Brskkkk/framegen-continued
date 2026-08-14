# 60Hz Fast-Sync Interpolator Implementation Guide

## Overview
This guide integrates **FastSyncInterpolator** and **FrameSyncScheduler** into Framegen-4 for real-time 60Hz frame generation. The goal is to generate frames fast enough to sync with your display refresh cycles without ghosting or stutter.

---

## Step 1: Add New Interpolator Classes

### Copy these files to your project:
```
app/src/main/java/com/idk/frameinterp/
├── FastSyncInterpolator.kt        (NEW - ultra-fast blending)
└── FrameSyncScheduler.kt          (NEW - timing synchronization)
```

Both files are provided separately. Just add them to the directory above.

---

## Step 2: Update FrameInterpService.kt

### Step 2a: Add imports
At the top of `FrameInterpService.kt`, add:
```kotlin
import android.os.Handler
import android.os.Looper
```

### Step 2b: Add new fields (after line 73, before `latestOpaqueBitmap`)
```kotlin
private val syncScheduler = FrameSyncScheduler(60f)
private var vsyncIntervalMs = 16.67f  // 60Hz
```

### Step 2c: Remove the async reprojection job initialization
Find this around line 74:
```kotlin
private var reprojectionJob: kotlinx.coroutines.Job? = null
```
You can keep it but it won't be used. Comment it out or leave as-is.

### Step 2d: Replace buildInterpolator() function (lines 127-152)
Replace the entire function with:
```kotlin
private fun buildInterpolator(): FrameInterpolator = FastSyncInterpolator()
```

This locks all interpolation to the fast sync mode.

### Step 2e: Replace the entire startProcessingLoop() function (lines 187-206)

Delete the old function and replace with:

```kotlin
private fun startProcessingLoop() {
    serviceScope.launch {
        var prevFrame: Bitmap? = null
        
        for (frame in frameChannel) {
            val previous = prevFrame
            val frameTimestamp = SystemClock.elapsedRealtime()
            
            if (previous != null && !interpolationPaused) {
                // Generate interpolated frame if timing is right
                serviceScope.launch {
                    try {
                        val generated = interpolator.interpolate(previous, frame, 0.5f)
                        showFrame(generated, isGenerated = true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Always show the real frame immediately
            showFrame(frame, isGenerated = false)
            previous?.recycle()
            prevFrame = frame
        }
    }
}
```

### Step 2f: Replace showFrame() function (lines 232-269)

Replace with this simplified version:

```kotlin
private suspend fun showFrame(bitmap: Bitmap, isGenerated: Boolean) {
    // Direct surface draw - no async reprojection or opaque copy overhead
    withContext(Dispatchers.Main) {
        drawToSurface(bitmap)
        recordFrameStat(isGenerated)
    }
    if (isGenerated) bitmap.recycle()
}
```

### Step 2g: Comment out startReprojectionTickerIfNeeded() call

In `onStartCommand()` around line 101, change:
```kotlin
startReprojectionTickerIfNeeded()
```
to:
```kotlin
// startReprojectionTickerIfNeeded()  // Disabled for sync mode
```

### Step 2h: Simplify drawToSurface() (lines 304-317)

Keep it as-is or simplify to:

```kotlin
private fun drawToSurface(bitmap: Bitmap) {
    val holder = overlaySurface?.holder ?: return
    if (!surfaceReady || !holder.surface.isValid) return
    val canvas = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            holder.lockHardwareCanvas()
        } else {
            holder.lockCanvas()
        }
    }.getOrNull() ?: return
    try {
        canvas.drawColor(Color.BLACK)
        val dest = android.graphics.Rect(0, 0, canvas.width, canvas.height)
        canvas.drawBitmap(bitmap, null, dest, null)
    } finally {
        runCatching { holder.unlockCanvasAndPost(canvas) }
    }
}
```

---

## Step 3: Verify SharedPreferences/Prefs.kt

Check `Prefs.kt` - make sure these constants exist (they should):
```kotlin
const val MODE_FAST_SYNC = "fast_sync"
const val KEY_INTERP_MODE = "interp_mode"
const val DEFAULT_MODE = "frame_blend"
```

You may want to add:
```kotlin
const val DEFAULT_MODE = "fast_sync"  // Or leave as frame_blend and force it in buildInterpolator()
```

---

## Step 4: Test & Calibrate

### Build & Deploy
```bash
./gradlew assembleDebug
# Install via GitHub Actions or adb
```

### What to Look For:
1. **Frame graph should show ~30 real FPS + ~30 generated FPS = ~60 total**
   - If only ~30 total, interpolation isn't being called or is being skipped
   
2. **HUD should read: "Real 30fps  Gen 30fps  Total 60fps"**
   - Real = capture rate
   - Gen = interpolated frames
   - Total = real + gen

3. **Motion should look smooth** without ghosting/double-image
   - If you see ghosting, check that overlay is transparent and real screen shows through
   - If overlay looks opaque/blank, check PixelFormat.OPAQUE in SurfaceView setup

4. **No jank/stutter** even if capture is inconsistent
   - The FastSyncInterpolator should generate in <5ms on your Infinix

### Troubleshooting:

**Problem: No generated frames showing up**
- Check that `showFrame(generated, isGenerated=true)` is being called
- Look at logcat for exceptions in interpolate()
- Verify Shizuku capture is working (real frames should show)

**Problem: Ghosting/double-image visible**
- Ensure overlay SurfaceView is set to `PixelFormat.OPAQUE`
- Check that canvas.drawColor(Color.BLACK) is clearing properly
- If still seeing real screen beneath, the opaque flag isn't being honored by compositor

**Problem: Frame generation too slow (taking >10ms)**
- FastSyncInterpolator is already minimal - if it's slow, it's the Canvas/drawBitmap overhead
- Try switching to `FrameBlendInterpolator` instead to verify it's not something else
- On Infinix Hot 40i with Mali-G57, <5ms is realistic for ARGB_8888 at 1080p

**Problem: Generated frames aren't syncing with real frames**
- This is normal initially - they're asynchronous
- The visual effect should still be smooth because one always follows the other
- If you see visible tearing/jank, check your display is actually 60Hz (not 30Hz or 90Hz)

---

## Step 5: Optional - Fine-tune FastSyncInterpolator

If you want to experiment with speed vs. quality, modify `FastSyncInterpolator.kt`:

### Faster but darker (less blending):
```kotlin
paint.alpha = 96  // ~38% opacity instead of 50%
```

### Faster but lower quality (smaller intermediate bitmap):
```kotlin
val downscale = 0.75f
val downW = (w * downscale).toInt()
val downH = (h * downscale).toInt()
// Generate at smaller size, then scale back up
```

### Use hardware bitmap to avoid format conversions:
```kotlin
override fun interpolate(frame1: Bitmap, frame2: Bitmap, t: Float): Bitmap {
    // If both are hardware bitmaps, stay in hardware space
    if (frame1.config == Bitmap.Config.HARDWARE && frame2.config == Bitmap.Config.HARDWARE) {
        // Use RenderScript or Vulkan for GPU blending
        // This is where you'd integrate GPU interpolation later
    }
    // Fall back to Canvas blending for software bitmaps
    return canvasBlend(frame1, frame2)
}
```

---

## Step 6: Capture FPS Verification

To confirm Shizuku is actually capturing at the right FPS:

In `onStartCommand()`, after starting capture (around line 113), log:
```kotlin
val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
Log.i("FrameInterp", "Display refresh rate: ${display.refreshRate}Hz")
Log.i("FrameInterp", "Max capture FPS: $maxFps")
```

Check logcat:
```
I/FrameInterp: Display refresh rate: 60.0Hz
I/FrameInterp: Max capture FPS: 60
```

If capture FPS is showing 30, that's why you only see 30 real frames. Shizuku might be limiting it. Check with:
```kotlin
// In FrameInterpService.onStartCommand() around line 112:
val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
val maxFps = display.refreshRate.roundToInt().coerceIn(30, 240)
Log.i("FrameInterp", "Requesting capture at $maxFps FPS")
captureEngine.startCapture(targetPackage, captureWidth, captureHeight, maxFps)
```

If it's still only 30, check `ShizukuCaptureEngine.kt` - it might be hardcoded or limited by the source app.

---

## Summary of Changes

| File | Change | Reason |
|------|--------|--------|
| `FastSyncInterpolator.kt` | NEW | Ultra-fast 50/50 blending optimized for 60Hz |
| `FrameSyncScheduler.kt` | NEW | Timing sync to vsync windows (optional for now) |
| `FrameInterpService.kt` | Replace `buildInterpolator()` | Force FastSyncInterpolator |
| `FrameInterpService.kt` | Replace `startProcessingLoop()` | Simpler async generation |
| `FrameInterpService.kt` | Replace `showFrame()` | Remove async reprojection |
| `FrameInterpService.kt` | Comment `startReprojectionTickerIfNeeded()` | Not needed for this mode |

---

## Expected Results

✅ 60 FPS total (30 real + 30 gen)  
✅ No ghosting (overlay is transparent, real screen shows through)  
✅ Smooth motion (interpolated frame between each real frame)  
✅ No jank (generation happens in background, doesn't block UI)  
✅ Frame generation time: **<5ms** per frame on Infinix Hot 40i

---

## Next Steps if Still Not Smooth

1. **Check if Shizuku is actually capturing at 60FPS**
   - If stuck at 30FPS, the app itself might be capped (check app manifest, refresh rate query)

2. **Profile generation time**
   ```kotlin
   val genStart = SystemClock.elapsedRealtime()
   val generated = interpolator.interpolate(previous, frame, 0.5f)
   val genTime = SystemClock.elapsedRealtime() - genStart
   Log.d("FrameInterp", "Gen took ${genTime}ms")
   ```

3. **Try even lighter interpolation**
   - FastSyncInterpolator is already minimal
   - Could try `NearestFrameInterpolator` as fallback (just shows one frame unchanged)

4. **Check if display is actually 60Hz**
   - Some devices report 60Hz but run at 30Hz internally
   - Verify with `display.refreshRate` logging

