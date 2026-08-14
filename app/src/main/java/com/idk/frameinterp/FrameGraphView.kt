package com.idk.frameinterp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

/**
 * Two-line rolling graph: real captured-frame rate (green) vs generated-
 * frame rate (orange), each sampled at a fixed interval and drawn as a
 * scrolling polyline, so you can visually confirm real and generated
 * frames are actually distinct and both flowing - not just trust a
 * number in the HUD text.
 */
class FrameGraphView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val maxSamples = 100
    private val realHistory = ArrayDeque<Float>()
    private val genHistory = ArrayDeque<Float>()

    private val realPaint = Paint().apply { color = Color.parseColor("#4CAF50"); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val genPaint = Paint().apply { color = Color.parseColor("#FF9800"); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val gridPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 2f }

    private var maxScale = 60f

    fun pushSample(realFps: Int, genFps: Int) {
        realHistory.addLast(realFps.toFloat())
        genHistory.addLast(genFps.toFloat())
        while (realHistory.size > maxSamples) realHistory.removeFirst()
        while (genHistory.size > maxSamples) genHistory.removeFirst()
        maxScale = (maxOf(realHistory.maxOrNull() ?: 60f, genHistory.maxOrNull() ?: 60f, 30f)) * 1.2f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // A couple of horizontal reference lines so the scale isn't a
        // total mystery at a glance.
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)

        drawSeries(canvas, realHistory, realPaint, w, h)
        drawSeries(canvas, genHistory, genPaint, w, h)
    }

    private fun drawSeries(canvas: Canvas, history: ArrayDeque<Float>, paint: Paint, w: Float, h: Float) {
        if (history.size < 2) return
        val step = w / (maxSamples - 1)
        val startIdx = maxSamples - history.size
        var prevX = 0f
        var prevY = 0f
        var first = true
        var i = startIdx
        for (value in history) {
            val x = i * step
            val y = h - (value / maxScale).coerceIn(0f, 1f) * h
            if (!first) canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x
            prevY = y
            first = false
            i++
        }
    }
}
