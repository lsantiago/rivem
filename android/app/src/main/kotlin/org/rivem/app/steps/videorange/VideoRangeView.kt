package org.rivem.app.steps.videorange

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rivem.app.common.CanvasStepLayout
import org.rivem.app.common.ErrorBanner
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.FramePlayerBar
import org.rivem.app.common.ImageCanvas
import org.rivem.app.common.LabeledContent
import org.rivem.app.common.rememberFrameSequence
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.FramesPipeline
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.pipeline.userMessage
import org.rivem.app.workflow.WorkflowModel
import org.rivem.core.project.VideoInfo
import org.rivem.core.project.VideoRange
import java.io.File
import java.util.Locale
import kotlin.math.max

private val factors: List<Double> = listOf(1.0, 0.75, 0.5, 0.25)

/**
 * Step 1, desktop "Video Range": the video plays on top (start / end can be set at the paused position); start/end
 * time, step and resize factor → `FrameExtractor` → `frames/` and `video_range`. Without the video (projects made
 * from frames, like the pisco example) the extracted frames play instead.
 */
@Composable
fun VideoRangeView(model: WorkflowModel) {
    val project = model.project
    val videoFile: File? = remember(model.revision) { project?.let { ProjectFiles.videoURL(it) } }
    val player = remember { VideoPlayerState() }
    val frames = rememberFrameSequence()

    LaunchedEffect(project?.folder, videoFile) {
        val p = project ?: return@LaunchedEffect
        if (videoFile == null) {
            val dt = p.settings.frameInterval ?: 0.1
            frames.configure(p.frameFiles, if (dt > 0) 1 / dt else 10.0)
        }
    }

    val fps = model.settings?.video?.fps ?: 30.0
    val media: @Composable () -> Unit = {
        if (videoFile != null) {
            VideoPlayer(videoFile, player)
        } else {
            Box(Modifier.fillMaxSize()) {
                ImageCanvas(image = frames.image ?: model.firstFrame, imageSize = model.frameSize, modifier = Modifier.fillMaxSize())
                // Source frame number and its time, from the file name (`{index:010d}.jpg`).
                val caption = frames.frames.getOrNull(frames.index)?.nameWithoutExtension?.toIntOrNull()?.let { n ->
                    "frame $n · ${Fmt.num(n / fps, 2)} s"
                }
                FramePlayerBar(frames, Modifier.align(Alignment.TopCenter), caption = caption)
            }
        }
    }

    if (model.hasVideo || model.frameCount > 0) {
        CanvasStepLayout(canvas = media, controls = { RangeForm(model, player.takeIf { videoFile != null }) })
    } else {
        RangeForm(model, null)
    }
}

/** Position and controls of the `VideoView` (Swift `AVPlayer`). */
class VideoPlayerState {
    var view: VideoView? = null
    var mediaPlayer: MediaPlayer? = null
    var durationMs by mutableIntStateOf(0)
    var positionMs by mutableIntStateOf(0)
    var playing by mutableStateOf(false)

    /** Current position in seconds (paused), or null when the video is not ready. */
    fun pauseAndPosition(): Double? {
        val v = view ?: return null
        if (v.isPlaying) v.pause()
        playing = false
        val ms = v.currentPosition
        positionMs = ms
        return if (ms >= 0) ms / 1000.0 else null
    }

    fun toggle() {
        val v = view ?: return
        if (v.isPlaying) {
            v.pause()
            playing = false
        } else {
            v.start()
            playing = true
        }
    }

    /** Exact seek (closest frame, not the previous key frame). */
    fun seek(ms: Int) {
        val mp = mediaPlayer
        if (mp != null) {
            try {
                mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
            } catch (_: Exception) {
                view?.seekTo(ms)
            }
        } else {
            view?.seekTo(ms)
        }
        positionMs = ms
    }
}

