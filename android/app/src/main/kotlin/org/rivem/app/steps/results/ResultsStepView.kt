package org.rivem.app.steps.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import org.rivem.app.Autotest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.RivemApplication
import org.rivem.app.common.CanvasLayout
import org.rivem.app.common.CanvasStepLayout
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.common.ProgressRow
import org.rivem.app.common.QuiverBuilder
import org.rivem.app.common.QuiverField
import org.rivem.app.common.SwitchRow
import org.rivem.app.common.VelocityLegend
import org.rivem.app.common.shareFile
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.PipelineError
import org.rivem.app.pipeline.QualityPipeline
import org.rivem.app.pipeline.ResultsOutcome
import org.rivem.app.pipeline.ResultsPipeline
import org.rivem.app.pipeline.SectionAssessment
import org.rivem.app.pipeline.SectionResult
import org.rivem.app.pipeline.UncertaintyAssumptions
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import java.io.File
import kotlin.math.max

@Suppress("EnumEntryName")
private enum class CanvasMode(val label: String) { section("Sección"), vectors("Vectores") }

/**
 * Step 5, desktop "Results": discharge per section (`DischargeCalculator` with frame step = `video_range.step` and
 * fps = `video.fps`), options "interpolate" and "artificial seeding", totals, velocity and depth profiles, the
 * median PIV field over the first frame and the station table. Values are stored in SI and shown in the project's
 * unit system.
 */
