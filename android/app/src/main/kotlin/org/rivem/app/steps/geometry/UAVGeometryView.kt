package org.rivem.app.steps.geometry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.common.CanvasPoint
import org.rivem.app.common.CanvasSegment
import org.rivem.app.common.CanvasStepLayout
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.GeometryPipeline
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.UAVTransform
import org.rivem.core.project.RiverProject
import kotlin.math.abs

/**
 * UAV / drone (desktop "Pixel Size"): two points on the frame and either their distance or their East/North
 * coordinates → `pixel_size`, `transformation_matrix.json`, `transformation{resolution, extent}`,
 * `transformed_image.png`.
 */
@Composable
fun UAVGeometryView(model: WorkflowModel) {
    var p1 by remember { mutableStateOf<Point2?>(null) }
    var p2 by remember { mutableStateOf<Point2?>(null) }
    var useCoordinates by remember { mutableStateOf(false) }
    var lengthText by remember { mutableStateOf("") }
    var e1 by remember { mutableStateOf("0") }
    var n1 by remember { mutableStateOf("0") }
    var e2 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("0") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val ps = model.settings?.pixelSize ?: return@LaunchedEffect
        p1 = ps.pixel1
        p2 = ps.pixel2
        val defaultWorld = ps.world1 == Point2(0.0, 0.0) && abs(ps.world2.y) < 1e-12
        useCoordinates = !defaultWorld
        lengthText = Fmt.num(ps.realWorldLength, 3)
        e1 = Fmt.num(ps.world1.x, 3)
        n1 = Fmt.num(ps.world1.y, 3)
        e2 = Fmt.num(ps.world2.x, 3)
        n2 = Fmt.num(ps.world2.y, 3)
    }

    val worldPoints: Pair<Point2, Point2>? = run {
        if (useCoordinates) {
            val a = Fmt.parse(e1)
            val b = Fmt.parse(n1)
            val c = Fmt.parse(e2)
            val d = Fmt.parse(n2)
            if (a == null || b == null || c == null || d == null) return@run null
            val w1 = Point2(a, b)
            val w2 = Point2(c, d)
            if (w1.distanceTo(w2) > 0) w1 to w2 else null
        } else {
            val l = Fmt.parse(lengthText)
            if (l == null || !(l > 0)) null else Point2(0.0, 0.0) to Point2(l, 0.0)
        }
    }
    val previewPixelSize: Double? = run {
        val a = p1 ?: return@run null
        val b = p2 ?: return@run null
        val w = worldPoints ?: return@run null
        try { UAVTransform.pixelSize(a, b, w.first, w.second) } catch (_: Exception) { null }
    }

    fun compute() {
        val a = p1 ?: return
        val b = p2 ?: return
        val w = worldPoints ?: return
        val project = model.project ?: return
        error = null
        result = null
        busy = true
        scope.launch {
            try {
                val (p, o) = withContext(Dispatchers.Default) {
                    val copy = RiverProject(project.folder, project.settings.copy())
                    val frame = GeometryPipeline.loadFirstFrame(copy)
                    copy to GeometryPipeline.applyUAV(copy, a, b, w.first, w.second, frame)
                }
                model.replace(p)
                result = "Tamaño de píxel ${Fmt.num(o.pixelSize * 100, 3)} cm/px. Imagen rectificada guardada."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Geometría UAV: $e")
                model.reload()
            } finally {
                busy = false
            }
        }
    }

    val points = buildList {
        p1?.let { add(CanvasPoint(1, it, "1", Color.Red)) }
        p2?.let { add(CanvasPoint(2, it, "2", Color.Yellow)) }
    }
    val a = p1
    val b = p2
    val segments = if (a != null && b != null) listOf(CanvasSegment(a, b, Color.Yellow, 2f, dashed = true)) else emptyList()

    CanvasStepLayout(
        canvas = {
            if (model.firstFrame != null) {
                ImageCanvas(
                    model.firstFrame, model.frameSize, Modifier.fillMaxSize(), points = points, segments = segments,
                    onTap = { p -> if (p1 == null) p1 = p else if (p2 == null) p2 = p },
                    onMove = { id, p -> if (id == 1) p1 = p else p2 = p },
                )
            } else {
                NoFrameView()
            }
        },
        controls = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                FormSection {
                    FormText(
                        when {
                            p1 == null -> "Toca el punto 1 sobre la imagen (pellizca para ampliar)."
                            p2 == null -> "Toca el punto 2."
                            else -> "Arrastra los puntos para ajustarlos."
                        },
                    )
                    if (p1 != null || p2 != null) {
                        FormButton("Borrar puntos", Icons.Filled.Delete, destructive = true) {
                            p1 = null
                            p2 = null
                        }
                    }
                }
                FormSection("Medida real") {
                    SegmentedPicker(listOf("Distancia", "Coordenadas E/N"), if (useCoordinates) 1 else 0) { useCoordinates = it == 1 }
                    if (useCoordinates) {
                        NumberField("Este 1", e1, { e1 = it })
                        NumberField("Norte 1", n1, { n1 = it })
                        NumberField("Este 2", e2, { e2 = it })
                        NumberField("Norte 2", n2, { n2 = it })
                    } else {
                        NumberField("Distancia 1–2", lengthText, { lengthText = it })
                    }
                    previewPixelSize?.let { LabeledContent("Tamaño de píxel", "${Fmt.num(it * 100, 3)} cm/px") }
                }
                FormSection(footer = "Guardar una geometría nueva borra las secciones, la máscara y los resultados PIV, como en escritorio.") {
                    BusyFormButton(
                        "Calcular y guardar", Icons.Filled.CheckCircle, busy,
                        enabled = p1 != null && p2 != null && worldPoints != null,
                    ) { compute() }
                    result?.let { FormText(it) }
                    ErrorBanner(error)
                }
                RectifiedPreviewSection(model, ProjectFiles.transformedImage)
            }
        },
    )
}
