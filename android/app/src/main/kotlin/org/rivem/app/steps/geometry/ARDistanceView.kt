package org.rivem.app.steps.geometry

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.Fmt
import org.rivem.core.geometry.AveragedPoint
import org.rivem.core.geometry.Point3
import org.rivem.core.geometry.SurveyedDistances
import java.util.EnumSet
import java.util.Locale
import kotlin.math.sqrt

private val arColors = listOf(Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF32ADE6))
private val arOrange = Color(0xFFFF9500)
private val zooms = listOf(1, 2, 4, 8)
private val loupeSize = 150.dp

/** One marked point: averaged position, phone-to-point distance (m) and RMS spread of the readings (m). */
data class MarkedPoint(val point: Point3, val range: Double, val spread: Double)

/** Screen state written by the AR session (posted to the main thread). */
@Stable
private class ArUiState {
    var status by mutableStateOf("Mueve el teléfono despacio para que reconozca el entorno.")
    var failure by mutableStateOf<String?>(null)
    var measuring by mutableStateOf(false)
    var aimRange by mutableStateOf<Double?>(null)
    var loupe by mutableStateOf<ImageBitmap?>(null)
    var projected by mutableStateOf<List<Offset?>>(emptyList())
    var hasDepthSensor by mutableStateOf(false)
    var depthEnabled by mutableStateOf(false)
    val marked = mutableStateListOf<MarkedPoint>()

    /** Reliable range of the depth: hardware depth sensor ~5 m; estimated depth degrades sooner. */
    val goodRange: Double get() = if (hasDepthSensor) 5.0 else 3.0
}

private sealed class ArPhase {
    object Checking : ArPhase()
    object NoPermission : ArPhase()
    object Installing : ArPhase()
    class Unsupported(val why: String) : ArPhase()
    object Running : ArPhase()
}

/**
 * Owns the ARCore [Session] and the [GLSurfaceView] of the AR screen (main thread). Pause / resume follow the
 * lifecycle in the order ARCore requires (view paused before the session, session resumed before the view).
 */
private class ArSurveyController(private val ui: ArUiState, context: Context) : ArSurveyListener {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    val renderer = ArSurveyRenderer(this) { windowManager.defaultDisplay.rotation }
    var view: GLSurfaceView? = null
    var session: Session? = null
        private set
    private var resumed = false
    private var closed = false

