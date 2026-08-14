package com.idk.frameinterp

import android.graphics.Bitmap
import android.opengl.GLES20

/**
 * (GPU) Pair to AlternateFrameCpuInterpolator: same toggle idea, but the
 * fragment shader has no mix() at all - it just samples and outputs
 * whichever one texture is bound this call. Cheaper than any blend shader
 * since there's no second texture read or lerp instruction.
 */
class AlternateFrameGpuInterpolator : FrameInterpolator {

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
            uniform sampler2D uFrame;
            void main() {
                gl_FragColor = texture2D(uFrame, vUv);
            }
        """
    }

    private val gl = GlOffscreenPipeline()
    private var program = 0
    private var tex1 = 0
    private var tex2 = 0
    private var showFirst = true

    override fun interpolate(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        val w = frame1.width
        val h = frame1.height
        if (program == 0) program = gl.compileProgram(VERTEX_SRC, FRAGMENT_SRC)

        val pick = if (showFirst) frame1 else frame2
        showFirst = !showFirst
        val texId = if (pick === frame1) {
            tex1 = gl.uploadBitmapTexture(pick, tex1); tex1
        } else {
            tex2 = gl.uploadBitmapTexture(pick, tex2); tex2
        }

        val prog = program
        return gl.renderToBitmap(prog, w, h, "aPosition") {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFrame"), 0)
        }
    }

    override fun release() {
        gl.deleteTexture(tex1)
        gl.deleteTexture(tex2)
        gl.release()
    }
}
