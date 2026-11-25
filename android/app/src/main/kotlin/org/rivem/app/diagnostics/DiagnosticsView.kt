package org.rivem.app.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.LabeledContent
import org.rivem.core.RiverCore

/** In-app log viewer with export (to debug on the phone without a computer). */
@Composable
fun DiagnosticsView(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() {
        text = AppLog.tail(maxCharacters = 20_000)
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = { BackTopBar("Diagnóstico", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FormSection("Aplicación") {
                LabeledContent("Versión", AppLog.appVersion(context))
                LabeledContent("RiverCore", RiverCore.VERSION)
                LabeledContent("Compatible con RIVeR", RiverCore.DESKTOP_COMPATIBILITY)
                LabeledContent("Núcleos", "${Runtime.getRuntime().availableProcessors()}")
            }
            FormSection {
                FormButton("Exportar registro", Icons.Filled.Share, enabled = text.isNotEmpty()) {
                    AppLog.flush()
                    try {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", AppLog.file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Registro de RIVEM")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Exportar registro"))
                    } catch (e: Exception) {
                        AppLog.error("Exportar registro: $e")
                    }
                }
                FormDivider()
                FormButton("Actualizar", Icons.Filled.Refresh) { refresh() }
                FormDivider()
                FormButton("Borrar registro", Icons.Filled.Delete, destructive = true) { confirmClear = true }
            }
            FormSection("Registro (últimas líneas)") {
                if (text.isEmpty()) {
                    FormText("Vacío")
                } else {
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar el registro?") },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    refresh()
                    confirmClear = false
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}
