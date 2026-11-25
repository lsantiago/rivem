package org.rivem.app.pipeline

import android.content.Context
import org.rivem.app.diagnostics.AppLog
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CrossSection
import org.rivem.core.project.JSONValue
import org.rivem.core.project.RiverProject
import org.rivem.core.section.DischargeCalculator
import org.rivem.core.section.DischargeOptions
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the Results screen shows for one section, read back from `xsections.json` (works for files written by the
 * app or by desktop's Python).
 */
class SectionResult private constructor(private val s: CrossSection, val distance: List<Double>) {
    val name: String = s.name
    val id: String get() = name

    val interpolated: Boolean = s.json["interpolated"]?.boolValue ?: false
    val artificialSeeding: Boolean = s.json["artificial_seeding"]?.boolValue ?: false

    private fun arr(k: String): List<Double> = s.json[k]?.doubleArray ?: emptyList()

    /** Surface velocity used for the discharge (filled / seeded profile when those options are on), m/s. */
    val velocity: List<Double> = run {
        var v = arr("streamwise_velocity_magnitude")
        if (artificialSeeding) {
            val seeded = if (interpolated) arr("filled_seeded_vel_profile") else arr("seeded_vel_profile")
            if (seeded.size == distance.size) v = seeded
        } else if (interpolated) {
            val filled = arr("filled_streamwise_velocity_magnitude")
            if (filled.size == distance.size) v = filled
        }
        v
    }

    /** Measured surface velocity (`streamwise_velocity_magnitude`, NaN where none), before filling. */
    val rawVelocity: List<Double> = arr("streamwise_velocity_magnitude")
    val minusStd: List<Double> = arr("minus_std")
    val plusStd: List<Double> = arr("plus_std")
    val depth: List<Double> = arr("depth")
    val q: List<Double> = arr("Q")

    /** Wetted area per station (m²). */
    val area: List<Double> = arr("A")

    /** 5th / 95th percentile of the surface velocity over the pairs (m/s). */
    val p5: List<Double> = arr("5th_percentile")
    val p95: List<Double> = arr("95th_percentile")
    val check: List<Boolean> = parseCheck(s.json["check"])
    val stationX: List<Double> = arr("x")
    val stationY: List<Double> = arr("y")

    /** Real-world station coordinates and streamwise direction (m), for desktop's chevron glyph. */
    val east: List<Double> = arr("east")
    val north: List<Double> = arr("north")
    val streamEast: List<Double> = arr("streamwise_east")
    val streamNorth: List<Double> = arr("streamwise_north")
    val streamX: List<Double> = arr("streamwise_x")
    val streamY: List<Double> = arr("streamwise_y")
    val totalQ: Double? = s.json["total_Q"]?.doubleValue
    val totalQStd: Double? = s.json["total_q_std"]?.doubleValue
    val totalA: Double? = s.json["total_A"]?.doubleValue
    val totalW: Double? = s.json["total_W"]?.doubleValue
    val meanV: Double? = s.json["mean_V"]?.doubleValue
    val meanVs: Double? = s.json["mean_Vs"]?.doubleValue
    val measuredQ: Double? = s.json["measured_Q"]?.doubleValue
    val interpolatedQ: Double? = s.json["interpolated_Q"]?.doubleValue
    val level: Double? = s.level
    val alpha: Double? = s.alpha

    val stationCount: Int get() = distance.size

    fun value(a: List<Double>, i: Int): Double? {
        if (i < 0 || i >= a.size || !a[i].isFinite()) return null
        return a[i]
    }

    /** Station counts in the discharge (`check`, true when missing). */
    fun isChecked(i: Int): Boolean = i >= check.size || check[i]

    companion object {
        /** Null when the section has no results yet (no `distance`). */
        fun from(section: CrossSection): SectionResult? {
            val distance = section.json["distance"]?.doubleArray ?: return null
            return SectionResult(section, distance)
        }

        fun parseCheck(v: JSONValue?): List<Boolean> =
            (v?.arrayValue ?: emptyList()).map { it.boolValue ?: ((it.doubleValue ?: 1.0) != 0.0) }
    }
}

class ResultsOutcome(val results: List<SectionResult>, val errors: List<String>)

/**
 * Step 5 (desktop "Results" → `update-xsection` per section): discharge of every section from `piv_results.json`
 * (`DischargeCalculator`, frame step = `video_range.step`, fps = `video.fps`), written into `xsections.json`.
 */
object ResultsPipeline {
    /** Blocking: call it off the main thread. */
    fun compute(project: RiverProject, interpolate: Boolean, artificialSeeding: Boolean): ResultsOutcome {
        val piv = project.readPIVResults() ?: throw PipelineError("No hay resultados PIV: ejecuta el procesamiento.")
        val matrix = project.readMatrix() ?: throw PipelineError("Falta la matriz de transformación.")
        val sections = project.readCrossSections()
        if (sections == null || sections.sections.isEmpty()) throw PipelineError("No hay secciones definidas.")
        val range = project.settings.videoRange
        val fps = project.settings.video?.fps
        if (range == null || fps == null || fps <= 0) throw PipelineError("Faltan video_range o video.fps en settings.json.")
        val options = DischargeOptions(frameStep = range.step, fps = fps, interpolate = interpolate, artificialSeeding = artificialSeeding)
        val errors = ArrayList<String>()
        for (i in sections.sections.indices) {
            val s = sections.sections[i]
            val url = project.bathymetryURL(s)
            if (url == null) {
                errors.add("${s.name}: la batimetría no está en la carpeta del proyecto")
                continue
            }
            try {
                val bathymetry = Bathymetry.read(url)
                val r = DischargeCalculator.update(sections, i, bathymetry, piv, matrix, options)
                AppLog.info(
                    "Caudal ${s.name}: Q = ${Fmt.num(r.totalQ, 4)} m3/s, A = ${Fmt.num(r.totalA, 3)} m2, " +
                        "W = ${Fmt.num(r.totalW, 3)} m, Vm = ${Fmt.num(r.meanV, 3)} m/s, medido ${Fmt.percent(r.measuredQ)}",
                )
            } catch (e: Exception) {
                errors.add("${s.name}: ${e.userMessage()}")
                AppLog.error("Caudal ${s.name}: $e")
            }
        }
        project.writeCrossSections(sections)
        return ResultsOutcome(sections.sections.mapNotNull { SectionResult.from(it) }, errors)
    }

    /**
     * Includes or excludes one station of a section (`check` in `xsections.json`, as desktop's results table does).
     * Call [compute] afterwards.
     */
    fun setCheck(project: RiverProject, section: String, station: Int, included: Boolean) {
        val sections = project.readCrossSections()
        val index = sections?.sections?.indexOfFirst { it.name == section } ?: -1
        if (sections == null || index < 0) throw PipelineError("No se encontró la sección $section.")
        val s = sections.sections[index].copy()
        val count = s.json["distance"]?.doubleArray?.size ?: 0
        var check = SectionResult.parseCheck(s.json["check"]).toMutableList()
        if (check.size != count) check = MutableList(count) { true }
        if (station < 0 || station >= count) throw PipelineError("Estación fuera de rango.")
        check[station] = included
        s.json["check"] = JSONValue.array(check.map { JSONValue.bool(it) })
        sections.sections[index] = s
        project.writeCrossSections(sections)
        AppLog.info("Sección $section: estación ${station + 1} ${if (included) "incluida" else "excluida"}")
    }

    fun read(project: RiverProject): List<SectionResult> {
        val sections = try { project.readCrossSections() } catch (_: Exception) { null } ?: return emptyList()
        return sections.sections.mapNotNull { SectionResult.from(it) }
    }

    /** One row per station of every section (SI units, dot decimals). */
    fun stationsCSV(results: List<SectionResult>): String {
        val out = StringBuilder("section,station,distance_m,depth_m,velocity_ms,minus_std_ms,plus_std_ms,Q_m3s,check,x_px,y_px\n")
        for (r in results) {
            for (i in 0 until r.stationCount) {
                val cols = listOf(
                    r.name, "${i + 1}",
                    csvNumber(r.value(r.distance, i)), csvNumber(r.value(r.depth, i)), csvNumber(r.value(r.velocity, i)),
                    csvNumber(r.value(r.minusStd, i)), csvNumber(r.value(r.plusStd, i)), csvNumber(r.value(r.q, i)),
                    if (i < r.check.size) (if (r.check[i]) "1" else "0") else "",
                    csvNumber(r.value(r.stationX, i)), csvNumber(r.value(r.stationY, i)),
                )
                out.append(cols.joinToString(",")).append('\n')
            }
        }
        out.append("\nsection,total_Q_m3s,total_q_std_m3s,total_A_m2,total_W_m,mean_V_ms,mean_Vs_ms,measured_Q\n")
        for (r in results) {
            val cols = listOf(
                r.name, csvNumber(r.totalQ), csvNumber(r.totalQStd), csvNumber(r.totalA), csvNumber(r.totalW),
                csvNumber(r.meanV), csvNumber(r.meanVs), csvNumber(r.measuredQ),
            )
            out.append(cols.joinToString(",")).append('\n')
        }
        return out.toString()
    }

    /** C's `%.6g` (what Swift's `String(format:)` writes). */
    private fun csvNumber(v: Double?): String {
        if (v == null || !v.isFinite()) return ""
        if (v == 0.0) return "0"
        val exp = Math.floor(Math.log10(Math.abs(v))).toInt()
        return if (exp < -4 || exp >= 6) {
            val s = String.format(Locale.ROOT, "%.5e", v)
            val mantissa = s.substringBefore('e').trimEnd('0').trimEnd('.')
            val e = s.substringAfter('e').toInt()
            mantissa + "e" + (if (e < 0) "-" else "+") + String.format(Locale.ROOT, "%02d", Math.abs(e))
        } else {
            val decimals = (5 - exp).coerceAtLeast(0)
            val s = String.format(Locale.ROOT, "%.${decimals}f", v)
            if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
        }
    }
}

/**
 * Step 6 (desktop "Report"/export): the project folder as a zip that unpacks as `<name>/<YYYYMMDDTHHMM>/` (drop it
 * in desktop's RIVeR workspace), and the stations CSV. Files go to the app cache (`export/`), shared through the
 * FileProvider (`cache-path`).
 */
object ExportPipeline {
    fun exportFolder(context: Context): File = File(context.cacheDir, "export")

    /** `<name>_<stamp>` of a project folder `RIVeR/<name>/<stamp>`. */
    fun baseName(project: RiverProject): String =
        "${project.folder.absoluteFile.parentFile?.name ?: "aforo"}_${project.folder.name}"

    /** Blocking: call it off the main thread. */
    fun zip(context: Context, project: RiverProject, includeVideo: Boolean): File {
        val nameFolder = project.folder.absoluteFile.parentFile?.name ?: "aforo"
        val stamp = project.folder.name
        val folder = exportFolder(context)
        folder.mkdirs()
        val dest = File(folder, "${nameFolder}_$stamp.zip")
        if (dest.exists()) dest.delete()
        val video: File? = if (includeVideo) null else ProjectFiles.videoURL(project)?.canonicalFile
        val root = project.folder.canonicalFile
        val tmp = File(folder, dest.name + ".part")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                zip.putNextEntry(ZipEntry("$nameFolder/"))
                zip.closeEntry()
                fun add(dir: File, prefix: String) {
                    zip.putNextEntry(ZipEntry("$prefix/"))
                    zip.closeEntry()
                    val children = dir.listFiles()?.sortedBy { it.name } ?: return
                    for (f in children) {
                        if (f.isDirectory) {
                            add(f, "$prefix/${f.name}")
                        } else if (f.isFile) {
                            if (video != null && f.canonicalFile == video) continue
                            val entry = ZipEntry("$prefix/${f.name}")
                            entry.time = f.lastModified()
                            zip.putNextEntry(entry)
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                add(root, "$nameFolder/$stamp")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        AppLog.info("Proyecto exportado: ${dest.name}")
        return dest
    }

    fun stationsCSV(context: Context, project: RiverProject): File {
        val results = ResultsPipeline.read(project)
        if (results.isEmpty()) throw PipelineError("No hay resultados de caudal para exportar.")
        val folder = exportFolder(context)
        folder.mkdirs()
        val file = File(folder, "${baseName(project)}_estaciones.csv")
        file.writeText(ResultsPipeline.stationsCSV(results), Charsets.UTF_8)
        return file
    }
}
