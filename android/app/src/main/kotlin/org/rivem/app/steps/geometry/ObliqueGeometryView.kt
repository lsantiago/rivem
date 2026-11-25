package org.rivem.app.steps.geometry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
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
import org.rivem.app.pipeline.ObliqueOutcome
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.geometry.ObliqueDistances
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.SurveyedDistances
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import java.util.Locale
import kotlin.math.min

private val distanceLabels = listOf("d12", "d23", "d34", "d41", "d13 (diagonal)", "d24 (diagonal)")
private val pointColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan)

/**
 * Oblique camera (desktop "Control Points"): four points on the water plane and the six tape distances
 * d12, d23, d34, d41, d13, d24 → `control_points`, matrix, `transformation{resolution, extent, roi}`,
 * `transformed_image.png`; shows the distance residuals and the reprojection of each point. The distances can be
 * measured with the phone (ARCore, [ARDistanceDialog]) or imported from a CSV.
 */
@Composable
fun ObliqueGeometryView(model: WorkflowModel) {
    var points by remember { mutableStateOf(listOf<Point2?>(null, null, null, null)) }
    var distances by remember { mutableStateOf(List(6) { "" }) }
    var outcome by remember { mutableStateOf<ObliqueOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var measuringAR by remember { mutableStateOf(false) }
    /** Last AR survey (shown until the distances are edited by hand). */
    var arSurvey by remember { mutableStateOf<SurveyedDistances?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val cp = model.settings?.controlPoints ?: return@LaunchedEffect
        if (cp.pixels.size != 4 || cp.distances.size != 6) return@LaunchedEffect
        points = cp.pixels
        distances = cp.distances.map { Fmt.num(it, 3) }
    }

    val picker = rememberTextFilePicker(
        onPicked = { text, _ ->
            try {
                val feet = model.settings?.unitSystem == UnitSystem.imperial
                val d = GeometryPipeline.parseDistances(text, feet)
                distances = d.values.map { Fmt.num(it, 3) }
                arSurvey = null
                error = null
            } catch (e: Exception) {
                error = "No se pudieron leer las distancias: ${e.userMessage()}"
            }
        },
        onError = { error = "No se pudieron leer las distancias: $it" },
    )

    val parsedDistances: ObliqueDistances? = run {
        val values = distances.mapNotNull { Fmt.parse(it) }
        if (values.size == 6 && values.all { it >= 0 }) ObliqueDistances(values) else null
    }

    fun compute() {
        val pixels = points.filterNotNull()
        val d = parsedDistances ?: return
        if (pixels.size != 4) return
        val project = model.project ?: return
        error = null
        busy = true
        scope.launch {
            try {
                val (p, o) = withContext(Dispatchers.Default) {
                    val copy = RiverProject(project.folder, project.settings.copy())
                    val frame = GeometryPipeline.loadFirstFrame(copy)
                    copy to GeometryPipeline.applyOblique(copy, pixels, d, frame)
                }
                model.replace(p)
                outcome = o
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Geometría oblicua: $e")
                model.reload()
            } finally {
                busy = false
            }
        }
    }

    val canvasPoints = (0 until 4).mapNotNull { i -> points[i]?.let { CanvasPoint(i, it, "${i + 1}", pointColors[i]) } }
    val canvasSegments = buildList {
        val pairs = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 0 to 2, 1 to 3)
        for ((k, pair) in pairs.withIndex()) {
            val a = points[pair.first]
            val b = points[pair.second]
            if (a != null && b != null) {
                add(CanvasSegment(a, b, if (k < 4) Color.Yellow else Color(0xFFFF9500), 1.5f, dashed = k >= 4))
            }
        }
    }
    val next = points.indexOfFirst { it == null }

    CanvasStepLayout(
        canvas = {
            if (model.firstFrame != null) {
                ImageCanvas(
                    model.firstFrame, model.frameSize, Modifier.fillMaxSize(), points = canvasPoints, segments = canvasSegments,
                    onTap = { p ->
                        val i = points.indexOfFirst { it == null }
                        if (i >= 0) points = points.toMutableList().also { it[i] = p }
                    },
                    onMove = { id, p -> if (id in 0..3) points = points.toMutableList().also { it[id] = p } },
                )
            } else {
                NoFrameView()
            }
        },
        controls = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                FormSection {
                    FormText(
                        if (next >= 0) "Toca el punto ${next + 1} sobre la imagen (pellizca para ampliar)."
                        else "Arrastra los puntos para ajustarlos.",
                    )
                    if (points.any { it != null }) {
                        FormButton("Borrar puntos", Icons.Filled.Delete, destructive = true) { points = listOf(null, null, null, null) }
                    }
                }
                val survey = arSurvey
                FormSection(
                    "Distancias medidas (m)",
                    footer = if (survey != null) {
                        "Medidas con AR (horizontales; desnivel entre puntos ${Fmt.num(survey.heightSpread * 100, 0)} cm). " +
                            "Contrasta al menos una con cinta: más allá de ~5 m la precisión baja."
                    } else {
                        "Con cinta, o con «Medir con la cámara»: apuntas a los 4 puntos en orden y el teléfono mide las 6 distancias."
                    },
                ) {
                    for (i in 0 until 6) {
                        NumberField(distanceLabels[i], distances[i], { v ->
                            distances = distances.toMutableList().also { it[i] = v }
                            arSurvey = null
                        })
                    }
                    FormButton("Medir con la cámara (AR)", Icons.Filled.CameraAlt) { measuringAR = true }
                    FormButton("Importar distancias (CSV)", Icons.Filled.UploadFile) { picker.launchTextPicker() }
                }
                FormSection(
                    footer = "Orden de los puntos: 1 y 2 en una orilla, 3 y 4 en la otra, recorriendo el cuadrilátero. " +
                        "Guardar borra secciones, máscara y resultados.",
                ) {
                    BusyFormButton(
                        "Calcular y guardar", Icons.Filled.CheckCircle, busy,
                        enabled = points.all { it != null } && parsedDistances != null,
                    ) { compute() }
                    ErrorBanner(error)
                }
                outcome?.let { ObliqueResidualsSection(it) }
                RectifiedPreviewSection(model, ProjectFiles.transformedImage)
            }
        },
    )

    if (measuringAR) {
        ARDistanceDialog(
            onUse = { s ->
                distances = s.distances.values.map { Fmt.num(it, 3) }
                arSurvey = s
                error = null
                AppLog.info(
                    "Distancias medidas con AR: " + s.distances.values.joinToString(", ") { Fmt.num(it, 3) } +
                        String.format(Locale.ROOT, " m, desnivel %.2f m", s.heightSpread),
                )
            },
            onDismiss = { measuringAR = false },
        )
    }
}

@Composable
private fun ObliqueResidualsSection(outcome: ObliqueOutcome) {
    val names = listOf("d12", "d23", "d34", "d41", "d13", "d24")
    FormSection("Control de calidad") {
        outcome.solution.distanceResiduals.forEachIndexed { i, r ->
            LabeledContent("Residuo ${names[min(i, 5)]}", "${Fmt.num(r * 100, 1)} cm")
        }
        outcome.reprojection.forEachIndexed { i, r ->
            LabeledContent("Reproyección punto ${i + 1}", "${Fmt.num(r * 100, 2)} cm")
        }
        FormText("Residuos grandes indican distancias inconsistentes con un cuadrilátero plano o puntos mal marcados.", small = true)
    }
}
