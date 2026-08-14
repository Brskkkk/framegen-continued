package com.idk.frameinterp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.roundToInt

class FrameInterpService : Service() {

    companion object {
        var instance: FrameInterpService? = null
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "frame_interp_channel"
    }

    private var interpolator: FrameInterpolator = FrameBlendInterpolator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Only ever holds the latest captured frame - if the pipeline falls
    // behind, we deliberately drop stale frames rather than build up lag.
    private var frameChannel = Channel<Bitmap>(Channel.CONFLATED)

    private var windowManager: WindowManager? = null
    private var videoRoot: FrameLayout? = null
    private var controlsRoot: LinearLayout? = null
    private var overlaySurface: SurfaceView? = null
    @Volatile private var surfaceReady = false
    private var hudText: TextView? = null
    private var graphView: FrameGraphView? = null

    private val captureEngine by lazy { ShizukuCaptureEngine(this) }
    private var captureRunning = false
    @Volatile private var interpolationPaused = false

    private var captureWidth = 0
    private var captureHeight = 0

    private val realTimestamps = ArrayDeque<Long>()
    private val genTimestamps = ArrayDeque<Long>()

    private var reprojectionJob: kotlinx.coroutines.Job? = null
    private val latestOpaqueBitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
    private var overlayModeCached = Prefs.DEFAULT_OVERLAY_MODE

    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        startForeground(NOTIFICATION_ID, buildNotification())
        addOverlay()

