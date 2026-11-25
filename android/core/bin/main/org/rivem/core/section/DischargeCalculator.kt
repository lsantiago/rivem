package org.rivem.core.section

import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CrossSection
import org.rivem.core.project.CrossSections
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.PIVResults
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Options of desktop's `river-cli update-xsection` (`getResultData.ts`): frame step and fps of the extracted
 * frames, `--interpolate`, `--artificial-seeding`, `--alpha`, `--num-stations`.
 */
data class DischargeOptions(
    /** Frames between the two images of a PIV pair (`video_range.step`). */
    var frameStep: Int,
    /** Frames per second of the video. */
    var fps: Double,
    /** Fill missing/unchecked stations from the Froude-number profile. */
    var interpolate: Boolean = false,
    /** Use the gradient-filtered ("artificial seeding") velocity profile. */
    var artificialSeeding: Boolean = false,
    /** Overrides (and is written to) the section's `alpha` when set. */
    var alpha: Double? = null,
    /** Overrides (and is written to) the section's `num_stations` when set. */
    var numStations: Int? = null,
) {
    /** `step / fps`, seconds between the two frames of a pair. */
    val timeBetweenFrames: Double get() = frameStep.toDouble() / fps
}

/**
 * Everything `update_current_x_section` writes into a section, plus the upstream values of the quantities this
 * port fixes. (Kotlin list equality treats NaN == NaN; Swift's `[Double] ==` does not.)
 */
data class SectionDischarge(
    // Station geometry
    var id: List<Int> = emptyList(),
    var east: List<Double> = emptyList(),
    var north: List<Double> = emptyList(),
    /** m from the water's left edge. */
    var distance: List<Double> = emptyList(),
    /** (start, end) of each wet stretch, in the same station coordinates as `distance`. */
    var wetSegments: List<List<Double>> = emptyList(),
    /** Station pixels. */
    var x: List<Double> = emptyList(),
    var y: List<Double> = emptyList(),
    // Median field at the stations
    var displacementX: List<Double> = emptyList(),
    var displacementY: List<Double> = emptyList(),
    var displacementEast: List<Double> = emptyList(),
    var displacementNorth: List<Double> = emptyList(),
    var streamwiseEast: List<Double> = emptyList(),
    var streamwiseNorth: List<Double> = emptyList(),
    var crosswiseEast: List<Double> = emptyList(),
    var crosswiseNorth: List<Double> = emptyList(),
    /** Signed streamwise surface velocity, m/s (positive to the left of the left→right section direction). */
    var streamwiseVelocity: List<Double> = emptyList(),
    var streamwiseX: List<Double> = emptyList(),
    var streamwiseY: List<Double> = emptyList(),
    var depth: List<Double> = emptyList(),
    var check: List<Boolean> = emptyList(),
    /** True when `check` was missing or had the wrong length and was reset to all `true` (then it is written). */
    var checkWasReset: Boolean = false,
    // Statistics over the frame pairs
    var minusStd: List<Double> = emptyList(),
    var plusStd: List<Double> = emptyList(),
    var percentile5: List<Double> = emptyList(),
    var percentile95: List<Double> = emptyList(),
    var seededProfile: List<Double> = emptyList(),
    var filledStreamwiseVelocity: List<Double>? = null,
    var filledSeededProfile: List<Double>? = null,
    // Mid-section discharge
    var w: List<Double> = emptyList(),
    var a: List<Double> = emptyList(),
    var q: List<Double> = emptyList(),
    var qMinusStd: List<Double> = emptyList(),
    var qPlusStd: List<Double> = emptyList(),
    var qPortion: List<Double> = emptyList(),
    /** Per-station velocity standard deviation used for `totalQStd` (see there). */
    var stationStd: List<Double> = emptyList(),
    // Totals
    var totalQ: Double = 0.0,
    var totalQStd: Double = 0.0,
    var measuredQ: Double = 0.0,
    var interpolatedQ: Double = 0.0,
    var totalA: Double = 0.0,
    var totalW: Double = 0.0,
    var maxDepth: Double = 0.0,
    var averageDepth: Double = 0.0,
    var meanV: Double = 0.0,
    var meanVs: Double = 0.0,
    /**
     * In seeding + interpolation mode upstream overwrites `streamwise_velocity_magnitude` with the seeded profile
     * before writing it (a side effect of `add_interpolated_velocity({**table, ...})`); kept for compatibility.
     */
    var writtenStreamwiseVelocity: List<Double> = emptyList(),
    /** Upstream (RIVeR 3.8.3) values of the quantities fixed here. */
    var upstream: Upstream = Upstream(),
) {
    data class Upstream(
        var totalQStd: Double = 0.0,
        var totalA: Double = 0.0,
        var totalW: Double = 0.0,
        var averageDepth: Double = 0.0,
        var meanV: Double = 0.0,
    )
}

