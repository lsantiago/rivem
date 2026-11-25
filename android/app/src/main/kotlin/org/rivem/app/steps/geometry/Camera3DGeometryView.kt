package org.rivem.app.steps.geometry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.common.CanvasPoint
import org.rivem.app.common.CanvasSegment
import org.rivem.app.common.CanvasStepLayout
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.CameraOutcome
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.GRPPoint
import org.rivem.app.pipeline.GeometryPipeline
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.geometry.Point2
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem

/**
 * Fixed station with 3-D control points (desktop "ipcam"): import a GRP file (Label, X, Y, Z [, x, y]), mark the
 * points that have no pixel position, solve the camera (11-parameter DLT), check the reprojection errors and give the
 * water level that defines the matrix. Writes `grp_3d.json`, `full_grp_3d.json`, `camera_solution_3d.json`,
 * `ortho_image.png` and `transformation_matrix.json` like desktop.
 */
@Composable
fun Camera3DGeometryView(model: WorkflowModel) {
    var points by remember { mutableStateOf<List<GRPPoint>>(emptyList()) }
    var activeID by remember { mutableStateOf<Int?>(null) }
    var levelText by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<CameraOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val project = model.project ?: return@LaunchedEffect
        GeometryPipeline.loadGRP(project)?.let { points = it }
        val m = try { project.readMatrix() } catch (_: Exception) { null }
        val cam = ProjectFiles.readCamera(project)
        if (m != null && cam != null) {
            levelText = Fmt.num(GeometryPipeline.ipcamLevel(m, cam), 3)
        } else if (points.isNotEmpty()) {
            levelText = Fmt.num(points.sumOf { it.Z } / points.size, 3)
        }
    }

    val picker = rememberTextFilePicker(
        onPicked = { text, _ ->
            try {
                val feet = model.settings?.unitSystem == UnitSystem.imperial
                val parsed = GeometryPipeline.parseGRP(text, feet)
                points = parsed
                outcome = null
                activeID = parsed.firstOrNull { it.pixel == null }?.id
                if (levelText.isEmpty()) levelText = Fmt.num(parsed.sumOf { it.Z } / parsed.size, 3)
                error = null
            } catch (e: Exception) {
                error = "No se pudieron leer los puntos: ${e.userMessage()}"
            }
        },
        onError = { error = "No se pudieron leer los puntos: $it" },
    )

    val markedCount = points.count { it.pixel != null }
    fun setPixel(id: Int, p: Point2?) {
        points = points.map { if (it.id == id) it.copy(pixel = p) else it }
    }

    fun compute() {
        val level = Fmt.parse(levelText) ?: return
        val list = points
        val project = model.project ?: return
        error = null
        busy = true
        scope.launch {
            try {
                val (p, o) = withContext(Dispatchers.Default) {
                    val copy = RiverProject(project.folder, project.settings.copy())
                    val frame = GeometryPipeline.loadFirstFrame(copy)
                    copy to GeometryPipeline.applyCamera3D(copy, list, level, frame)
                }
                model.replace(p)
                outcome = o
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Geometría 3D: $e")
                model.reload()
            } finally {
                busy = false
            }
        }
    }

    val errorsByID: Map<Int, Double> = outcome?.let { o ->
        o.usedIDs.withIndex().filter { it.index < o.solution.reprojectionErrors.size }
            .associate { it.value to o.solution.reprojectionErrors[it.index] }
    } ?: emptyMap()

    val canvasPoints = points.mapNotNull { p ->
        p.pixel?.let { CanvasPoint(p.id, it, p.label, if (p.id == activeID) Color.Yellow else Color.Red) }
    }
    // Reprojection residual of each point (from the marked position to where the camera projects it).
    val canvasSegments = outcome?.let { o ->
        o.usedIDs.withIndex().mapNotNull { (k, id) ->
            if (k >= o.solution.projectedPoints.size) return@mapNotNull null
            val px = points.firstOrNull { it.id == id }?.pixel ?: return@mapNotNull null
            CanvasSegment(px, o.solution.projectedPoints[k], Color(0xFFFF9500), 2f, arrow = true)
        }
    } ?: emptyList()

    val instruction = when {
        points.isEmpty() -> "Importa el archivo de puntos de control."
        activeID != null && points.any { it.id == activeID } ->
            "Toca la imagen para ubicar ${points.first { it.id == activeID }.label}."
        else -> "Elige un punto de la lista y tócalo en la imagen. Marcados: $markedCount de ${points.size}."
    }

    CanvasStepLayout(
        canvas = {
            if (model.firstFrame != null) {
                ImageCanvas(
                    model.firstFrame, model.frameSize, Modifier.fillMaxSize(), points = canvasPoints, segments = canvasSegments,
                    onTap = { p ->
                        val id = activeID
                        if (id != null && points.any { it.id == id }) {
                            setPixel(id, p)
                            // Move on to the next point without a position.
                            activeID = points.firstOrNull { it.pixel == null }?.id
                        }
                    },
                    onMove = { id, p -> setPixel(id, p) },
                )
            } else {
                NoFrameView()
            }
        },
        controls = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                FormSection(
                    footer = "Columnas: Label, X, Y, Z y opcionalmente x, y (píxel). Coordenadas en metros; cota Z en el mismo " +
                        "sistema que la batimetría.",
                ) {
                    FormButton(
                        if (points.isEmpty()) "Importar puntos de control (CSV)" else "Reemplazar puntos de control",
                        Icons.Filled.UploadFile,
                    ) { picker.launchTextPicker() }
                    FormText(instruction)
                }
                if (points.isNotEmpty()) {
                    FormSection("Puntos de control") {
                        points.forEachIndexed { i, p ->
                            if (i > 0) FormDivider()
                            GRPRow(p, p.id == activeID, errorsByID[p.id], select = { activeID = p.id }, clear = { setPixel(p.id, null) })
                        }
                    }
                    FormSection("Nivel del agua") {
                        NumberField("Cota del nivel (Z)", levelText, { levelText = it })
                        FormText(
                            "La matriz depende del nivel: en el paso de secciones se recalcula con el nivel de la primera sección.",
                            small = true,
                        )
                    }
                    FormSection(
                        footer = "Se necesitan al menos 6 puntos marcados que no estén todos en un plano. Guardar borra secciones, " +
                            "máscara y resultados.",
                    ) {
                        BusyFormButton(
                            "Resolver cámara y guardar", Icons.Filled.CameraAlt, busy,
                            enabled = markedCount >= 6 && Fmt.parse(levelText) != null,
                        ) { compute() }
                        outcome?.let { o ->
                            LabeledContent("Error medio de reproyección", "${Fmt.num(o.solution.meanError, 2)} px")
                            val pos = o.solution.cameraPosition
                            if (pos.size == 3) {
                                LabeledContent("Posición de la cámara", "${Fmt.num(pos[0], 2)}, ${Fmt.num(pos[1], 2)}, ${Fmt.num(pos[2], 2)}")
                            }
                        }
                        ErrorBanner(error)
                    }
                }
                RectifiedPreviewSection(model, ProjectFiles.orthoImage)
            }
        },
    )
}

@Composable
private fun GRPRow(point: GRPPoint, active: Boolean, error: Double?, select: () -> Unit, clear: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = select).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (point.pixel == null) Icons.Filled.PanoramaFishEye else Icons.Filled.Place,
            contentDescription = if (point.pixel == null) "sin marcar" else "marcado",
            tint = if (point.pixel == null) secondary else Color.Red,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(point.label)
            Text(
                "X ${Fmt.num(point.X, 2)}  Y ${Fmt.num(point.Y, 2)}  Z ${Fmt.num(point.Z, 2)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = secondary,
            )
        }
        if (error != null) {
            Text(
                "${Fmt.num(error, 1)} px", style = MaterialTheme.typography.bodySmall,
                color = if (error > 5) Color(0xFFFF9500) else secondary,
            )
        }
        if (active) Icon(Icons.Filled.TouchApp, contentDescription = "punto activo", tint = Color(0xFFFFCC00), modifier = Modifier.padding(start = 6.dp))
        if (point.pixel != null) {
            IconButton(onClick = clear) { Icon(Icons.Filled.Close, contentDescription = "Quitar", tint = MaterialTheme.colorScheme.error) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}