@Composable
private fun VideoPlayer(file: File, state: VideoPlayerState) {
    LaunchedEffect(state) {
        while (true) {
            val v = state.view
            if (v != null && v.isPlaying) state.positionMs = v.currentPosition
            if (v != null) state.playing = v.isPlaying
            delay(200)
        }
    }
    DisposableEffect(file) {
        onDispose {
            state.view?.stopPlayback()
            state.view = null
            state.mediaPlayer = null
            state.playing = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            state.mediaPlayer = mp
                            state.durationMs = mp.duration
                            // Show the first frame instead of a black view.
                            state.seek(state.positionMs.coerceAtLeast(1))
                        }
                        setOnCompletionListener { state.playing = false }
                        setOnErrorListener { _, what, extra ->
                            AppLog.error("Reproductor de video: error $what/$extra con ${file.name}")
                            true
                        }
                        setVideoPath(file.path)
                        state.view = this
                    }
                },
                modifier = Modifier.wrapContentSize(),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.toggle() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pausar" else "Reproducir",
                    tint = Color.White,
                )
            }
            Slider(
                value = state.positionMs.toFloat(),
                onValueChange = { v ->
                    state.view?.let { if (it.isPlaying) it.pause() }
                    state.playing = false
                    state.seek(v.toInt())
                },
                valueRange = 0f..max(1, state.durationMs).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                String.format(Locale.ROOT, "%.2f s", state.positionMs / 1000.0),
                color = Color.White,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun RangeForm(model: WorkflowModel, player: VideoPlayerState?) {
    val scope = rememberCoroutineScope()
    var startText by rememberSaveable { mutableStateOf("0") }
    var endText by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableIntStateOf(1) }
    var factor by rememberSaveable { mutableDoubleStateOf(1.0) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var factorMenu by remember { mutableStateOf(false) }

    val fps = model.settings?.video?.fps ?: 30.0

    val currentRange: VideoRange? = run {
        val start = Fmt.parse(startText)
        val end = Fmt.parse(endText)
        if (start == null || end == null || end <= start) return@run null
        val r = FramesPipeline.range(start, end, step, factor, fps)
        if (r.frameIndices.isEmpty()) null else r
    }

    LaunchedEffect(model.revision) {
        val s = model.settings ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        loaded = true
        val r = s.videoRange ?: s.video?.let { FramesPipeline.defaultRange(it) } ?: return@LaunchedEffect
        startText = Fmt.num(r.start / fps, 2)
        endText = Fmt.num(r.end / fps, 2)
        step = r.step
        factor = if (r.factor in factors) r.factor else 1.0
    }

    fun mark(start: Boolean) {
        val t = player?.pauseAndPosition() ?: return
        if (!t.isFinite()) return
        if (start) startText = Fmt.num(t, 2) else endText = Fmt.num(t, 2)
    }

    fun startExtraction() {
        val range = currentRange ?: return
        val project = model.project ?: return
        error = null
        message = null
        progress = 0.0
        job = scope.launch {
            try {
                val outcome = FramesPipeline.extract(project, range) { value -> progress = value }
                model.replace(outcome.project)
                var text = "${outcome.written} frames extraídos."
                if (outcome.sizeChanged) {
                    text += " El tamaño de los frames cambió: vuelve a georreferenciar y a dibujar las secciones."
                }
                message = text
            } catch (e: CancellationException) {
                message = "Extracción cancelada."
                model.reload()
            } catch (e: Exception) {
                error = e.userMessage()
                AppLog.error("Extracción de frames: $e")
                model.reload()
            } finally {
                job = null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        VideoInfoSection(model)
        FormSection("Rango") {
            LabeledContent("Inicio (s)") {
                OutlinedTextField(
                    value = startText, onValueChange = { startText = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
            }
            LabeledContent("Fin (s)") {
                OutlinedTextField(
                    value = endText, onValueChange = { endText = it }, singleLine = true, placeholder = { Text("fin") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
            }
            if (player != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { mark(start = true) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardTab, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Inicio aquí")
                    }
                    OutlinedButton(onClick = { mark(start = false) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardTab, contentDescription = null,
                            modifier = Modifier.size(18.dp).scale(scaleX = -1f, scaleY = 1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Fin aquí")
                    }
                }
            }
            LabeledContent("Paso: $step (uno de cada $step frames)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { if (step > 1) step -= 1 }, enabled = step > 1) {
                        Icon(Icons.Filled.Remove, contentDescription = "Menos")
                    }
                    FilledTonalIconButton(onClick = { if (step < 30) step += 1 }, enabled = step < 30) {
                        Icon(Icons.Filled.Add, contentDescription = "Más")
                    }
                }
            }
            LabeledContent("Escala de los frames") {
                Box {
                    TextButton(onClick = { factorMenu = true }) { Text(percent(factor)) }
                    DropdownMenu(expanded = factorMenu, onDismissRequest = { factorMenu = false }) {
                        for (f in factors) {
                            DropdownMenuItem(text = { Text(percent(f)) }, onClick = { factor = f; factorMenu = false })
                        }
                    }
                }
            }
            RangeSummaryRow(currentRange, model.settings?.video)
        }
        FormSection(
            footer = "Pausa el video donde quieras empezar o terminar y toca «Inicio aquí» / «Fin aquí». Como en RIVeR escritorio, " +
                "el frame N del video se guarda como frames/N.jpg (10 dígitos) y el intervalo entre frames es paso / fps. " +
                "La extracción no corrige lente ni estabiliza.",
        ) {
            val running = job
            if (running != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(String.format(Locale.ROOT, "Extrayendo frames… %.0f %%", progress * 100))
                    Spacer(Modifier.size(6.dp))
                    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                FormButton("Cancelar", destructive = true) { running.cancel() }
            } else {
                FormButton(
                    if (model.frameCount > 0) "Volver a extraer los frames" else "Extraer frames",
                    Icons.Filled.Movie,
                    enabled = model.hasVideo && currentRange != null,
                ) { startExtraction() }
            }
            message?.let { FormText(it) }
            ErrorBanner(error)
        }
    }
}

private fun percent(f: Double): String = String.format(Locale.ROOT, "%.0f %%", f * 100)

@Composable
private fun VideoInfoSection(model: WorkflowModel) {
    FormSection("Video") {
        model.settings?.video?.let { v ->
            LabeledContent("Archivo", v.name ?: "—")
            val w = v.width
            val h = v.height
            if (w != null && h != null) LabeledContent("Resolución", "$w × $h")
            LabeledContent("FPS (redondeado como escritorio)", Fmt.num(v.fps, 0))
            LabeledContent("Duración", "${Fmt.num(v.duration, 2)} s")
        }
        LabeledContent("Frames en el proyecto", "${model.frameCount}")
        if (!model.hasVideo) {
            FormText("El video no está en la carpeta del proyecto: no se pueden extraer frames.", color = Color(0xFFFF9500), small = true)
        }
    }
}

@Composable
private fun RangeSummaryRow(range: VideoRange?, video: VideoInfo?) {
    val fps = video?.fps
    if (range != null && fps != null && fps > 0) {
        val count = range.frameIndices.size
        val dt = range.step / fps
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Frames ${range.start}–${range.end} · $count imágenes · ${max(count - 1, 0)} pares",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Δt entre frames: ${Fmt.num(dt * 1000, 1)} ms",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        FormText("Rango no válido", color = MaterialTheme.colorScheme.error)
    }
}