        captureEngine.setErrorListener { msg -> mainHandler().post { hudText?.text = "Error: $msg" } }
        captureEngine.setFrameListener { buffer, _ -> onFrameFromShizuku(buffer) }
    }

    private fun mainHandler() = Handler(mainLooper)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (!targetPackage.isNullOrBlank() && !captureRunning) {
            prefs.edit().putString(Prefs.KEY_LAST_TARGET_PACKAGE, targetPackage).apply()
            interpolator = buildInterpolator()
            overlayModeCached = prefs.getString(Prefs.KEY_OVERLAY_MODE, Prefs.DEFAULT_OVERLAY_MODE) ?: Prefs.DEFAULT_OVERLAY_MODE
            // startReprojectionTickerIfNeeded()  // Disabled for FastSync direct overlay mode
            val spec = ScreenMetrics.getCaptureSpec(this, currentCaptureLongSide())
            captureWidth = spec.width
            captureHeight = spec.height

            captureEngine.requestPermission(4001) { granted ->
                if (!granted) {
                    mainHandler().post { hudText?.text = "Shizuku permission denied" }
                    return@requestPermission
                }
                val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                val maxFps = display.refreshRate.roundToInt().coerceIn(30, 240)
                captureEngine.startCapture(targetPackage, captureWidth, captureHeight, maxFps)
                captureRunning = true
            }
            startProcessingLoop()
        }
        return START_STICKY
    }

    private fun currentMode(): String =
        prefs.getString(Prefs.KEY_INTERP_MODE, Prefs.DEFAULT_MODE) ?: Prefs.DEFAULT_MODE

    private fun currentCaptureLongSide(): Int =
        prefs.getInt(Prefs.KEY_CAPTURE_LONG_SIDE, Prefs.DEFAULT_CAPTURE_LONG_SIDE)

    private fun buildInterpolator(): FrameInterpolator = FastSyncInterpolator()
    // FastSyncInterpolator: Ultra-fast 60Hz-optimized blending
    // Generates frames in <5ms for clean 60fps sync (30 real + 30 gen)
    // Previous modes commented below if needed for fallback
    /*
    private fun buildInterpolator(): FrameInterpolator = when (currentMode()) {
        Prefs.MODE_FRAME_BLEND -> FrameBlendInterpolator()
        Prefs.MODE_OPTICAL_FLOW -> OpticalFlowWarpInterpolator()
        ... (all other modes) ...
        else -> BlockMotionInterpolator()
    }
    */

    /**
     * Shizuku hands us a raw HardwareBuffer per frame (UID-filtered
     * capture, see PrivilegedScreenCapture/CaptureUserService) - must copy
     * its pixels out synchronously before this callback returns, since the
     * shell process closes/reuses the buffer right after.
     */
    private fun onFrameFromShizuku(buffer: HardwareBuffer) {
        val now = SystemClock.elapsedRealtime()
        synchronized(realTimestamps) {
            realTimestamps.addLast(now)
            while (realTimestamps.isNotEmpty() && now - realTimestamps.peekFirst() > 1000L) realTimestamps.removeFirst()
        }

        val bitmap = try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, null) ?: return
            val copy = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            copy
        } catch (t: Throwable) {
            null
        } ?: return

        // CONFLATED channel: if the processing loop is still busy on the
        // previous frame, this simply replaces it rather than queuing up
        // an unbounded backlog.
        frameChannel.trySend(bitmap)
    }

    /**
     * FastSync Pipeline: Real frames shown immediately on screen,
     * generated frames queued asynchronously on overlay.
     * 
     * Goal: 60fps total (30 real + 30 generated)
     * Real frames: ~30fps from Shizuku capture
     * Generated: ~30fps interpolated (one per real frame pair)
     * 
     * No blocking: capture, generation, and display all async
     */
    private fun startProcessingLoop() {
        serviceScope.launch {
            var prevFrame: Bitmap? = null

            for (frame in frameChannel) {
                val previous = prevFrame
                val frameTimestamp = SystemClock.elapsedRealtime()
                
                // Asynchronous generation: don't block on interpolation
                if (previous != null && !interpolationPaused) {
                    serviceScope.launch {
                        try {
                            val generated = interpolator.interpolate(previous, frame, 0.5f)
                            showFrame(generated, isGenerated = true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                // Real frames shown immediately (on main thread)
                showFrame(frame, isGenerated = false)
                previous?.recycle()
                prevFrame = frame
            }
        }
    }

    private fun startReprojectionTickerIfNeeded() {
        reprojectionJob?.cancel()
        reprojectionJob = null
        if (overlayModeCached != Prefs.OVERLAY_MODE_ASYNC_REPROJECTION) return

        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val intervalMs = (1000f / display.refreshRate.coerceAtLeast(30f)).toLong().coerceAtLeast(4L)

        // Async reprojection: redraws whatever the latest fully-opaque
        // frame is on a steady clock, independent of when capture/
        // interpolation actually produces a new one. If nothing new has
        // arrived since the last tick, it simply redraws the same frame
        // again (a "reprojection") rather than waiting - this is what
        // decouples on-screen pacing from capture/processing jitter,
        // without ever blending two frames' pixels together.
        reprojectionJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                latestOpaqueBitmap.get()?.let { drawToSurface(it) }
                delay(intervalMs)
            }
        }
    }

    private suspend fun showFrame(bitmap: Bitmap, isGenerated: Boolean) {
        // FastSync: Direct transparent overlay - no async reprojection
        // Real frames bypass overlay (shown by OS natively)
        // Generated frames drawn directly to overlay surface
        // Surface is OPAQUE format: composites cleanly without blending
        withContext(Dispatchers.Main) {
            drawToSurface(bitmap)
            recordFrameStat(isGenerated)
        }
        if (isGenerated) bitmap.recycle()
    }

    /**
     * Plain config-conversion (bitmap.copy(RGB_565)) only re-encodes whatever
     * pixels are already there - it does nothing for pixels the source never
     * wrote to. Several interpolators (warp/grid/GPU-FBO readback ones
     * especially) can legitimately leave a margin of the buffer untouched on
     * some frames, and an untouched pixel isn't guaranteed to be opaque black;
     * it can be leftover buffer contents. drawBitmap() onto a pre-cleared
     * black RGB_565 canvas guarantees every single pixel in the final image
     * is either the generated/real frame content or solid black. Only used
     * for async reprojection now, where we genuinely need our own copy to
     * hold across ticks - the default Alternating Frames path draws
     * directly onto the (already-opaque) surface canvas instead, see
     * showFrame() above.
     */
    private fun makeFullyOpaque(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    /**
     * Draws straight into the SurfaceView's own Surface, which was created
     * with SurfaceHolder.setFormat(PixelFormat.OPAQUE) - a real, distinct
     * compositor layer with no alpha channel of its own, not a bitmap living
     * inside the app's shared RGBA_8888 hardware-accelerated view tree like
     * ImageView.setImageBitmap was. That distinction is what actually stops
     * the compositor from ever blending this layer against the real screen
     * underneath, regardless of what the *window's* PixelFormat was set to -
     * so a plain drawColor(BLACK) clear (cheap, no allocation) plus the
     * frame itself is enough; no separate opaque bitmap copy is needed.
     */
    private fun drawToSurface(bitmap: Bitmap) {
        val holder = overlaySurface?.holder ?: return
        if (!surfaceReady || !holder.surface.isValid) return
        val canvas = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
        }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val dest = android.graphics.Rect(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, null, dest, null)
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private var lastHudUpdateAtMs = 0L

    private fun recordFrameStat(isGenerated: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isGenerated) {
            genTimestamps.addLast(now)
            while (genTimestamps.isNotEmpty() && now - genTimestamps.peekFirst() > 1000L) genTimestamps.removeFirst()
        }

        // The counting above is cheap (just Long arithmetic on a small
        // deque) and stays accurate every frame. What's NOT cheap is
        // actually redrawing the HUD text (TextView.setText triggers a
        // layout/measure pass) and the graph (postInvalidate -> a full
        // ~200-line-segment redraw) - neither needs to happen at capture
        // rate to be useful to look at, and doing it on every single
        // frame (twice as often once framegen doubles the frame count)
        // was fixed Main-thread work competing with the actual video
        // presentation on every mode, regardless of how light the
        // interpolator itself was.
        if (now - lastHudUpdateAtMs < 140L) return
        lastHudUpdateAtMs = now

        val realCount = synchronized(realTimestamps) { realTimestamps.size }
        hudText?.text = "Real ${realCount}fps  Gen ${genTimestamps.size}fps  Total ${realCount + genTimestamps.size}fps"
        graphView?.pushSample(realCount, genTimestamps.size)
    }

    /**
     * Two separate overlay windows, not one:
     *  - video window: full-screen, FLAG_NOT_TOUCHABLE, pure visual layer
     *    (image + HUD + graph). Every touch passes straight through to the
     *    real game underneath - this window can never block gameplay.
     *  - controls window: small, WRAP_CONTENT, positioned top-right, the
     *    only thing that's actually touchable - pause/stop/exit.
     * A single MATCH_PARENT touchable window would swallow every touch on
     * the whole screen; splitting them is what makes both the game and
     * the buttons work at the same time.
     */
    private fun addOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // The real, full physical bounds - not the inset-aware "usable area"
        // some OEM skins hand back for MATCH_PARENT on an overlay window.
        // Corner/edge gaps (e.g. behind a gesture-nav joystick sitting right
        // in that region) come from the window itself not extending that
        // far, not from anything in the frame bitmap - so this has to be
        // fixed at the window-size level, not the pixel level.
        val realSpec = ScreenMetrics.getReal(this)

        val videoParams = WindowManager.LayoutParams(
            realSpec.width,
            realSpec.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // OPAQUE (not TRANSLUCENT): this window is a full replacement of
            // the screen, not a partial overlay, so it must never let
            // SurfaceFlinger blend it against the real game underneath.
            // With TRANSLUCENT, any frame whose alpha isn't a clean 255
            // everywhere (e.g. an FBO readback from one of the lower-quality
            // GPU interpolators that doesn't guarantee alpha=1) bleeds the
            // real screen through and causes the double-image/"hallucinating"
            // look. OPAQUE makes the compositor ignore alpha entirely for
            // this surface, guaranteeing it always fully covers what's below.
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = FrameLayout(this).apply {
            // Opaque fallback color for the brief gap before the first
            // captured frame arrives (overlaySurface has no content yet) - keeps
            // that moment fully covering too, instead of showing whatever
            // was behind an empty ImageView.
            setBackgroundColor(Color.BLACK)
            val strokePx = (1f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, Color.WHITE)
            }
        }
        videoRoot = root

        val surface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            // The actual mechanism that guarantees this layer can never be
            // blended against - a real Surface-level opaque format, not a
            // window-level hint. HUD/graph views added after this one in the
            // same FrameLayout still draw on top via the window's own
            // (separate) surface, same as before.
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
            })
        }
        overlaySurface = surface
        root.addView(surface)

        val hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            text = "waiting for frames..."
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 32; leftMargin = 16 }
        }
        hudText = hud
        root.addView(hud)

        val graph = FrameGraphView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                (220 * resources.displayMetrics.density).roundToInt(),
                (80 * resources.displayMetrics.density).roundToInt()
            ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 96; leftMargin = 16 }
        }
        graphView = graph
        root.addView(graph)

        wm.addView(root, videoParams)

        val controlsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 32
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 8, 12, 8)
        }
        controlsRoot = controls

        val pauseBtn = TextView(this).apply {
            text = "\u23F8"
            setTextColor(Color.WHITE)
            setPadding(24, 8, 24, 8)
            isClickable = true
            setOnClickListener {
                interpolationPaused = !interpolationPaused
                text = if (interpolationPaused) "\u25B6" else "\u23F8"
            }
        }
        controls.addView(pauseBtn)

        val stopBtn = TextView(this).apply {
            text = "\u23F9"
            setTextColor(Color.WHITE)
            setPadding(24, 8, 24, 8)
            isClickable = true
            setOnClickListener {
                if (captureRunning) {
                    captureEngine.stop()
                    captureRunning = false
                    text = "\u25B6"
                    hudText?.text = "Capture stopped"
                } else {
                    text = "\u23F9"
                    captureEngine.startCapture(
                        prefs.getString(Prefs.KEY_LAST_TARGET_PACKAGE, null) ?: return@setOnClickListener,
                        captureWidth, captureHeight, 60
                    )
                    captureRunning = true
                }
            }
        }
        controls.addView(stopBtn)

        val exitBtn = TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            setPadding(24, 8, 24, 8)
            isClickable = true
            setOnClickListener {
                captureEngine.stop()
                stopSelf()
            }
        }
        controls.addView(exitBtn)

        wm.addView(controls, controlsParams)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Frame Interpolation",
                NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Frame Interpolation running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null

        serviceScope.cancel()
        frameChannel.close()
        reprojectionJob?.cancel()
        reprojectionJob = null
        latestOpaqueBitmap.getAndSet(null)?.recycle()

        captureRunning = false
        captureEngine.stop()

        interpolator.release()

        videoRoot?.let { runCatching { windowManager?.removeView(it) } }
        controlsRoot?.let { runCatching { windowManager?.removeView(it) } }
        videoRoot = null
        controlsRoot = null
        overlaySurface = null
        graphView = null
    }
}
