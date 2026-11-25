package org.rivem.core.section

import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CrossSection
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.swiftRounded
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Swift `SectionError` (Equatable: two errors are equal when they are the same case with the same values). */
sealed class SectionError(message: String) : Exception(message) {
    /** The water level does not cross the bed profile (desktop: `bathimetryNotValid`). */
    class LevelDoesNotCrossBathymetry : SectionError("El nivel del agua no corta la batimetría.")

    /** No bathymetry point is at or below the water level. */
    class DryCrossSection :
        SectionError("La sección está seca: ningún punto de la batimetría queda bajo el nivel del agua.")

    /** `left_station` puts the drawn left point outside the bathymetry (desktop: `CrossSections.Errors.leftBank`). */
    class LeftStationOutOfRange : SectionError("La progresiva de la margen izquierda está fuera de la batimetría.")

    /**
     * The level is above both banks once the GUI rounds the bed to cm: desktop finds no water-edge
     * intersection when it reopens the project and its results screen crashes (blank window).
     */
    class LevelAboveBanks(val maximum: Double) : SectionError(
        "El nivel del agua queda por encima de las dos orillas de la batimetría (redondeadas al cm, " +
            "como hace RIVeR escritorio). Usa un nivel de ${SectionBuilder.formatFixed2(maximum)} m o menor, " +
            "o agrega puntos más altos en las orillas.",
    ) {
        override val values: List<Any?> get() = listOf(maximum)
    }

    class MissingKey(val key: String) : SectionError("Falta la clave '$key' en la sección.") {
        override val values: List<Any?> get() = listOf(key)
    }

    class InvalidValue(val why: String) : SectionError("Valor no válido: $why") {
        override val values: List<Any?> get() = listOf(why)
    }

    /** Associated values of the case, for equality. */
    protected open val values: List<Any?> get() = emptyList()

    /** Swift `description`. */
    val description: String get() = message ?: ""

    override fun equals(other: Any?): Boolean =
        other is SectionError && other::class == this::class && other.values == values

    override fun hashCode(): Int = this::class.hashCode() * 31 + values.hashCode()
    override fun toString(): String = description
}

/**
 * Builds a cross-section exactly as RIVeR desktop's GUI writes it to `xsections.json`
 * (`adapterCrossSections.ts`, `useSectionSlice.ts` `onUpdateSectionPoints`/`onSetSections`, `getBathimetryValues.ts`).
 *
 * Meaning of the keys (and of `left_station`):
 * - The user draws a line on the frame: its pixel end points are `dir_xl…dir_yr`, mapped to the world by the
 *   matrix as `dir_east_l…dir_north_r`. The line only gives the section's position and direction.
 * - `left_station` ("Left Bank Station" in the GUI) is the distance, along the line, from the water's left edge
 *   (the first crossing of the level with the bed, `x1Intersection`) to the drawn left point. The bathymetry chart
 *   draws the drawn left point at bathymetry station `x1Intersection + left_station` (`Graphs/Bathimetry.tsx`).
 * - The section stored for the computation starts at the water's left edge:
 *   `east_l = dir_east_l − left_station·û` and runs the wetted width `rw_length = x2Intersection − x1Intersection`
 *   along û (the unit vector of the drawn line), so `east_r = east_l + rw_length·û`. `xl…yr` are those two points
 *   in pixels (world → pixel with the inverse matrix), and `east_l…north_r` come back through the matrix once more,
 *   as `onSetSections` does.
 *
 * Hence `left_station` is fully consumed here: the discharge computation must not apply it again (see
 * `DischargeCalculator`, PLAN #6).
 */
object SectionBuilder {
    /** Desktop defaults (`constants.ts`). */
    const val defaultAlpha = 0.85
    const val defaultNumStations = 15

    /** Values the GUI derives from the bathymetry (`getBathimetryValues`). */
    data class BathymetryLimits(
        var level: Double,
        var x1Intersection: Double,
        var x2Intersection: Double,
        var xMin: Double,
        var xMax: Double,
    ) {
        val width: Double get() = x2Intersection - x1Intersection
    }

    /**
     * C's `%.2f` (Swift `String(format:)`): the exact binary value rounded half-to-even. Java's `String.format`
     * rounds the shortest decimal representation half-up instead (1.765 → "1.77"), so it is not used.
     */
    internal fun formatFixed2(v: Double): String {
        if (!v.isFinite()) return if (v.isNaN()) "nan" else if (v > 0) "inf" else "-inf"
        return BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
    }

