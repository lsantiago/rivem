package org.rivem.app.steps.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.RivemApplication
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.ProgressRow
import org.rivem.app.common.SwitchRow
import org.rivem.app.common.shareFile
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.ExportPipeline
import org.rivem.app.pipeline.userMessage
import org.rivem.app.steps.results.ReportPDF
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.RiverCore
import java.io.File

/**
 * Step 6: share the project folder as a zip (it unpacks as `<name>/<YYYYMMDDTHHMM>/`, ready for desktop's RIVeR
 * workspace), the stations CSV and the PDF report.
 */
@Composable
fun ExportStepView(model: WorkflowModel) {
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as RivemApplication).preferences }
    val scope = rememberCoroutineScope()
    var includeVideo by rememberSaveable { mutableStateOf(false) }
    var zipFile by remember { mutableStateOf<File?>(null) }
    var csvFile by remember { mutableStateOf<File?>(null) }
    var reportFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reportBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun makeZip() {
        val project = model.project ?: return
        val video = includeVideo
        busy = true
        error = null
        scope.launch {
            try {
                zipFile = withContext(Dispatchers.IO) { ExportPipeline.zip(context, project, video) }
            } catch (e: Exception) {
                error = "No se pudo crear el ZIP: ${e.userMessage()}"
                AppLog.error("Exportar ZIP: $e")
            }
            busy = false
        }
    }

    fun makeCSV() {
        val project = model.project ?: return
        try {
            csvFile = ExportPipeline.stationsCSV(context, project)
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    fun makeReport() {
        val project = model.project ?: return
        val quality = preferences.showQuality
        reportBusy = true
        scope.launch {
            try {
                reportFile = withContext(Dispatchers.Default) { ReportPDF.make(context, project, quality) }
                error = null
            } catch (e: Exception) {
                error = "No se pudo crear el informe: ${e.userMessage()}"
                AppLog.error("Informe PDF: $e")
            }
            reportBusy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        FormSection {
            FormText(
                "Para abrir el aforo en RIVeR escritorio ${RiverCore.DESKTOP_COMPATIBILITY}, descomprime el ZIP dentro de la carpeta RIVeR " +
                    "del espacio de trabajo del PC (queda RIVeR/nombre/fecha) y ábrelo desde \"Load project\".",
                color = MaterialTheme.colorScheme.onSurface,
            )
            model.project?.let { p ->
                val issues = remember(model.revision) { try { p.compatibilityIssues() } catch (_: Exception) { emptyList() } }
                if (issues.isEmpty()) {
                    StatusRow("Compatible con RIVeR escritorio", Icons.Filled.Verified, Color(0xFF34C759))
                } else {
                    for (issue in issues) StatusRow(issue.message, Icons.Filled.Warning, Color(0xFFFF9500))
                }
            }
        }
        FormSection("Proyecto completo (ZIP)") {
            SwitchRow("Incluir el video", includeVideo, enabled = model.hasVideo) {
                includeVideo = it
                zipFile = null
            }
            FormDivider()
            if (busy) {
                ProgressRow("Preparando el ZIP…")
            } else {
                FormButton("Preparar ZIP", Icons.Filled.Archive) { makeZip() }
            }
            zipFile?.let { f ->
                FormDivider()
                FormButton("Compartir ${f.name}", Icons.Filled.Share) { shareFile(context, f, "application/zip") }
            }
        }
        FormSection("Estaciones (CSV)") {
            FormButton("Preparar CSV de estaciones", Icons.Filled.TableChart, enabled = model.hasResults) { makeCSV() }
            csvFile?.let { f ->
                FormDivider()
                FormButton("Compartir ${f.name}", Icons.Filled.Share) { shareFile(context, f, "text/csv") }
            }
        }
        FormSection("Informe (PDF)") {
            if (reportBusy) {
                ProgressRow("Generando el informe…")
            } else {
                FormButton("Generar informe PDF", Icons.Filled.Description, enabled = model.hasResults) { makeReport() }
            }
            reportFile?.let { f ->
                FormDivider()
                FormButton("Compartir ${f.name}", Icons.Filled.Share) { shareFile(context, f, "application/pdf") }
            }
        }
        FormSection(footer = "La carpeta del proyecto también está en el teléfono: Android/data/org.rivem.app/files/RIVeR (accesible por USB).") {
            if (error != null) ErrorBanner(error) else FormText("Prepara un archivo y compártelo con la app que quieras (correo, Drive, WhatsApp…).", small = true)
        }
    }
}

@Composable
private fun StatusRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
