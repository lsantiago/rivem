package org.rivem.app.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.media.ImageCoding
import org.rivem.core.imaging.GrayImage
import org.rivem.core.piv.PIVError
import org.rivem.core.piv.PIVFailedPair
import org.rivem.core.piv.PIVMask
import org.rivem.core.piv.PIVParameters
import org.rivem.core.piv.PIVSequence
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.PIVResults
import org.rivem.core.project.ProjectSettings
import org.rivem.core.project.RiverProject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class ProcessingOutcome(
    val pairs: Int,
    val failed: List<PIVFailedPair>,
    val seconds: Double,
    val rows: Int,
    val columns: Int,
    val parallel: Boolean,
    val maskUsed: Boolean,
    val results: PIVResults,
)

/**
 * Step 4 (desktop "Processing" → `analyze-all`): PIV over every pair of consecutive frames with the mask and
 * bounding box of the sections, written as `piv_results.json` + `settings.piv_results` + `settings.processing`
 * (docs/desktop-format.md §2.1, §2.5).
 */
object ProcessingPipeline {
    /** First-pass windows offered by desktop. */
    val interrogationAreas: List<Int> = listOf(512, 256, 128, 64)

    /** Parameters stored in `settings.processing`, or desktop's defaults. */
    fun parameters(settings: ProjectSettings): PIVParameters {
        val o = settings.json["processing"]?.objectValue
        return if (o != null) PIVParameters(o) else PIVParameters()
    }

    /**
     * `settings.processing` with desktop's keys and order (`getQuiver.ts`). STIV/iWave flags already present are
     * kept (the app does not run them); otherwise false.
     */
    fun processingJSON(p: PIVParameters, roiHeight: Double?, existing: JSONObject?): JSONObject {
        val roi: Double = roiHeight ?: existing?.get("roi_height")?.doubleValue ?: p.roiHeight ?: 0.0
        val roiValue: JSONValue = if (roi == Math.rint(roi) && abs(roi) < 1e9) JSONValue.int(roi.roundToLong()) else JSONValue.double(roi)
        return JSONObject(
            "interrogation_area_1" to JSONValue.int(p.interrogationArea1),
            "interrogation_area_2" to JSONValue.int(p.interrogationArea1 / 2),
            "roi_height" to roiValue,
            "remove_background" to JSONValue.bool(p.removeBackground),
            "clahe" to JSONValue.bool(p.clahe),
            "clip_limit" to JSONValue.double(p.clipLimit),
            "std_filtering" to JSONValue.bool(p.stdFiltering),
            "std_threshold" to JSONValue.double(p.stdThreshold),
            "median_test_filtering" to JSONValue.bool(p.medianTestFiltering),
            "median_test_epsilon" to JSONValue.double(p.medianTestEpsilon),
            "median_test_threshold" to JSONValue.double(p.medianTestThreshold),
            "stiv" to (existing?.get("stiv") ?: JSONValue.bool(false)),
            "iwave" to (existing?.get("iwave") ?: JSONValue.bool(false)),
            "iwave_manual" to (existing?.get("iwave_manual") ?: JSONValue.Null),
        )
    }

