package org.rivem.app.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.media.ImageCoding
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plays the project's extracted frames (desktop's `FramePlayer`): current index, its image (a display-size copy
 * decoded off the main thread) and play/pause at a given rate. Create it with [rememberFrameSequence].
 */
@Stable
class FrameSequence(private val scope: CoroutineScope) {
    var frames: List<File> by mutableStateOf(emptyList())
        private set

    /** Frames shown per second while playing. */
    var rate: Double by mutableStateOf(10.0)
        private set
    var index: Int by mutableIntStateOf(0)
        private set
    var image: ImageBitmap? by mutableStateOf(null)
        private set
    var playing: Boolean by mutableStateOf(false)
        private set
    private var loadedIndex = -1
    private var playJob: Job? = null

    val count: Int get() = frames.size

    fun configure(frames: List<File>, rate: Double) {
        if (frames == this.frames) return
        stop()
        this.frames = frames
        this.rate = rate.coerceIn(1.0, 30.0)
        index = 0
        loadedIndex = -1
        image = null
        scope.launch { load(0) }
    }

    fun show(i: Int) {
        if (count == 0) return
        val k = i.coerceIn(0, count - 1)
        index = k
        scope.launch { load(k) }
    }

    fun toggle() {
        if (playing) stop() else play()
    }

    fun stop() {
        playing = false
        playJob?.cancel()
        playJob = null
    }

    private fun play() {
        if (count <= 1) return
        playing = true
        playJob = scope.launch {
            while (isActive && playing) {
                val next = (index + 1) % count
                index = next
                val started = System.nanoTime()
                load(next)
                val wait = max(0.0, 1 / rate - (System.nanoTime() - started) / 1e9)
                delay((wait * 1000).toLong())
            }
        }
    }

    private suspend fun load(k: Int) {
        if (k >= frames.size || k == loadedIndex) return
        val file = frames[k]
        val decoded = withContext(Dispatchers.IO) { ImageCoding.thumbnail(file, maxPixel = 1280) }
        if (index == k && decoded != null) {
            image = decoded
            loadedIndex = k
        }
    }
}

@Composable
fun rememberFrameSequence(): FrameSequence {
    val scope = rememberCoroutineScope()
    val sequence = remember { FrameSequence(scope) }
    DisposableEffect(sequence) { onDispose { sequence.stop() } }
    return sequence
}

/** Play/pause, scrubber and position of a [FrameSequence], drawn over a canvas. */
@Composable
fun FramePlayerBar(
    sequence: FrameSequence,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accessory: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { sequence.toggle() }, modifier = Modifier.size(40.dp)) {
            Icon(
                if (sequence.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (sequence.playing) "Pausar" else "Reproducir",
                tint = Color.White,
            )
        }
        if (sequence.count > 1) {
            Slider(
                value = sequence.index.toFloat(),
                onValueChange = { v ->
                    sequence.stop()
                    sequence.show(v.roundToInt())
                },
                valueRange = 0f..(sequence.count - 1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            caption ?: "${sequence.index + 1}/${sequence.count}",
            color = Color.White,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        accessory()
    }
}
