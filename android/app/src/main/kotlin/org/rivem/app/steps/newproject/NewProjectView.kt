package org.rivem.app.steps.newproject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.Workspace
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.LabeledContent
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.icon
import org.rivem.app.label
import org.rivem.app.media.VideoMetadata
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.ProjectCreation
import org.rivem.app.pipeline.SitePipeline
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.app.pipeline.userMessage
import org.rivem.core.project.Footage
import org.rivem.core.project.RiverProject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** `aforo_YYYYMMDD_HHMM`: the gallery and the camera only give temporary file names. */
fun defaultProjectName(date: Date = Date()): String =
    "aforo_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(date)

private fun footageHint(footage: Footage): String = when (footage) {
    Footage.uav -> "Toma cenital desde dron: 2 puntos con distancia o coordenadas conocidas."
    Footage.oblique -> "Cámara oblicua desde la orilla: 4 puntos sobre el agua y las 6 distancias entre ellos."
    Footage.ipcam -> "Estación fija: 6 o más puntos con coordenadas X, Y, Z (archivo de puntos de control)."
}

/** Temporary folder owned by the app for a received video (it is moved into the project). */
private fun newImportFolder(context: Context): File =
    File(context.cacheDir, "import-" + UUID.randomUUID().toString()).apply { mkdirs() }

/** Display name of a content URI (gallery, Files). */
private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

/** Copies a picked video into the cache (blocking). */
private fun copyToCache(context: Context, uri: Uri): File {
    val name = displayName(context, uri)?.takeIf { it.isNotBlank() }?.replace('/', '_') ?: "video.mp4"
    val dest = File(newImportFolder(context), name)
    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no se pudo abrir el archivo")
    input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    return dest
}

