package org.rivem.app.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.rivem.app.diagnostics.AppLog
import org.rivem.core.project.RiverProject
import org.rivem.core.project.VideoRange
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the app needs to know about a video to create a project (desktop's `settings.video` block). */
data class VideoMetadata(
    val duration: Double,
    /** Nominal frame rate (playback rate of the file; desktop stores it rounded to an integer). */
    val fps: Double,
    /** Size after applying the track's rotation (portrait phone videos are tall), like desktop's metadata. */
    val width: Int,
    val height: Int,
    /** Number of frames reported by the container, when known. */
    val sourceFrameCount: Int? = null,
) {
    /** Number of frames (container count, else duration × fps). */
    val frameCount: Int get() = sourceFrameCount ?: (duration * fps).roundToInt()

    companion object {
        /**
         * Reads the metadata with `MediaMetadataRetriever` (duration, rotated size, frame count) and `MediaExtractor`
         * (track frame rate). The frame rate is the playback rate, like iOS `nominalFrameRate` and desktop's OpenCV
         * `CAP_PROP_FPS`: frame count / track duration, else the track's `frame-rate`, else
         * `METADATA_KEY_CAPTURE_FRAMERATE`. Blocking: call it off the main thread.
         */
        fun load(file: File): VideoMetadata {
            val r = MediaMetadataRetriever()
            try {
                try {
                    r.setDataSource(file.path)
                } catch (e: Exception) {
                    throw FrameExtractor.Failure.Reader(e.message ?: e.toString())
                }
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    throw FrameExtractor.Failure.NoVideoTrack()
                }
                val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
                val w0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                val capture = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                val track = trackInfo(file)
                val trackDuration = track?.durationUs?.let { it / 1_000_000.0 } ?: (durationMs / 1000)
                var fps: Double? = null
                if (count != null && count > 1 && trackDuration > 0) fps = count / trackDuration
                if (fps == null || !fps.isFinite() || fps <= 0) fps = track?.frameRate
                if (fps == null || !fps.isFinite() || fps <= 0) fps = capture
                if (fps == null || !fps.isFinite() || fps <= 0) throw FrameExtractor.Failure.Reader("no se pudo leer la frecuencia de cuadros")
                val swap = rotation == 90 || rotation == 270
                AppLog.info(
                    "Metadatos ${file.name}: ${w0}x$h0 rotación $rotation, ${count ?: "?"} frames, duración " +
                        String.format(Locale.ROOT, "%.3f s (pista %.3f s)", durationMs / 1000, trackDuration) +
                        ", fps pista ${track?.frameRate ?: "?"}, fps captura ${capture ?: "?"} → " +
                        String.format(Locale.ROOT, "%.3f", fps) + ", ${track?.mime ?: "?"}",
                )
                return VideoMetadata(
                    duration = durationMs / 1000,
                    fps = fps,
                    width = if (swap) h0 else w0,
                    height = if (swap) w0 else h0,
                    sourceFrameCount = count,
                )
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }

        private class TrackInfo(val mime: String, val frameRate: Double?, val durationUs: Long?)

        private fun trackInfo(file: File): TrackInfo? {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.path)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue
                    val rate: Double? = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Exception) {
                            try { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Exception) { null }
                        }
                    } else null
                    val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else null
                    return TrackInfo(mime, rate, dur)
                }
            } catch (_: Exception) {
            } finally {
                ex.release()
            }
            return null
        }
    }
}

/**
 * Extracts frames from a video into a project's `frames/` folder with desktop's naming and selection rules: source
 * frame index `i` is written as `{i:010d}.jpg` when `i % step == 0` and `start <= i < end` (`video_to_frames.py`),
 * JPEG quality 95, optionally downscaled by `factor`.
 *
 * Frames are requested by index with `MediaMetadataRetriever.getFrameAtIndex` / `getFramesAtIndex` (API 28), so file
 * `N.jpg` really holds source frame N (desktop's file N holds frame N+1, docs/PLAN.md #2), and any decoding error
 * stops the extraction with an error instead of leaving silent gaps. Consecutive frames are decoded in batches
 * (`getFramesAtIndex`) because every call seeks back to a key frame.
 */
class FrameExtractor(val video: File, val range: VideoRange) {
    sealed class Failure(message: String) : Exception(message) {
        class NoVideoTrack : Failure("El archivo no tiene pista de video.")
        class Reader(why: String) : Failure("No se pudo leer el video: $why")
        class NothingExtracted : Failure("No se extrajo ningún frame: revisa el rango.")
        class Black(why: String) : Failure("Los frames salen negros ($why). Exporta el registro desde Diagnóstico.")
    }

