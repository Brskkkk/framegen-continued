package com.idk.frameinterp

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs under the shell UID via Shizuku.bindUserService (not a manifest
 * <service> - Shizuku instantiates this class directly).
 *
 * The loop/pacing here is the same shape as LSFG-Android-Application's
 * ShizukuCaptureUserService: a plain Thread doing
 * capture -> callback -> sleep(remaining period), just trimmed of the
 * FPS/graph metrics plumbing this simple app doesn't need.
 */
class CaptureUserService : IShizukuCaptureService.Stub() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun startCapture(
        targetUid: Int,
        width: Int,
        height: Int,
        maxFps: Int,
        callback: IShizukuFrameCallback
    ) {
        stopCapture()
        val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
        running.set(true)
        worker = Thread({
            runCaptureLoop(targetUid, width, height, periodMs, callback)
        }, "frameinterp-shizuku-capture").also { it.start() }
    }

    override fun stopCapture() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }

    private fun runCaptureLoop(
        targetUid: Int,
        width: Int,
        height: Int,
        periodMs: Long,
        callback: IShizukuFrameCallback
    ) {
        val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
            .getOrElse { e ->
                Log.w(TAG, "Unable to initialize privileged capture", e)
                callback.onError("Capture unavailable: ${e.message ?: e.javaClass.simpleName}")
                running.set(false)
                return
            }

        while (running.get()) {
            val started = SystemClock.uptimeMillis()
            val hb = runCatching { capture.captureHardwareBuffer() }
                .onFailure {
                    Log.w(TAG, "captureHardwareBuffer failed", it)
                    callback.onError("Capture failed: ${it.message ?: it.javaClass.simpleName}")
                }
                .getOrNull()

            if (hb != null) {
                val timestampNs = System.nanoTime()
                try {
                    callback.onFrame(hb, timestampNs)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame callback failed", t)
                    running.set(false)
                } finally {
                    runCatching { hb.close() }
                }
            }

            val elapsed = SystemClock.uptimeMillis() - started
            val sleepMs = periodMs - elapsed
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    companion object {
        private const val TAG = "CaptureUserService"
    }
}