    fun createView(context: Context): GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
        view = this
    }

    fun start(s: Session) {
        session = s
        renderer.session = s
    }

    fun resume() {
        if (resumed || closed) return
        val s = session ?: return
        try {
            s.resume()
        } catch (e: Exception) {
            AppLog.error("AR distancias: $e")
            ui.failure = "No se pudo abrir la cámara para AR: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        view?.onResume()
        resumed = true
    }

    fun pause() {
        if (!resumed) return
        view?.onPause()
        session?.pause()
        resumed = false
    }

    fun close() {
        if (closed) return
        closed = true
        view?.onPause()
        renderer.session = null
        try {
            session?.pause()
            session?.close()
        } catch (e: Exception) {
            AppLog.error("AR distancias al cerrar: $e")
        }
        session = null
        resumed = false
    }

    fun mark() {
        if (ui.marked.size >= 4 || ui.measuring) return
        ui.failure = null
        if (renderer.startBurst()) ui.measuring = true
    }

    fun undo() {
        if (ui.measuring || ui.marked.isEmpty()) return
        ui.marked.removeAt(ui.marked.lastIndex)
        renderer.worldPoints = ui.marked.map { it.point }
    }

    // ArSurveyListener (GL thread → main thread)

    override fun onStatus(text: String) {
        main.post { ui.status = text }
    }

    override fun onLive(range: Double?, loupe: Bitmap?) {
        val image = loupe?.asImageBitmap()
        main.post {
            ui.aimRange = range
            ui.loupe = image
        }
    }

    override fun onProjected(points: List<FloatArray?>) {
        val offsets = points.map { p -> p?.let { Offset(it[0], it[1]) } }
        main.post { ui.projected = offsets }
    }

    override fun onBurst(readings: List<Point3>, camera: Point3) {
        main.post { finishBurst(readings, camera) }
    }

    override fun onError(text: String) {
        main.post { ui.failure = text }
    }

    private fun finishBurst(readings: List<Point3>, camera: Point3) {
        ui.measuring = false
        val averaged = if (readings.size >= 3) AveragedPoint.from(readings) else null
        if (averaged == null || ui.marked.size >= 4) {
            ui.failure = "No se encontró una superficie estable en la mira. Acércate, apunta a un borde con textura y no " +
                "muevas el teléfono al marcar."
            return
        }
        val p = averaged.point
        val dx = p.x - camera.x
        val dy = p.y - camera.y
        val dz = p.z - camera.z
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        ui.marked.add(MarkedPoint(p, range, averaged.spread))
        renderer.worldPoints = ui.marked.map { it.point }
        AppLog.info(
            String.format(
                Locale.ROOT, "AR distancias: punto %d en (%.3f, %.3f, %.3f) m, a %.2f m, dispersión %.1f cm (%d/%d lecturas)",
                ui.marked.size, p.x, p.y, p.z, range, averaged.spread * 100, averaged.used, averaged.total,
            ),
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * RIVEM addition: the six oblique distances measured with the phone instead of a tape (full-screen dialog). ARCore
 * tracks the phone in a gravity-aligned frame (+Y up, like ARKit); the user aims the crosshair at the four control
 * points in order (a zoom loupe of the camera image helps at distance) and each point is the average of a burst of
 * 15 hit tests from the screen centre (Depth API when the phone supports it, detected planes otherwise), with its
 * spread. The distances are horizontal (`SurveyedDistances`), as the oblique solution puts the points on the water
 * plane. The live distance to the aimed surface says when to walk closer: tracking keeps the coordinates while the
 * user moves between points.
 */
@Composable
fun ARDistanceDialog(onUse: (SurveyedDistances) -> Unit, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        ARDistanceScreen(onUse, onDismiss)
    }
}

@Composable
private fun ARDistanceScreen(onUse: (SurveyedDistances) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui = remember { ArUiState() }
    val controller = remember { ArSurveyController(ui, context) }
    var phase by remember { mutableStateOf<ArPhase>(ArPhase.Checking) }
    var installRequested by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(4) }

    controller.renderer.zoom = zoom
    controller.renderer.loupeSizePx = with(density) { loupeSize.roundToPx() }

    fun prepare() {
        if (controller.session != null) return
        val activity = context.findActivity()
        if (activity == null) {
            phase = ArPhase.Unsupported("No se encontró la actividad de la app.")
            return
        }
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                phase = ArPhase.Unsupported("Este teléfono no admite ARCore (Servicios de Google Play para RA).")
                return
            }
            if (ArCoreApk.getInstance().requestInstall(activity, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                phase = ArPhase.Installing
                return
            }
            val session = Session(activity)
            var depthSensor = false
            try {
                val filter = CameraConfigFilter(session)
                filter.setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE))
                val configs = session.getSupportedCameraConfigs(filter)
                if (configs.isNotEmpty()) {
                    session.setCameraConfig(configs[0])
                    depthSensor = true
                }
            } catch (e: Exception) {
                AppLog.error("AR distancias: configuración con sensor de profundidad: $e")
            }
            val config = Config(session)
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            config.setFocusMode(Config.FocusMode.AUTO)
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            val depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depth) config.setDepthMode(Config.DepthMode.AUTOMATIC)
            session.configure(config)
            ui.hasDepthSensor = depthSensor
            ui.depthEnabled = depth
            controller.start(session)
            phase = ArPhase.Running
            AppLog.info("AR distancias: sesión iniciada, profundidad ${if (depth) "sí" else "no"}, sensor de profundidad ${if (depthSensor) "sí" else "no"}")
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.resume()
        } catch (e: UnavailableDeviceNotCompatibleException) {
            phase = ArPhase.Unsupported("Este teléfono no admite ARCore (Servicios de Google Play para RA).")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            phase = ArPhase.Unsupported("Hace falta instalar Servicios de Google Play para RA (ARCore).")
        } catch (e: Exception) {
            AppLog.error("AR distancias: $e")
            phase = ArPhase.Unsupported("ARCore no está disponible: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepare() else phase = ArPhase.NoPermission
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepare()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (phase is ArPhase.Installing) prepare()
                    controller.resume()
                }
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }

    val survey: SurveyedDistances? = SurveyedDistances.from(ui.marked.map { it.point })
    val nextColor = if (ui.marked.size < 4) arColors[ui.marked.size] else Color.White

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val ph = phase) {
            ArPhase.Running -> {
                // The crosshair, the overlay and the hit test share this full box: all use its exact centre.
                AndroidView(factory = { ctx -> controller.createView(ctx) }, modifier = Modifier.fillMaxSize())
                MarkersOverlay(ui, nextColor)
                RangeLabel(ui, Modifier.align(Alignment.Center).offset(y = 34.dp))
            }
            ArPhase.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            ArPhase.Installing -> ArMessage("Instalando ARCore", "Instala Servicios de Google Play para RA desde Play Store y vuelve a la app.")
            ArPhase.NoPermission -> ArMessage(
                "Sin permiso de cámara",
                "Permite el uso de la cámara en los ajustes de Android para medir con AR, o mide las distancias con cinta.",
            )
            is ArPhase.Unsupported -> ArMessage("AR no disponible", "${ph.why} Mide las distancias con cinta.")
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(onDismiss)
            if (phase is ArPhase.Running) {
                StatusBar(ui)
                val image = ui.loupe
                if (zoom > 1 && image != null) {
                    Loupe(image, zoom, nextColor, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Panel(
                    ui, zoom, { zoom = it }, survey,
                    mark = { controller.mark() },
                    undo = { controller.undo() },
                    use = { s ->
                        onUse(s)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun ArMessage(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(text, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TopBar(onDismiss: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.75f)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) { Text("Cancelar", color = Color.White) }
            Text(
                "Medir con la cámara", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun StatusBar(ui: ArUiState) {
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(10.dp)) {
        Text(ui.status, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Text(
            when {
                ui.hasDepthSensor -> "Sensor de profundidad activo: preciso hasta ~5 m del teléfono."
                ui.depthEnabled -> "Sin sensor de profundidad: profundidad estimada por ARCore, menos precisa."
                else -> "Sin profundidad: solo superficies detectadas, menos preciso."
            },
            color = Color.LightGray, style = MaterialTheme.typography.labelSmall,
        )
        val r = ui.aimRange
        if (r != null && r > ui.goodRange && ui.marked.size < 4) {
            Text(
                "La mira está a ${Fmt.num(r, 1)} m: acércate al punto para marcarlo. Puedes caminar entre puntos; las coordenadas se mantienen.",
                color = arOrange, style = MaterialTheme.typography.bodySmall,
            )
        }
        ui.failure?.let { Text(it, color = arOrange, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Crosshair at the exact centre, marked points and the lines between them (projected every frame). */
@Composable
private fun MarkersOverlay(ui: ArUiState, nextColor: Color) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val pts = ui.projected
        val lineWidth = 2.dp.toPx()
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (a != null && b != null) drawLine(Color.White, a, b, strokeWidth = lineWidth)
        }
        if (pts.size == 4) {
            val a = pts[3]
            val b = pts[0]
            if (a != null && b != null) drawLine(Color.White, a, b, strokeWidth = lineWidth)
        }
        val style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        pts.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val color = arColors[i.coerceIn(0, 3)]
            drawCircle(color, 8.dp.toPx(), p)
            drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            val text = textMeasurer.measure("${i + 1}", style)
            drawText(text, topLeft = Offset(p.x + 10.dp.toPx(), p.y - 10.dp.toPx() - text.size.height / 2f))
        }
        // Crosshair (circle + cross) with a dark outline for contrast.
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = 17.dp.toPx()
        val arm = 7.dp.toPx()
        for ((color, w) in listOf(Color.Black.copy(alpha = 0.6f) to 4.dp.toPx(), nextColor to 2.dp.toPx())) {
            drawCircle(color, r, c, style = Stroke(w))
            drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
            drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = w)
        }
    }
}

@Composable
private fun RangeLabel(ui: ArUiState, modifier: Modifier) {
    val r = ui.aimRange ?: return
    Text(
        "${Fmt.num(r, 1)} m",
        color = Color.White,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
        modifier = modifier
            .background(if (r > ui.goodRange) arOrange else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Loupe(image: ImageBitmap, zoom: Int, color: Color, modifier: Modifier) {
    Box(modifier.size(loupeSize)) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium,
            modifier = Modifier.fillMaxSize().clip(CircleShape).border(2.dp, Color.White, CircleShape),
        )
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val h = 13.dp.toPx()
            drawLine(color, Offset(c.x - h, c.y), Offset(c.x + h, c.y), strokeWidth = 1.5.dp.toPx())
            drawLine(color, Offset(c.x, c.y - h), Offset(c.x, c.y + h), strokeWidth = 1.5.dp.toPx())
        }
        Text(
            "$zoom×", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Panel(
    ui: ArUiState,
    zoom: Int,
    setZoom: (Int) -> Unit,
    survey: SurveyedDistances?,
    mark: () -> Unit,
    undo: () -> Unit,
    use: (SurveyedDistances) -> Unit,
) {
    val secondary = Color.LightGray
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ui.marked.size < 4) {
            Text(
                "Apunta la mira al punto ${ui.marked.size + 1} y toca «Marcar» sin mover el teléfono. Mismo orden que en la " +
                    "imagen: 1 y 2 en una orilla, 3 y 4 en la otra.",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
            SegmentedPicker(zooms.map { if (it == 1) "Sin zoom" else "$it×" }, zooms.indexOf(zoom).coerceAtLeast(0)) {
                setZoom(zooms[it])
            }
        }
        if (ui.marked.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ui.marked.forEachIndexed { i, m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(10.dp).background(arColors[i.coerceIn(0, 3)], CircleShape))
                        Text("P${i + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "a ${Fmt.num(m.range, 1)} m", style = MaterialTheme.typography.labelSmall,
                            color = if (m.range > ui.goodRange) arOrange else secondary,
                        )
                        Text(
                            "±${Fmt.num(m.spread * 100, 1)} cm", style = MaterialTheme.typography.labelSmall,
                            color = if (m.spread > 0.03) arOrange else secondary,
                        )
                    }
                }
            }
        }
        if (survey != null) DistancesGrid(ui, survey)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = undo, enabled = ui.marked.isNotEmpty() && !ui.measuring) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Deshacer")
            }
            Spacer(Modifier.weight(1f))
            if (survey != null) {
                Button(onClick = { use(survey) }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Usar distancias")
                }
            } else {
                Button(onClick = mark, enabled = !ui.measuring) {
                    if (ui.measuring) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Filled.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Marcar punto ${ui.marked.size + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun DistancesGrid(ui: ArUiState, s: SurveyedDistances) {
    val names = listOf("d12", "d23", "d34", "d41", "d13", "d24")
    val values = s.distances.values
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val k = row * 3 + col
                    Text("${names[k]} ${Fmt.num(values[k], 2)} m", style = mono, modifier = Modifier.weight(1f))
                }
            }
        }
        if (s.heightSpread > 0.10) {
            Text(
                "Los puntos difieren ${Fmt.num(s.heightSpread * 100, 0)} cm en altura: el método supone los cuatro sobre la " +
                    "superficie del agua. Se usan distancias horizontales.",
                color = arOrange, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.marked.any { it.range > ui.goodRange || it.spread > 0.03 }) {
            Text(
                "Algún punto se marcó lejos o con lecturas dispersas (naranja): deshazlo y márcalo más cerca, o contrasta esa " +
                    "distancia con cinta.",
                color = arOrange, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