/**
 * Port of `update_current_x_section` (`compute_section.py:1337-1739`) and the functions it uses: stations along
 * the section, depth, the PIV field sampled at the stations, statistics over the frame pairs, mid-section
 * discharge, interpolation of missing stations and the totals. Everything not listed below is numerically the
 * same as RIVeR 3.8.3.
 *
 * Fixes (docs/PLAN.md, "Errores de RIVeR corregidos"):
 * - **#6 `left_station`** — not a defect. Upstream computes `filtered − left_station − (filtered[0] − left_station)`,
 *   which cancels it, and that is right: the GUI already applied `left_station` when it placed `east_l/north_l` at
 *   the water's left edge (`east_l = dir_east_l − left_station·û`, see `SectionBuilder`), and the stations start at
 *   `east_l` ↔ the first wet bathymetry point. Applying it again would shift the stations twice. The same
 *   arithmetic is kept here, so the value has no effect, as in desktop.
 * - **#7 `total_q_std`** — upstream takes `Σ Q_plus_std − total_Q`, which mixes populations: unchecked stations are
 *   left out of `total_Q` but not of `Σ Q_plus_std` (std inflated), stations filled by interpolation are in
 *   `total_Q` but their `Q_plus_std` is NaN and dropped by `nansum` (std reduced, possibly negative), and with
 *   artificial seeding `Q_plus_std` is built on the median velocity while `Q` uses the seeded one (any sign).
 *   Here `total_q_std = Σ_{i in total_Q} |A_i·α|·σ_i`: the same stations as `total_Q`; σ_i is the standard
 *   deviation of the station velocity over the frame pairs (NaN pairs ignored); stations whose velocity was filled
 *   by interpolation, or whose σ is undefined, take σ interpolated over the station index from the measured ones
 *   (the same rule used to fill the velocity); the two end stations forced to zero velocity take σ = 0. It is a
 *   sum of non-negative terms, never negative, and equals upstream's value when every station is checked and
 *   measured (fully correlated errors, as upstream assumes). The per-station `minus_std`/`plus_std`/`Q_*_std`
 *   arrays are unchanged.
 * - **#8 area and width** — `total_A`, `total_W`, `max_depth`, `average_depth` come from `SectionProfile.properties`
 *   (exact water-level crossings, the same crossing points the stations use); `mean_V = total_Q / total_A`.
 * - **#9 caches** — nothing is cached: upstream reuses `minus_std…seeded_vel_profile` already stored in the
 *   section whenever their length matches `num_stations` (so a new PIV run, level, bathymetry or fps keeps the old
 *   statistics) and keeps per-frame profiles keyed by a hash that leaves out the bathymetry. This port recomputes
 *   everything from the inputs it is given on every call.
 * - Divisions by zero are guarded (flat bed segments never create crossings, zero-length sections and
 *   `num_stations < 2` throw).
 */
object DischargeCalculator {
    /** Points of the dense grid used for the per-frame statistics (`N_CACHE_POINTS`). */
    const val denseGridPoints = 200

    /** Percentile of the gradient above which a pair counts as "seeded" (`get_artificial_seeded_profile_optimized`). */
    const val seedingPercentile = 85.0

    internal val stivColumns = listOf(
        "stiv_velocity_profile", "stiv_sigma_profile", "stiv_angle_profile", "stiv_sign_profile",
        "stiv_skip_profile", "stiv_angle_manual_profile",
    )
    internal val iwaveColumns = listOf(
        "iwave_velocity_profile", "iwave_quality_profile", "iwave_depth_profile",
        "iwave_valid_profile", "iwave_skip_profile", "iwave_params",
    )

    // MARK: - Public entry points

