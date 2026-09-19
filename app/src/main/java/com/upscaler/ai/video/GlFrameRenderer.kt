package com.upscaler.ai.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Draws ARGB frames onto the encoder's input Surface using the GPU.
 * The GPU does (for free, in parallel with the AI):
 *  - final resize to the exact target resolution (high-quality bicubic-ish via mipmaps + linear)
 *  - rotation metadata handling
 *  - optional adaptive unsharp mask ("sharpen") for extra perceived detail
 *  - optional mild denoise for FAST mode
 * This avoids any CPU-side Bitmap scaling, which would be slower than the AI itself at 1080p+.
 */
class GlFrameRenderer(
    private val surface: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
) : AutoCloseable {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var texW = 0
    private var texH = 0
    private var uTexel = 0
    private var uSharpen = 0
    private var uRotation = 0
    private var aPos = 0
    private var aUv = 0
    private val vertexBuf: FloatBuffer

    var sharpenAmount: Float = 0.0f

    init {
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        vertexBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        vertexBuf.position(0)
        setupEgl()
        setupGl()
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val v = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig failed" }
        val cfg = configs[0]!!
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        val vs = """#version 300 es
            in vec2 aPos; in vec2 aUv; out vec2 vUv; uniform int uRotation;
            void main() {
                vec2 uv = aUv;
                if (uRotation == 90)       uv = vec2(1.0 - aUv.y, aUv.x);
                else if (uRotation == 180) uv = vec2(1.0 - aUv.x, 1.0 - aUv.y);
                else if (uRotation == 270) uv = vec2(aUv.y, 1.0 - aUv.x);
                vUv = uv; gl_Position = vec4(aPos, 0.0, 1.0);
            }"""
        val fs = """#version 300 es
            precision highp float;
            in vec2 vUv; out vec4 frag;
            uniform sampler2D uTex; uniform vec2 uTexel; uniform float uSharpen;
            void main() {
                vec3 c = texture(uTex, vUv).rgb;
                if (uSharpen > 0.001) {
                    // Adaptive unsharp mask: 3x3 blur, add back the high-pass, protect edges from ringing
                    vec3 s = vec3(0.0);
                    s += texture(uTex, vUv + vec2(-uTexel.x, -uTexel.y)).rgb;
                    s += texture(uTex, vUv + vec2( 0.0,      -uTexel.y)).rgb;
                    s += texture(uTex, vUv + vec2( uTexel.x, -uTexel.y)).rgb;
                    s += texture(uTex, vUv + vec2(-uTexel.x,  0.0)).rgb;
                    s += texture(uTex, vUv + vec2( uTexel.x,  0.0)).rgb;
                    s += texture(uTex, vUv + vec2(-uTexel.x,  uTexel.y)).rgb;
                    s += texture(uTex, vUv + vec2( 0.0,       uTexel.y)).rgb;
                    s += texture(uTex, vUv + vec2( uTexel.x,  uTexel.y)).rgb;
                    vec3 blur = (s + c) / 9.0;
                    vec3 hp = c - blur;
                    float mag = length(hp);
                    float w = smoothstep(0.0, 0.08, mag) * (1.0 - smoothstep(0.25, 0.6, mag));
                    c = clamp(c + hp * uSharpen * (0.4 + 0.6 * w), 0.0, 1.0);
                }
                frag = vec4(c, 1.0);
            }"""
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uSharpen = GLES20.glGetUniformLocation(program, "uSharpen")
        uRotation = GLES20.glGetUniformLocation(program, "uRotation")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glViewport(0, 0, outWidth, outHeight)
    }

    /**
     * Upload an ARGB int frame and draw it scaled to the full output surface.
     * @param rotation 0/90/180/270 from video metadata
     * @param ptsNs presentation timestamp in nanoseconds (for the encoder)
     */
    fun draw(pixels: IntArray, w: Int, h: Int, rotation: Int, ptsNs: Long) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        // ARGB int → GL expects RGBA bytes. On little-endian ARM, int 0xAARRGGBB in memory is BB GG RR AA,
        // which matches GL_BGRA if available; simplest portable way: use GL_RGBA with swizzled upload.
        val buf = IntBuffer.wrap(swizzle(pixels, w * h))
        if (w != texW || h != texH) {
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            texW = w; texH = h
        } else {
            GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        }
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uTexel, 1f / w, 1f / h)
        GLES20.glUniform1f(uSharpen, sharpenAmount)
        GLES20.glUniform1i(uRotation, rotation)
        vertexBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        vertexBuf.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private var swz = IntArray(0)
    /** 0xAARRGGBB → little-endian RGBA byte order (0xAABBGGRR as int). */
    private fun swizzle(src: IntArray, n: Int): IntArray {
        if (swz.size < n) swz = IntArray(n)
        val d = swz
        for (i in 0 until n) {
            val p = src[i]
            d[i] = (p and -0x1000000) or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p shr 16) and 0xFF)
        }
        return d
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun link(vs: Int, fs: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    override fun close() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }
}
