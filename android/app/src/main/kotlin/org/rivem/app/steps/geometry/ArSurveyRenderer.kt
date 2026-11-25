package org.rivem.app.steps.geometry

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.SessionPausedException
import org.rivem.core.geometry.Point3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera image of an ARCore frame drawn as the background: the standard ARCore `BackgroundRenderer` (external OES
 * texture filled by ARCore, two triangles over the whole viewport, texture coordinates from
 * `Frame.transformCoordinates2d` so the image is aspect-filled and rotated like the display).
 */
internal class CameraBackground {
    var textureId: Int = -1
        private set
    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0
    private val quadCoords: FloatBuffer = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floats(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    /** Call on the GL thread once the surface exists. */
    fun create() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords,
            )
        }
        if (frame.timestamp == 0L) return // no camera image yet
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(textureUniform, 0)
        quadCoords.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """

        fun floats(values: FloatArray): FloatBuffer {
            val b = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            b.put(values)
            b.position(0)
            return b
        }

        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            // The #extension line must come first: drop the indentation of the raw string.
            GLES20.glShaderSource(s, source.trimIndent())
            GLES20.glCompileShader(s)
            return s
        }
    }
}

/** What the GL thread reports to the screen (called on the GL thread: post to the main thread). */
internal interface ArSurveyListener {
    fun onStatus(text: String)

    /** Distance to the aimed surface (null: nothing under the crosshair) and the zoom loupe (null without zoom). */
    fun onLive(range: Double?, loupe: Bitmap?)

    /** Screen positions (view pixels) of the marked points, null when behind the camera or not tracking. */
    fun onProjected(points: List<FloatArray?>)

    /** End of a burst: the readings and the camera position at the end. */
    fun onBurst(readings: List<Point3>, camera: Point3)

    fun onError(text: String)
}

/**
 * ARCore session driven from the GL thread: updates the session, draws the camera image, ray-casts from the true
 * centre of the view (depth points when the Depth API is on, detected planes, oriented feature points), runs the
 * bursts of readings, reports the live range and the loupe about 12 times a second and projects the marked points
 * with the camera's view and projection matrices for the Compose overlay.
 */
internal class ArSurveyRenderer(
    private val listener: ArSurveyListener,
    private val displayRotation: () -> Int,
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var zoom: Int = 4
    @Volatile var loupeSizePx: Int = 400
    /** Points already marked (world frame, m), projected every frame for the overlay. */
    @Volatile var worldPoints: List<Point3> = emptyList()

    private val background = CameraBackground()
    private var textureSession: Session? = null
    private var width = 0
    private var height = 0
    private var geometryChanged = false
    private val burstRequested = AtomicBoolean(false)
    private var burst: ArrayList<Point3>? = null
    private var burstFrames = 0
    private var lastLive = 0L
    private var lastStatus = ""
    private var lastProjectedEmpty = true
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val world = FloatArray(4)
    private val clip = FloatArray(4)

    companion object {
        const val BURST_READINGS = 15
        const val BURST_MAX_FRAMES = 45
        const val LIVE_INTERVAL_NS = 1_000_000_000L / 12
    }

    /** Starts a burst of readings on the next frames (ignored while one runs). */
    fun startBurst(): Boolean = burstRequested.compareAndSet(false, true)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        textureSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (textureSession !== s) {
            s.setCameraTextureName(background.textureId)
            textureSession = s
            geometryChanged = true
        }
        if (geometryChanged && width > 0 && height > 0) {
            s.setDisplayGeometry(displayRotation(), width, height)
            geometryChanged = false
        }
        val frame: Frame = try {
            s.update()
        } catch (_: SessionPausedException) {
            return
        } catch (e: Exception) {
            listener.onError("Error de AR: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        background.draw(frame)
        val camera = frame.camera
        reportStatus(camera)

        // Burst: one reading per frame from the crosshair, up to 15 readings or 45 frames.
        if (burst == null && burstRequested.get()) {
            burst = ArrayList()
            burstFrames = 0
        }
        val readings = burst
        if (readings != null) {
            burstFrames += 1
            aim(frame, camera)?.let { readings.add(it.first) }
            if (readings.size >= BURST_READINGS || burstFrames >= BURST_MAX_FRAMES) {
                burst = null
                burstRequested.set(false)
                val c = camera.pose
                listener.onBurst(readings, Point3(c.tx().toDouble(), c.ty().toDouble(), c.tz().toDouble()))
            }
        }

        project(camera)

        val now = System.nanoTime()
        if (now - lastLive >= LIVE_INTERVAL_NS) {
            lastLive = now
            val range = aim(frame, camera)?.second
            val loupe = if (zoom > 1 && frame.timestamp != 0L) readLoupe() else null
            listener.onLive(range, loupe)
        }
    }

    /** Hit from the centre of the view: the point and its distance to the camera (m). */
    private fun aim(frame: Frame, camera: Camera): Pair<Point3, Double>? {
        if (camera.trackingState != TrackingState.TRACKING || width <= 0 || height <= 0) return null
        val hits = try {
            frame.hitTest(width / 2f, height / 2f)
        } catch (_: Exception) {
            return null
        }
        for (hit in hits) {
            val t = hit.trackable
            val usable = when (t) {
                is DepthPoint -> true
                is Plane -> t.isPoseInPolygon(hit.hitPose)
                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (!usable) continue
            val p = hit.hitPose
            val point = Point3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
            val c = camera.pose
            val dx = point.x - c.tx()
            val dy = point.y - c.ty()
            val dz = point.z - c.tz()
            return point to sqrt(dx * dx + dy * dy + dz * dz)
        }
        return null
    }

    private fun project(camera: Camera) {
        val points = worldPoints
        if (points.isEmpty()) {
            if (!lastProjectedEmpty) listener.onProjected(emptyList())
            lastProjectedEmpty = true
            return
        }
        lastProjectedEmpty = false
        if (camera.trackingState != TrackingState.TRACKING || width <= 0 || height <= 0) {
            listener.onProjected(points.map { null })
            return
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 500f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val out = points.map { p ->
            world[0] = p.x.toFloat()
            world[1] = p.y.toFloat()
            world[2] = p.z.toFloat()
            world[3] = 1f
            Matrix.multiplyMV(clip, 0, viewProjection, 0, world, 0)
            if (clip[3] <= 1e-6f) {
                null
            } else {
                val nx = clip[0] / clip[3]
                val ny = clip[1] / clip[3]
                floatArrayOf((nx + 1f) / 2f * width, (1f - ny) / 2f * height)
            }
        }
        listener.onProjected(out)
    }

    /** Square of the drawn camera image around the view centre, [zoom] × what the screen shows there. */
    private fun readLoupe(): Bitmap? {
        val z = max(zoom, 1)
        var side = (loupeSizePx.toFloat() / z).roundToInt()
        side = min(max(side, 8), min(width, height))
        if (side <= 0) return null
        val x0 = width / 2 - side / 2
        val y0 = height / 2 - side / 2
        return try {
            val buffer = ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(x0, y0, side, side, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.position(0)
            val raw = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            // GL rows go bottom-up.
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(raw, 0, 0, side, side, flip, false)
            if (flipped !== raw) raw.recycle()
            flipped
        } catch (_: Throwable) {
            null
        }
    }

    private fun reportStatus(camera: Camera) {
        val text = when (camera.trackingState) {
            TrackingState.TRACKING -> "Seguimiento correcto. Apunta la mira a cada punto."
            TrackingState.PAUSED -> when (camera.trackingFailureReason) {
                TrackingFailureReason.NONE -> "Iniciando: mueve el teléfono despacio de lado a lado."
                TrackingFailureReason.EXCESSIVE_MOTION -> "Mueve el teléfono más despacio."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Poca textura: apunta a la orilla, rocas o vegetación."
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Poca luz: el seguimiento necesita más iluminación."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "La cámara no está disponible."
                else -> "Seguimiento limitado."
            }
            else -> "Seguimiento no disponible."
        }
        if (text != lastStatus) {
            lastStatus = text
            listener.onStatus(text)
        }
    }
}
