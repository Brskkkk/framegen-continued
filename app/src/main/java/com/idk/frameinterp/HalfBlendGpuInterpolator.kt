package com.idk.frameinterp

import android.graphics.Bitmap
import android.opengl.GLES20

/**
 * (GPU) Pair to HalfBlendCpuInterpolator: the same fixed 50/50 blend, but
 * as a one-line shader (`mix(tex1, tex2, 0.5)`) with the alpha baked in
 * as a literal, so there's not even a uniform upload for it - the
 * absolute minimum a GPU blend can be.
 */
class HalfBlendGpuInterpolator : FrameInterpolator {

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
            void main() {
                gl_FragColor = mix(texture2D(uFrame1, vUv), texture2D(uFrame2, vUv), 0.5);
            }
        """
    }

    private val gl = GlOffscreenPipeline()
    private var program = 0
    private var tex1 = 0
    private var tex2 = 0

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
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
        }
    }

    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