    /** Returns the number of frames written. [progress] receives values in 0…1 (from a background thread). */
    suspend fun extract(framesFolder: File, progress: (Double) -> Unit = {}): Int = withContext(Dispatchers.Default) {
        val r = MediaMetadataRetriever()
        try {
            try {
                r.setDataSource(video.path)
            } catch (e: Exception) {
                throw Failure.Reader(e.message ?: e.toString())
            }
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") throw Failure.NoVideoTrack()
            val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?: throw Failure.Reader("el video no informa su número de frames")
            val w0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "?"
            AppLog.info("Video ${video.name}: $mime, ${w0}x$h0, rotación $rotation, $count frames")

            cleanFolder(framesFolder)
            val wanted = range.frameIndices.filter { it < count }
            val wantedSet = wanted.toHashSet()
            val total = max(wanted.size, 1)
            // Batch of consecutive decoded frames kept in memory at once (~64 MB of ARGB bitmaps, 1…16 frames).
            val bytesPerFrame = max(1L, w0.toLong() * h0.toLong() * 4)
            val batch = (64L * 1024 * 1024 / bytesPerFrame).toInt().coerceIn(1, 16)
            val sequential = range.step < batch
            var written = 0

            fun write(index: Int, frame: Bitmap) {
                val upright = orient(frame, rotation, w0, h0)
                val image = scale(upright)
                try {
                    val url = File(framesFolder, RiverProject.frameFileName(index))
                    if (written == 0) {
                        val mean = ImageCoding.meanLuminance(image)
                        AppLog.info(
                            "Primer frame ${url.name}: ${image.width}x${image.height}, brillo medio " +
                                String.format(Locale.ROOT, "%.1f", mean),
                        )
                        if (mean < 0.5) throw Failure.Black("brillo medio " + String.format(Locale.ROOT, "%.2f", mean))
                    }
                    ImageCoding.writeJPEG(image, url)
                    written += 1
                    progress(written.toDouble() / total)
                } finally {
                    if (image !== frame) image.recycle()
                    if (upright !== frame && upright !== image) upright.recycle()
                }
            }

            var i = 0
            while (i < wanted.size) {
                ensureActive()
                val first = wanted[i]
                if (!sequential) {
                    val frame = try {
                        r.getFrameAtIndex(first)
                    } catch (e: Exception) {
                        throw Failure.Reader("frame $first: ${e.message ?: e}")
                    } ?: throw Failure.Reader("frame $first vacío")
                    try { write(first, frame) } finally { frame.recycle() }
                    i += 1
                } else {
                    val last = min(first + batch - 1, count - 1)
                    val frames: List<Bitmap> = try {
                        r.getFramesAtIndex(first, last - first + 1)
                    } catch (e: Exception) {
                        throw Failure.Reader("frames $first–$last: ${e.message ?: e}")
                    }
                    try {
                        if (frames.size != last - first + 1) throw Failure.Reader("frames $first–$last: se obtuvieron ${frames.size}")
                        for ((k, frame) in frames.withIndex()) {
                            ensureActive()
                            val index = first + k
                            if (index in wantedSet) write(index, frame)
                        }
                    } finally {
                        frames.forEach { it.recycle() }
                    }
                    while (i < wanted.size && wanted[i] <= last) i += 1
                }
            }
            if (written == 0) throw Failure.NothingExtracted()
            written
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Upright frame. The platform already applies the track rotation on recent versions; if a bitmap still has the
     * stored (unrotated) size of a 90°/270° video it is rotated here.
     */
    private fun orient(frame: Bitmap, rotation: Int, storedWidth: Int, storedHeight: Int): Bitmap {
        if ((rotation == 90 || rotation == 270) && storedWidth != storedHeight &&
            frame.width == storedWidth && frame.height == storedHeight
        ) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
        }
        return frame
    }

    /** Scaled by `range.factor` (floor of the size, like `cv2.resize` in desktop). */
    private fun scale(frame: Bitmap): Bitmap {
        val factor = range.factor
        if (!(factor > 0 && factor < 1)) return frame
        val w = max(1, floor(frame.width * factor).toInt())
        val h = max(1, floor(frame.height * factor).toInt())
        return Bitmap.createScaledBitmap(frame, w, h, true)
    }

    companion object {
        /** Desktop lists every file in frames/: start from a clean folder. */
        fun cleanFolder(framesFolder: File) {
            framesFolder.mkdirs()
            framesFolder.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        }
    }
}
