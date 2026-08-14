package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.min

/**
 * FastSyncInterpolator: Ultra-lightweight frame generation for 60Hz displays.
 * 
 * Goal: Generate interpolated frames fast enough (~5ms) to keep sync with
 * real display refresh cycles. Not about visual quality—purely about
 * timing synchronization and reducing perceived jank.
 * 
 * Strategy:
 * - Simple cross-dissolve blending at 0.5 alpha (fastest)
 * - Early exit on decoders/scaling for small/cached bitmaps
 * - Direct canvas drawing, no GPU overhead
 * - Minimal allocation, aggressive reuse
 */
class FastSyncInterpolator : FrameInterpolator {
    
    private var cachedBlend: Bitmap? = null
    private var lastWidth = 0
    private var lastHeight = 0
    
    override fun interpolate(frame1: Bitmap, frame2: Bitmap, t: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        
        // Reuse cached blend bitmap if dimensions match
        val blend = if (cachedBlend != null && lastWidth == w && lastHeight == h) {
            cachedBlend!!
        } else {
            cachedBlend?.recycle()
            lastWidth = w
            lastHeight = h
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cachedBlend = it }
        }
        
        // Direct canvas drawing: frame1 + frame2 at 0.5 blend
        val canvas = Canvas(blend)
        canvas.drawColor(0xFF000000.toInt()) // Clear black first
        
        // Frame 1 at 50% opacity
        val paint = Paint().apply {
            alpha = 128  // 50% = 128/255
            isFilterBitmap = false  // No scaling overhead
        }
        canvas.drawBitmap(frame1, 0f, 0f, paint)
        
        // Frame 2 at 50% opacity (additive blending effect)
        canvas.drawBitmap(frame2, 0f, 0f, paint)
        
        return blend
    }
    
    override fun release() {
        cachedBlend?.recycle()
        cachedBlend = null
    }
}