    /** Computes the section's results (does not modify it). Throws [SectionError]. */
    fun compute(
        section: CrossSection,
        bathymetry: Bathymetry,
        piv: PIVResults,
        matrix: Matrix3,
        options: DischargeOptions,
    ): SectionDischarge {
        val json = section.json
        fun number(k: String): Double = json[k]?.doubleValue ?: throw SectionError.MissingKey(k)
        val eastL = number("east_l")
        val northL = number("north_l")
        val eastR = number("east_r")
        val northR = number("north_r")
        val level = number("level")
        val leftStation: Double = json["left_station"]?.doubleValue ?: 0.0
        val alpha: Double = options.alpha ?: number("alpha")
        val ns: Int = options.numStations
            ?: (json["num_stations"]?.intValue ?: throw SectionError.MissingKey("num_stations"))
        if (ns < 2) throw SectionError.InvalidValue("num_stations debe ser al menos 2")
        val dt = options.timeBetweenFrames
        if (!(dt > 0) || !dt.isFinite()) throw SectionError.InvalidValue("step/fps")

        val r = SectionDischarge()

        // --- calculate_station_coordinates
        val aug = SectionProfile.augmented(bathymetry.stations, bathymetry.elevations, level)
        val filtered = ArrayList<Double>()
        val filteredStages = ArrayList<Double>()
        for (i in aug.stations.indices) {
            if (!aug.wet[i]) continue
            filtered.add(aug.stations[i])
            filteredStages.add(aug.stages[i])
        }
        if (filtered.isEmpty()) throw SectionError.DryCrossSection()
        // Upstream arithmetic kept as is: `left_station` cancels (PLAN #6, see the type documentation).
        var shifted: List<Double> = filtered.map { it - leftStation }
        val shiftOffset = shifted[0]
        shifted = shifted.map { it - shiftOffset }
        val segments = SectionProfile.wetSegments(bathymetry.stations, bathymetry.elevations, level)
        r.wetSegments = segments.map { listOf(it.first - leftStation - shiftOffset, it.second - leftStation - shiftOffset) }

        val ddx: Double = eastR - eastL
        val ddy: Double = northR - northL
        val totalDistance: Double = sqrt(ddx * ddx + ddy * ddy)
        if (!(totalDistance > 0) || !totalDistance.isFinite()) {
            throw SectionError.InvalidValue("la sección tiene longitud nula")
        }
        val dirX: Double = ddx / totalDistance
        val dirY: Double = ddy / totalDistance
        val last = shifted[shifted.size - 1]
        // A single wet point gives a zero-length section (upstream divides by zero and fills everything with NaN).
        if (!(last > 0)) throw SectionError.DryCrossSection()
        val extEastL: Double = eastL + shifted[0] * dirX
        val extNorthL: Double = northL + shifted[0] * dirY
        val extEastR: Double = eastL + last * dirX
        val extNorthR: Double = northL + last * dirY

        // --- divide_segment_to_dict
        val stations = divide(extEastL, extNorthL, extEastR, extNorthR, ns)
        r.id = (1..ns).toList()
        r.east = stations.east
        r.north = stations.north
        r.distance = stations.distance

        // --- add_pixel_coordinates
        val inverse = matrix.inverse()
        val px = ArrayList<Double>(ns)
        val py = ArrayList<Double>(ns)
        for (i in 0 until ns) {
            val p = inverse.apply(Point2(r.east[i], r.north[i]))
            px.add(p.x)
            py.add(p.y)
        }
        r.x = px
        r.y = py

        // --- compute_transformation_matrix (real world <-> section)
        val rot = Rotation(r.east[0], r.north[0], r.east[ns - 1], r.north[ns - 1])

        // --- add_median_results
        val n = piv.x.size
        val nodesPixel = ArrayList<Point2>(n)
        val nodesWorld = ArrayList<Point2>(n)
        for (k in 0 until n) {
            val p = Point2(piv.x[k], piv.y[k])
            nodesPixel.add(p)
            nodesWorld.add(matrix.apply(p))
        }
        val stationPixels: List<Point2> = (0 until ns).map { Point2(r.x[it], r.y[it]) }
        val stationWorld: List<Point2> = (0 until ns).map { Point2(r.east[it], r.north[it]) }
        val pixelInterp = SectionLinearInterpolator(nodesPixel, stationPixels)
        val worldInterp = SectionLinearInterpolator(nodesWorld, stationWorld)
        r.displacementX = pixelInterp.interpolate(piv.uMedian)
        r.displacementY = pixelInterp.interpolate(piv.vMedian)
        val medianField = worldDisplacements(nodesPixel, nodesWorld, piv.uMedian, piv.vMedian, matrix)
        r.displacementEast = worldInterp.interpolate(medianField.first)
        r.displacementNorth = worldInterp.interpolate(medianField.second)
        val crosswise = ArrayList<Double>(ns)
        val streamwise = ArrayList<Double>(ns)
        for (i in 0 until ns) {
            val cs = rot.toSection(r.displacementEast[i], r.displacementNorth[i])
            crosswise.add(cs.first)
            streamwise.add(cs.second)
        }
        val se = ArrayList<Double>(ns)
        val sn = ArrayList<Double>(ns)
        val ce = ArrayList<Double>(ns)
        val cn = ArrayList<Double>(ns)
        for (i in 0 until ns) {
            val s = rot.toWorld(crosswise[i] * 0, streamwise[i])
            val c = rot.toWorld(crosswise[i], streamwise[i] * 0)
            se.add(s.first)
            sn.add(s.second)
            ce.add(c.first)
            cn.add(c.second)
        }
        r.streamwiseEast = se
        r.streamwiseNorth = sn
        r.crosswiseEast = ce
        r.crosswiseNorth = cn
        val pixStream = pixelDisplacement(r.streamwiseEast, r.streamwiseNorth, r, inverse)
        r.streamwiseX = pixStream.first
        r.streamwiseY = pixStream.second
        r.streamwiseVelocity = streamwise.map { it / dt }

        // --- add_depth
        r.depth = r.distance.map { level - SectionMath.interp(it, shifted, filteredStages) }

        // --- check
        var check: List<Boolean>? = null
        val arr = json["check"]?.arrayValue
        if (arr != null && arr.size == ns) {
            check = arr.map { v ->
                val b = v.boolValue
                val d = v.doubleValue
                when {
                    b != null -> b
                    d != null -> d != 0.0
                    else -> true
                }
            }
        }
        if (check != null) {
            r.check = check
        } else {
            r.check = List(ns) { true }
            r.checkWasReset = true
        }

        // --- statistics over the frame pairs (always recomputed: PLAN #9)
        val stats = frameStatistics(
            piv, matrix, nodesPixel, nodesWorld,
            extEastL, extNorthL, extEastR, extNorthR,
            rot, dt, r.distance,
        )
        val medianVelocity = r.streamwiseVelocity
        r.minusStd = (0 until ns).map { medianVelocity[it] - stats.std[it] }
        r.plusStd = (0 until ns).map { medianVelocity[it] + stats.std[it] }
        r.percentile5 = stats.p5
        r.percentile95 = stats.p95
        r.seededProfile = stats.seeded

        // --- discharge
        discharge(r, alpha, dt, options, rot, inverse, stats.nanStd)

        // --- section properties (PLAN #8) and mean velocities
        val props = SectionProfile.properties(bathymetry.stations, bathymetry.elevations, level)
        r.totalA = props.area
        r.totalW = props.width
        r.maxDepth = props.maxDepth
        r.averageDepth = props.averageDepth
        r.meanV = if (r.totalA > 0) r.totalQ / r.totalA else 0.0
        val up = SectionProfile.upstreamProperties(bathymetry.stations, bathymetry.elevations, level)
        r.upstream.totalA = up.area
        r.upstream.totalW = up.width
        r.upstream.averageDepth = up.averageDepth
        r.upstream.meanV = if (up.area > 0) r.totalQ / up.area else 0.0
        return r
    }

