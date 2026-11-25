package org.rivem.core.section

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// RIVEM addition (not in desktop RIVeR): stage–discharge (rating) curve of a site from its gaugings.

/** One gauging of a site: water level (m, same datum as the bathymetry) and discharge (m³/s). */
data class RatingPoint(
    var level: Double,
    var discharge: Double,
)

/** Q = a · (h − h₀)ᵇ, the usual power-law rating curve (h₀: level of zero flow). */
data class RatingCurve(
    var a: Double,
    var b: Double,
    var h0: Double,
    /** Coefficient of determination on Q. */
    var r2: Double,
    /** RMS of the relative residuals (Q_fit − Q) / Q. */
    var relativeRMS: Double,
    var pointCount: Int,
) {
    /** Swift `discharge(at:)`. */
    fun discharge(at: Double): Double = if (at > h0) a * (at - h0).pow(b) else 0.0

    /** Inverse: the level for a discharge (Swift `level(for:)`). */
    fun level(discharge: Double): Double = if (discharge > 0) h0 + (discharge / a).pow(1 / b) else h0

    private class Line(val lnA: Double, val b: Double, val sse: Double)

    companion object {
        const val minimumPoints = 3

        /**
         * Least squares in log space (relative errors weigh the same at low and high flows). For each candidate h₀
         * below the lowest level, ln Q = ln a + b · ln(h − h₀) is a straight line; h₀ is searched on a grid down to
         * three level ranges below the lowest gauging, then refined by golden-section search. Needs at least three
         * gaugings with positive discharge and two distinct levels.
         */
        fun fit(points: List<RatingPoint>): RatingCurve? {
            val p: List<RatingPoint> = points.filter { it.level.isFinite() && it.discharge.isFinite() && it.discharge > 0 }
            if (p.size < minimumPoints) return null
            val levels: List<Double> = p.map { it.level }
            val hMin = levels.minOrNull() ?: return null
            val hMax = levels.maxOrNull() ?: return null
            if (!(hMax - hMin > 1e-6)) return null
            val span: Double = hMax - hMin
            val upper: Double = hMin - span * 1e-4
            val lower: Double = hMin - span * 3 - 0.5

            fun line(h0: Double): Line? {
                var sx = 0.0
                var sy = 0.0
                var sxx = 0.0
                var sxy = 0.0
                val n: Double = p.size.toDouble()
                val xs = ArrayList<Double>(p.size)
                val ys = ArrayList<Double>(p.size)
                for (q in p) {
                    val d: Double = q.level - h0
                    if (!(d > 0)) return null
                    val x: Double = ln(d)
                    val y: Double = ln(q.discharge)
                    xs.add(x)
                    ys.add(y)
                    sx += x; sy += y; sxx += x * x; sxy += x * y
                }
                val den: Double = n * sxx - sx * sx
                if (!(abs(den) > 1e-12)) return null
                val b: Double = (n * sxy - sx * sy) / den
                val lnA: Double = (sy - b * sx) / n
                var sse = 0.0
                for (k in xs.indices) {
                    val r: Double = ys[k] - (lnA + b * xs[k])
                    sse += r * r
                }
                return Line(lnA, b, sse)
            }

            val steps = 400
            var bestH0: Double? = null
            var bestSse = 0.0
            for (k in 0..steps) {
                val h0: Double = lower + (upper - lower) * k.toDouble() / steps.toDouble()
                val l = line(h0)
                if (l != null && l.b > 0 && (bestH0 == null || l.sse < bestSse)) {
                    bestH0 = h0
                    bestSse = l.sse
                }
            }
            var h0: Double = bestH0 ?: return null
            // Golden-section refinement around the best grid point.
            val cell: Double = (upper - lower) / steps.toDouble()
            var lo: Double = max(lower, h0 - cell)
            var hi: Double = min(upper, h0 + cell)
            val g: Double = (sqrt(5.0) - 1) / 2
            fun cost(h: Double): Double = line(h)?.sse ?: Double.POSITIVE_INFINITY
            repeat(60) {
                val c: Double = hi - g * (hi - lo)
                val d: Double = lo + g * (hi - lo)
                if (cost(c) < cost(d)) hi = d else lo = c
            }
            if (cost((lo + hi) / 2) <= cost(h0)) h0 = (lo + hi) / 2
            val l = line(h0)
            if (l == null || !(l.b > 0)) return null

            val a: Double = exp(l.lnA)
            val curve = RatingCurve(a, l.b, h0, Double.NaN, Double.NaN, p.size)
            val mean: Double = p.fold(0.0) { acc, q -> acc + q.discharge } / p.size.toDouble()
            var ssRes = 0.0
            var ssTot = 0.0
            var rel = 0.0
            for (q in p) {
                val f: Double = curve.discharge(q.level)
                ssRes += (q.discharge - f) * (q.discharge - f)
                ssTot += (q.discharge - mean) * (q.discharge - mean)
                rel += ((f - q.discharge) / q.discharge) * ((f - q.discharge) / q.discharge)
            }
            curve.r2 = if (ssTot > 0) 1 - ssRes / ssTot else Double.NaN
            curve.relativeRMS = sqrt(rel / p.size.toDouble())
            return curve
        }
    }
}
