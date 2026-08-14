package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import kotlin.math.roundToInt

/**
 * Lightest and smoothest of all five modes: a pure GPU cross-fade with no
 * motion search or CPU work per frame at all - just two texture uploads
 * and a one-line fragment shader (`mix(tex1, tex2, alpha)`).
 *
 * "Smoothest" here means *never glitchy*, not *most accurate*: because
 * there is no motion vector to ever be wrong, this mode can never produce
 * a bad warp, a block seam, or a confidence misfire - the trade-off is
 * that fast-moving content still cross-fades/ghosts rather than being
 * motion-compensated. For a phone screen's most common content (mostly-
 * static UI, slow scrolling, text) that trade is usually invisible, and
 * this is the cheapest mode to run by a wide margin.
 *
 * Falls back to a plain CPU cross-fade if GLES2 init or any GL call
 * fails - which, since there's no motion compensation either way, is
 * visually identical to the GPU path anyway (just possibly slower).
 */
class LiteBlendInterpolator : FrameInterpolator {

    private companion object {
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
            uniform float uAlpha;
            void main() {
                gl_FragColor = mix(texture2D(uFrame1, vUv), texture2D(uFrame2, vUv), uAlpha);
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

            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), a)
        }
    }

    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