    /**
     * Pairs run concurrently (one worker per core) when the frames are up to ~1080p and the workers fit in the heap:
     * each one holds two decoded frames plus their preprocessed copies (~6 float copies of a frame). 4K frames, or
     * a small heap, run one pair at a time to stay far from the memory limit (iOS does the same).
     */
    fun shouldRunParallel(width: Int, height: Int): Boolean {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores <= 1 || width.toLong() * height > 2_100_000L) return false
        val perWorker = width.toLong() * height * 4 * 6
        return perWorker * cores < Runtime.getRuntime().maxMemory() * 0.6
    }

    /**
     * Grayscale frame (0–255) straight from the JPEG: luminance 0.299 R + 0.587 G + 0.114 B like the core's
     * `RGBAImage.toGray`, row by row so no RGBA copy of the whole frame is kept.
     */
    fun grayFrame(file: File): GrayImage {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw ImageCoding.Failure.Unreadable(file)
        try {
            val w = bitmap.width
            val h = bitmap.height
            val px = FloatArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                val base = y * w
                for (x in 0 until w) {
                    val c = row[x]
                    val r = (c shr 16 and 0xFF).toFloat()
                    val g = (c shr 8 and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()
                    px[base + x] = 0.299f * r + 0.587f * g + 0.114f * b
                }
            }
            return GrayImage(w, h, px)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Runs PIV and writes the results; [project]'s settings are updated and saved. Blocking: call it off the main
     * thread. Throws `PIVError.Cancelled` when [cancel] is set.
     *
     * @param progress `(pairsDone, pairsTotal)`, called from worker threads.
     */
    fun run(
        project: RiverProject,
        parameters: PIVParameters,
        parallel: Boolean? = null,
        progress: (Int, Int) -> Unit,
        cancel: CancellationFlag,
    ): ProcessingOutcome {
        parameters.validate()
        val frames: List<File> = project.frameFiles
        if (frames.size < 2) throw PipelineError("Se necesitan al menos 2 frames (hay ${frames.size}).")
        val size = ImageCoding.pixelSize(frames[0]) ?: throw PipelineError("No se pudo leer el primer frame.")
        val bbox: List<Double>? = ProjectFiles.readBBox(project)
        var mask: PIVMask? = null
        val m = ProjectFiles.readMask(project)
        if (m != null) {
            if (m.width == size.first && m.height == size.second && m.width > 0 && m.height > 0) {
                mask = PIVMask(m.width, m.height, m.values)
            } else {
                AppLog.error("La máscara (${m.width}x${m.height}) no coincide con los frames (${size.first}x${size.second}); se ignora")
            }
        }
        val useParallel = parallel ?: shouldRunParallel(size.first, size.second)
        val bboxText = bbox?.joinToString(",") { Fmt.num(it, 0) } ?: "-"
        AppLog.info(
            "PIV: ${frames.size} frames ${size.first}x${size.second}, IA ${parameters.interrogationArea1}/${parameters.effectiveInterrogationArea2}, " +
                "paralelo $useParallel, máscara ${mask != null}, bbox $bboxText",
        )
        val started = System.nanoTime()
        val result = try {
            PIVSequence.run(
                frameCount = frames.size,
                frame = { i -> grayFrame(frames[i]) },
                parameters = parameters,
                bbox = bbox,
                mask = mask,
                background = null,
                parallel = useParallel,
                progress = progress,
                isCancelled = { cancel.isCancelled },
            )
        } catch (e: PIVError.Cancelled) {
            AppLog.info("PIV cancelado por el usuario")
            throw e
        }
        result.results.write(File(project.folder, RiverProject.pivResultsFile))
        ProjectFiles.remove(project, listOf("_stats_cache.json"))
        project.settings.pivResultsPath = RiverProject.pivResultsFile
        val existing: JSONObject? = project.settings.json["processing"]?.objectValue
        project.settings.json["processing"] = JSONValue.obj(processingJSON(parameters, null, existing))
        project.save()
        val seconds = (System.nanoTime() - started) / 1e9
        for (f in result.failedPairs) AppLog.error("PIV: par ${f.index} fallido: ${f.reason}")
        AppLog.info(
            "PIV terminado: ${frames.size - 1} pares, ${result.failedPairs.size} fallidos, rejilla " +
                "${result.results.rows}x${result.results.columns}, ${String.format(Locale.ROOT, "%.1f", seconds)} s",
        )
        return ProcessingOutcome(
            pairs = frames.size - 1, failed = result.failedPairs, seconds = seconds,
            rows = result.results.rows, columns = result.results.columns,
            parallel = useParallel, maskUsed = mask != null, results = result.results,
        )
    }
}
