package org.rivem.app.steps.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.SectionDraft
import org.rivem.app.pipeline.SectionsPipeline
import org.rivem.app.pipeline.userMessage
import org.rivem.app.steps.geometry.BusyFormButton
import org.rivem.app.steps.geometry.NoFrameView
import org.rivem.app.steps.geometry.NumberField
import org.rivem.app.steps.geometry.launchTextPicker
import org.rivem.app.steps.geometry.rememberTextFilePicker
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.Footage
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import kotlin.math.abs
import kotlin.math.max

private val palette = listOf(Color.Yellow, Color.Cyan, Color(0xFFFF2D55), Color.Green, Color(0xFFFF9500))
private val warning = Color(0xFFFF9500)

/**
 * Step 3, desktop "Cross Sections": draw each section with two taps (left bank first), import or build its
 * bathymetry, set level, number of stations, alpha and left-bank station; saving writes `xsections.json`, the
 * bathymetry CSVs, `mask.npy`, `mask.png`, `bbox.json` and `processing.roi_height`. The ROI mask is drawn over the
 * frame once saved.
 */
@Composable
fun SectionsStepView(model: WorkflowModel) {
    var drafts by remember { mutableStateOf<List<SectionDraft>>(emptyList()) }
    var selectedID by remember { mutableStateOf<String?>(null) }
    var bathymetries by remember { mutableStateOf<Map<String, Bathymetry>>(emptyMap()) }
    var editingBathymetry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val project = model.project ?: return@LaunchedEffect
        val sections = try { project.readCrossSections() } catch (_: Exception) { null }
        drafts = if (sections != null && sections.sections.isNotEmpty()) SectionsPipeline.drafts(sections) else listOf(SectionDraft(name = "CS_1"))
        selectedID = drafts.firstOrNull()?.id
        val loaded = HashMap<String, Bathymetry>()
        for (d in drafts) {
            val f = d.bathymetryFile ?: continue
            if (f in loaded) continue
            try { loaded[f] = SectionsPipeline.readBathymetry(project, f) } catch (_: Exception) {}
        }
        bathymetries = loaded
    }

    val selectedIndex: Int? = drafts.indexOfFirst { it.id == selectedID }.takeIf { it >= 0 }
    fun updateSelected(change: (SectionDraft) -> SectionDraft) {
        val i = drafts.indexOfFirst { it.id == selectedID }
        if (i < 0) return
        drafts = drafts.toMutableList().also { it[i] = change(it[i]) }
    }

    val picker = rememberTextFilePicker(
        onPicked = { text, name ->
            val project = model.project
            if (project != null && selectedIndex != null) {
                try {
                    val feet = project.settings.unitSystem == UnitSystem.imperial
                    val imported = SectionsPipeline.importBathymetry(text, name, project.folder, feet)
                    bathymetries = bathymetries + (imported.file to imported.bathymetry)
                    updateSelected { it.copy(bathymetryFile = imported.file, level = null) }
                    error = null
                } catch (e: Exception) {
                    error = "No se pudo importar la batimetría: ${e.userMessage()}"
                }
            }
        },
        onError = { error = "No se pudo importar la batimetría: $it" },
    )

    fun useEditedBathymetry(b: Bathymetry, level: Double?) {
        val i = selectedIndex ?: return
        val project = model.project ?: return
        try {
            val name = SectionsPipeline.editorFileName(drafts[i].name)
            val file = SectionsPipeline.writeBathymetry(b, name, project.folder)
            bathymetries = bathymetries + (file to SectionsPipeline.readBathymetry(project, file))
            updateSelected { it.copy(bathymetryFile = file, level = level) }
            error = null
        } catch (e: Exception) {
            error = "No se pudo guardar la batimetría: ${e.userMessage()}"
        }
    }

    fun save() {
        val list = drafts
        val project = model.project ?: return
        error = null
        message = null
        busy = true
        scope.launch {
            try {
                val (p, o) = withContext(Dispatchers.Default) {
                    val copy = RiverProject(project.folder, project.settings.copy())
                    copy to SectionsPipeline.save(copy, list, SectionsPipeline.windowSize(copy))
                }
                model.replace(p)
                val saved = SectionsPipeline.drafts(o.sections)
                val position = selectedIndex ?: 0
                drafts = saved
                selectedID = saved.getOrNull(position)?.id ?: saved.firstOrNull()?.id
                val box = o.bbox.joinToString(", ") { Fmt.num(it, 0) }
                message = "Guardadas ${saved.size} secciones. Altura de la ROI ${Fmt.num(o.heightROI, 1)} m, recuadro [$box] px."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Secciones: $e")
                model.reload()
            } finally {
                busy = false
            }
        }
    }

    val canvasPoints = selectedIndex?.let { i ->
        buildList {
            drafts[i].left?.let { add(CanvasPoint(0, it, "I", Color.Red)) }
            drafts[i].right?.let { add(CanvasPoint(1, it, "D", Color.Red)) }
        }
    } ?: emptyList()
    val canvasSegments = drafts.mapIndexedNotNull { k, d ->
        val l = d.left
        val r = d.right
        if (l == null || r == null) null
        else CanvasSegment(l, r, palette[k % palette.size], if (d.id == selectedID) 3f else 2f, arrow = true)
    }

    val missing: String? = drafts.mapNotNull { d ->
        val m = ArrayList<String>()
        if (d.left == null || d.right == null) m.add("la línea (dos toques sobre la imagen)")
        if (d.bathymetryFile == null) m.add("la batimetría")
        if (m.isEmpty()) null else "${d.name}: falta " + m.joinToString(" y ")
    }.takeIf { it.isNotEmpty() }?.joinToString("\n")

    CanvasStepLayout(
        canvas = {
            if (model.firstFrame != null) {
                ImageCanvas(
                    model.firstFrame, model.frameSize, Modifier.fillMaxSize(), points = canvasPoints,
                    segments = canvasSegments, overlay = model.maskOverlay,
                    onTap = { p ->
                        updateSelected { d ->
                            when {
                                d.left == null -> d.copy(left = p)
                                d.right == null -> d.copy(right = p)
                                else -> d
                            }
                        }
                    },
                    onMove = { id, p -> updateSelected { d -> if (id == 0) d.copy(left = p) else d.copy(right = p) } },
                )
            } else {
                NoFrameView()
            }
        },
        controls = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                SectionPicker(
                    drafts, selectedID, select = { selectedID = it },
                    add = {
                        // Reuse the previous bathymetry by default (several sections over the same profile is common).
                        val d = SectionDraft(name = "CS_${drafts.size + 1}", bathymetryFile = drafts.lastOrNull()?.bathymetryFile)
                        drafts = drafts + d
                        selectedID = d.id
                    },
                    remove = {
                        val i = selectedIndex
                        if (i != null) {
                            drafts = drafts.toMutableList().also { it.removeAt(i) }
                            selectedID = drafts.firstOrNull()?.id
                        }
                    },
                )
                if (selectedIndex != null) {
                    val d = drafts[selectedIndex]
                    FormSection {
                        FormText(instruction(d))
                        if (d.left != null || d.right != null) {
                            FormButton("Borrar línea", Icons.Filled.Delete, destructive = true) {
                                updateSelected { it.copy(left = null, right = null) }
                            }
                        }
                    }
                    SectionDraftEditor(
                        d, d.bathymetryFile?.let { bathymetries[it] },
                        isIpcam = model.footage == Footage.ipcam, isFirst = selectedIndex == 0,
                        onChange = { change -> updateSelected(change) },
                        importBathymetry = { picker.launchTextPicker() },
                        editBathymetry = { editingBathymetry = true },
                    )
                }
                FormSection(
                    footer = "La máscara de la región de interés se arma con un rectángulo alrededor de cada sección (altura " +
                        "recomendada como en escritorio). Guardar descarta los resultados PIV anteriores.",
                ) {
                    BusyFormButton(
                        "Guardar secciones y máscara", Icons.Filled.Save, busy,
                        enabled = drafts.isNotEmpty() && drafts.all { it.isComplete },
                    ) { save() }
                    missing?.let { FormText(it, color = warning) }
                    message?.let { FormText(it) }
                    ErrorBanner(error)
                }
            }
        },
    )

    if (editingBathymetry && selectedIndex != null) {
        val d = drafts[selectedIndex]
        BathymetryEditorDialog(
            sectionName = d.name,
            initial = d.bathymetryFile?.let { bathymetries[it] },
            onSave = { b, level -> useEditedBathymetry(b, level) },
            onDismiss = { editingBathymetry = false },
        )
    }
}

