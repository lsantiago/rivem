package org.rivem.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.LabeledContent
import org.rivem.app.pipeline.Fmt
import org.rivem.core.RiverCore
import org.rivem.core.geometry.Matrix3
import org.rivem.core.project.CrossSection
import org.rivem.core.project.CrossSections
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem

private class ProjectDetail(
    val project: RiverProject?,
    val sections: CrossSections?,
    val matrix: Matrix3?,
    val frameCount: Int,
    val error: String?,
)

private fun loadDetail(summary: ProjectSummary): ProjectDetail = try {
    val p = RiverProject.open(summary.folder)
    ProjectDetail(p, p.readCrossSections(), p.readMatrix(), p.frameFiles.size, null)
} catch (e: Exception) {
    ProjectDetail(null, null, null, 0, e.message ?: e.toString())
}

/** Read-only view of a project: what desktop stored, and whether desktop would open it as it is. */
@Composable
fun ProjectDetailView(summary: ProjectSummary, modifier: Modifier = Modifier) {
    val d = remember(summary) { loadDetail(summary) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        d.error?.let { error ->
            FormSection { IconRow(Icons.Filled.Error, error, MaterialTheme.colorScheme.error) }
        }
        val p = d.project ?: return@Column
        val s = p.settings
        FormSection("Proyecto") {
            LabeledContent("Carpeta", "${summary.name}/${summary.stamp}")
            LabeledContent("Tipo", s.footage?.label ?: "desconocido")
            LabeledContent("Etapa", s.stage.label)
            s.riverName?.let { LabeledContent("Río", it) }
            s.site?.let { LabeledContent("Sitio", it) }
            LabeledContent("Unidades", if (s.unitSystem == UnitSystem.imperial) "Imperial (se guarda en SI)" else "SI")
        }
        FormSection("Video y frames") {
            s.video?.let { v ->
                LabeledContent("Video", v.name ?: "—")
                val w = v.width
                val h = v.height
                if (w != null && h != null) LabeledContent("Resolución", "$w × $h")
                v.fps?.let { LabeledContent("FPS", Fmt.upTo(it, 2)) }
            }
            s.videoRange?.let { r -> LabeledContent("Rango", "${r.start}–${r.end}, paso ${r.step}") }
            s.frameInterval?.let { dt -> LabeledContent("Δt entre frames", "${Fmt.num(dt, 4)} s") }
            LabeledContent("Frames en el proyecto", "${d.frameCount}")
        }
        d.matrix?.let { m ->
            FormSection("Georreferenciación (píxel → m)") {
                for (row in m.rows) {
                    Text(
                        row.joinToString("   ") { Fmt.significant(it, 6) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val xs = d.sections
        if (xs != null && xs.sections.isNotEmpty()) {
            FormSection("Secciones") {
                xs.sections.forEachIndexed { i, sec ->
                    if (i > 0) FormDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(sec.name, style = MaterialTheme.typography.titleSmall)
                        Text(sectionDetail(sec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        FormSection("Compatibilidad con RIVeR escritorio") {
            val issues = p.compatibilityIssues()
            if (issues.isEmpty()) {
                IconRow(Icons.Filled.Verified, "Se abre en RIVeR ${RiverCore.DESKTOP_COMPATIBILITY}", Color(0xFF34C759))
            }
            for (issue in issues) IconRow(Icons.Filled.Warning, issue.message, Color(0xFFFF9500))
        }
    }
}

@Composable
private fun IconRow(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, color = color)
    }
}

private fun sectionDetail(s: CrossSection): String {
    val parts = ArrayList<String>()
    s.numStations?.let { parts.add("$it estaciones") }
    s.level?.let { parts.add("nivel ${Fmt.num(it, 3)} m") }
    s.alpha?.let { parts.add("α ${Fmt.upTo(it, 6)}") }
    s.realWorldLength?.let { parts.add("ancho ${Fmt.num(it, 2)} m") }
    s.totalDischarge?.let { if (it.isFinite()) parts.add("Q ${Fmt.num(it, 3)} m³/s") }
    return parts.joinToString(" · ")
}
