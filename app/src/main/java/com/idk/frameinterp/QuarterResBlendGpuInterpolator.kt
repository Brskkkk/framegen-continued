package com.idk.frameinterp

import android.graphics.Bitmap
import android.opengl.GLES20
import kotlin.math.max

/**
 * (GPU) Pair to QuarterResBlendCpuInterpolator - same one-line blend
 * shader as Half Blend (GPU), but rendered into (and read back from) a
 * quarter-resolution FBO: 1/16th the fragment-shader invocations and
 * glReadPixels bandwidth of a full-res blend. The overlay's ImageView
 * already stretches whatever size bitmap it's given, so no extra work is
 * needed here to display it back at full size.
 */
class QuarterResBlendGpuInterpolator : FrameInterpolator {

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
        val outW = max(1, frame1.width / 4)
        val outH = max(1, frame1.height / 4)
        if (program == 0) program = gl.compileProgram(VERTEX_SRC, FRAGMENT_SRC)
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
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAlpha"), alpha.coerceIn(0f, 1f))
        }
    }

    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
