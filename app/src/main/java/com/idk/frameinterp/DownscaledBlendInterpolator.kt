package com.idk.frameinterp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The lightest of the GPU modes: identical one-line `mix()` cross-fade
 * shader as Lite Blend, but rendered into (and read back from) an FBO at
 * HALF resolution rather than the full capture size - a quarter the
 * fragment-shader invocations and a quarter the glReadPixels bandwidth,
 * which is usually the actual bottleneck on a low-end GPU, not the shader
 * math itself.
 *
 * The overlay's ImageView already uses FIT_XY (see FrameInterpService.
 * addOverlay()), so handing back a half-size bitmap just means the
 * View system does one extra bilinear upscale it was already capable of
 * doing for free - no extra work needed on this class's end for that.
 */
class DownscaledBlendInterpolator : FrameInterpolator {

    private companion object {
        const val OUTPUT_DOWNSCALE = 2

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
        val outW = max(1, frame1.width / OUTPUT_DOWNSCALE)
        val outH = max(1, frame1.height / OUTPUT_DOWNSCALE)
        val a = alpha.coerceIn(0f, 1f)

        if (program == 0) program = gl.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
        // Textures still upload at full input resolution - the saving here
        // is entirely in how many fragments get shaded/read back, i.e. the
        // FBO/output size, not the source texture size.
        tex1 = gl.uploadBitmapTexture(frame1, tex1)
        tex2 = gl.uploadBitmapTexture(frame2, tex2)

        val prog = program
        return gl.renderToBitmap(prog, outW, outH, "aPosition") {
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
