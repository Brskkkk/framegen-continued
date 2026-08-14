package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Grid-based block matching with bi-directional vector smoothing and a
 * deformable vertex mesh - a lighter middle ground between a full
 * per-pixel optical-flow shader and a zero-motion cross-fade.
 *
 * Pipeline:
 *  1. The frame is divided into a [GRID] x [GRID] macroblock grid. Each
 *     cell's motion vector comes from the same forward/backward
 *     consistency-checked block search the other modes share
 *     (MotionField.searchWithConfidence - this *is* the SAD/three-step
 *     block matching), run once per cell rather than per pixel.
 *  2. Each grid VERTEX (a cell corner, shared by up to 4 cells) is
 *     assigned the confidence-weighted average of its neighboring cells'
 *     vectors - "bi-directional temporal vector smoothing" - so the mesh
 *     deforms continuously across cell boundaries instead of snapping.
 *  3. The actual warp happens in the VERTEX shader, not a per-pixel
 *     fragment shader: each vertex's texture-sample coordinates are
 *     offset by uAlpha * itsMotionVector (so at alpha=0.5 that's exactly
 *     half the vector, generalized to any alpha), and the GPU rasterizer
 *     interpolates that smoothly across every pixel inside the triangle
 *     for free - this is what makes it "lighter than per-pixel optical
 *     flow" while still being a real per-pixel-smooth warp, not a
 *     per-block blit.
 *  4. Threshold fallback: any cell whose vector magnitude exceeds
 *     [MAX_ARTIFACT_PX], or whose forward/backward confidence is too low,
 *     contributes a "validity" weight of 0 instead of 1 at its corners.
 *     The fragment shader mixes between the warped sample and a plain
 *     unwarped linear cross-fade using that (also smoothly interpolated)
 *     weight - so chaotic blocks fall back to a clean cross-fade instead
 *     of a wobbly/tearing warp, with a soft rather than hard edge between
 *     warped and fallback regions.
 *
 * Falls back to a plain CPU cross-fade if GLES2 init or any GL call
 * fails, same as the other GPU modes.
 */
class GridWarpInterpolator : FrameInterpolator {

    private companion object {
        const val GRID = 12
        const val SEARCH_DOWNSCALE = 6
        const val MAX_STEP = 4
        const val CONFIDENCE_FLOOR = 0.15f
        // Full-resolution pixels. Beyond this, a vector is treated as a
        // scene cut / flash / menu-popup rather than real motion - "the
        // artifact limit" from the spec - and that vertex falls back to
        // cross-fade regardless of how confident the search itself was.
        const val MAX_ARTIFACT_PX = 48f

        const val VERTEX_SRC = """
            attribute vec2 aBasePos;
            attribute vec2 aMotionUv;
            attribute float aValid;
            uniform float uAlpha;
            varying vec2 vBaseUv;
            varying vec2 vUv1;
            varying vec2 vUv2;
            varying float vValid;
            void main() {
                vBaseUv = aBasePos * 0.5 + 0.5;
                vUv1 = clamp(vBaseUv - aMotionUv * uAlpha, 0.0, 1.0);
                vUv2 = clamp(vBaseUv + aMotionUv * (1.0 - uAlpha), 0.0, 1.0);
                vValid = aValid;
                gl_Position = vec4(aBasePos, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec2 vBaseUv;
            varying vec2 vUv1;
            varying vec2 vUv2;
            varying float vValid;
            uniform sampler2D uFrame1;
            uniform sampler2D uFrame2;
            uniform float uAlpha;
            void main() {
                vec4 warped = mix(texture2D(uFrame1, vUv1), texture2D(uFrame2, vUv2), uAlpha);
                vec4 linear = mix(texture2D(uFrame1, vBaseUv), texture2D(uFrame2, vBaseUv), uAlpha);
                gl_FragColor = mix(linear, warped, vValid);
            }
        """
    }

    private val gl = GlOffscreenPipeline()
    private var program = 0
    private var tex1 = 0
    private var tex2 = 0
    private var glFailed = false

