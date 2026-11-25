package org.rivem.core.geometry

import kotlin.math.hypot
import kotlin.math.sqrt

// RIVEM addition (not in desktop RIVeR): the six oblique distances from points surveyed in 3D (ARKit / ARCore / LiDAR).

/** A point in a gravity-aligned 3D frame (metres): `y` is up, `x` and `z` horizontal (ARKit's / ARCore's world frame). */
data class Point3(val x: Double, val y: Double, val z: Double)

/** One surveyed point from several readings (raycasts over a fraction of a second). */
data class AveragedPoint(
    val point: Point3,
    /** RMS distance of the kept readings to `point` (m): how steady the aim and the depth were. */
    val spread: Double,
    val used: Int,
    val total: Int,
) {
    companion object {
        /**
         * Median per axis, readings farther than 3× the median distance (and > 1 cm) dropped as outliers, then the
         * mean of the rest. Null without finite readings.
         */
        fun from(readings: List<Point3>): AveragedPoint? {
            val r: List<Point3> = readings.filter { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
            if (r.isEmpty()) return null
            fun median(v: List<Double>): Double {
                val s: List<Double> = v.sorted()
                return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            }
            val m = Point3(median(r.map { it.x }), median(r.map { it.y }), median(r.map { it.z }))
            fun dist(a: Point3, b: Point3): Double =
                sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            val limit: Double = maxOf(3 * median(r.map { dist(it, m) }), 0.01)
            val kept: List<Point3> = r.filter { dist(it, m) <= limit }
            val n: Double = kept.size.toDouble()
            val mean = Point3(kept.fold(0.0) { a, p -> a + p.x } / n, kept.fold(0.0) { a, p -> a + p.y } / n,
                              kept.fold(0.0) { a, p -> a + p.z } / n)
            val rms: Double = sqrt(kept.fold(0.0) { a, p -> a + dist(p, mean) * dist(p, mean) } / n)
            return AveragedPoint(point = mean, spread = rms, used = kept.size, total = r.size)
        }
    }
}

data class SurveyedDistances(
    /** d12, d23, d34, d41, d13, d24 measured horizontally. */
    val distances: ObliqueDistances,
    /**
     * Highest minus lowest point (m). The oblique method assumes the four points on the water plane: a large spread
     * means some point is not at water level.
     */
    val heightSpread: Double,
    /**
     * Largest difference between the straight (slant) and horizontal distance of a pair (m), what a tape held
     * along the slope would have added.
     */
    val largestSlantExcess: Double,
) {
    companion object {
        /**
         * The oblique solution places the four points on one horizontal plane (the water surface), so the distances
         * it needs are horizontal: the vertical difference between surveyed points is dropped. Null unless four points.
         */
        fun from(points: List<Point3>): SurveyedDistances? {
            if (!(points.size == 4 && points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })) return null
            val pairs: List<Pair<Int, Int>> = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 0 to 2, 1 to 3)
            val horizontal = ArrayList<Double>(6)
            var excess = 0.0
            for ((i, j) in pairs) {
                val a: Point3 = points[i]
                val b: Point3 = points[j]
                val h: Double = hypot(b.x - a.x, b.z - a.z)
                val slant: Double = sqrt(h * h + (b.y - a.y) * (b.y - a.y))
                horizontal.add(h)
                excess = maxOf(excess, slant - h)
            }
            val d = ObliqueDistances(horizontal) ?: return null
            val ys: List<Double> = points.map { it.y }
            return SurveyedDistances(distances = d, heightSpread = (ys.maxOrNull() ?: 0.0) - (ys.minOrNull() ?: 0.0),
                                     largestSlantExcess = excess)
        }
    }
}