    /**
     * Computes section `index` and writes its results into it (same keys as desktop, plus the GUI's
     * `interpolated`/`artificial_seeding` flags), then refreshes `summary`. Mutates `sections` in place (Swift
     * `inout`).
     */
    fun update(
        sections: CrossSections,
        index: Int,
        bathymetry: Bathymetry,
        piv: PIVResults,
        matrix: Matrix3,
        options: DischargeOptions,
    ): SectionDischarge {
        if (index !in sections.sections.indices) throw SectionError.InvalidValue("índice de sección")
        val section = sections.sections[index].copy()
        val result = compute(section, bathymetry, piv, matrix, options)
        apply(result, section, options)
        sections.sections[index] = section
        sections.summary = summary(sections)
        return result
    }

    /**
     * Writes `r` into `section` as `update_current_x_section` does (key order of a first write included).
     * Replaces `section.json` with a new object (Swift `inout`).
     */
    fun apply(r: SectionDischarge, section: CrossSection, options: DischargeOptions) {
        val o = section.json.copy()
        for (key in o.keys) if (key.startsWith("filled_")) o[key] = null
        o["_stats_cache"] = null
        val previous = o["num_stations"]?.intValue
        options.numStations?.let { o["num_stations"] = JSONValue.int(it) }
        val current = o["num_stations"]?.intValue
        if (previous != null && current != null && previous != current) {
            for (key in stivColumns + iwaveColumns) o[key] = null
        }
        options.alpha?.let { o["alpha"] = JSONValue.double(it) }

        fun put(k: String, v: List<Double>) {
            o[k] = JSONValue.doubles(v)
        }
        o["id"] = JSONValue.array(r.id.map { JSONValue.int(it) })
        put("east", r.east)
        put("north", r.north)
        put("distance", r.distance)
        o["wet_segments"] = JSONValue.array(r.wetSegments.map { JSONValue.doubles(it) })
        put("x", r.x)
        put("y", r.y)
        put("displacement_x", r.displacementX)
        put("displacement_y", r.displacementY)
        put("displacement_east", r.displacementEast)
        put("displacement_north", r.displacementNorth)
        put("streamwise_east", r.streamwiseEast)
        put("streamwise_north", r.streamwiseNorth)
        put("crosswise_east", r.crosswiseEast)
        put("crosswise_north", r.crosswiseNorth)
        put("streamwise_velocity_magnitude", r.writtenStreamwiseVelocity)
        put("streamwise_x", r.streamwiseX)
        put("streamwise_y", r.streamwiseY)
        put("depth", r.depth)
        if (r.checkWasReset) o["check"] = JSONValue.array(r.check.map { JSONValue.bool(it) })
        put("minus_std", r.minusStd)
        put("plus_std", r.plusStd)
        put("5th_percentile", r.percentile5)
        put("95th_percentile", r.percentile95)
        put("seeded_vel_profile", r.seededProfile)
        r.filledStreamwiseVelocity?.let { put("filled_streamwise_velocity_magnitude", it) }
        r.filledSeededProfile?.let { put("filled_seeded_vel_profile", it) }
        if (options.interpolate) {
            put("W", r.w); put("A", r.a); put("Q", r.q)
            put("Q_minus_std", r.qMinusStd); put("Q_plus_std", r.qPlusStd); put("Q_portion", r.qPortion)
        } else {
            put("W", r.w); put("A", r.a); put("Q", r.q)
            put("Q_portion", r.qPortion); put("Q_minus_std", r.qMinusStd); put("Q_plus_std", r.qPlusStd)
        }
        o["total_Q"] = JSONValue.double(r.totalQ)
        o["total_q_std"] = JSONValue.double(r.totalQStd)
        o["measured_Q"] = JSONValue.double(r.measuredQ)
        o["interpolated_Q"] = JSONValue.double(r.interpolatedQ)
        o["total_A"] = JSONValue.double(r.totalA)
        o["total_W"] = JSONValue.double(r.totalW)
        o["max_depth"] = JSONValue.double(r.maxDepth)
        o["average_depth"] = JSONValue.double(r.averageDepth)
        o["mean_V"] = JSONValue.double(r.meanV)
        o["mean_Vs"] = JSONValue.double(r.meanVs)
        // Written by desktop's Electron handler right after the CLI (`getResultData.ts`).
        o["interpolated"] = JSONValue.bool(options.interpolate)
        o["artificial_seeding"] = JSONValue.bool(options.artificialSeeding)
        section.json = o
    }

