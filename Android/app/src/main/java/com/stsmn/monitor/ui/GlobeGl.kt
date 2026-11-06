package com.stsmn.monitor.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** Camera shared by the UI thread (gestures, markers) and the GL thread (drawing). */
class GlobeCam {
    @Volatile var lat = 22.5
    @Volatile var lon = 78.96
    @Volatile var scale = 1f
}

/** Radius in pixels of the globe for a surface of w x h pixels. Used by drawing AND marker placement so they always agree. */
fun globeRadiusPx(w: Float, h: Float, scale: Float): Float = min(w, h) * 0.45f * scale

/**
 * Real-time GPU globe (OpenGL ES 2.0): a textured sphere with stars and an atmosphere ring, redrawn every frame so
 * dragging, inertia and zooming are smooth. If OpenGL cannot start, [onFailure] is called once.
 */
class GlobeSurface(
    ctx: Context,
    private val cam: GlobeCam,
    private val onFailure: () -> Unit,
    private val onReady: () -> Unit,
) : GLSurfaceView(ctx) {
    @Volatile private var texture: Bitmap? = null
    @Volatile private var textureDirty = false
    @Volatile private var reported = false
    private val main = Handler(Looper.getMainLooper())

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(Painter())
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setTexture(b: Bitmap) {
        if (texture === b) return
        texture = b
        textureDirty = true
    }

    private fun fail(msg: String) {
        Log.w("StsmnEarth", msg)
        if (!reported) { reported = true; main.post(onFailure) }
    }

    private inner class Painter : Renderer {
        private var sphereProg = 0
        private var flatProg = 0
        private var tex = 0
        private var hasTex = false
        private var w = 1
        private var h = 1
        private var announced = false

        private lateinit var sphereVerts: FloatBuffer
        private lateinit var sphereIdx: ShortBuffer
        private var idxCount = 0
        private lateinit var ring: FloatBuffer
        private var ringCount = 0
        private lateinit var stars: FloatBuffer
        private val starCount = 160

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                sphereProg = program(SPHERE_VS, SPHERE_FS)
                flatProg = program(FLAT_VS, FLAT_FS)
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                tex = t[0]
                buildMeshes()
                hasTex = false
                textureDirty = texture != null           // context (re)created: upload again
                GLES20.glClearColor(0.008f, 0.016f, 0.04f, 1f)
            } catch (e: Exception) {
                fail("GL init failed: ${e.message}")
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = maxOf(1, width); h = maxOf(1, height)
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                if (sphereProg == 0 || flatProg == 0) return
                if (textureDirty) { upload(); textureDirty = false }
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                val r = globeRadiusPx(w.toFloat(), h.toFloat(), cam.scale)
                val sx = 2f * r / w
                val sy = 2f * r / h

                // 1) stars and atmosphere ring (blended, no depth)
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUseProgram(flatProg)
                val aP = GLES20.glGetAttribLocation(flatProg, "aP")
                val aA = GLES20.glGetAttribLocation(flatProg, "aA")
                val uS = GLES20.glGetUniformLocation(flatProg, "uS")
                val uC = GLES20.glGetUniformLocation(flatProg, "uC")
                val uPt = GLES20.glGetUniformLocation(flatProg, "uPt")
                GLES20.glEnableVertexAttribArray(aP)
                GLES20.glEnableVertexAttribArray(aA)
                stars.position(0); GLES20.glVertexAttribPointer(aP, 2, GLES20.GL_FLOAT, false, 12, stars)
                stars.position(2); GLES20.glVertexAttribPointer(aA, 1, GLES20.GL_FLOAT, false, 12, stars)
                GLES20.glUniform2f(uS, 1f, 1f); GLES20.glUniform3f(uC, 1f, 1f, 1f); GLES20.glUniform1f(uPt, 2.5f)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
                if (hasTex) {
                    ring.position(0); GLES20.glVertexAttribPointer(aP, 2, GLES20.GL_FLOAT, false, 12, ring)
                    ring.position(2); GLES20.glVertexAttribPointer(aA, 1, GLES20.GL_FLOAT, false, 12, ring)
                    GLES20.glUniform2f(uS, sx, sy); GLES20.glUniform3f(uC, 0.30f, 0.58f, 1f); GLES20.glUniform1f(uPt, 1f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, ringCount)
                }
                GLES20.glDisableVertexAttribArray(aP)
                GLES20.glDisableVertexAttribArray(aA)
                GLES20.glDisable(GLES20.GL_BLEND)

                // 2) the sphere
                if (!hasTex) return
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                GLES20.glUseProgram(sphereProg)
                val aPos = GLES20.glGetAttribLocation(sphereProg, "aPos")
                val aUv = GLES20.glGetAttribLocation(sphereProg, "aUv")
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(sphereProg, "uRot"), 1, false, rotation(cam.lat, cam.lon), 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(sphereProg, "uScale"), sx, sy)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(sphereProg, "uTex"), 0)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glEnableVertexAttribArray(aUv)
                sphereVerts.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, sphereVerts)
                sphereVerts.position(3); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, sphereVerts)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, sphereIdx)
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aUv)

                if (!announced) { announced = true; main.post(onReady) }
            } catch (e: Exception) {
                fail("GL draw failed: ${e.message}")
            }
        }

        private fun upload() {
            val b = texture ?: return
            if (b.isRecycled) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            hasTex = true
        }

        private fun buildMeshes() {
            val rows = 120
            val cols = 192
            val v = FloatArray((rows + 1) * (cols + 1) * 5)
            var n = 0
            val maxLat = 85.0511 * PI / 180.0
            for (r in 0..rows) {
                val phi = PI / 2 - PI * r / rows                      // +90 .. -90
                val pc = phi.coerceIn(-maxLat, maxLat)
                val tv = (0.5 - ln(tan(PI / 4 + pc / 2)) / (2 * PI)).toFloat()
                for (c in 0..cols) {
                    val u = c.toFloat() / cols
                    val lam = -PI + 2 * PI * u
                    v[n++] = (cos(phi) * sin(lam)).toFloat()
                    v[n++] = sin(phi).toFloat()
                    v[n++] = (cos(phi) * cos(lam)).toFloat()
                    v[n++] = u
                    v[n++] = tv
                }
            }
            sphereVerts = floats(v)
            val idx = ShortArray(rows * cols * 6)
            n = 0
            for (r in 0 until rows) for (c in 0 until cols) {
                val a = (r * (cols + 1) + c).toShort()
                val b = (r * (cols + 1) + c + 1).toShort()
                val d = ((r + 1) * (cols + 1) + c).toShort()
                val e = ((r + 1) * (cols + 1) + c + 1).toShort()
                idx[n++] = a; idx[n++] = d; idx[n++] = b
                idx[n++] = b; idx[n++] = d; idx[n++] = e
            }
            idxCount = idx.size
            sphereIdx = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }

            val seg = 96
            val rg = FloatArray((seg + 1) * 2 * 3)
            n = 0
            for (i in 0..seg) {
                val a = 2 * PI * i / seg
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                rg[n++] = ca * 0.985f; rg[n++] = sa * 0.985f; rg[n++] = 0.65f
                rg[n++] = ca * 1.22f; rg[n++] = sa * 1.22f; rg[n++] = 0f
            }
            ring = floats(rg)
            ringCount = (seg + 1) * 2

            val rnd = Random(7)
            val st = FloatArray(starCount * 3)
            for (i in 0 until starCount) {
                st[i * 3] = rnd.nextFloat() * 2f - 1f
                st[i * 3 + 1] = rnd.nextFloat() * 2f - 1f
                st[i * 3 + 2] = 0.25f + rnd.nextFloat() * 0.6f
            }
            stars = floats(st)
        }
    }

    private companion object {
        const val SPHERE_VS = """
            attribute vec3 aPos; attribute vec2 aUv;
            uniform mat3 uRot; uniform vec2 uScale;
            varying vec2 vUv; varying float vZ;
            void main() {
                vec3 p = uRot * aPos;
                vUv = aUv; vZ = p.z;
                gl_Position = vec4(p.x * uScale.x, p.y * uScale.y, -p.z * 0.5, 1.0);
            }"""
        const val SPHERE_FS = """
            precision mediump float;
            uniform sampler2D uTex; varying vec2 vUv; varying float vZ;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb;
                float z = max(vZ, 0.0);
                float shade = 0.62 + 0.38 * sqrt(z);
                float rim = 1.0 - z;
                float glow = rim * rim * rim * 0.55;
                c = mix(c * shade, vec3(0.35, 0.63, 1.0), glow);
                gl_FragColor = vec4(c, 1.0);
            }"""
        const val FLAT_VS = """
            attribute vec2 aP; attribute float aA;
            uniform vec2 uS; uniform float uPt; varying float vA;
            void main() { vA = aA; gl_Position = vec4(aP * uS, 0.0, 1.0); gl_PointSize = uPt; }"""
        const val FLAT_FS = """
            precision mediump float;
            uniform vec3 uC; varying float vA;
            void main() { gl_FragColor = vec4(uC, vA); }"""

        fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        /** Column-major 3x3: Rx(lat) * Ry(-lon); brings the point (lat, lon) to the +Z axis (towards the viewer). */
        fun rotation(latDeg: Double, lonDeg: Double): FloatArray {
            val p = Math.toRadians(latDeg); val l = Math.toRadians(lonDeg)
            val cp = cos(p); val sp = sin(p); val cl = cos(-l); val sl = sin(-l)
            // Ry(a): [cl 0 sl; 0 1 0; -sl 0 cl], Rx(t): [1 0 0; 0 cp -sp; 0 sp cp]
            val m00 = cl;        val m01 = 0.0; val m02 = sl
            val m10 = sp * sl;   val m11 = cp;  val m12 = -sp * cl
            val m20 = -cp * sl;  val m21 = sp;  val m22 = cp * cl
            // m = Rx * Ry
            return floatArrayOf(
                m00.toFloat(), m10.toFloat(), m20.toFloat(),
                m01.toFloat(), m11.toFloat(), m21.toFloat(),
                m02.toFloat(), m12.toFloat(), m22.toFloat(),
            )
        }

        fun program(vs: String, fs: String): Int {
            val v = shader(GLES20.GL_VERTEX_SHADER, vs)
            val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
    }
}