    /**
     * JavaScript's `parseFloat(v.toFixed(2))`, used by the GUI on bed elevations, default level and alpha.
     * `toFixed` rounds the exact binary value (1.765 is stored as 1.76499… → "1.76", where `(v*100).rounded()`
     * would give 1.77) and breaks exact ties away from zero (0.125 → "0.13"; C's `%.2f` gives "0.12").
     */
    internal fun round2(v: Double): Double {
        if (!v.isFinite()) return v
        val negative = v < 0
        val x = abs(v)
        val t = x * 100
        val eighths = x * 8
        val text: String = if (t - floor(t) == 0.5 && eighths == swiftRounded(eighths)) {
            formatFixed2(ceil(t) / 100)
        } else {
            formatFixed2(x)
        }
        val r: Double = text.toDoubleOrNull() ?: x
        return if (negative) -r else r
    }

    /**
     * Level and wetted limits as the GUI computes them.
     * - `level == null`: the default level is the lower of the two end elevations rounded to cm
     *   (`getBathimetryLimitsY`), and the limits are the first and last intersection points
     *   (`getIntersectionPoints`, bed elevations rounded to cm).
     * - `level` given (the user typed it): the limits are the ends of the first and last wet segments
     *   (`findWetSegments`, as `onUpdateSection({level})` does).
     * A flat stretch exactly at the level would make the GUI divide 0/0 (NaN limits); here its first end is used.
     */
    fun limits(bathymetry: Bathymetry, level: Double?): BathymetryLimits {
        val xs = bathymetry.stations
        val ys = bathymetry.elevations
        if (xs.size < 2 || xs.size != ys.size) throw SectionError.InvalidValue("batimetría con menos de dos puntos")
        val xMin = swiftMinOf(xs)
        val xMax = swiftMaxOf(xs)
        if (level != null) {
            val segments = wetSegmentsGUI(xs, ys, level)
            val first = segments.firstOrNull()
            val last = segments.lastOrNull()
            if (first == null || last == null) throw SectionError.LevelDoesNotCrossBathymetry()
            return BathymetryLimits(level, first.first, last.second, xMin, xMax)
        }
        val yFirst = ys[0]
        val yLast = ys[ys.size - 1]
        val yMax: Double = round2(if (yFirst >= yLast) yLast else yFirst)
        val points = intersectionPointsGUI(xs, ys, yMax)
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        if (first == null || last == null) throw SectionError.LevelDoesNotCrossBathymetry()
        return BathymetryLimits(yMax, first, last, xMin, xMax)
    }

    /** Swift `Array.min()` (first of equal minima, compared with `<`). */
    private fun swiftMinOf(a: List<Double>): Double {
        var m = a[0]
        for (v in a) if (v < m) m = v
        return m
    }

    private fun swiftMaxOf(a: List<Double>): Double {
        var m = a[0]
        for (v in a) if (m < v) m = v
        return m
    }

    /** `getIntersectionPoints`. */
    internal fun intersectionPointsGUI(xs: List<Double>, ys: List<Double>, level: Double): List<Double> {
        val out = ArrayList<Double>()
        for (i in 0 until xs.size - 1) {
            val cy = round2(ys[i])
            val ny = round2(ys[i + 1])
            if ((cy <= level && ny >= level) || (cy >= level && ny <= level)) {
                var x: Double = xs[i]
                if (ny != cy) {
                    val t: Double = (level - cy) / (ny - cy)
                    x = xs[i] + t * (xs[i + 1] - xs[i])
                }
                if (out.isEmpty() || out[out.size - 1] != x) out.add(x)
            }
        }
        return out
    }

    /** `findWetSegments` (GUI copy of Python's `find_wet_segments`, with bed elevations rounded to cm). */
    internal fun wetSegmentsGUI(xs: List<Double>, ys: List<Double>, level: Double): List<Pair<Double, Double>> {
        val merged = ArrayList<Pair<Double, Double>>()
        for (i in xs.indices) {
            val cy = round2(ys[i])
            merged.add(Pair(xs[i], cy))
            if (i < xs.size - 1) {
                val ny = round2(ys[i + 1])
                if ((cy < level && ny > level) || (cy > level && ny < level)) {
                    val t: Double = (level - cy) / (ny - cy)
                    merged.add(Pair(xs[i] + t * (xs[i + 1] - xs[i]), level))
                }
            }
        }
        // JS Array.prototype.sort is stable (and so is Kotlin's sortedWith).
        val sorted = merged.sortedWith { l, r ->
            val a = l.first
            val b = r.first
            when {
                a.isNaN() || b.isNaN() -> a.compareTo(b)
                a != b -> if (a < b) -1 else 1
                else -> 0
            }
        }
        val segments = ArrayList<Pair<Double, Double>>()
        var start: Double? = null
        for (i in sorted.indices) {
            val wet = sorted[i].second <= level
            val s = start
            if (wet && s == null) {
                start = sorted[i].first
            } else if (!wet && s != null) {
                segments.add(Pair(s, sorted[i - 1].first))
                start = null
            }
        }
        val s = start
        val last = sorted.lastOrNull()
        if (s != null && last != null) segments.add(Pair(s, last.first))
        return segments
    }