    /**
     * `get_general_statistics`: mean, population std and coefficient of variation of the section totals over the
     * sections that have all of them (`total_W` is taken from `rw_length`, as upstream does).
     */
    fun summary(sections: CrossSections): JSONValue {
        val keys = listOf("total_W", "total_A", "total_Q", "mean_V", "alpha", "mean_Vs", "max_depth", "average_depth", "measured_Q")
        val data = LinkedHashMap<String, ArrayList<Double>>()
        for (k in keys) data[k] = ArrayList()
        for (s in sections.sections) {
            if (keys.any { s.json[it] == null }) continue
            for (k in keys) {
                val source = if (k == "total_W") "rw_length" else k
                val v: Double = s.json[source]?.doubleValue ?: Double.NaN
                data.getOrPut(k) { ArrayList() }.add(v)
            }
        }
        val mean = JSONObject()
        val std = JSONObject()
        val cov = JSONObject()
        for (k in keys) {
            val values = data[k] ?: emptyList<Double>()
            if (values.isEmpty()) {
                mean[k] = JSONValue.Null
                std[k] = JSONValue.Null
                cov[k] = JSONValue.Null
                continue
            }
            val m = SectionMath.mean(values)
            val sd = SectionMath.std(values)
            mean[k] = JSONValue.double(m)
            std[k] = JSONValue.double(sd)
            // Python: `std / mean if mean and mean != 0` (NaN is truthy).
            cov[k] = if (m != 0.0) JSONValue.double(sd / m) else JSONValue.Null
        }
        return JSONValue.obj(
            JSONObject("mean" to JSONValue.obj(mean), "std" to JSONValue.obj(std), "cov" to JSONValue.obj(cov)),
        )
    }

    // MARK: - Pieces

    internal class Rotation(originEast: Double, originNorth: Double, endEast: Double, endNorth: Double) {
        val c: Double
        val s: Double

        /** Rotation part of the inverse affine matrix (`np.linalg.inv`), i.e. the transpose over c² + s². */
        val i00: Double
        val i01: Double
        val i10: Double
        val i11: Double

        init {
            val dx: Double = endEast - originEast
            val dy: Double = endNorth - originNorth
            val length: Double = sqrt(dx * dx + dy * dy)
            c = dx / length
            s = dy / length
            val det: Double = c * c + s * s
            i00 = c / det
            i01 = -s / det
            i10 = s / det
            i11 = c / det
        }

