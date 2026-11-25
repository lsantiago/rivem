package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Planar homography (oblique footage with the water surface treated as a plane), estimated from four or more
 * control points with the normalised DLT (Hartley): points are centred and scaled before solving, which keeps
 * the least-squares system well conditioned for pixel values in the thousands and UTM coordinates.
 */
object Homography {
    /**
     * Fits `H` such that `world ≈ H · pixel` (pixel → real world, same direction as desktop's
     * `transformation_matrix`). Returned with `H[2,2] == 1`.
     */
    fun fit(pixel: List<Point2>, world: List<Point2>): Matrix3 {
        if (pixel.size != world.size) throw RiverCoreError.MismatchedPointCounts()
        if (pixel.size < 4) throw RiverCoreError.NotEnoughPoints(needed = 4, got = pixel.size)
        // With exactly four points any three on a line make the solution undetermined; with more points
        // only a fully collinear set is.
        val degenerate: (List<Point2>) -> Boolean = { if (pixel.size == 4) minTriangleRatio(it) < 1e-6 else areCollinear(it) }
        if (degenerate(pixel) || degenerate(world)) {
            throw RiverCoreError.DegenerateGeometry("hay puntos alineados: no definen un plano")
        }

        val (tp, np, _) = normalization(pixel)
        val (_, nw, twInverse) = normalization(world)

        // Unknowns h11..h32 with h33 = 1 on normalised coordinates. Each point gives two equations.
        val rows = ArrayList<List<Double>>(2 * np.size)
        val rhs = ArrayList<Double>(2 * np.size)
        for ((p, w) in np.zip(nw)) {
            rows.add(listOf(p.x, p.y, 1.0, 0.0, 0.0, 0.0, -w.x * p.x, -w.x * p.y))
            rhs.add(w.x)
            rows.add(listOf(0.0, 0.0, 0.0, p.x, p.y, 1.0, -w.y * p.x, -w.y * p.y))
            rhs.add(w.y)
        }
        val h = LinearSolver.leastSquares(rows, rhs)
        val hn = Matrix3(h + listOf(1.0))
        // Undo the normalisations: H = Tw⁻¹ · Hn · Tp
        return (twInverse * hn * tp).normalized()
    }

    /**
     * Distance, in real-world units, between each control point's known coordinates and where `matrix`
     * sends its pixel position (what the desktop Control Points step grades).
     */
    fun reprojectionErrors(matrix: Matrix3, pixel: List<Point2>, world: List<Point2>): List<Double> =
        pixel.zip(world).map { (p, w) -> matrix.apply(p).distanceTo(w) }

    /**
     * Similarity that moves the centroid to the origin and the mean distance to √2, the normalised points,
     * and the exact inverse of that similarity (built in closed form, no numerical inversion).
     */
    internal fun normalization(pts: List<Point2>): Triple<Matrix3, List<Point2>, Matrix3> {
        val n = pts.size.toDouble()
        val cx = pts.fold(0.0) { acc, p -> acc + p.x } / n
        val cy = pts.fold(0.0) { acc, p -> acc + p.y } / n
        val meanDist = pts.fold(0.0) { acc, p -> acc + hypot(p.x - cx, p.y - cy) } / n
        if (!(meanDist > 0)) throw RiverCoreError.DegenerateGeometry("todos los puntos coinciden")
        val s = sqrt(2.0) / meanDist
        val t = Matrix3.rowMajor(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        val inverse = Matrix3.rowMajor(1 / s, 0.0, cx, 0.0, 1 / s, cy, 0.0, 0.0, 1.0)
        return Triple(t, pts.map { Point2(s * (it.x - cx), s * (it.y - cy)) }, inverse)
    }

    /** True when every point lies (numerically) on one line, i.e. the set cannot define a homography. */
    internal fun areCollinear(pts: List<Point2>): Boolean = (triangleAreas(pts)?.second ?: 0.0) < 1e-6

    /** Smallest triangle area among all point triples, relative to the squared extent of the set. */
    internal fun minTriangleRatio(pts: List<Point2>): Double = triangleAreas(pts)?.first ?: 0.0

    /** (min, max) triangle area over all triples divided by the squared largest distance; null if undefined. */
    private fun triangleAreas(pts: List<Point2>): Pair<Double, Double>? {
        if (pts.size < 3) return null
        var extent = 0.0
        for (a in pts) for (b in pts) extent = max(extent, a.distanceTo(b))
        if (!(extent > 0)) return null
        var lo = Double.POSITIVE_INFINITY
        var hi = 0.0
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                for (k in j + 1 until pts.size) {
                    val u = pts[j] - pts[i]
                    val v = pts[k] - pts[i]
                    val area = abs(u.x * v.y - u.y * v.x) / 2 / (extent * extent)
                    lo = min(lo, area)
                    hi = max(hi, area)
                }
            }
        }
        return Pair(lo, hi)
    }
}
