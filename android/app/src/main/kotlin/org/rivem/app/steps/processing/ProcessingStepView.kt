package org.rivem.app.steps.processing

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.common.CanvasStepLayout
import org.rivem.app.common.DisclosureRow
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.FramePlayerBar
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.common.NumberFieldRow
import org.rivem.app.common.QuiverBuilder
import org.rivem.app.common.SwitchRow
import org.rivem.app.common.VelocityLegend
import org.rivem.app.common.rememberFrameSequence
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.CancellationFlag
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.ProcessingOutcome
import org.rivem.app.pipeline.ProcessingPipeline
import org.rivem.app.pipeline.ResultsOutcome
import org.rivem.app.pipeline.ResultsPipeline
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.geometry.Matrix3
import org.rivem.core.piv.PIVError
import org.rivem.core.piv.PIVFailedPair
import org.rivem.core.piv.PIVParameters
import org.rivem.core.project.PIVResults
import org.rivem.core.project.RiverProject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** "12 s" / "3 min 05 s". */
fun durationText(seconds: Double): String {
    val s = seconds.roundToInt()
    if (s < 60) return "$s s"
    return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60)
}

/**
 * Step 4, desktop "Processing" + "Analyze all": PIV parameters (desktop defaults, advanced filters), full run in the
 * background with progress, ETA and cancel; the screen stays awake meanwhile. Afterwards the discharge of every
 * section is computed.
 */
@Composable
fun ProcessingStepView(model: WorkflowModel) {
    CanvasStepLayout(canvas = { PairPreview(model) }) { ProcessingForm(model) }
}