        /** (crosswise, streamwise) = R · (east, north), R = [[c, s], [-s, c]]. */
        fun toSection(e: Double, n: Double): Pair<Double, Double> {
            val cross: Double = c * e + s * n
            val stream: Double = -s * e + c * n
            return Pair(cross, stream)
        }

        /** (east, north) = R⁻¹ · (crosswise, streamwise). */
        fun toWorld(cross: Double, stream: Double): Pair<Double, Double> {
            val e: Double = i00 * cross + i01 * stream
            val n: Double = i10 * cross + i11 * stream
            return Pair(e, n)
        }
    }

    internal class Stations(val east: List<Double>, val north: List<Double>, val distance: List<Double>)

    /** `divide_segment_to_dict` (same operation order, so the distances match NumPy bit for bit). */
    internal fun divide(el: Double, nl: Double, er: Double, nr: Double, count: Int): Stations {
        val vx: Double = er - el
        val vy: Double = nr - nl
        val stepSize: Double = 1.0 / (count - 1).toDouble()
        val length: Double = sqrt(vx * vx + vy * vy)
        val east = ArrayList<Double>(count)
        val north = ArrayList<Double>(count)
        val distance = ArrayList<Double>(count)
        for (i in 0 until count) {
            val f: Double = i.toDouble() * stepSize
            east.add(el + f * vx)
            north.add(nl + f * vy)
            distance.add(f * length)
        }
        return Stations(east, north, distance)
    }

    /** `convert_displacement_field`: real-world displacement of every PIV node, as (east, north). */
    internal fun worldDisplacements(
        nodes: List<Point2>,
        world: List<Point2>,
        u: List<Double>,
        v: List<Double>,
        matrix: Matrix3,
    ): Pair<List<Double>, List<Double>> {
        val de = ArrayList<Double>(nodes.size)
        val dn = ArrayList<Double>(nodes.size)
        for (k in nodes.indices) {
            val moved = matrix.apply(Point2(nodes[k].x + u[k], nodes[k].y + v[k]))
            de.add(moved.x - world[k].x)
            dn.add(moved.y - world[k].y)
        }
        return Pair(de, dn)
    }

    /** `get_pix_displacement`. */
    internal fun pixelDisplacement(
        de: List<Double>,
        dn: List<Double>,
        r: SectionDischarge,
        inverse: Matrix3,
    ): Pair<List<Double>, List<Double>> {
        val px = ArrayList<Double>(de.size)
        val py = ArrayList<Double>(de.size)
        for (i in de.indices) {
            val p = inverse.apply(Point2(r.east[i] + de[i], r.north[i] + dn[i]))
            px.add(p.x - r.x[i])
            py.add(p.y - r.y[i])
        }
        return Pair(px, py)
    }

    internal class FrameStatistics(
        val std: List<Double>,
        val nanStd: List<Double>,
        val p5: List<Double>,
        val p95: List<Double>,
        val seeded: List<Double>,
    )