@Composable
fun ResultsStepView(model: WorkflowModel) {
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as RivemApplication).preferences }
    val showQuality = preferences.showQuality
    val scope = rememberCoroutineScope()

    var results by remember { mutableStateOf<List<SectionResult>>(emptyList()) }
    var interpolate by rememberSaveable { mutableStateOf(false) }
    var seeding by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var field by remember { mutableStateOf<QuiverField?>(null) }
    var mode by rememberSaveable { mutableStateOf(CanvasMode.section) }
    var selected by remember { mutableStateOf<StationRef?>(null) }
    var reportFile by remember { mutableStateOf<File?>(null) }
    /** Inverse of the pixel → world matrix, to project desktop's chevron glyph (built in metres). */
    var worldToPixel by remember { mutableStateOf<Matrix3?>(null) }
    /** Traffic light and Q ± U of every section (RIVEM addition). */
    var assessments by remember { mutableStateOf<List<SectionAssessment>>(emptyList()) }
    var assumptions by remember { mutableStateOf(UncertaintyAssumptions()) }
    var pairCount by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val units: UnitDisplay = model.units

    LaunchedEffect(Unit) {
        val project = model.project ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        loaded = true
        val read = withContext(Dispatchers.IO) { ResultsPipeline.read(project) }
        results = read
        worldToPixel = withContext(Dispatchers.IO) { try { project.readMatrix()?.inverse() } catch (_: Exception) { null } }
        assumptions = UncertaintyAssumptions.from(project.settings)
        read.firstOrNull()?.let {
            interpolate = it.interpolated
            seeding = it.artificialSeeding
        }
        // CI screenshot hooks (iOS `Autotest.stationToSelect` / `resultsVectors`).
        val n = Autotest.stationToSelect
        val first = read.firstOrNull()
        if (n != null && first != null && n >= 1 && n <= first.stationCount) selected = StationRef(0, n - 1)
        if (Autotest.resultsVectors) mode = CanvasMode.vectors
        val width = model.frameSize.width.toDouble()
        val computed = withContext(Dispatchers.Default) {
            try {
                val piv = project.readPIVResults() ?: return@withContext null
                val matrix = try { project.readMatrix() } catch (_: Exception) { null }
                QuiverBuilder.quiver(piv, width, matrix, project.settings.frameInterval) to piv.pairCount
            } catch (e: Exception) {
                AppLog.error("Campo PIV de resultados: $e")
                null
            }
        }
        if (computed != null) {
            field = computed.first
            pairCount = computed.second
        }
    }

    // Traffic light and Q ± U (Monte Carlo, a few ms) from the results on screen, again when they or the
    // assumptions (saved in the project → revision) change.
    LaunchedEffect(showQuality, results, pairCount, model.revision) {
        val project = model.project
        if (!showQuality || project == null || results.isEmpty()) {
            assessments = emptyList()
            return@LaunchedEffect
        }
        val current = results
        val pairs = pairCount
        val a = withContext(Dispatchers.Default) { QualityPipeline.assess(project, current, pairs) }
        assessments = a
        for (s in a) {
            val doubtful = s.quality.doubtful.map { it.index + 1 }
            AppLog.info(
                "Calidad ${s.name}: ${QualityPipeline.levelText(s.quality.overall)}, Q = ${Fmt.num(s.uncertainty.discharge, 3)} " +
                    "± ${Fmt.num(s.uncertainty.expanded, 3)} m3/s (95 %), dudosas $doubtful",
            )
        }
    }

    fun assessment(name: String): SectionAssessment? = assessments.firstOrNull { it.name == name }

    fun recompute(edit: ((RiverProject) -> Unit)?) {
        val interp = interpolate
        val seed = seeding
        error = null
        busy = true
        reportFile = null
        scope.launch {
            try {
                val project = model.project ?: throw PipelineError("El proyecto no está abierto.")
                val done: Pair<RiverProject, ResultsOutcome> = withContext(Dispatchers.Default) {
                    val p = RiverProject(project.folder, project.settings.copy())
                    edit?.invoke(p)
                    p to ResultsPipeline.compute(p, interp, seed)
                }
                model.replace(done.first)
                results = done.second.results
                if (done.second.errors.isNotEmpty()) error = done.second.errors.joinToString("\n")
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Resultados: $e")
                model.reload()
            }
            busy = false
        }
    }

    fun toggleStation(section: Int, station: Int) {
        val r = results.getOrNull(section) ?: return
        val included = r.isChecked(station)
        val name = r.name
        recompute { p -> ResultsPipeline.setCheck(p, name, station, !included) }
    }

    fun saveAssumptions(new: UncertaintyAssumptions) {
        assumptions = new
        val settings = model.settings ?: return
        if (UncertaintyAssumptions.from(settings) == new) return
        try {
            model.update { p ->
                new.write(p.settings)
                p.save()
            }
        } catch (e: Exception) {
            error = "No se pudieron guardar los supuestos: ${e.userMessage()}"
        }
    }

    fun makeReport() {
        val project = model.project ?: return
        val quality = showQuality
        scope.launch {
            try {
                reportFile = withContext(Dispatchers.Default) { ReportPDF.make(context, project, quality) }
                error = null
            } catch (e: Exception) {
                error = "No se pudo crear el informe: ${e.userMessage()}"
                AppLog.error("Informe PDF: $e")
            }
        }
    }

    val canvas: @Composable () -> Unit = {
        if (model.firstFrame != null) {
            ResultsCanvas(
                model = model,
                results = results,
                field = field,
                mode = mode,
                onMode = { mode = it },
                selected = selected,
                onSelect = { selected = it },
                worldToPixel = worldToPixel,
                units = units,
            )
        } else {
            NoFrame()
        }
    }

    val formScroll = rememberScrollState()
    var qualityTop by remember { mutableStateOf<Int?>(null) }
    // CI hook `autotest_results_scroll quality`: the form opens scrolled to the quality section.
    LaunchedEffect(qualityTop) {
        val y = qualityTop
        if (Autotest.resultsScroll == "quality" && y != null) formScroll.scrollTo(y)
    }

    CanvasStepLayout(canvas = canvas) {
        Column(Modifier.fillMaxSize().verticalScroll(formScroll).imePadding()) {
            if (results.isNotEmpty()) {
                FormSection {
                    results.forEachIndexed { k, r ->
                        if (k > 0) FormDivider()
                        val a = assessment(r.name)
                        DischargeHero(r, units, results.size > 1, a?.uncertainty, a?.quality?.overall)
                    }
                    FormText("Toca una estación en la imagen para ver su detalle.", small = true)
                }
            }
            assessments.forEachIndexed { k, a ->
                if (k == 0) Spacer(Modifier.height(0.dp).onGloballyPositioned { qualityTop = it.positionInParent().y.toInt() })
                QualitySection(a, units, assessments.size > 1) { station ->
                    val si = results.indexOfFirst { it.name == a.name }
                    if (si >= 0) {
                        mode = CanvasMode.section
                        selected = StationRef(si, station)
                    }
                }
            }
            if (results.isNotEmpty() && showQuality) {
                UncertaintyAssumptionsSection(assumptions, units) { saveAssumptions(it) }
            }
            FormSection("Opciones de cálculo") {
                SwitchRow("Interpolar estaciones sin dato", interpolate) { interpolate = it }
                FormDivider()
                SwitchRow("Siembra artificial (perfil filtrado por gradiente)", seeding) { seeding = it }
                FormDivider()
                if (busy) {
                    ProgressRow("Calculando…")
                } else {
                    FormButton("Recalcular caudal", Icons.Filled.Refresh) { recompute(null) }
                }
                FormDivider()
                UnitPicker(model)
                ErrorBanner(error)
            }
            if (results.isEmpty() && !busy) {
                FormSection { FormText("Sin resultados de caudal. Pulsa Recalcular.") }
            }
            results.forEachIndexed { si, r ->
                val doubtful = assessment(r.name)?.quality?.doubtful?.map { it.index }?.toSet() ?: emptySet()
                SectionResultView(r, units, enabled = !busy, doubtful = doubtful) { station -> toggleStation(si, station) }
            }
            if (results.size > 1) {
                FormSection("Todas las secciones") {
                    val qs = results.mapNotNull { it.totalQ }.filter { it.isFinite() }
                    val mean = if (qs.isEmpty()) Double.NaN else qs.sum() / qs.size
                    LabeledContent("Caudal medio", units.dischargeText(mean))
                }
            }
            if (results.isNotEmpty()) {
                FormSection("Informe") {
                    FormButton("Generar informe PDF", Icons.Filled.Description, enabled = !busy) { makeReport() }
                    reportFile?.let { file ->
                        FormDivider()
                        FormButton("Compartir ${file.name}", Icons.Filled.Share) { shareFile(context, file, "application/pdf") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoFrame() {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Photo, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
        Text("Sin frames", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Extrae los frames del video en el paso 1.", color = Color.Gray, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** First frame with the animated chevrons (Sección) or the median PIV field (Vectores); tap a station for its card. */
@Composable
private fun ResultsCanvas(
    model: WorkflowModel,
    results: List<SectionResult>,
    field: QuiverField?,
    mode: CanvasMode,
    onMode: (CanvasMode) -> Unit,
    selected: StationRef?,
    onSelect: (StationRef?) -> Unit,
    worldToPixel: Matrix3?,
    units: UnitDisplay,
) {
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val range = remember(results) { ChevronLayer.stationRange(results) }
    val fieldSegments = remember(field) { field?.let { QuiverBuilder.segments(it.vectors, it.range) } ?: emptyList() }
    val sectionLayer: DrawScope.(CanvasLayout, Double) -> Unit = { layout, time ->
        val d = density
        drawIntoCanvas { c -> ChevronLayer.draw(c.nativeCanvas, layout, results, worldToPixel, range, time, selected, d, accent) }
    }
    val layer: (DrawScope.(CanvasLayout, Double) -> Unit)? = if (mode == CanvasMode.section) sectionLayer else null
    Box(Modifier.fillMaxSize()) {
        ImageCanvas(
            image = model.firstFrame,
            imageSize = model.frameSize,
            modifier = Modifier.fillMaxSize(),
            segments = if (mode == CanvasMode.vectors) fieldSegments else emptyList(),
            onTap = { p: Point2 ->
                if (mode == CanvasMode.section) {
                    val radius = 0.04 * max(model.frameSize.width, model.frameSize.height)
                    onSelect(ChevronLayer.hit(p, results, radius))
                }
            },
            animated = layer,
        )
        if (selected == null) {
            SingleChoiceSegmentedButtonRow(
                Modifier.align(Alignment.TopStart).padding(8.dp).width(210.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
            ) {
                CanvasMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = CanvasMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = Color.White,
                        ),
                    ) { Text(m.label, maxLines = 1) }
                }
            }
        }
        if (selected != null && selected.section < results.size) {
            StationCard(results[selected.section], selected.station, units, onClose = { onSelect(null) }, modifier = Modifier.align(Alignment.TopCenter))
        }
        if (mode == CanvasMode.section && results.isNotEmpty()) {
            VelocityLegend(range, units, Modifier.align(Alignment.BottomStart))
        } else if (mode == CanvasMode.vectors && field != null && field.vectors.isNotEmpty()) {
            VelocityLegend(field.range, units, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** SI / imperial display preference (`settings.unit_system`). */
@Composable
private fun UnitPicker(model: WorkflowModel) {
    val current = model.settings?.unitSystem ?: UnitSystem.si
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Unidades", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val options = listOf(UnitSystem.si to "SI (m, m³/s)", UnitSystem.imperial to "Imperial (ft, ft³/s)")
            options.forEachIndexed { i, (system, label) ->
                SegmentedButton(
                    selected = current == system,
                    onClick = {
                        if (current != system) {
                            try {
                                model.update { p ->
                                    p.settings.unitSystem = system
                                    p.save()
                                }
                            } catch (e: Exception) {
                                AppLog.error("Unidades: $e")
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label, maxLines = 1, fontSize = 12.sp) }
            }
        }
    }
}

/** Totals, charts and table of one section. */
@Composable
private fun SectionResultView(result: SectionResult, units: UnitDisplay, enabled: Boolean, doubtful: Set<Int>, onToggle: (Int) -> Unit) {
    FormSection(result.name) {
        Text(
            "Caudal ${units.dischargeText(result.totalQ)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LabeledContent("Área", units.areaText(result.totalA))
        LabeledContent("Ancho", units.lengthText(result.totalW))
        LabeledContent("Velocidad media", units.velocityText(result.meanV, 3))
        LabeledContent("Velocidad superficial media", units.velocityText(result.meanVs, 3))
        LabeledContent("Caudal medido", Fmt.percent(result.measuredQ))
        result.alpha?.let { LabeledContent("α", Fmt.num(it, 2)) }
        result.level?.let { LabeledContent("Nivel", units.lengthText(it, 3)) }
        FormDivider()
        ChartView(150.dp) { c, rect, style -> Charts.dischargeBars(c, rect, result, units, style) }
        ChartView(170.dp) { c, rect, style -> Charts.velocityProfile(c, rect, result, units, style) }
        ChartView(140.dp) { c, rect, style -> Charts.depthProfile(c, rect, result, units, style) }
    }
    FormSection(
        "Estaciones · ${result.name}",
        footer = "Desmarca una estación para sacarla del caudal (con «Interpolar» se rellena desde sus vecinas). Se guarda en el proyecto, igual que en RIVeR escritorio. ⚠︎ = estación dudosa (ver Calidad del aforo).",
    ) {
        StationTable(result, units, enabled, doubtful, onToggle)
    }
}

@Composable
private fun StationTable(result: SectionResult, units: UnitDisplay, enabled: Boolean, doubtful: Set<Int>, onToggle: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        StationRow(null, listOf("#", "x", "d", "A", "Vs", "Q"), header = true, flagged = false, enabled = enabled) {}
        for (i in 0 until result.stationCount) {
            fun cell(a: List<Double>, convert: (Double) -> Double): String = result.value(a, i)?.let { Fmt.num(convert(it), 2) } ?: "—"
            val cells = listOf(
                "${i + 1}",
                cell(result.distance) { units.length(it) },
                cell(result.depth) { units.length(it) },
                cell(result.area) { units.area(it) },
                cell(result.velocity) { units.velocity(it) },
                cell(result.q) { units.discharge(it) },
            )
            StationRow(result.isChecked(i), cells, header = false, flagged = i in doubtful, enabled = enabled) { onToggle(i) }
        }
        Text(
            "x, d en ${units.lengthUnit} · A en ${units.areaUnit} · Vs en ${units.velocityUnit} · Q en ${units.dischargeUnit}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StationRow(check: Boolean?, cells: List<String>, header: Boolean, flagged: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val base = TextStyle(
        fontSize = 12.sp,
        fontFamily = if (header) FontFamily.Default else FontFamily.Monospace,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    )
    Row(Modifier.fillMaxWidth().heightIn(min = if (header) 24.dp else 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (check != null) {
                IconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (check) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = if (check) "Estación incluida" else "Estación excluida",
                        tint = if (check) MaterialTheme.colorScheme.primary else secondary,
                    )
                }
            }
        }
        cells.forEachIndexed { k, text ->
            val color = when {
                k == 0 && flagged -> QualityColors.orange
                check == false -> secondary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                if (k == 0 && flagged) "$text⚠︎" else text,
                style = base,
                color = color,
                textAlign = if (k == 0) TextAlign.Start else TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            )
        }
    }
}