@Composable
private fun ProcessingForm(model: WorkflowModel) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var ia1 by rememberSaveable { mutableIntStateOf(128) }
    var clahe by rememberSaveable { mutableStateOf(true) }
    var clipLimit by rememberSaveable { mutableStateOf("5") }
    var removeBackground by rememberSaveable { mutableStateOf(false) }
    var stdFiltering by rememberSaveable { mutableStateOf(true) }
    var stdThreshold by rememberSaveable { mutableStateOf("4") }
    var medianFiltering by rememberSaveable { mutableStateOf(true) }
    var medianEpsilon by rememberSaveable { mutableStateOf("0.02") }
    var medianThreshold by rememberSaveable { mutableStateOf("2") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val cancel = remember { FlagHolder() }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<List<PIVFailedPair>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model.revision) {
        if (loaded) return@LaunchedEffect
        val s = model.settings ?: return@LaunchedEffect
        loaded = true
        val p = ProcessingPipeline.parameters(s)
        ia1 = if (p.interrogationArea1 in ProcessingPipeline.interrogationAreas) p.interrogationArea1 else 128
        clahe = p.clahe
        clipLimit = Fmt.upTo(p.clipLimit, 4)
        removeBackground = p.removeBackground
        stdFiltering = p.stdFiltering
        stdThreshold = Fmt.upTo(p.stdThreshold, 4)
        medianFiltering = p.medianTestFiltering
        medianEpsilon = Fmt.upTo(p.medianTestEpsilon, 4)
        medianThreshold = Fmt.upTo(p.medianTestThreshold, 4)
    }

    // Leaving the step cancels the run; the screen stays on while it runs.
    DisposableEffect(Unit) { onDispose { cancel.flag?.cancel() } }
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(running) {
        while (running) {
            now = System.nanoTime()
            delay(1000)
        }
    }

    fun parameters(): PIVParameters? {
        val clip = Fmt.parse(clipLimit)
        val std = Fmt.parse(stdThreshold)
        val eps = Fmt.parse(medianEpsilon)
        val med = Fmt.parse(medianThreshold)
        if (clip == null || std == null || eps == null || med == null) {
            error = "Revisa los números de los parámetros."
            return null
        }
        return PIVParameters(
            interrogationArea1 = ia1, removeBackground = removeBackground, clahe = clahe, clipLimit = clip,
            stdFiltering = stdFiltering, stdThreshold = std, medianTestFiltering = medianFiltering,
            medianTestEpsilon = eps, medianTestThreshold = med,
        )
    }

    fun run() {
        val project = model.project ?: return
        error = null
        val params = parameters() ?: return
        try {
            params.validate()
        } catch (e: Exception) {
            error = e.userMessage()
            return
        }
        val flag = CancellationFlag()
        cancel.flag = flag
        done = 0
        total = max(model.frameCount - 1, 0)
        startedAt = System.nanoTime()
        now = startedAt
        running = true
        summary = null
        failed = emptyList()
        val main = Handler(Looper.getMainLooper())
        val units = model.units
        scope.launch {
            try {
                val result: Triple<RiverProject, ProcessingOutcome, ResultsOutcome?> = withContext(Dispatchers.Default) {
                    val p = RiverProject(project.folder, project.settings.copy())
                    val o = ProcessingPipeline.run(p, params, progress = { d, t ->
                        main.post {
                            done = d
                            total = t
                        }
                    }, cancel = flag)
                    // Discharge with desktop's default options (no interpolation, no artificial seeding).
                    val r = try { ResultsPipeline.compute(p, interpolate = false, artificialSeeding = false) } catch (_: Exception) { null }
                    Triple(p, o, r)
                }
                model.replace(result.first)
                val o = result.second
                failed = o.failed
                var text = "${o.pairs} pares en ${durationText(o.seconds)}, rejilla ${o.rows}×${o.columns}"
                text += if (o.failed.isEmpty()) ", sin pares fallidos." else ", ${o.failed.size} pares fallidos."
                result.third?.let { r ->
                    val totalQ = r.results.mapNotNull { it.totalQ }.sum()
                    if (r.results.isNotEmpty()) text += " Caudal calculado: ${units.dischargeText(totalQ)}."
                    if (r.errors.isNotEmpty()) text += " " + r.errors.joinToString("; ")
                }
                summary = text
            } catch (e: PIVError.Cancelled) {
                summary = "Procesamiento cancelado: no se guardaron resultados."
                model.reload()
            } catch (e: kotlinx.coroutines.CancellationException) {
                flag.cancel()
                throw e
            } catch (e: Throwable) {
                error = if (e is OutOfMemoryError) "Sin memoria: prueba con frames más chicos (factor de escala en el paso 1)." else e.userMessage()
                AppLog.error("Procesamiento: $e")
                model.reload()
            } finally {
                cancel.flag = null
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        FormSection("Entrada") {
            LabeledContent("Frames", "${model.frameCount} (${max(model.frameCount - 1, 0)} pares)")
            if (model.frameSize.width > 0) LabeledContent("Tamaño", "${model.frameSize.width} × ${model.frameSize.height} px")
            model.settings?.frameInterval?.let { LabeledContent("Δt entre frames", "${Fmt.num(it * 1000, 1)} ms") }
            LabeledContent("Máscara de secciones", if (model.hasMask) "sí" else "falta (guarda las secciones)")
            if (model.hasResults) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Hay resultados PIV guardados", color = Color(0xFF34C759))
                }
            }
        }
        FormSection("Ventana de interrogación") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Ventana (paso 1)", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    val areas = ProcessingPipeline.interrogationAreas
                    areas.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = ia1 == v,
                            onClick = { ia1 = v },
                            enabled = !running,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = areas.size),
                        ) { Text("$v px", maxLines = 1, fontSize = 12.sp) }
                    }
                }
            }
            FormDivider()
            LabeledContent("Ventana (paso 2)", "${ia1 / 2} px")
        }
        FormSection("Preprocesamiento") {
            SwitchRow("CLAHE (realce de contraste)", clahe, enabled = !running) { clahe = it }
            if (clahe) {
                FormDivider()
                NumberFieldRow("Límite de recorte", clipLimit, { clipLimit = it }, enabled = !running)
            }
            FormDivider()
            SwitchRow("Quitar fondo (media de los frames)", removeBackground, enabled = !running) { removeBackground = it }
        }
        FormSection {
            DisclosureRow("Filtros de vectores (avanzado)", filtersOpen, { filtersOpen = !filtersOpen }) {
                SwitchRow("Filtro de desviación estándar", stdFiltering, enabled = !running) { stdFiltering = it }
                NumberFieldRow("Umbral (σ)", stdThreshold, { stdThreshold = it }, enabled = !running)
                SwitchRow("Test de la mediana", medianFiltering, enabled = !running) { medianFiltering = it }
                NumberFieldRow("Épsilon", medianEpsilon, { medianEpsilon = it }, enabled = !running)
                NumberFieldRow("Umbral", medianThreshold, { medianThreshold = it }, enabled = !running)
            }
        }
        FormSection(
            footer = "La pantalla no se apaga mientras procesa. Con frames grandes (4K) los pares se procesan de a uno para no agotar la memoria.",
        ) {
            if (running) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Pares procesados: $done de $total", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val elapsed = (now - startedAt) / 1e9
                    if (done > 0 && total > done && elapsed > 0) {
                        val eta = elapsed / done * (total - done)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tiempo restante estimado: ${durationText(eta)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FormDivider()
                FormButton("Cancelar", destructive = true) { cancel.flag?.cancel() }
            } else {
                FormButton(
                    if (model.hasResults) "Volver a procesar" else "Procesar todos los frames",
                    Icons.Filled.PlayArrow,
                    enabled = model.frameCount >= 2 && model.hasMask,
                ) { run() }
            }
            summary?.let { FormText(it, color = MaterialTheme.colorScheme.onSurface) }
            ErrorBanner(error)
        }
        if (failed.isNotEmpty()) {
            FormSection("Pares fallidos (${failed.size})") {
                for (f in failed) FormText("Par ${f.index}: ${f.reason}", small = true)
            }
        }
    }
}