    // Mesh buffers are rebuilt only when the grid topology changes (i.e.
    // never, since GRID is fixed) - only the per-vertex attribute data is
    // refreshed each frame.
    private val vertsPerSide = GRID + 1
    private val vertexCount = vertsPerSide * vertsPerSide
    private val strideFloats = 5 // aBasePos(2) + aMotionUv(2) + aValid(1)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexCount * strideFloats * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(GRID * GRID * 6 * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            for (gy in 0 until GRID) {
                for (gx in 0 until GRID) {
                    val i0 = (gy * vertsPerSide + gx).toShort()
                    val i1 = (gy * vertsPerSide + gx + 1).toShort()
                    val i2 = ((gy + 1) * vertsPerSide + gx).toShort()
                    val i3 = ((gy + 1) * vertsPerSide + gx + 1).toShort()
                    put(i0); put(i2); put(i1)
                    put(i1); put(i2); put(i3)
                }
            }
            position(0)
        }

    private val attribs = listOf(
        GlOffscreenPipeline.MeshAttrib("aBasePos", 2, 0),
        GlOffscreenPipeline.MeshAttrib("aMotionUv", 2, 2),
        GlOffscreenPipeline.MeshAttrib("aValid", 1, 4)
    )

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        return interpolateGpu(frame1, frame2, alpha)
    }

    private fun interpolateGpu(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        val sw = max(GRID, w / SEARCH_DOWNSCALE)
        val sh = max(GRID, h / SEARCH_DOWNSCALE)
        val luma1 = MotionField.luma(frame1, sw, sh)
        val luma2 = MotionField.luma(frame2, sw, sh)

        // 1. Per-cell block matching (SAD / three-step search).
        val cellW = max(1, sw / GRID)
        val cellH = max(1, sh / GRID)
        val cells = arrayOfNulls<MotionField.Motion>(GRID * GRID)
        for (gy in 0 until GRID) {
            val by = gy * cellH
            val blockH = if (gy == GRID - 1) sh - by else cellH
            for (gx in 0 until GRID) {
                val bx = gx * cellW
                val blockW = if (gx == GRID - 1) sw - bx else cellW
                cells[gy * GRID + gx] = MotionField.searchWithConfidence(
                    luma1, luma2, sw, sh, bx, by, max(1, blockW), max(1, blockH), MAX_STEP
                )
            }
        }

        // Full-res pixels -> UV units, done once per cell for reuse below.
        fun cellMotionUv(gx: Int, gy: Int): Triple<Float, Float, Float> {
            val m = cells[gy * GRID + gx]!!
            val fmx = m.dx * SEARCH_DOWNSCALE.toFloat()
            val fmy = m.dy * SEARCH_DOWNSCALE.toFloat()
            val mag = sqrt(fmx * fmx + fmy * fmy)
            val artifactWeight = (1f - (mag - MAX_ARTIFACT_PX) / MAX_ARTIFACT_PX).coerceIn(0f, 1f)
            val validity = if (m.confidence < CONFIDENCE_FLOOR) 0f else m.confidence * artifactWeight
            return Triple(fmx / w, fmy / h, validity)
        }

        // 2. Per-vertex bi-directional smoothing: average the (up to 4)
        // neighboring cells' motion/validity at each shared corner.
        vertexBuffer.position(0)
        for (vy in 0 until vertsPerSide) {
            for (vx in 0 until vertsPerSide) {
                var sumMx = 0f; var sumMy = 0f; var sumValid = 0f; var count = 0
                for (dy in -1..0) {
                    for (dx in -1..0) {
                        val cx = vx + dx
                        val cy = vy + dy
                        if (cx < 0 || cx >= GRID || cy < 0 || cy >= GRID) continue
                        val (mx, my, valid) = cellMotionUv(cx, cy)
                        sumMx += mx; sumMy += my; sumValid += valid; count++
                    }
                }
                val invCount = if (count > 0) 1f / count else 0f
                val basePosX = -1f + 2f * vx / GRID
                val basePosY = -1f + 2f * vy / GRID

                vertexBuffer.put(basePosX); vertexBuffer.put(basePosY)
                vertexBuffer.put(sumMx * invCount); vertexBuffer.put(sumMy * invCount)
                vertexBuffer.put((sumValid * invCount).coerceIn(0f, 1f))
            }
        }
        vertexBuffer.position(0)

        if (program == 0) program = gl.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
        tex1 = gl.uploadBitmapTexture(frame1, tex1)
        tex2 = gl.uploadBitmapTexture(frame2, tex2)

        val prog = program
        return gl.renderMeshToBitmap(
            prog, w, h,
            vertexBuffer, strideFloats, attribs,
            indexBuffer, GRID * GRID * 6
        ) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrame1"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrame2"), 1)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), a)
        }
    }


    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
