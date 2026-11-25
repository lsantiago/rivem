package org.rivem.app.pipeline

import android.content.Context
import org.rivem.app.diagnostics.AppLog
import org.rivem.core.geometry.ObliqueDistances
import org.rivem.core.geometry.Point2
import org.rivem.core.project.Footage
import org.rivem.core.project.JSONValue
import org.rivem.core.project.RiverProject
import org.rivem.core.project.VideoInfo
import org.rivem.core.project.VideoRange
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * RIVeR's `pisco` example (oblique camera, 30 frames) installed as a project. On iOS steps 1–3 (frames,
 * georeferencing, cross section with its bathymetry and ROI mask) are filled with the same pipeline functions as the
 * screens, so the user only runs PIV and Results.
 *
 * Android, this version: step 1 (frames, `video_range`) is installed; steps 2 and 3 are filled by [completeSteps]
 * once `GeometryPipeline` / `SectionsPipeline` are ported (the constants below are those of the iOS app).
 *
 * Data (RIVeR 3.8.3, commit `ea65612`, AGPL-3.0, © ORUS): the frames `examples/data/frames/pisco` (downloaded into
 * `app/src/main/assets/pisco/frames` by `ci/fetch-example.sh` and bundled as assets),
 * `examples/data/bathymetry/pisco_section.csv` (asset `pisco/pisco_section.csv`), and the parameters of the notebooks
 * `03_cross_sections` / `05_discharge_calculation` (fps 25, step 2, level 1.76 m, 15 stations, alpha 1 →
 * Q = 50.52 m³/s in RIVeR).
 */
object ExampleProject {
    const val projectName = "Ejemplo_Pisco"
    const val riverName = "Río Pisco (ejemplo de RIVeR)"
    const val fps: Double = 25.0
    const val step = 2
    const val width = 1920
    const val height = 1080

    /** 1, 2 on the left bank (near, far), 3, 4 on the right bank (far, near). */
    val pixels: List<Point2> = listOf(Point2(49.94, 935.04), Point2(240.0, 470.0), Point2(1250.0, 480.0), Point2(1800.19, 687.6))
    val distances = ObliqueDistances(d12 = 29.426, d23 = 28.746, d34 = 15.83, d41 = 22.154, d13 = 28.163, d24 = 38.029)
    const val sectionName = "Seccion_Pisco"
    val sectionLeft = Point2(49.94, 935.04)
    val sectionRight = Point2(1800.19, 687.6)

    /** Desktop's GUI default level (lower bank to cm); the notebook uses 1.765 m. */
    const val level: Double = 1.76
    const val numStations = 15
    const val alpha: Double = 1.0
    const val bathymetryFile = "pisco_section.csv"

    /** Discharge of RIVeR's notebook 05 (its own PIV field, interpolation on). */
    const val referenceQ: Double = 50.52

    const val assetFolder = "pisco"
    const val framesAsset = "pisco/frames"
    const val bathymetryAsset = "pisco/pisco_section.csv"

    fun bundledFrames(context: Context): List<String> = try {
        context.assets.list(framesAsset)?.filter { it.lowercase(Locale.ROOT).endsWith(".jpg") }?.sorted() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** The example is only offered when the build bundled its frames (CI builds do). */
    fun isAvailable(context: Context): Boolean = bundledFrames(context).size >= 2

    /**
     * Hook for the geometry / sections phase: fills steps 2 (oblique georeferencing with [pixels] and [distances])
     * and 3 (bathymetry from [bathymetryAsset], section [sectionLeft]–[sectionRight], [level], [numStations],
     * [alpha]). Until then it leaves the project at step 1.
     */
    var completeSteps: ((Context, RiverProject) -> Unit)? = null

    /** Creates `RIVeR/Ejemplo_Pisco/<date>/` and fills the steps available. Blocking: call it off the main thread. */
    fun install(context: Context, root: File): RiverProject {
        val frames = bundledFrames(context)
        if (frames.size < 2) throw PipelineError("Esta compilación no incluye los frames del ejemplo.")
        val started = System.nanoTime()
        val info = VideoInfo(
            filepath = "pisco.mp4", name = "pisco.mp4", duration = (frames.size * step).toDouble() / fps,
            fps = fps, width = width, height = height,
        )
        val project = RiverProject.create(root, projectName, Footage.oblique, info)
        project.settings.json["river_name"] = JSONValue.String(riverName)
        try {
            // 1. Frames: RIVeR's own extraction (every 2nd frame, names = source frame index).
            FramesPipeline.saveRange(project, VideoRange(start = 0, end = frames.size * step, step = step, factor = 1.0))
            val framesFolder = File(project.folder, RiverProject.framesFolder)
            framesFolder.mkdirs()
            for (name in frames) {
                context.assets.open("$framesAsset/$name").use { input ->
                    FileOutputStream(File(framesFolder, name)).use { input.copyTo(it) }
                }
            }
            // 2–3. Georeferencing and cross section (next phase).
            completeSteps?.invoke(context, project)
        } catch (e: Exception) {
            // Do not leave a half-built example in the list.
            project.folder.deleteRecursively()
            val parent = project.folder.absoluteFile.parentFile
            if (parent != null && parent.list()?.isEmpty() == true) parent.delete()
            throw e
        }
        AppLog.info(
            "Ejemplo Pisco instalado en ${project.folder.name}: ${frames.size} frames, " +
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9) + " s",
        )
        return project
    }
}