    /**
     * `_compute_stats_cache` + `_compute_stats_from_cache`: streamwise velocity of every frame pair on a dense
     * 200-point grid, interpolated to the stations; std, 5th/95th percentiles and the artificial-seeding profile.
     */
    internal fun frameStatistics(
        piv: PIVResults,
        matrix: Matrix3,
        nodesPixel: List<Point2>,
        nodesWorld: List<Point2>,
        extEastL: Double,
        extNorthL: Double,
        extEastR: Double,
        extNorthR: Double,
        rotation: Rotation,
        dt: Double,
        stationDistances: List<Double>,
    ): FrameStatistics {
        val dense = divide(extEastL, extNorthL, extEastR, extNorthR, denseGridPoints)
        val densePoints: List<Point2> = (0 until denseGridPoints).map { Point2(dense.east[it], dense.north[it]) }
        val interp = SectionLinearInterpolator(nodesWorld, densePoints)
        // Nearest PIV node of each dense point (`_interpolate_gradient_numba`: first minimum).
        val nearest = ArrayList<Int>(denseGridPoints)
        for (q in densePoints) {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for (k in nodesWorld.indices) {
                val dx: Double = nodesWorld[k].x - q.x
                val dy: Double = nodesWorld[k].y - q.y
                val d: Double = dx * dx + dy * dy
                if (best < 0 || d < bestD) {
                    best = k
                    bestD = d
                }
            }
            nearest.add(best)
        }
        val ns = stationDistances.size
        val frames = piv.pairCount
        val vel = List(ns) { ArrayList<Double>(frames) } // [station][frame]
        val grad = List(ns) { ArrayList<Double>(frames) }
        for (f in 0 until frames) {
            val field = worldDisplacements(nodesPixel, nodesWorld, piv.u[f], piv.v[f], matrix)
            val ie = interp.interpolate(field.first)
            val inn = interp.interpolate(field.second)
            val denseVel = ArrayList<Double>(denseGridPoints)
            for (j in 0 until denseGridPoints) {
                denseVel.add(rotation.toSection(ie[j], inn[j]).second / dt)
            }
            val denseGrad: List<Double> = if (f < piv.gradient.size && piv.gradient[f].size == nodesWorld.size) {
                val g = piv.gradient[f]
                nearest.map { g[it] }
            } else {
                List(denseGridPoints) { Double.NaN }
            }
            for (i in 0 until ns) {
                vel[i].add(SectionMath.interp(stationDistances[i], dense.distance, denseVel))
                grad[i].add(SectionMath.interp(stationDistances[i], dense.distance, denseGrad))
            }
        }
        val std = ArrayList<Double>(ns)
        val nanStd = ArrayList<Double>(ns)
        val p5 = ArrayList<Double>(ns)
        val p95 = ArrayList<Double>(ns)
        val seeded = ArrayList<Double>(ns)
        for (i in 0 until ns) {
            std.add(SectionMath.std(vel[i]))
            nanStd.add(SectionMath.nanstd(vel[i]))
            p5.add(SectionMath.percentile(vel[i], 5.0))
            p95.add(SectionMath.percentile(vel[i], 95.0))
            seeded.add(seededMean(vel[i], grad[i]))
        }
        return FrameStatistics(std, nanStd, p5, p95, seeded)
    }

    /** `get_artificial_seeded_profile_optimized` for one station. */
    internal fun seededMean(velocity: List<Double>, gradient: List<Double>): Double {
        val valid = gradient.filter { !it.isNaN() }.sorted()
        if (valid.isEmpty()) return Double.NaN
        var idx = ((seedingPercentile / 100.0) * valid.size.toDouble()).toInt()
        if (idx >= valid.size) idx = valid.size - 1
        val threshold = valid[idx]
        var sum = 0.0
        var count = 0
        for (j in gradient.indices) {
            if (gradient[j].isNaN() || !(gradient[j] > threshold)) continue
            sum += velocity[j]
            count += 1
        }
        return if (count > 0) sum / count.toDouble() else Double.NaN
    }

    /**
     * `add_interpolated_velocity`: fills NaN/unchecked stations through the Froude number; the end stations are
     * set to 0. Returns the filled profile and the mask of filled stations.
     */
    internal fun filledVelocity(v: List<Double>, depth: List<Double>, check: List<Boolean>): Pair<List<Double>, List<Boolean>> {
        val n = v.size
        val g = 9.81
        val d: List<Double> = depth.map { if (it.isNaN()) it else swiftMax(it, 1e-6) }
        var fr: List<Double> = (0 until n).map { v[it] / sqrt(g * d[it]) }
        val invalid: List<Boolean> = (0 until n).map { v[it].isNaN() || !check[it] }
        if (!invalid.contains(false)) {
            return Pair(List(n) { 0.0 }, invalid)
        }
        fr = SectionMath.fillByIndex(fr, invalid)
        val filled = MutableList(n) { fr[it] * sqrt(g * d[it]) }
        filled[0] = 0.0
        filled[n - 1] = 0.0
        return Pair(filled, invalid)
    }

    /** Swift `max(a, b)`: `b >= a ? b : a`. */
    private fun swiftMax(a: Double, b: Double): Double = if (b >= a) b else a

    internal class WAQ(
        val w: List<Double>,
        val a: List<Double>,
        val q: List<Double>,
        val qMinus: List<Double>,
        val qPlus: List<Double>,
        val portion: List<Double>,
    )

    /** `add_w_a_q`: mid-section widths, areas and discharges. */
    internal fun waq(
        x: List<Double>,
        v: List<Double>,
        minus: List<Double>,
        plus: List<Double>,
        d: List<Double>,
        alpha: Double,
    ): WAQ {
        val n = x.size
        val w = MutableList(n) { 0.0 }
        if (n >= 3) {
            for (i in 1 until n - 1) w[i] = (x[i + 1] - x[i - 1]) / 2
        }
        w[0] = x[1] - x[0]
        w[n - 1] = x[n - 1] - x[n - 2]
        val a: List<Double> = (0 until n).map { w[it] * d[it] }
        val q: List<Double> = (0 until n).map { a[it] * v[it] * alpha }
        val qm: List<Double> = (0 until n).map { a[it] * minus[it] * alpha }
        val qp: List<Double> = (0 until n).map { a[it] * plus[it] * alpha }
        val total = SectionMath.nansum(q)
        val portion: List<Double> = if (total != 0.0) q.map { it / total } else List(n) { 0.0 }
        return WAQ(w, a, q, qm, qp, portion)
    }