/**
 * Step 0, desktop "New project" + "Footage": video source (camera app, gallery, Files), footage type and name. The video
 * is copied into the new project folder and `settings.video` is filled from its metadata (fps rounded like desktop).
 *
 * With a [templateFolder] it creates a **new visit** of that aforo's site instead (RIVEM addition): same name folder,
 * and the georeferencing, sections, bathymetry and PIV settings are reused ([SitePipeline]); only the video and the
 * water level are asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectView(
    workspace: Workspace,
    templateFolder: File?,
    onCancel: () -> Unit,
    onCreated: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val template: RiverProject? = remember(templateFolder) {
        templateFolder?.let { try { RiverProject.open(it) } catch (_: Exception) { null } }
    }

    var name by rememberSaveable { mutableStateOf("") }
    /** The name was proposed by the app (not typed): choosing another video may replace it. */
    var nameIsAuto by rememberSaveable { mutableStateOf(true) }
    var river by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf("") }
    var footage by rememberSaveable { mutableStateOf(Footage.oblique) }
    /** Temporary copy owned by the app (gallery, camera or Files): it is moved into the project. */
    var videoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var metadata by remember { mutableStateOf<VideoMetadata?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** New visit: water level in the project's display unit. */
    var levelText by rememberSaveable { mutableStateOf("") }
    /** File the camera app records into (kept across a recreation of the activity). */
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun discardTemporaryVideo() {
        val p = videoPath ?: return
        val f = File(p)
        if (f.path.startsWith(context.cacheDir.path)) f.parentFile?.deleteRecursively()
    }

    fun useVideo(file: File, suggestedName: String?) {
        discardTemporaryVideo()
        videoPath = file.path
        metadata = null
        error = null
        if (nameIsAuto || name.isEmpty()) {
            if (template == null) name = suggestedName ?: defaultProjectName()
            nameIsAuto = true
        }
        busy = true
        scope.launch {
            try {
                metadata = withContext(Dispatchers.IO) { VideoMetadata.load(file) }
            } catch (e: Exception) {
                error = "No se pudo leer el video: ${e.userMessage()}"
                AppLog.error("Leer video: $e")
                videoPath = null
            }
            busy = false
        }
    }

    fun importUri(uri: Uri, fromFiles: Boolean) {
        busy = true
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                busy = false
                useVideo(file, if (fromFiles) file.nameWithoutExtension else null)
            } catch (e: Exception) {
                error = "No se pudo copiar el video: ${e.userMessage()}"
                AppLog.error("Importar video: $e")
                busy = false
            }
        }
    }

    // Metadata is not saveable: read it again after a recreation.
    LaunchedEffect(Unit) {
        val p = videoPath
        if (p != null && metadata == null && File(p).isFile) useVideo(File(p), null)
    }

    // New visit: prefill the level of the template's first section and the site name.
    LaunchedEffect(template) {
        val t = template ?: return@LaunchedEffect
        if (levelText.isEmpty()) {
            val level = try { t.readCrossSections()?.sections?.firstNotNullOfOrNull { it.level } } catch (_: Exception) { null }
            if (level != null) levelText = Fmt.num(UnitDisplay(t.settings.unitSystem).length(level), 3)
        }
        if (name.isEmpty()) name = SitePipeline.siteName(t)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importUri(uri, fromFiles = false)
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri, fromFiles = true)
    }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = capturePath?.let { File(it) }
        capturePath = null
        if (ok && f != null && f.isFile && f.length() > 0) {
            useVideo(f, null)
        } else {
            f?.parentFile?.deleteRecursively()
        }
    }

    fun launchCamera() {
        try {
            val file = File(newImportFolder(context), "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4")
            capturePath = file.path
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            captureLauncher.launch(uri)
        } catch (e: Exception) {
            error = "No se pudo abrir la cámara: ${e.userMessage()}"
            AppLog.error("Abrir cámara: $e")
        }
    }

    // The manifest declares CAMERA, so ACTION_VIDEO_CAPTURE needs it granted.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = "Sin permiso de cámara no se puede grabar."
    }

    fun create() {
        val path = videoPath ?: return
        val meta = metadata ?: return
        val source = File(path)
        val projectName = name.ifEmpty { defaultProjectName() }
        val riverName = river.trim()
        val siteName = site.trim()
        val kind = footage
        val root = workspace.root
        var level: Double? = null
        if (template != null && template.settings.footage != Footage.ipcam && levelText.isNotEmpty()) {
            val typed = Fmt.parse(levelText)
            if (typed == null || !typed.isFinite()) {
                error = "El nivel no es un número."
                return
            }
            level = UnitDisplay(template.settings.unitSystem).lengthToSI(typed)
        }
        val newLevel = level
        busy = true
        error = null
        scope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    if (template != null) {
                        SitePipeline.newVisit(template, source, meta, moveSource = true, level = newLevel)
                    } else {
                        ProjectCreation.create(root, projectName, kind, source, meta, moveSource = true, riverName = riverName, site = siteName)
                    }
                }
                source.parentFile?.let { if (it.path.startsWith(context.cacheDir.path)) it.deleteRecursively() }
                videoPath = null
                busy = false
                onCreated(project.folder)
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Crear proyecto: $e")
                busy = false
            }
        }
    }

    fun cancel() {
        discardTemporaryVideo()
        onCancel()
    }

    BackHandler { cancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (template == null) "Nuevo aforo" else "Nueva visita") },
                navigationIcon = { TextButton(onClick = { cancel() }) { Text("Cancelar") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(),
        ) {
            FormSection("Video") {
                FormButton("Grabar con la cámara", Icons.Filled.Videocam, enabled = !busy) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                FormDivider()
                FormButton("Elegir de la galería", Icons.Filled.PhotoLibrary, enabled = !busy) {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                FormDivider()
                FormButton("Elegir de Archivos", Icons.Filled.Folder, enabled = !busy) {
                    filesLauncher.launch(arrayOf("video/*"))
                }
                FormDivider()
                VideoSummary(videoPath?.let { File(it) }, metadata, loading = busy && metadata == null)
            }
            if (template != null) {
                VisitSection(template, levelText) { levelText = it }
            } else {
                FormSection("Tipo de toma") {
                    for (f in Footage.entries) {
                        Row(
                            Modifier.fillMaxWidth().clickable { footage = f }.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = footage == f, onClick = { footage = f })
                            Icon(f.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(f.label)
                        }
                    }
                    FormText(footageHint(footage), small = true)
                }
                FormSection("Nombre") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameIsAuto = false
                        },
                        label = { Text("Nombre del aforo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    FormText("Carpeta: RIVeR/${ProjectCreation.safeName(name)}/fecha", small = true)
                    OutlinedTextField(
                        value = river, onValueChange = { river = it }, label = { Text("Río (opcional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    OutlinedTextField(
                        value = site, onValueChange = { site = it }, label = { Text("Sitio (opcional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            FormSection {
                FormButton(
                    if (template == null) "Crear aforo" else "Crear visita",
                    Icons.Filled.AddCircle,
                    enabled = !busy && videoPath != null && metadata != null,
                ) { create() }
                ErrorBanner(error)
            }
        }
    }
}

@Composable
private fun VisitSection(t: RiverProject, levelText: String, onLevel: (String) -> Unit) {
    val units = UnitDisplay(t.settings.unitSystem)
    val ipcam = t.settings.footage == Footage.ipcam
    FormSection(
        "Visita nueva",
        footer = "Se reutilizan los puntos de control, las secciones, la batimetría y los parámetros PIV. Pon la cámara en el mismo lugar, " +
            "con la misma orientación y zoom, y revisa en Georreferenciación que los puntos caigan sobre sus marcas." +
            (if (ipcam) " En estación fija el nivel se cambia en el paso Secciones." else " Lee el nivel en la misma referencia que la batimetría."),
    ) {
        LabeledContent("Sitio", SitePipeline.siteName(t))
        LabeledContent("Configuración de", t.folder.name)
        LabeledContent("Tipo de toma", t.settings.footage?.label ?: "—")
        if (!ipcam) {
            OutlinedTextField(
                value = levelText,
                onValueChange = onLevel,
                label = { Text("Nivel del agua") },
                suffix = { Text(units.lengthUnit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun VideoSummary(file: File?, metadata: VideoMetadata?, loading: Boolean) {
    when {
        loading -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Leyendo el video…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        metadata != null && file != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${metadata.width} × ${metadata.height} · ${Fmt.num(metadata.fps, 2)} fps · ${Fmt.num(metadata.duration, 1)} s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (abs(metadata.fps - metadata.fps.roundToInt()) > 0.01) {
                Text(
                    "RIVeR guarda los fps redondeados (${metadata.fps.roundToInt()}): el Δt se calcula con ese valor, como en escritorio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9500),
                    textAlign = TextAlign.Start,
                )
            }
        }
        else -> FormText("Ningún video elegido")
    }
}
