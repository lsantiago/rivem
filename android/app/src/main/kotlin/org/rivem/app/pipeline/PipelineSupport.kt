package org.rivem.app.pipeline

import org.rivem.app.media.ImageCoding
import org.rivem.core.geometry.CameraMatrix
import org.rivem.core.geometry.ROIMask
import org.rivem.core.imaging.NPY
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONValue
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Error with a message for the user (Spanish), raised by the pipeline steps. */
class PipelineError(message: String) : Exception(message) {
    override fun toString(): String = message ?: "Error"
}

/** Thread-safe cancellation flag shared between the UI and a background computation. */
class CancellationFlag {
    private val value = AtomicBoolean(false)
    val isCancelled: Boolean get() = value.get()
    fun cancel() = value.set(true)
}

/** Message of an exception for the user: the message alone for our own errors, like Swift's `"\(error)"`. */
fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * Names and readers of the project files the pipeline writes besides those `RiverProject` already knows
 * (docs/desktop-format.md §1.2).
 */
object ProjectFiles {
    const val transformedImage = "transformed_image.png"
    const val orthoImage = "ortho_image.png"
    const val grp3D = "grp_3d.json"
    const val fullGRP3D = "full_grp_3d.json"
    const val cameraSolution = "camera_solution_3d.json"
    const val maskNPY = "mask.npy"
    const val maskPNG = "mask.png"
    const val bbox = "bbox.json"
    const val finalMaskNPY = "final_mask.npy"
    const val finalMaskPNG = "final_mask.png"

    fun url(project: RiverProject, name: String): File = File(project.folder, name)

    fun exists(project: RiverProject, name: String): Boolean = url(project, name).exists()

    fun remove(project: RiverProject, names: List<String>) {
        for (n in names) {
            val u = url(project, n)
            if (u.exists()) u.delete()
        }
    }

    /** The video copied into the project folder (looked up by file name, like every stored path). */
    fun videoURL(project: RiverProject): File? {
        val stored = project.settings.video?.filepath ?: return null
        val u = project.resolve(stored)
        return if (u.isFile) u else null
    }

    /** Size of the extracted frames (first frame), the pixel space of every geometry file. */
    fun frameSize(project: RiverProject): Pair<Int, Int>? {
        val first = project.frameFiles.firstOrNull() ?: return null
        return ImageCoding.pixelSize(first)
    }

    /** `bbox.json` → `[x, y, w, h]`. */
    fun readBBox(project: RiverProject): List<Double>? {
        val u = url(project, bbox)
        if (!u.exists()) return null
        val list = try { JSON.read(u).doubleArray } catch (_: Exception) { null } ?: return null
        return if (list.size == 4) list else null
    }

    /** The mask PIV uses: `final_mask.npy` if present, else `mask.npy` (desktop's search order). */
    fun readMask(project: RiverProject): ROIMask? {
        for (name in listOf(finalMaskNPY, maskNPY)) {
            val u = url(project, name)
            if (!u.exists()) continue
            try {
                return NPY.decodeMask(u.readBytes())
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Camera of a fixed-station (ipcam) project, from `camera_solution_3d.json`. */
    fun readCamera(project: RiverProject): CameraMatrix? {
        val u = url(project, cameraSolution)
        if (!u.exists()) return null
        val obj = try { JSON.read(u).objectValue } catch (_: Exception) { null } ?: return null
        val rows = obj["camera_matrix"]?.arrayValue?.mapNotNull { it.doubleArray } ?: return null
        if (rows.size != 3 || !rows.all { it.size == 4 }) return null
        return CameraMatrix.fromRows(rows)
    }
}

/** Number formatting shared by the screens and the reports (always with a decimal point, like the iOS app). */
object Fmt {
    fun num(v: Double?, digits: Int = 2): String {
        if (v == null || !v.isFinite()) return "—"
        return String.format(Locale.ROOT, "%.${digits}f", v)
    }

    fun num(v: Int?): String = v?.toString() ?: "—"

    fun percent(fraction: Double?, digits: Int = 0): String {
        if (fraction == null || !fraction.isFinite()) return "—"
        return String.format(Locale.ROOT, "%.${digits}f %%", fraction * 100)
    }

    /** At most [maxDigits] decimals, trailing zeros removed (Swift `.precision(.fractionLength(0...n))`). */
    fun upTo(v: Double?, maxDigits: Int = 2): String {
        if (v == null || !v.isFinite()) return "—"
        val s = BigDecimal(v).setScale(maxDigits, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    /** [digits] significant digits, plain notation (Swift `.precision(.significantDigits(n))`). */
    fun significant(v: Double, digits: Int = 6): String {
        if (!v.isFinite()) return "—"
        if (v == 0.0) return "0"
        return BigDecimal(v).round(MathContext(digits)).stripTrailingZeros().toPlainString()
    }

    /** Parses a number typed with either decimal separator. */
    fun parse(text: String): Double? {
        val t = text.trim().replace(",", ".")
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()
    }
}

/** Display units. Everything on disk is SI (desktop §2.0); `unit_system` only changes what is shown. */
data class UnitDisplay(val system: UnitSystem) {
    companion object {
        private const val foot: Double = 0.3048
    }

    private val imperial: Boolean get() = system == UnitSystem.imperial

    val lengthUnit: String get() = if (imperial) "ft" else "m"
    val velocityUnit: String get() = if (imperial) "ft/s" else "m/s"
    val areaUnit: String get() = if (imperial) "ft²" else "m²"
    val dischargeUnit: String get() = if (imperial) "ft³/s" else "m³/s"

    fun length(m: Double): Double = if (imperial) m / foot else m
    fun velocity(ms: Double): Double = if (imperial) ms / foot else ms
    fun area(m2: Double): Double = if (imperial) m2 / (foot * foot) else m2
    fun discharge(m3s: Double): Double = if (imperial) m3s / (foot * foot * foot) else m3s

    /** A length typed in the display unit, back to metres. */
    fun lengthToSI(v: Double): Double = if (imperial) v * foot else v

    fun lengthText(m: Double?, digits: Int = 2): String = if (m == null) "—" else "${Fmt.num(length(m), digits)} $lengthUnit"
    fun velocityText(v: Double?, digits: Int = 2): String = if (v == null) "—" else "${Fmt.num(velocity(v), digits)} $velocityUnit"
    fun areaText(a: Double?, digits: Int = 2): String = if (a == null) "—" else "${Fmt.num(area(a), digits)} $areaUnit"
    fun dischargeText(q: Double?, digits: Int = 3): String = if (q == null) "—" else "${Fmt.num(discharge(q), digits)} $dischargeUnit"
}

/** A number for JSON files read by desktop's Node code with `JSON.parse` (no NaN tokens): non-finite → null. */
fun finiteOrNull(d: Double): JSONValue = if (d.isFinite()) JSONValue.Double(d) else JSONValue.Null

/** Moves a file, copying across file systems (app cache → external files) when a rename is not possible. */
fun moveFile(source: File, dest: File) {
    if (source.renameTo(dest)) return
    source.copyTo(dest, overwrite = false)
    source.delete()
}
