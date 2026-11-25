package org.rivem.app.pipeline

import org.rivem.app.diagnostics.AppLog
import org.rivem.app.media.FrameExtractor
import org.rivem.app.media.VideoMetadata
import org.rivem.core.project.Footage
import org.rivem.core.project.JSONValue
import org.rivem.core.project.RiverProject
import org.rivem.core.project.VideoInfo
import org.rivem.core.project.VideoRange
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** Step 0 (desktop "New project"): project folder + `settings.json` + the video copied inside the project. */
object ProjectCreation {
    /** ASCII folder/file name (desktop paths go through Latin-1 on Windows, docs/desktop-format.md §6.1). */
    fun safeName(proposed: String, fallback: String = "aforo"): String {
        val folded = Normalizer.normalize(proposed, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val out = StringBuilder()
        for (ch in folded) {
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_') {
                out.append(ch)
            } else if (ch == ' ' || ch == '.') {
                out.append('_')
            }
        }
        val s = out.toString().trimStart('_')
        return s.ifEmpty { fallback }
    }

    /** Video file name inside the project: sanitised base name + lower-case extension. */
    fun videoFileName(source: File): String {
        val ext = source.extension.lowercase(Locale.ROOT)
        val base = safeName(source.nameWithoutExtension, fallback = "video")
        return if (ext.isEmpty()) "$base.mov" else "$base.$ext"
    }

    /**
     * Creates `<root>/<name>/<YYYYMMDDTHHMM>/` with `settings.json`, empty `frames/` and the video. `video.filepath`
     * stores only the file name: desktop relocates project-owned paths by name, and for the video it opens the
     * project anyway and offers "Locate" when the path does not exist (§6.2).
     * @param moveSource move (temporary copies from the gallery / the camera) instead of copying.
     */
    fun create(
        root: File, name: String, footage: Footage, source: File, metadata: VideoMetadata,
        moveSource: Boolean, riverName: String = "", site: String = "",
    ): RiverProject {
        val fileName = videoFileName(source)
        val info = VideoInfo(
            filepath = fileName, name = fileName, duration = metadata.duration, fps = metadata.fps,
            width = metadata.width, height = metadata.height,
        )
        val project = RiverProject.create(root, safeName(name), footage, info)
        if (riverName.isNotEmpty() || site.isNotEmpty()) {
            // Desktop writes these metadata keys at the report step (`setProjectMetadata.ts`).
            if (riverName.isNotEmpty()) project.settings.json["river_name"] = JSONValue.String(riverName)
            if (site.isNotEmpty()) project.settings.json["site"] = JSONValue.String(site)
            project.save()
        }
        val dest = File(project.folder, fileName)
        try {
            if (moveSource) moveFile(source, dest) else source.copyTo(dest, overwrite = false)
        } catch (e: Exception) {
            project.folder.deleteRecursively()
            throw PipelineError("No se pudo copiar el video al proyecto: ${e.userMessage()}")
        }
        AppLog.info(
            "Proyecto creado ${project.folder.name} (${footage.rawValue}), video $fileName " +
                "${metadata.width}x${metadata.height} ${String.format(Locale.ROOT, "%.3f", metadata.fps)} fps " +
                String.format(Locale.ROOT, "%.2f", metadata.duration) + " s",
        )
        return project
    }
}

/** Step 1 (desktop "Video Range"): `video_range` and `frames/`. */
object FramesPipeline {
    /** Desktop defaults: whole video, step 1, factor 1 (`projectSlice.ts`), `end = floor(duration · fps)`. */
    fun defaultRange(video: VideoInfo): VideoRange {
        val fps = video.fps ?: 30.0
        val duration = video.duration ?: 0.0
        return VideoRange(start = 0, end = floor(duration * fps).toInt(), step = 1, factor = 1.0)
    }

    /** Desktop converts the times typed in the form with `floor(t · fps)` (`useProjectSlice.ts`). */
    fun range(startTime: Double, endTime: Double, step: Int, factor: Double, fps: Double): VideoRange {
        val start = floor(max(startTime, 0.0) * fps).toInt()
        val end = floor(max(endTime, 0.0) * fps).toInt()
        return VideoRange(start = start, end = end, step = max(step, 1), factor = factor)
    }

    /** Writes `video_range` (+ the keys desktop writes with it). No lens correction or stabilisation is applied. */
    fun saveRange(project: RiverProject, range: VideoRange) {
        project.settings.videoRange = range
        project.settings.json["lens_correction"] = JSONValue.Null
        project.settings.json["stabilization"] = JSONValue.Null
        project.settings.json["stabilization_regions_path"] = JSONValue.Null
        project.save()
    }

    class Outcome(
        val project: RiverProject,
        val written: Int,
        /** The frame size changed while a geometry existed: the pixel coordinates no longer match the frames. */
        val sizeChanged: Boolean,
    )

    /**
     * Extracts the frames ([FrameExtractor]), then writes `video_range`. PIV results are discarded (they belong to
     * the previous frames); geometry and sections are kept. The project passed in is not modified: the outcome
     * holds an updated copy.
     */
    suspend fun extract(project: RiverProject, range: VideoRange, progress: (Double) -> Unit): Outcome {
        val video = ProjectFiles.videoURL(project)
            ?: throw PipelineError("El video no está en la carpeta del proyecto (${project.settings.video?.name ?: "?"}).")
        if (range.end <= range.start || range.frameIndices.isEmpty()) {
            throw PipelineError("El rango no contiene frames: revisa inicio, fin y paso.")
        }
        val before = ProjectFiles.frameSize(project)
        val hadMatrix = try { project.readMatrix() != null } catch (_: Exception) { false }
        val started = System.nanoTime()
        val framesFolder = File(project.folder, RiverProject.framesFolder)
        val written = FrameExtractor(video, range).extract(framesFolder, progress)
        val updated = RiverProject(project.folder, project.settings.copy())
        saveRange(updated, range)
        if (updated.settings.pivResultsPath != null) {
            ProjectFiles.remove(updated, listOf(RiverProject.pivResultsFile, "_stats_cache.json"))
            updated.settings.pivResultsPath = null
            updated.save()
        }
        val after = ProjectFiles.frameSize(updated)
        val changed = before != null && after != null && hadMatrix && before != after
        AppLog.info(
            "Frames extraídos: $written (inicio ${range.start}, fin ${range.end}, paso ${range.step}, factor ${range.factor}) en " +
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9) + " s",
        )
        return Outcome(updated, written, changed)
    }
}
