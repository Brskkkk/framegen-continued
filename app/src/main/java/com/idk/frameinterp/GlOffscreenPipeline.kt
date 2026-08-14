package com.idk.frameinterp

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.SynchronousQueue

/**
 * Shared off-screen GLES2 plumbing used by the GPU interpolators.
 *
 * One persistent EGL context lives on one dedicated HandlerThread for the
 * life of the interpolator - context creation/destruction is too expensive
 * to redo every frame. EGL contexts are only ever current on the thread
 * that made them current, so every call in here that touches GL hops onto
 * that thread via [runOnGl] and blocks the caller until the result is
 * back. That keeps FrameInterpolator.interpolate()'s synchronous, plain-
 * Bitmap-in/Bitmap-out contract unchanged - callers never need to know GL
 * is involved at all.
 *
 * Rendering targets an FBO+texture sized to the actual frame, not the EGL
 * surface itself. The EGL surface is just a throwaway 1x1 pbuffer needed
 * to have *something* current for the context - this avoids any surface
 * resize dance when the capture resolution changes; only the FBO is
 * (re)allocated on demand, in ensureFbo().
 *
 * Note: written and reasoned through carefully but not compiled or run on
 * an actual device/emulator (none available here). If output ends up
 * flipped vertically, that's a sign flip in the vertex shader's UV mapping
 * (aPosition * 0.5 + 0.5 in both interpolators); if it's solid black or
 * throws, the exceptions here are captured and printed by the interpolator
 * that owns this pipeline, and both interpolators fall back to a plain
 * CPU cross-fade so the app keeps working either way.
 */
class GlOffscreenPipeline {

    private val glThread = HandlerThread("FrameInterpGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var initialized = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0

    private val quadVertices = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private var quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVertices); position(0) }

    /** Runs [block] on the dedicated GL thread (initializing EGL first if needed) and blocks until it's done. */
    private fun <T> runOnGl(block: () -> T): T {
        val queue = SynchronousQueue<Result<T>>()
        glHandler.post {
            queue.put(
                try {
                    if (!initialized) {
                        initEgl()
                        initialized = true
                    }
                    Result.success(block())
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            )
        }
        return queue.take().getOrThrow()
    }

    private fun initEgl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) error("eglInitialize failed")

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            error("eglChooseConfig failed")
        }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) error("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) error("eglMakeCurrent failed")

        eglDisplay = display
        eglContext = context
        eglSurface = surface
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) error("FBO incomplete: 0x${status.toString(16)}")

        fbo = fb[0]; fboTex = tex[0]; fboW = w; fboH = h
    }

    /** Compiles+links a program. Cheap to call once per interpolator instance and reuse - do NOT call per-frame. */
    fun compileProgram(vertexSrc: String, fragmentSrc: String): Int = runOnGl {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }

    /** Uploads [bitmap] into [existingTex] (or a freshly-generated texture if 0). Returns the texture id to reuse next call. */
    fun uploadBitmapTexture(bitmap: Bitmap, existingTex: Int): Int = runOnGl {
        val tex = if (existingTex != 0) existingTex else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        tex
    }

    /**
     * Uploads a small RGBA byte grid - (dx, dy, confidence, unused) per
     * texel, one texel per motion-search block - as GL_RGBA. GL_LINEAR
     * filtering means sampling it in the shader gives free bilinear
     * smoothing of *both* the vector field and the confidence/occlusion
     * mask between grid cells - see BlockMotionInterpolator and
     * OpticalFlowWarpInterpolator. RGBA (rather than the old 2-channel
     * LUMINANCE_ALPHA) is what makes room for the confidence channel that
     * drives occlusion-aware blending.
     */
    fun uploadGridTexture(data: ByteBuffer, w: Int, h: Int, existingTex: Int): Int = runOnGl {
        val tex = if (existingTex != 0) existingTex else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        data.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        tex
    }

    /**
     * Draws the fullscreen quad with [program], [positionAttrib] bound to
     * it, and whatever textures/uniforms [bindUniforms] sets up, into an
     * FBO sized (w, h), then reads that back into a Bitmap. [bindUniforms]
     * runs on the GL thread, after glUseProgram - do all
     * glActiveTexture/glBindTexture/glUniform* calls in there.
     */
    fun renderToBitmap(program: Int, w: Int, h: Int, positionAttrib: String, bindUniforms: () -> Unit): Bitmap = runOnGl {
        ensureFbo(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, positionAttrib)
        GLES20.glEnableVertexAttribArray(posLoc)
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

        bindUniforms()

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)

        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        bitmap
    }

    /** One vertex attribute's layout within an interleaved vertex buffer, for [renderMeshToBitmap]. */
    data class MeshAttrib(val name: String, val componentCount: Int, val offsetFloats: Int)

    /**
     * Like [renderToBitmap], but for an arbitrary triangle mesh instead of
     * the fixed fullscreen quad - used by GridWarpInterpolator's per-vertex
     * motion-vector grid warp, where each vertex needs its own motion
     * vector/validity attributes rather than just a position.
     *
     * [vertexData] is one interleaved float buffer (stride
     * [vertexStrideFloats] floats/vertex) described by [attribs].
     * [indexData]/[indexCount] describe the triangle list to draw.
     */
    fun renderMeshToBitmap(
        program: Int, w: Int, h: Int,
        vertexData: FloatBuffer, vertexStrideFloats: Int, attribs: List<MeshAttrib>,
        indexData: java.nio.ShortBuffer, indexCount: Int,
        bindUniforms: () -> Unit
    ): Bitmap = runOnGl {
        ensureFbo(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        val strideBytes = vertexStrideFloats * 4
        val locs = IntArray(attribs.size)
        for (i in attribs.indices) {
            val attr = attribs[i]
            val loc = GLES20.glGetAttribLocation(program, attr.name)
            locs[i] = loc
            if (loc < 0) continue
            GLES20.glEnableVertexAttribArray(loc)
            vertexData.position(attr.offsetFloats)
            GLES20.glVertexAttribPointer(loc, attr.componentCount, GLES20.GL_FLOAT, false, strideBytes, vertexData)
        }

        bindUniforms()

        indexData.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexData)

        for (loc in locs) if (loc >= 0) GLES20.glDisableVertexAttribArray(loc)

        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        bitmap
    }

    fun deleteTexture(tex: Int) {
        if (tex == 0) return
        try {
            runOnGl { GLES20.glDeleteTextures(1, intArrayOf(tex), 0) }
        } catch (_: Throwable) {
            // best-effort cleanup - if the context is already gone there's nothing to leak into
        }
    }

    fun release() {
        try {
            runOnGl {
                if (fbo != 0) {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
                    fbo = 0; fboTex = 0
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
            }
        } catch (_: Throwable) {
            // best-effort teardown - the thread is about to be killed either way
        } finally {
            glThread.quitSafely()
        }
    }
}
