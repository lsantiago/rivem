package org.rivem.app.steps.site

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.LabeledContent
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.SiteGauging
import org.rivem.app.pipeline.SitePipeline
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.app.steps.results.ChartView
import org.rivem.app.steps.results.Charts
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import org.rivem.core.section.RatingCurve
import org.rivem.core.section.RatingPoint
import java.io.File
import java.text.DateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val orange = Color(0xFFFF9500)

/**
 * RIVEM addition: the gaugings of a site (`RIVeR/<name>/`, one per dated folder with results) and its stage–discharge
 * curve Q = a·(h − h₀)ᵇ, with a calculator that estimates Q from a level read on the staff gauge. Gaugings can be
 * left out of the fit (tap). Nothing is written: the curve is refitted from the projects every time. Values are shown
 * in the unit system of the latest visit.
 */
@Composable
fun SiteScreen(siteFolder: File, onBack: () -> Unit) {
    var gaugings by remember { mutableStateOf<List<SiteGauging>>(emptyList()) }
    var units by remember { mutableStateOf(UnitDisplay(UnitSystem.si)) }
    var loaded by remember { mutableStateOf(false) }
    var excluded by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var levelText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(siteFolder) {
        val (list, system) = withContext(Dispatchers.IO) {
            val g = SitePipeline.gaugings(siteFolder)
            val last = g.lastOrNull()?.folder
            val system = last?.let { try { RiverProject.open(it).settings.unitSystem } catch (_: Exception) { null } } ?: UnitSystem.si
            g to system
        }
        gaugings = list
        units = UnitDisplay(system)
        loaded = true
        if (levelText.isEmpty()) list.lastOrNull()?.let { levelText = Fmt.num(units.length(it.level), 2) }
    }

    val included = gaugings.filter { it.folder.path !in excluded }
    val curve: RatingCurve? = remember(gaugings, excluded) {
        RatingCurve.fit(included.map { RatingPoint(it.level, it.discharge) })
    }

    Scaffold(topBar = { BackTopBar(siteFolder.name, onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(),
        ) {
            FormSection("Curva nivel–caudal") {
                if (gaugings.isEmpty()) {
                    FormText(if (loaded) "Este sitio aún no tiene aforos con resultados." else "Buscando aforos…")
                } else {
                    RatingChart(gaugings, excluded, included, curve, units)
                }
                if (curve != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "Q = ${Fmt.num(curve.a, 3)} · (h − ${Fmt.num(curve.h0, 3)})^${Fmt.num(curve.b, 3)}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            "R² ${Fmt.num(curve.r2, 4)} · error relativo RMS ${Fmt.percent(curve.relativeRMS, 1)} · ${curve.pointCount} aforos · h y h₀ en m, Q en m³/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (curve.pointCount < 5) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = orange, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Con menos de 5 aforos la curva es orientativa: el ajuste tiene 3 parámetros.",
                                style = MaterialTheme.typography.bodySmall,
                                color = orange,
                            )
                        }
                    }
                } else if (gaugings.isNotEmpty()) {
                    FormText("Hacen falta al menos ${RatingCurve.minimumPoints} aforos con resultados a niveles distintos (hay ${included.size}).")
                }
            }

            if (curve != null) {
                FormSection("Estimar el caudal desde el nivel") {
                    LabeledContent("Nivel") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = levelText,
                                onValueChange = { levelText = it },
                                singleLine = true,
                                placeholder = { Text("nivel") },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(120.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(units.lengthUnit, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val typed = Fmt.parse(levelText)
                    if (typed != null) {
                        val h = units.lengthToSI(typed)
                        FormDivider()
                        LabeledContent("Caudal estimado") {
                            Text(units.dischargeText(curve.discharge(h), 2), style = MaterialTheme.typography.titleMedium)
                        }
                        val lo = included.minOfOrNull { it.level }
                        val hi = included.maxOfOrNull { it.level }
                        if (lo != null && hi != null && (h < lo || h > hi)) {
                            FormText(
                                "Fuera del rango aforado (${units.lengthText(lo, 2)} – ${units.lengthText(hi, 2)}): extrapolación.",
                                color = orange,
                                small = true,
                            )
                        }
                    }
                }
            }

            FormSection(
                "Aforos del sitio",
                footer = "Toca un aforo para sacarlo del ajuste. Cada visita es una carpeta con fecha dentro de RIVeR/${siteFolder.name}/. " +
                    "El nivel debe estar en la misma referencia en todas (la de la batimetría).",
            ) {
                gaugings.forEachIndexed { k, g ->
                    if (k > 0) FormDivider()
                    val isIn = g.folder.path !in excluded
                    GaugingRow(g, isIn, if (isIn) curve else null, units) {
                        excluded = if (isIn) excluded + g.folder.path else excluded - g.folder.path
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingChart(gaugings: List<SiteGauging>, excluded: List<String>, included: List<SiteGauging>, curve: RatingCurve?, units: UnitDisplay) {
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val samples = ArrayList<Pair<Double, Double>>()
    val lo = included.minOfOrNull { it.level }
    val hi = included.maxOfOrNull { it.level }
    if (curve != null && lo != null && hi != null) {
        val span = max(hi - lo, 0.05)
        val start = max(curve.h0, lo - 0.3 * span)
        val end = hi + 0.2 * span
        for (k in 0..60) {
            val h = start + (end - start) * k / 60
            samples.add(units.discharge(curve.discharge(h)) to units.length(h))
        }
    }
    val points = gaugings.map { Charts.RatingPointMark(units.discharge(it.discharge), units.length(it.level), it.folder.path !in excluded) }
    ChartView(240.dp, Modifier.padding(vertical = 4.dp)) { c, rect, style ->
        Charts.ratingCurve(c, rect, points, samples, units, accent, style)
    }
}

@Composable
private fun GaugingRow(g: SiteGauging, isIn: Boolean, curve: RatingCurve?, units: UnitDisplay, onToggle: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val dateText = remember(g.stamp) {
        g.date?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale("es")).format(it) } ?: g.stamp
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isIn) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (isIn) "En el ajuste" else "Fuera del ajuste",
            tint = if (isIn) MaterialTheme.colorScheme.primary else secondary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(dateText, style = MaterialTheme.typography.bodyLarge)
            Text(
                "h ${units.lengthText(g.level, 3)} · Q ${units.dischargeText(g.discharge, 2)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = secondary,
            )
        }
        if (curve != null && g.discharge != 0.0) {
            val r = (curve.discharge(g.level) - g.discharge) / g.discharge
            val text = (if (r >= 0) "+" else "") + Fmt.percent(r, 1)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (abs(r) > 0.1) orange else secondary,
                modifier = Modifier.semantics { contentDescription = "Desvío de la curva ${Fmt.percent(r, 1)}" },
            )
        }
    }
}