    /** The discharge part of `update_current_x_section` (`compute_section.py:1589-1732`) plus fix #7. */
    internal fun discharge(
        r: SectionDischarge,
        alpha: Double,
        dt: Double,
        options: DischargeOptions,
        rotation: Rotation,
        inverse: Matrix3,
        nanStd: List<Double>,
    ) {
        val ns = r.distance.size
        val interpolate = options.interpolate
        val seeding = options.artificialSeeding
        val velMasked = ArrayList(r.streamwiseVelocity)
        val seededMasked = ArrayList(r.seededProfile)
        if (!interpolate) {
            for (i in 0 until ns) {
                if (r.check[i]) continue
                velMasked[i] = Double.NaN
                seededMasked[i] = Double.NaN
            }
        }
        r.writtenStreamwiseVelocity = r.streamwiseVelocity
        val used: List<Double>
        var filledMask: List<Boolean> = List(ns) { false }
        if (seeding && interpolate) {
            val f = filledVelocity(r.seededProfile, r.depth, r.check)
            r.filledSeededProfile = f.first
            filledMask = f.second
            used = f.first
            r.writtenStreamwiseVelocity = r.seededProfile
        } else if (seeding) {
            used = seededMasked
        } else if (interpolate) {
            val f = filledVelocity(r.streamwiseVelocity, r.depth, r.check)
            r.filledStreamwiseVelocity = f.first
            filledMask = f.second
            used = f.first
        } else {
            used = velMasked
        }
        val t = waq(r.distance, used, r.minusStd, r.plusStd, r.depth, alpha)
        r.w = t.w
        r.a = t.a
        r.q = t.q
        r.qMinusStd = t.qMinus
        r.qPlusStd = t.qPlus
        r.qPortion = t.portion

        if (interpolate || seeding) {
            val profile: List<Double> = if (seeding) {
                if (interpolate) (r.filledSeededProfile ?: used) else r.seededProfile
            } else {
                r.filledStreamwiseVelocity ?: used
            }
            val se = ArrayList<Double>(ns)
            val sn = ArrayList<Double>(ns)
            for (i in 0 until ns) {
                val disp: Double = profile[i] * dt
                val s = rotation.toWorld(0 * disp, disp)
                se.add(s.first)
                sn.add(s.second)
            }
            val p = pixelDisplacement(se, sn, r, inverse)
            r.streamwiseX = p.first
            r.streamwiseY = p.second
        }

        // Totals
        val included = BooleanArray(ns)
        var totalQ = 0.0
        for (i in 0 until ns) {
            if (!r.q[i].isNaN() && (interpolate || r.check[i])) {
                included[i] = true
                totalQ += r.q[i]
            }
        }
        r.totalQ = totalQ
        r.upstream.totalQStd = SectionMath.nansum(r.qPlusStd) - totalQ
        var measured = 0.0
        var interpolated = 0.0
        for (i in 0 until ns) {
            if (r.q[i].isNaN()) continue
            if (r.check[i]) measured += r.q[i] else interpolated += r.q[i]
        }
        r.measuredQ = if (totalQ > 0) measured / totalQ else 0.0
        r.interpolatedQ = if (totalQ > 0) interpolated / totalQ else 0.0

        // Fix #7: σ of the stations in total_Q.
        val sigmaInvalid: List<Boolean> = (0 until ns).map { nanStd[it].isNaN() || filledMask[it] }
        val sigma: MutableList<Double> = if (sigmaInvalid.contains(false)) {
            SectionMath.fillByIndex(nanStd, sigmaInvalid).toMutableList()
        } else {
            MutableList(ns) { 0.0 }
        }
        if (interpolate) {
            sigma[0] = 0.0
            sigma[ns - 1] = 0.0
        }
        var totalStd = 0.0
        for (i in 0 until ns) {
            if (!included[i]) continue
            val term: Double = abs(r.a[i] * alpha) * sigma[i]
            if (term.isFinite()) totalStd += term
        }
        r.stationStd = sigma
        r.totalQStd = totalStd

        // mean_Vs
        val surface: List<Double> = if (seeding) {
            if (interpolate) (r.filledSeededProfile ?: used) else seededMasked
        } else {
            if (interpolate) (r.filledStreamwiseVelocity ?: used) else velMasked
        }
        r.meanVs = SectionMath.nanmean(surface)
    }
}
