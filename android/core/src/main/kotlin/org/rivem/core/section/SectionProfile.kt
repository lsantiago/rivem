package org.rivem.core.section

import kotlin.math.max
import kotlin.math.min

/**
 * Bed profile against the water level: crossings, wet points, wet segments and the section's hydraulic
 * properties. Port of `_find_crossings`, `find_wet_segments` and (fixed) `calculate_river_section_properties`
 * of `compute_section.py` (Swift `SectionProfile`).
 */
object SectionProfile {
    /** The bed profile with every crossing of the water level inserted, sorted by station. */
    data class Augmented(
        var stations: List<Double>,
        var stages: List<Double>,
        /** `stage <= level`. */
        var wet: List<Boolean>,
    )

    /**
     * `_find_crossings`: a crossing is inserted only where the bed goes strictly from below to strictly above the
     * level (or back), so the interpolation never divides by zero; points exactly at the level are already in the
     * profile. Sorting is stable (NumPy's default `argsort` is not, which only matters for repeated stations).
     */
    fun augmented(stations: List<Double>, stages: List<Double>, level: Double): Augmented {
        val s = ArrayList(stations)
        val z = ArrayList(stages)
        val n = min(stations.size, stages.size)
        if (n >= 2) {
            for (i in 0 until n - 1) {
                val a = stages[i]
                val b = stages[i + 1]
                if ((a < level && b > level) || (a > level && b < level)) {
                    val num: Double = (level - a) * (stations[i + 1] - stations[i])
                    val x: Double = stations[i] + num / (b - a)
                    s.add(x)
                    z.add(level)
                }
            }
        }
        // Swift: `s[l] != s[r] ? s[l] < s[r] : l < r`. NaN stations (never in a valid bathymetry) fall back to a
        // total order so the JVM sort cannot reject an inconsistent comparator.
        val order = s.indices.sortedWith { l, r ->
            val a = s[l]
            val b = s[r]
            when {
                a.isNaN() || b.isNaN() -> if (a.compareTo(b) != 0) a.compareTo(b) else l.compareTo(r)
                a != b -> if (a < b) -1 else 1
                else -> l.compareTo(r)
            }
        }
        val ss = order.map { s[it] }
        val zz = order.map { z[it] }
        return Augmented(ss, zz, zz.map { it <= level })
    }

    /** `find_wet_segments`: (start, end) of every contiguous wet run, in bathymetry stations. */
    fun wetSegments(stations: List<Double>, stages: List<Double>, level: Double): List<Pair<Double, Double>> {
        val a = augmented(stations, stages, level)
        val segments = ArrayList<Pair<Double, Double>>()
        var start: Int? = null
        for (i in a.wet.indices) {
            val s0 = start
            if (a.wet[i] && s0 == null) {
                start = i
            } else if (!a.wet[i] && s0 != null) {
                segments.add(Pair(a.stations[s0], a.stations[i - 1]))
                start = null
            }
        }
        val s0 = start
        val last = a.stations.lastOrNull()
        if (s0 != null && last != null) segments.add(Pair(a.stations[s0], last))
        return segments
    }

    data class Properties(
        var area: Double,
        var width: Double,
        var maxDepth: Double,
        var averageDepth: Double,
    )

    /**
     * Wetted area, top width, maximum and average depth of the section — FIX of PLAN #8.
     *
     * Upstream (`calculate_river_section_properties`, `compute_section.py:1171-1214`) works on the raw bathymetry:
     * a segment from a wet point to a dry one adds the trapezoid of the clipped depths (too much area, the water
     * actually ends at the crossing) and the width adds a whole segment whenever its *left* point is wet, so the
     * result depends on the direction of the profile and on where the points happen to be (in `pisco` the first
     * segment, whose left point is exactly at the level, is left out: W = 21.155 m instead of 22.656 m).
     *
     * Here the same crossing points used for the stations (`augmented`) are inserted first, so every segment is
     * either entirely wet or entirely dry: the trapezoids are exact for the piecewise-linear bed, and the width is
     * the sum of the wet segments (equal to Σ(end − start) of `wetSegments`). A stretch lying exactly at the level
     * (zero depth at both ends) adds neither area nor width. `averageDepth = area / width` (0 when dry).
     */
    fun properties(stations: List<Double>, stages: List<Double>, level: Double): Properties {
        val a = augmented(stations, stages, level)
        var area = 0.0
        var width = 0.0
        var maxDepth = 0.0
        for (i in a.stations.indices) {
            val d = max(level - a.stages[i], 0.0)
            if (d > maxDepth) maxDepth = d
        }
        if (a.stations.size >= 2) {
            for (i in 0 until a.stations.size - 1) {
                val d1 = level - a.stages[i]
                val d2 = level - a.stages[i + 1]
                val dx = a.stations[i + 1] - a.stations[i]
                if (d1 >= 0 && d2 >= 0 && d1 + d2 > 0) {
                    area += (d1 + d2) / 2 * dx
                    width += dx
                }
            }
        }
        val average: Double = if (width > 0) area / width else 0.0
        return Properties(area, width, maxDepth, average)
    }

    /** Upstream's `calculate_river_section_properties`, kept to document and test the difference of the fix. */
    fun upstreamProperties(stations: List<Double>, stages: List<Double>, level: Double): Properties {
        val n = min(stations.size, stages.size)
        if (n < 1) return Properties(0.0, 0.0, 0.0, 0.0)
        val depths = ArrayList<Double>()
        for (i in 0 until n) depths.add(max(level - stages[i], 0.0))
        var area = 0.0
        var width = 0.0
        if (n >= 2) {
            for (i in 0 until n - 1) {
                val dx = stations[i + 1] - stations[i]
                area += (depths[i] + depths[i + 1]) / 2 * dx
                if (depths[i] > 0) width += dx
            }
        }
        // Swift `max()` uses `<`: the first of equal maxima, NaN never wins over a number seen before it.
        var maxDepth = depths[0]
        for (d in depths) if (maxDepth < d) maxDepth = d
        val average: Double = if (width > 0) area / width else 0.0
        return Properties(area, width, maxDepth, average)
    }
}