    /**
     * Builds the 22 keys desktop's GUI writes for a section, in the same order.
     * - drawnLeft/drawnRight: pixel end points of the line drawn on the frame (left bank first).
     * - matrix: pixel → real-world matrix (`transformation_matrix.json`).
     * - bathymetryPath: value stored in `bath`.
     * - leftStation: distance from the water's left edge to the drawn left point (GUI default 0).
     * - level: water level; null uses the GUI default (see `limits`).
     * - alpha: rounded to cm as the GUI does (`toFixed(2)`).
     */
    fun build(
        name: String,
        drawnLeft: Point2,
        drawnRight: Point2,
        matrix: Matrix3,
        bathymetry: Bathymetry,
        bathymetryPath: String,
        leftStation: Double = 0.0,
        level: Double? = null,
        numStations: Int = defaultNumStations,
        alpha: Double = defaultAlpha,
    ): CrossSection {
        if (numStations < 2) throw SectionError.InvalidValue("num_stations < 2")
        if (drawnLeft.x == drawnRight.x && drawnLeft.y == drawnRight.y) {
            throw SectionError.InvalidValue("los dos extremos de la línea coinciden")
        }
        val lim = limits(bathymetry, level)
        // Desktop reopens a section with getBathimetryValues(line, level): without a water-edge intersection it
        // drops the bathymetry and the results screen crashes (blank window). Reject such levels here.
        if (level != null && intersectionPointsGUI(bathymetry.stations, bathymetry.elevations, lim.level).isEmpty()) {
            val ys = bathymetry.elevations
            throw SectionError.LevelAboveBanks(maximum = swiftMin(round2(ys[0]), round2(ys[ys.size - 1])))
        }
        if (!(lim.width > 0)) throw SectionError.LevelDoesNotCrossBathymetry()
        val shifted: Double = lim.x1Intersection + leftStation
        if (!(shifted >= lim.xMin) || !(shifted <= lim.xMax)) throw SectionError.LeftStationOutOfRange()

        val rwLeft = matrix.apply(drawnLeft)
        val rwRight = matrix.apply(drawnRight)
        val dx: Double = rwRight.x - rwLeft.x
        val dy: Double = rwRight.y - rwLeft.y
        val magnitude: Double = sqrt(dx * dx + dy * dy)
        if (!(magnitude > 0) || !magnitude.isFinite()) {
            throw SectionError.InvalidValue("línea de longitud nula en el mundo real")
        }
        val ux: Double = dx / magnitude
        val uy: Double = dy / magnitude
        val offset: Double = if (leftStation != 0.0) -leftStation else 0.0
        val wl = Point2(rwLeft.x + ux * offset, rwLeft.y + uy * offset)
        val wrx: Double = rwLeft.x + ux * lim.width + ux * offset
        val wry: Double = rwLeft.y + uy * lim.width + uy * offset
        val wr = Point2(wrx, wry)

        val inverse = matrix.inverse()
        val pl = inverse.apply(wl)
        val pr = inverse.apply(wr)
        val el = matrix.apply(pl)
        val er = matrix.apply(pr)

        // JSON.stringify writes integral numbers without a decimal part.
        val leftValue: JSONValue = if (leftStation == swiftRounded(leftStation) && abs(leftStation) < 1e15) {
            JSONValue.int(leftStation.toLong())
        } else {
            JSONValue.double(leftStation)
        }
        val pairs: List<Pair<String, JSONValue>> = listOf(
            "bath" to JSONValue.string(bathymetryPath),
            "rw_length" to JSONValue.double(lim.width),
            "level" to JSONValue.double(lim.level),
            "left_station" to leftValue,
            "east_l" to JSONValue.double(el.x),
            "north_l" to JSONValue.double(el.y),
            "east_r" to JSONValue.double(er.x),
            "north_r" to JSONValue.double(er.y),
            "xl" to JSONValue.double(pl.x),
            "yl" to JSONValue.double(pl.y),
            "xr" to JSONValue.double(pr.x),
            "yr" to JSONValue.double(pr.y),
            "dir_east_l" to JSONValue.double(rwLeft.x),
            "dir_north_l" to JSONValue.double(rwLeft.y),
            "dir_east_r" to JSONValue.double(rwRight.x),
            "dir_north_r" to JSONValue.double(rwRight.y),
            "dir_xl" to JSONValue.double(drawnLeft.x),
            "dir_yl" to JSONValue.double(drawnLeft.y),
            "dir_xr" to JSONValue.double(drawnRight.x),
            "dir_yr" to JSONValue.double(drawnRight.y),
            "num_stations" to JSONValue.int(numStations),
            "alpha" to JSONValue.double(round2(alpha)),
        )
        return CrossSection(name, JSONObject(pairs))
    }

    private fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a
}
