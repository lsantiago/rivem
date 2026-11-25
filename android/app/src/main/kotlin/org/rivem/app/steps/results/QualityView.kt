package org.rivem.app.steps.results

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rivem.app.common.DisclosureRow
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.StepperRow
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.QualityPipeline
import org.rivem.app.pipeline.SectionAssessment
import org.rivem.app.pipeline.UncertaintyAssumptions
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.core.section.QualityLevel
import org.rivem.core.section.UncertaintySource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** iOS system colours of the quality screens. */
object QualityColors {
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9500)
    val red = Color(0xFFFF3B30)
}

val QualityLevel.color: Color
    get() = when (this) {
        QualityLevel.good -> QualityColors.green
        QualityLevel.fair -> QualityColors.orange
        QualityLevel.poor -> QualityColors.red
    }

val QualityLevel.argb: Int get() = color.toArgb()

val QualityLevel.icon: ImageVector
    get() = when (this) {
        QualityLevel.good -> Icons.Filled.CheckCircle
        QualityLevel.fair -> Icons.Filled.Error
        QualityLevel.poor -> Icons.Filled.Report
    }

/** Three lamps, the one of [level] lit. */
@Composable
fun TrafficLight(level: QualityLevel, size: Dp = 14.dp) {
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50))
            .padding(horizontal = size * 0.4f, vertical = size * 0.3f)
            .clearAndSetSemantics { contentDescription = "Calidad ${QualityPipeline.levelText(level)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(QualityLevel.poor, QualityLevel.fair, QualityLevel.good).forEachIndexed { k, l ->
            if (k > 0) Spacer(Modifier.width(size * 0.3f))
            val lit = l == level
            Box(
                Modifier.size(size)
                    .then(if (lit) Modifier.shadow(3.dp, CircleShape, ambientColor = l.color, spotColor = l.color) else Modifier)
                    .background(l.color.copy(alpha = if (lit) 1f else 0.15f), CircleShape),
            )
        }
    }
}

/**
 * "Calidad del aforo" of one section: traffic light with Q ± U (95 %), one row per criterion (the hint of the ones
 * that are not good), the doubtful stations (tap to see them on the image) and where the uncertainty comes from.
 */
@Composable
fun QualitySection(assessment: SectionAssessment, units: UnitDisplay, showName: Boolean, onSelectStation: (Int) -> Unit) {
    var showBudget by rememberSaveable(assessment.name) { mutableStateOf(false) }
    val quality = assessment.quality
    val u = assessment.uncertainty
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    FormSection(if (showName) "Calidad del aforo · ${assessment.name}" else "Calidad del aforo") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TrafficLight(quality.overall, 16.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    QualityPipeline.levelText(quality.overall),
                    style = MaterialTheme.typography.titleMedium,
                    color = quality.overall.color,
                )
                Text(
                    "Q = ${units.dischargeText(u.discharge, 2)} ± ${units.dischargeText(u.expanded, 2)} (95 %)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        for (c in quality.checks) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(c.level.icon, contentDescription = null, tint = c.level.color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(QualityPipeline.title(c.criterion), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(QualityPipeline.valueText(c), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = secondary)
                }
                if (c.level != QualityLevel.good) {
                    Text(QualityPipeline.hint(c.criterion, assessment.fit), style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            }
        }
        for (d in quality.doubtful) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onSelectStation(d.index) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = QualityColors.orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Estación ${d.index + 1}: ${d.reasons.joinToString(", ") { QualityPipeline.reasonText(it) }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = QualityColors.orange,
                )
            }
        }
        DisclosureRow("De dónde viene la incertidumbre", showBudget, { showBudget = !showBudget }) {
            val budget = UncertaintySource.allCases
                .map { it to (u.shares[it] ?: 0.0) }
                .filter { it.second > 0.0005 }
                .sortedByDescending { it.second }
            val accent = MaterialTheme.colorScheme.primary
            for ((source, share) in budget) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(QualityPipeline.sourceText(source), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Box(Modifier.width(80.dp).height(8.dp).background(accent.copy(alpha = 0.2f), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(min(max(share, 0.0), 1.0).toFloat()).background(accent, RoundedCornerShape(50)))
                    }
                    Text(
                        Fmt.percent(share),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.width(48.dp).padding(start = 6.dp),
                    )
                }
            }
            FormText(
                "Intervalo del 95 %: ${units.dischargeText(u.lower, 2)} – ${units.dischargeText(u.upper, 2)}. " +
                    "Simulación Monte Carlo (4000 muestras) de α, la velocidad promedio de los pares, el nivel, la batimetría " +
                    "y la escala de la georreferenciación (${Fmt.percent(assessment.scaleStd, 1)}, según los residuos de los puntos).",
                small = true,
            )
        }
    }
}

/** The assumptions of the uncertainty (saved in the project). */
@Composable
fun UncertaintyAssumptionsSection(assumptions: UncertaintyAssumptions, units: UnitDisplay, onChange: (UncertaintyAssumptions) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    /** Rounded to the step, inside [lo, hi] (avoids 0.035000000000000003). */
    fun stepped(v: Double, step: Double, lo: Double, hi: Double): Double = min(max((v / step).roundToInt() * step, lo), hi)
    FormSection {
        DisclosureRow("Supuestos de la incertidumbre", expanded, { expanded = !expanded }) {
            val a = assumptions
            StepperRow(
                "Nivel del agua ±", units.lengthText(a.levelStd, 3), a.levelStd > 0, a.levelStd < 0.2,
                { onChange(a.copy(levelStd = stepped(a.levelStd - 0.005, 0.005, 0.0, 0.2))) },
                { onChange(a.copy(levelStd = stepped(a.levelStd + 0.005, 0.005, 0.0, 0.2))) },
            )
            StepperRow(
                "Batimetría ±", units.lengthText(a.depthStd, 2), a.depthStd > 0, a.depthStd < 0.5,
                { onChange(a.copy(depthStd = stepped(a.depthStd - 0.01, 0.01, 0.0, 0.5))) },
                { onChange(a.copy(depthStd = stepped(a.depthStd + 0.01, 0.01, 0.0, 0.5))) },
            )
            StepperRow(
                "Coeficiente α ±", Fmt.num(a.alphaStd, 2), a.alphaStd > 0, a.alphaStd < 0.2,
                { onChange(a.copy(alphaStd = stepped(a.alphaStd - 0.01, 0.01, 0.0, 0.2))) },
                { onChange(a.copy(alphaStd = stepped(a.alphaStd + 0.01, 0.01, 0.0, 0.2))) },
            )
            FormText(
                "Desviaciones estándar (1σ). Valores típicos: nivel leído en regla ±1 cm, batimetría ±2 cm, α = 0,85 ± 0,05 si no se calibró. Se guardan en el proyecto.",
                small = true,
            )
        }
    }
}
