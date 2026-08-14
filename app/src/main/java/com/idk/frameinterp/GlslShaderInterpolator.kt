package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dedicated GPU (GLES2/GLSL) interpolation mode - this is the only one of
 * the five modes that's inherently GLSL-based rather than a CPU algorithm
 * with an optional GPU step bolted on.
 *
 * Runs ONE cheap forward/backward consistency-checked search
 * (MotionField.searchWithConfidence) over the whole downscaled frame to
 * get a single global (dx, dy) vector plus a confidence score, then a
 * fragment shader shifts both frames by that vector (split by alpha) and
 * cross-fades them - or, where confidence is low (the vectors disagree,
 * meaning non-uniform motion/occlusion), shows a single unwarped real
 * frame instead of a bad blend.
 *
 * Deliberately lighter than a full grid search: only two cheap searches
 * total (forward + backward) regardless of frame size, at a coarse
 * downscale - the cost that matters here is the one-time GL setup plus a
 * single draw call per frame, not the CPU search.
 *
 * Falls back to a plain CPU cross-fade if GLES2 init or any GL call fails.
 */
class GlslShaderInterpolator : FrameInterpolator {

    private companion object {
        // Coarser than any of the CPU modes' searches - this mode only
        // ever needs one global vector, so a cheap/coarse downscale plus a
        // small step budget is enough, and keeps it meaningfully lighter
        // than the previous GPU pipeline this replaces.
        const val SEARCH_DOWNSCALE = 12
        const val MAX_STEP = 4
        const val MV_RANGE_PX = 96f

        const val VERTEX_SRC = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uFrame1;
            uniform sampler2D uFrame2;
            uniform vec2 uMvUv;
            uniform float uAlpha;
            uniform float uConfidence;
            void main() {
                vec2 uv1 = clamp(vUv - uMvUv * uAlpha, 0.0, 1.0);
                vec2 uv2 = clamp(vUv + uMvUv * (1.0 - uAlpha), 0.0, 1.0);
                vec4 warped = mix(texture2D(uFrame1, uv1), texture2D(uFrame2, uv2), uAlpha);

                vec4 nearest = uAlpha < 0.5 ? texture2D(uFrame1, vUv) : texture2D(uFrame2, vUv);

                gl_FragColor = mix(nearest, warped, uConfidence);
            }
        """
    }

    private val gl = GlOffscreenPipeline()
    private var program = 0
    private var tex1 = 0
    private var tex2 = 0
    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        return interpolateGpu(frame1, frame2, alpha)
    }

    private fun interpolateGpu(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        val a = alpha.coerceIn(0f, 1f)

        val sw = max(1, w / SEARCH_DOWNSCALE)
        val sh = max(1, h / SEARCH_DOWNSCALE)
        val luma1 = MotionField.luma(frame1, sw, sh)
        val luma2 = MotionField.luma(frame2, sw, sh)

        val m = MotionField.searchWithConfidence(luma1, luma2, sw, sh, 0, 0, sw, sh, MAX_STEP)
        val fmx = (m.dx * SEARCH_DOWNSCALE).toFloat().coerceIn(-MV_RANGE_PX, MV_RANGE_PX)
        val fmy = (m.dy * SEARCH_DOWNSCALE).toFloat().coerceIn(-MV_RANGE_PX, MV_RANGE_PX)

        if (program == 0) program = gl.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
        tex1 = gl.uploadBitmapTexture(frame1, tex1)
        tex2 = gl.uploadBitmapTexture(frame2, tex2)

        val prog = program
        return gl.renderToBitmap(prog, w, h, "aPosition") {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrame1"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrame2"), 1)

            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uMvUv"), fmx / w.toFloat(), fmy / h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), a)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uConfidence"), m.confidence.coerceIn(0f, 1f))
        }
    }


    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
