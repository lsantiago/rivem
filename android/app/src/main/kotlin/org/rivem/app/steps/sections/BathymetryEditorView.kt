package org.rivem.app.steps.sections

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormSection
import org.rivem.app.pipeline.BathymetryTemplates
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.SectionsPipeline
import org.rivem.app.pipeline.userMessage
import org.rivem.app.steps.geometry.NumberField
import org.rivem.app.steps.geometry.SegmentedPicker
import org.rivem.core.project.Bathymetry
import java.util.UUID

/** One editable point of the bed profile. */
private data class BathymetryRow(val id: String = UUID.randomUUID().toString(), val station: String, val stage: String)

private fun rowsOf(b: Bathymetry): List<BathymetryRow> {
    val n = minOf(b.stations.size, b.elevations.size)
    return (0 until n).map { BathymetryRow(station = Fmt.num(b.stations[it], 3), stage = Fmt.num(b.elevations[it], 3)) }
}

/**
 * In-app bathymetry for a section with no surveyed file: a quick template (rectangular / trapezoidal channel from
 * water width, depth and banks), an editable table of (station, elevation) points, or a CSV pasted from the
 * clipboard. "Usar" hands back the profile (and the template's water level); the caller writes it into the project
 * as the same `station,stage` CSV an import produces.
 */
@Composable
fun BathymetryEditorDialog(
    sectionName: String,
    initial: Bathymetry?,
    onSave: (Bathymetry, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember { mutableStateOf(initial?.let { rowsOf(it) } ?: emptyList()) }
    var shape by remember { mutableStateOf(BathymetryTemplates.Shape.trapezoidal) }
    var widthText by remember { mutableStateOf("10") }
    var depthText by remember { mutableStateOf("1") }
    var slopeText by remember { mutableStateOf("2") }
    var freeboardText by remember { mutableStateOf("0.5") }
    var levelText by remember { mutableStateOf("10") }
    /** Water level of the last template applied (null once the user builds the table by hand). */
    var templateLevel by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val parsedRows: List<Pair<Double, Double>> = rows.mapNotNull { r ->
        val s = Fmt.parse(r.station)
        val z = Fmt.parse(r.stage)
        if (s != null && z != null) s to z else null
    }
    val current: Bathymetry? = try { BathymetryTemplates.fromRows(parsedRows) } catch (_: Exception) { null }
    val validation: String? = when {
        rows.isEmpty() -> "Genera un perfil con la plantilla, pega un CSV o agrega puntos."
        rows.size - parsedRows.size > 0 -> "Hay ${rows.size - parsedRows.size} filas incompletas o con números no válidos (se ignoran)."
        current == null -> "Se necesitan al menos dos puntos con estaciones distintas."
        else -> null
    }

    fun applyTemplate() {
        val w = Fmt.parse(widthText)
        val d = Fmt.parse(depthText)
        val fb = Fmt.parse(freeboardText)
        val level = Fmt.parse(levelText)
        if (w == null || d == null || fb == null || level == null) {
            error = "Completa ancho, profundidad, altura de orillas y nivel."
            return
        }
        val slope = Fmt.parse(slopeText) ?: 0.0
        try {
            val b = BathymetryTemplates.make(shape, w, d, slope, fb, level)
            rows = rowsOf(b)
            templateLevel = level
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    fun sortRows() {
        rows = rows.sortedBy { Fmt.parse(it.station) ?: Double.POSITIVE_INFINITY } // stable
    }

    fun pasteCSV() {
        val text = clipboard.getText()?.text
        if (text.isNullOrEmpty()) {
            error = "El portapapeles no contiene texto."
            return
        }
        try {
            rows = rowsOf(Bathymetry.parse(text))
            templateLevel = null
            error = null
        } catch (e: Exception) {
            error = "No se pudo leer el CSV pegado: ${e.userMessage()}"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                BackTopBar("Batimetría · $sectionName", onDismiss) {
                    TextButton(
                        onClick = {
                            val b = current
                            if (b != null) {
                                onSave(b, templateLevel)
                                onDismiss()
                            }
                        },
                        enabled = current != null,
                    ) { Text("Usar") }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding()) {
                FormSection("Plantilla rápida", footer = "Reemplaza la tabla. El nivel del agua de la sección se fija en la cota indicada.") {
                    val shapes = BathymetryTemplates.Shape.entries
                    SegmentedPicker(shapes.map { it.label }, shapes.indexOf(shape)) { shape = shapes[it] }
                    NumberField("Ancho del agua", widthText, { widthText = it })
                    NumberField("Profundidad máxima", depthText, { depthText = it })
                    if (shape == BathymetryTemplates.Shape.trapezoidal) {
                        NumberField("Talud (horizontal por 1 vertical)", slopeText, { slopeText = it }, unit = "H:V")
                    }
                    NumberField("Altura de las orillas sobre el agua", freeboardText, { freeboardText = it })
                    NumberField("Cota del nivel del agua", levelText, { levelText = it })
                    FormButton("Generar perfil con la plantilla", Icons.Filled.AutoFixHigh) { applyTemplate() }
                }
                FormSection("Puntos (estación m, cota m): ${rows.size}") {
                    rows.forEachIndexed { i, row ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CellField(row.station, "estación", { v ->
                                rows = rows.toMutableList().also { it[i] = row.copy(station = v) }
                            }, Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            CellField(row.stage, "cota", { v ->
                                rows = rows.toMutableList().also { it[i] = row.copy(stage = v) }
                            }, Modifier.weight(1f), end = true)
                            IconButton(onClick = {
                                rows = rows.filterIndexed { k, _ -> k != i }
                                templateLevel = null
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Borrar punto", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    FormButton("Agregar punto", Icons.Filled.Add) { rows = rows + BathymetryRow(station = "", stage = "") }
                    FormButton("Ordenar por estación", Icons.AutoMirrored.Filled.Sort) { sortRows() }
                    FormButton("Pegar CSV del portapapeles", Icons.Filled.ContentPaste) { pasteCSV() }
                }
                val b = current
                if (b != null) {
                    FormSection("Vista previa") {
                        BathymetryChart(
                            b, templateLevel ?: SectionsPipeline.defaultLevel(b),
                            Modifier.fillMaxWidth().height(180.dp).padding(8.dp),
                        )
                    }
                }
                FormSection(
                    footer = "Estación: distancia horizontal en metros desde la margen izquierda, creciente. Cota: elevación del " +
                        "lecho en metros, en el mismo sistema que el nivel del agua. Se guarda como " +
                        "${SectionsPipeline.editorFileName(sectionName)} (station,stage), igual que en RIVeR escritorio.",
                ) {
                    ErrorBanner(error ?: validation)
                }
            }
        }
    }
}