private fun instruction(d: SectionDraft): String = when {
    d.left == null -> "Toca la margen izquierda de ${d.name} sobre la imagen."
    d.right == null -> "Toca la margen derecha."
    d.bathymetryFile == null ->
        "Falta la batimetría: impórtala (CSV) o créala con el editor (plantilla rectangular/trapezoidal, tabla o CSV pegado)."
    else -> "Arrastra los extremos para ajustar la línea."
}

@Composable
private fun SectionPicker(
    drafts: List<SectionDraft>,
    selectedID: String?,
    select: (String) -> Unit,
    add: () -> Unit,
    remove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    FormSection("Secciones") {
        if (drafts.size > 1) {
            LabeledContent("Sección") {
                Box {
                    TextButton(onClick = { menu = true }) { Text(drafts.firstOrNull { it.id == selectedID }?.name ?: "—") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (d in drafts) {
                            DropdownMenuItem(text = { Text(d.name) }, onClick = { select(d.id); menu = false })
                        }
                    }
                }
            }
            FormDivider()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = add) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Agregar")
            }
            Spacer(Modifier.weight(1f))
            if (drafts.size > 1) {
                TextButton(onClick = remove) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Numeric field bound to a Double: keeps what is typed, reports each value that parses. */
@Composable
internal fun DoubleField(title: String, value: Double?, key: Any, unit: String = "", onValue: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(value?.let { Fmt.upTo(it, 6) } ?: "") }
    LaunchedEffect(key, value) {
        if (value != null && Fmt.parse(text) != value) text = Fmt.upTo(value, 6)
    }
    NumberField(title, text, { t ->
        text = t
        Fmt.parse(t)?.let(onValue)
    }, unit)
}

/** Form of one section. */
@Composable
private fun SectionDraftEditor(
    draft: SectionDraft,
    bathymetry: Bathymetry?,
    isIpcam: Boolean,
    isFirst: Boolean,
    onChange: ((SectionDraft) -> SectionDraft) -> Unit,
    importBathymetry: () -> Unit,
    editBathymetry: () -> Unit,
) {
    val defaultLevel = bathymetry?.let { SectionsPipeline.defaultLevel(it) }
    FormSection("Datos de la sección") {
        LabeledContent("Nombre") {
            OutlinedTextField(
                value = draft.name, onValueChange = { v -> onChange { it.copy(name = v) } }, singleLine = true,
                placeholder = { Text("CS_1") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End),
                modifier = Modifier.width(160.dp),
            )
        }
        val file = draft.bathymetryFile
        if (file != null) LabeledContent("Batimetría", file) else FormText("Sin batimetría", color = warning)
        FormButton("Importar batimetría (CSV)", Icons.Filled.UploadFile) { importBathymetry() }
        FormButton(
            if (file == null) "Crear batimetría (plantilla o tabla)" else "Editar batimetría",
            Icons.AutoMirrored.Filled.ShowChart,
        ) { editBathymetry() }
        FormDivider()
        LabeledContent("Nivel automático") {
            Switch(checked = draft.level == null, onCheckedChange = { auto ->
                onChange { it.copy(level = if (auto) null else (defaultLevel ?: 0.0)) }
            })
        }
        val level = draft.level
        if (level == null) {
            LabeledContent("Nivel del agua", defaultLevel?.let { "${Fmt.num(it, 3)} m" } ?: "—")
        } else {
            DoubleField("Nivel del agua (m)", level, key = draft.id + "-level") { v -> onChange { it.copy(level = v) } }
        }
        if (isIpcam && !isFirst) {
            FormText("En estación fija todas las secciones usan el nivel de la primera (la matriz depende del nivel).", small = true)
        }
        DoubleField("Progresiva margen izquierda", draft.leftStation, key = draft.id + "-left") { v ->
            onChange { it.copy(leftStation = v) }
        }
        LabeledContent("Estaciones: ${draft.numStations}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onChange { it.copy(numStations = max(2, it.numStations - 1)) } }, enabled = draft.numStations > 2,
                ) { Icon(Icons.Filled.Remove, contentDescription = "Menos") }
                FilledTonalIconButton(
                    onClick = { onChange { it.copy(numStations = minOf(200, it.numStations + 1)) } }, enabled = draft.numStations < 200,
                ) { Icon(Icons.Filled.Add, contentDescription = "Más") }
            }
        }
        DoubleField("Coeficiente α", draft.alpha, key = draft.id + "-alpha") { v -> onChange { it.copy(alpha = v) } }
    }
    if (bathymetry != null) {
        FormSection("Perfil del lecho") {
            BathymetryChart(bathymetry, draft.level ?: defaultLevel, Modifier.fillMaxWidth().height(180.dp).padding(8.dp))
        }
    }
}

