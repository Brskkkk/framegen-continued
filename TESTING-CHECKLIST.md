# FastSync Interpolator Testing Checklist

## Before Testing
- [ ] Copy `FastSyncInterpolator.kt` to `com/idk/frameinterp/`
- [ ] Copy `FrameSyncScheduler.kt` to `com/idk/frameinterp/`
- [ ] Apply all changes from `IMPLEMENTATION-GUIDE.md` to `FrameInterpService.kt`
- [ ] Build: `./gradlew assembleDebug`
- [ ] Install to device or via GitHub Actions
- [ ] Open a fast-moving game (animations, scrolling, etc.)

## FPS Targets to Verify

### Expected Display Output (on 60Hz display):
```
Real: 30fps (from Shizuku capture)
Gen:  30fps (interpolated frames)
Total: 60fps (all frames on screen)
```

### HUD Reading:
When running, the overlay HUD should read something like:
```
Real 30fps  Gen 30fps  Total 60fps
```

Or:
```
Real 28fps  Gen 28fps  Total 56fps
```
(Small variations ±2fps are normal)

### ❌ Red Flags:
- **Real 30fps, Gen 0fps, Total 30fps** → Interpolation isn't running
  - Check `buildInterpolator()` is returning `FastSyncInterpolator()`
  - Check no exceptions in logcat
  
- **Real 30fps, Gen 60fps, Total 90fps** → Generating too many frames
  - Should be 1:1 ratio (real : gen)
  - Check `startProcessingLoop()` isn't calling interpolate twice per frame

- **Real 0fps, Gen 0fps, Total 0fps** → No capture at all
  - Check Shizuku permissions
  - Check target package name is correct

## Visual Checks

### Ghosting / Double-Image
If you see two copies of moving objects offset by a frame:
- [ ] Check `PixelFormat.OPAQUE` is set on SurfaceView (line 418 in FrameInterpService)
- [ ] Check `canvas.drawColor(Color.BLACK)` is being called in `drawToSurface()` (line 311)
- [ ] Verify overlay window params have `PixelFormat.OPAQUE` (line 386)
- [ ] If still seeing ghosting, real screen might be bleeding through - check compositor settings

### Motion Smoothness
When scrolling or watching animations:
- [ ] Motion should look 2x smoother than without interpolation
- [ ] No jank/stutter spikes
- [ ] If stuttering, check if generation is too slow:
  ```kotlin
  // Temporarily add to FastSyncInterpolator.interpolate():
  val start = SystemClock.elapsedRealtime()
  // ... blending code ...
  val ms = SystemClock.elapsedRealtime() - start
  if (ms > 10) Log.w("FastSync", "Slow gen: ${ms}ms")
  ```

### Overlay Transparency
- [ ] You should clearly see the game/app behind the overlay
- [ ] HUD (FPS counter, graph) should be visible in top-left
- [ ] Control buttons (pause, stop, exit) should be visible in top-right

## Performance Profiling

### Check Generation Time
Add temporary logging to `FastSyncInterpolator.interpolate()`:
```kotlin
override fun interpolate(frame1: Bitmap, frame2: Bitmap, t: Float): Bitmap {
    val start = SystemClock.elapsedRealtime()
    
    // ... existing code ...
    val blend = /* blend creation/drawing */
    
    val elapsed = SystemClock.elapsedRealtime() - start
    if (elapsed > 8) {  // Log only if >8ms
        android.util.Log.d("FastSync", "Frame gen took ${elapsed}ms")
    }
    
    return blend
}
```

Then in logcat filter for "FastSync" and check times:
```
D/FastSync: Frame gen took 3ms   ← Good
D/FastSync: Frame gen took 5ms   ← Good
D/FastSync: Frame gen took 12ms  ← Too slow, might miss refresh
```

### Memory Profiling
In Android Studio Profiler:
- [ ] Memory should stay stable (no climbing)
- [ ] Bitmap cache should reuse same objects (watch "Framegen-4" allocations)
- [ ] No sudden spikes when generating frames

## Capture FPS Verification

Check if Shizuku is capturing at 60FPS (not capped at 30FPS):

Add this to `FrameInterpService.onStartCommand()` after line 112:
```kotlin
val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
Log.i("FrameInterp", "Display refresh: ${display.refreshRate}Hz, requesting capture at $maxFps FPS")
```

Check logcat:
```
I/FrameInterp: Display refresh: 60.0Hz, requesting capture at 60 FPS
```

If you see 30 FPS requested, that's the bottleneck. To debug why:
1. Check `ShizukuCaptureEngine.kt` - is it capping maxFps?
2. Check target app - does it itself run at 30fps? (some games lock to 30)
3. Check device - run `adb shell getprop ro.surface_flinger.vsync_event_phase_offset_ns`

---

## Troubleshooting by Symptom

### Symptom: Visible tearing (horizontal line moving across screen)
**Cause**: Frames drawing at different times, not synced to vsync
**Fix**: 
- This is expected with transparent overlay - real screen + overlay aren't guaranteed synced
- Try full-screen opaque approach (hide real screen completely) if tearing is bad
- Or verify display is 60Hz not 90Hz (some devices lie)

### Symptom: Frame generation taking 20+ms
**Cause**: Canvas/Bitmap operations too expensive on this device
**Fix**:
- Try Bitmap.Config.RGB_565 instead of ARGB_8888 (half memory)
- Try scaling input bitmaps down 25% before blending
- Or accept 30fps total (no interpolation) on weak devices

### Symptom: Real capture reporting 30fps but display shows jank
**Cause**: Interpolated frames aren't being generated/shown
**Fix**:
- Check HUD says "Gen 30fps" not "Gen 0fps"
- Check exceptions in logcat: `adb logcat | grep -i exception`
- Verify `showFrame()` is being called with `isGenerated=true`

### Symptom: Interpolated frames look weird/artifacts
**Cause**: Canvas blending isn't 50/50
**Fix**:
- Verify `paint.alpha = 128` is correct (0-255 scale)
- Try `paint.alpha = 100` (dimmer blend) or `150` (brighter)
- Check both input frames are valid (not corrupted/recycled)

---

## Before Committing

- [ ] Frame graph shows 60fps total consistently
- [ ] No crashes/exceptions in logcat
- [ ] Generation time <8ms (target <5ms)
- [ ] No ghosting visible
- [ ] Motion looks smooth

If all pass, **integrate into Framegen-4 main branch** and update preferences to use FastSyncInterpolator by default.

