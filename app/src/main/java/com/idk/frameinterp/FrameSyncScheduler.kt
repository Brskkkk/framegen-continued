package com.idk.frameinterp

import android.os.SystemClock
import kotlin.math.max

/**
 * FrameSyncScheduler: Coordinates generated frame timing with display refresh.
 * 
 * Problem: If capture is 30fps and display is 60Hz, we generate frames between
 * captures but they're not aligned with refresh cycles. This scheduler ensures
 * generated frames are queued/drawn at times that sync with vsync.
 * 
 * Approach:
 * - Track real frame timestamps
 * - Calculate when next vsync window opens (16.67ms at 60Hz)
 * - Generate & queue interpolated frame in that window
 * - If generation takes too long, skip to next vsync window (don't queue stale frames)
 */
class FrameSyncScheduler(displayRefreshRateHz: Float = 60f) {
    
    private val vsyncIntervalMs = 1000f / displayRefreshRateHz.coerceAtLeast(30f)
    private var lastRealFrameTimestamp = 0L
    private var nextVsyncWindow = 0L
    
    /**
     * Called when a real frame arrives.
     * Returns the ideal timestamp at which to draw an interpolated frame
     * to fill the gap before this real frame.
     * 
     * @return time in ms before this real frame when gen frame should draw
     *         (negative = in the past, meaning this real frame has arrived late)
     *         null = skip generation (real frames arriving too fast)
     */
    fun scheduleGeneratedFrame(realFrameTimestamp: Long): Long? {
        val now = SystemClock.elapsedRealtime()
        
        if (lastRealFrameTimestamp == 0L) {
            lastRealFrameTimestamp = realFrameTimestamp
            nextVsyncWindow = realFrameTimestamp
            return null  // First frame, no interpolation
        }
        
        val timeSinceLastReal = realFrameTimestamp - lastRealFrameTimestamp
        
        // If real frames are coming in faster than vsync, skip interp
        if (timeSinceLastReal < vsyncIntervalMs * 0.8) {
            lastRealFrameTimestamp = realFrameTimestamp
            return null
        }
        
        // Calculate ideal gen frame time: midpoint between last real and this real
        val genIdealTime = (lastRealFrameTimestamp + realFrameTimestamp) / 2
        
        // Snap to nearest vsync window for alignment
        val vsyncAligned = snapToVsyncWindow(genIdealTime)
        
        lastRealFrameTimestamp = realFrameTimestamp
        return vsyncAligned
    }
    
    /**
     * Given a target timestamp, snap it to the nearest vsync window start.
     * This ensures the gen frame is drawn at a refresh boundary, not between them.
     */
    private fun snapToVsyncWindow(targetTime: Long): Long {
        if (nextVsyncWindow == 0L) return targetTime
        
        val offset = targetTime - nextVsyncWindow
        val numVsyncs = (offset / vsyncIntervalMs).toLong()
        val snapped = nextVsyncWindow + (numVsyncs * vsyncIntervalMs.toLong())
        
        // Update next expected vsync
        nextVsyncWindow = snapped + vsyncIntervalMs.toLong()
        
        return snapped
    }
    
    /**
     * Check if enough time has passed to safely draw now without missing vsync.
     * Returns true if we're within the "safe window" to draw (first 2ms of 16.67ms cycle)
     */
    fun isTimeToDrawNow(scheduledTime: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val timeTilScheduled = scheduledTime - now
        
        // Safe to draw if we're at or slightly past scheduled time
        // but not so far that we've missed this refresh cycle
        return timeTilScheduled in -2L..2L
    }
    
    /**
     * If generation is too slow, should we skip and wait for next vsync?
     * Returns true if we've missed the window by >3ms
     */
    fun shouldSkipThisFrame(scheduledTime: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - scheduledTime > 3L
    }
    
    fun reset() {
        lastRealFrameTimestamp = 0L
        nextVsyncWindow = 0L
    }
}