/** Bed profile (station vs. elevation) with the water level (dashed blue). */
@Composable
fun BathymetryChart(bathymetry: Bathymetry, level: Double?, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = labelColor.copy(alpha = 0.4f)
    val brown = Color(0xFFA2845E)
    val blue = Color(0xFF007AFF)
    val n = minOf(bathymetry.stations.size, bathymetry.elevations.size)
    Canvas(modifier) {
        if (n < 1) return@Canvas
        val xs = bathymetry.stations.take(n)
        val ys = bathymetry.elevations.take(n)
        var x0 = xs.min()
        var x1 = xs.max()
        var y0 = ys.min()
        var y1 = ys.max()
        if (level != null && level.isFinite()) {
            y0 = minOf(y0, level)
            y1 = maxOf(y1, level)
        }
        if (abs(x1 - x0) < 1e-9) { x0 -= 1; x1 += 1 }
        if (abs(y1 - y0) < 1e-9) { y0 -= 1; y1 += 1 }
        val padY = (y1 - y0) * 0.08
        y0 -= padY
        y1 += padY
        val style = TextStyle(color = labelColor, fontSize = 10.sp)
        val yTop = textMeasurer.measure(Fmt.num(y1, 2), style)
        val yBottom = textMeasurer.measure(Fmt.num(y0, 2), style)
        val left = max(yTop.size.width, yBottom.size.width) + 6f
        val bottomLabel = textMeasurer.measure("Progresiva (m)", style)
        val bottom = size.height - bottomLabel.size.height - 4f
        val right = size.width - 4f
        val top = 4f
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return@Canvas
        fun px(x: Double) = (left + (x - x0) / (x1 - x0) * w).toFloat()
        fun py(y: Double) = (top + (y1 - y) / (y1 - y0) * h).toFloat()
        drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1f)
        drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)
        drawText(yTop, topLeft = Offset(left - yTop.size.width - 4f, top))
        drawText(yBottom, topLeft = Offset(left - yBottom.size.width - 4f, bottom - yBottom.size.height))
        val xLeft = textMeasurer.measure(Fmt.num(x0, 1), style)
        val xRight = textMeasurer.measure(Fmt.num(x1, 1), style)
        drawText(xLeft, topLeft = Offset(left, bottom + 2f))
        drawText(xRight, topLeft = Offset(right - xRight.size.width, bottom + 2f))
        drawText(bottomLabel, topLeft = Offset(left + (w - bottomLabel.size.width) / 2f, bottom + 2f))
        val yLabel = textMeasurer.measure("Cota (m)", style)
        drawText(yLabel, topLeft = Offset(left + 4f, top))
        val path = Path()
        for (i in 0 until n) {
            val p = Offset(px(xs[i]), py(ys[i]))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, brown, style = Stroke(2.dp.toPx()))
        for (i in 0 until n) drawCircle(brown, 2.5.dp.toPx(), Offset(px(xs[i]), py(ys[i])))
        if (level != null && level.isFinite()) {
            val y = py(level)
            drawLine(
                blue, Offset(left, y), Offset(right, y), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }
    }
}

/** Text field for a number typed in a table cell (no label). */
@Composable
internal fun CellField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, end: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = if (end) TextAlign.End else TextAlign.Start),
        modifier = modifier,
    )
}