/**
 * The frames with the PIV vectors (desktop's processing carousel): play / scrub the frames; "Mediana" shows the
 * median field, "Par" the field of the pair starting at the frame on screen. Colours on the median field's range.
 */
@Composable
private fun PairPreview(model: WorkflowModel) {
    val frames = rememberFrameSequence()
    var piv by remember { mutableStateOf<PIVResults?>(null) }
    var matrix by remember { mutableStateOf<Matrix3?>(null) }
    var scale by remember { mutableDoubleStateOf(10.0) }
    var range by remember { mutableStateOf(0.0..1.0) }
    var median by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(model.revision) {
        val project = model.project ?: return@LaunchedEffect
        val dt = project.settings.frameInterval ?: 0.1
        frames.configure(project.frameFiles, if (dt > 0) 1 / dt else 10.0)
        if (!model.hasResults) {
            piv = null
            return@LaunchedEffect
        }
        val width = model.frameSize.width.toDouble()
        val loaded = withContext(Dispatchers.Default) {
            try {
                val p = project.readPIVResults() ?: return@withContext null
                val m = try { project.readMatrix() } catch (_: Exception) { null }
                val s = QuiverBuilder.scale(p, width)
                val r = QuiverBuilder.range(QuiverBuilder.vectors(p, null, s, m, project.settings.frameInterval))
                PreviewField(p, m, s, r)
            } catch (e: Exception) {
                AppLog.error("Vista previa PIV: $e")
                null
            }
        }
        if (loaded != null) {
            piv = loaded.piv
            matrix = loaded.matrix
            scale = loaded.scale
            range = loaded.range
        }
    }

    val p = piv
    val pair: Int? = if (p == null || median) null else min(frames.index, max(p.pairCount - 1, 0))
    val interval = model.settings?.frameInterval
    val segments = remember(p, pair, scale, range, matrix, interval) {
        if (p == null) emptyList() else QuiverBuilder.segments(QuiverBuilder.vectors(p, pair, scale, matrix, interval), range)
    }
    val caption = if (p == null || median) "${frames.index + 1}/${frames.count}" else "par ${(pair ?: 0) + 1}/${p.pairCount}"

    Box(Modifier.fillMaxSize()) {
        ImageCanvas(
            image = frames.image ?: model.firstFrame,
            imageSize = model.frameSize,
            modifier = Modifier.fillMaxSize(),
            segments = segments,
        )
        FramePlayerBar(frames, Modifier.align(Alignment.TopCenter).fillMaxWidth(), caption = caption) {
            if (p != null) {
                OutlinedButton(
                    onClick = { median = !median },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(if (median) "Mediana" else "Par", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (p != null) VelocityLegend(range, model.units, Modifier.align(Alignment.BottomStart))
    }
}

private class PreviewField(val piv: PIVResults, val matrix: Matrix3?, val scale: Double, val range: ClosedFloatingPointRange<Double>)

/** The flag of the run in progress, kept across recompositions. */
private class FlagHolder {
    @Volatile var flag: CancellationFlag? = null
}
