package org.rivem.app.steps.geometry

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormSection
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.media.ImageCoding
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.project.Footage

/** Step 2: desktop "Pixel Size" (UAV), "Control Points" (oblique) or the 3-D fixed station (ipcam). */
@Composable
fun GeometryStepView(model: WorkflowModel) {
    when (model.footage) {
        Footage.uav -> UAVGeometryView(model)
        Footage.oblique -> ObliqueGeometryView(model)
        Footage.ipcam -> Camera3DGeometryView(model)
        null -> ContentUnavailable(
            "Tipo de toma desconocido", Icons.Filled.QuestionMark,
            "settings.json no indica uav, oblique ni ipcam.",
        )
    }
}

/** SwiftUI `ContentUnavailableView`: icon, title and description centred. */
@Composable
fun ContentUnavailable(title: String, icon: ImageVector, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Placeholder when the first frame cannot be shown. */
@Composable
fun NoFrameView() {
    ContentUnavailable("Sin frames", Icons.Filled.Photo, "Extrae los frames del video en el paso 1.")
}

/** A labelled numeric text field bound to a String (accepts comma or dot). */
@Composable
fun NumberField(title: String, text: String, onChange: (String) -> Unit, unit: String = "m") {
    LabeledContent(title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = onChange, singleLine = true,
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End),
                modifier = Modifier.width(120.dp),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** SwiftUI segmented `Picker`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedPicker(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** Button row of a form that shows a spinner while [busy]. */
@Composable
fun BusyFormButton(text: String, icon: ImageVector, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (busy) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        }
    } else {
        FormButton(text, icon, enabled = enabled, onClick = onClick)
    }
}

/** Display name of a content URI (Files, Drive…). */
fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

/** Text of a picked document (UTF-8, Latin-1 fallback like iOS `String(decoding:)`) and its file name. */
fun readPickedText(context: Context, uri: Uri): Pair<String, String> {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("no se pudo abrir el archivo")
    val text = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        String(bytes, Charsets.ISO_8859_1)
    }
    val name = displayName(context, uri)?.takeIf { it.isNotBlank() } ?: "archivo.csv"
    return text.removePrefix("﻿") to name
}

/**
 * Document picker for CSV / TSV / text files (SwiftUI `fileImporter`). [onPicked] gets the text and the file name;
 * read errors go to [onError]. Open it with [launchTextPicker].
 */
@Composable
fun rememberTextFilePicker(
    onPicked: (text: String, name: String) -> Unit,
    onError: (String) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val (text, name) = readPickedText(context, uri)
            picked(text, name)
        } catch (e: Exception) {
            failed(e.message ?: e.toString())
        }
    }
}

/** CSV, TSV and plain text (some providers report CSV as octet-stream, so anything can be picked). */
fun ManagedActivityResultLauncher<Array<String>, Uri?>.launchTextPicker() {
    launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "text/tab-separated-values", "application/csv", "application/octet-stream", "*/*"))
}

/** The rectified image written with the geometry, shown on demand. */
@Composable
fun RectifiedPreviewSection(model: WorkflowModel, fileName: String) {
    val project = model.project ?: return
    val exists = remember(model.revision, fileName) { ProjectFiles.exists(project, fileName) }
    if (!exists) return
    var showing by remember { mutableStateOf(false) }
    FormSection("Imagen rectificada") {
        FormButton("Ver $fileName", Icons.Filled.Image) { showing = true }
        val r = project.settings.json["transformation"]?.objectValue?.get("resolution")?.doubleValue
        if (r != null) LabeledContent("Resolución", "${Fmt.num(r * 100, 2)} cm/px")
    }
    if (showing) {
        RectifiedImageDialog(ProjectFiles.url(project, fileName), model.revision) { showing = false }
    }
}

@Composable
private fun RectifiedImageDialog(file: java.io.File, revision: Int, onDismiss: () -> Unit) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, revision) {
        image = withContext(Dispatchers.IO) { ImageCoding.thumbnail(file, 2048) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                BackTopBar("Vista rectificada (norte arriba)", onDismiss) {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val img = image
                if (img != null) {
                    ImageCanvas(img, IntSize(img.width, img.height), Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
