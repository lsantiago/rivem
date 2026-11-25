package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The six tape distances of the oblique setup, in metres: four sides and the two diagonals of the quadrilateral
 * 1-2-3-4 (desktop order `d12, d23, d34, d41, d13, d24`).
 */
data class ObliqueDistances(
    val d12: Double,
    val d23: Double,
    val d34: Double,
    val d41: Double,
    val d13: Double,
    val d24: Double,
) {
    /** Desktop order `d12, d23, d34, d41, d13, d24`. */
    val values: List<Double> get() = listOf(d12, d23, d34, d41, d13, d24)

    companion object {
        /**
         * From a list in desktop order (`ControlPointsSetup.distances`); null unless it has exactly six values
         * (Swift's failable `ObliqueDistances(_ values:)`).
         */
        operator fun invoke(values: List<Double>): ObliqueDistances? {
            if (values.size != 6) return null
            return ObliqueDistances(values[0], values[1], values[2], values[3], values[4], values[5])
        }
    }
}

/** Result of the oblique (4 points + 6 distances) calibration. */
data class ObliqueSolution(
    /**
     * Pixel → real-world matrix (same direction as `transformation_matrix.json`), scaled so `m[2,2] == 1`.
     * Desktop writes `inv(findHomography(world, pixel))` without normalising; both describe the same mapping.
     */
    val matrix: Matrix3,
    /** Real-world (East, North) coordinates assigned to points 1…4. */
    val world: List<Point2>,
    /**
     * `[x, y, width, height]` in pixels of the rectangle framing the four points (`calculate_roi`), stored by
     * desktop as `settings.transformation.roi`.
     */
    val roi: List<Double>,
    /**
     * Measured minus solved distance for `d12, d23, d34, d41, d13, d24` (m). Large values mean the tape
     * distances are inconsistent with a planar quadrilateral.
     */
    val distanceResiduals: List<Double>,
)

/** P3 and P4 of a trilateration (Swift's `(p3:, p4:)` tuple). */
data class Trilateration(val p3: Point2, val p4: Point2)

/**
 * Oblique footage calibrated with four points on the water plane and the six distances between them. Port of
 * `oblique_view_transformation_matrix`, `optimize_coordinates` and `calculate_roi` in
 * `river/core/coordinate_transform.py` (RIVeR 3.8.3).
 *
 * Points 1 and 2 define the frame: P1 = (0, 0), P2 = (d12, 0). P3 and P4 are found by least squares on the
 * other five distances, starting from desktop's initial guess `[d12/2, d12/2, d12/2, d12/2]` so the solver lands
 * on the same branch (P3 and P4 north of the 1→2 baseline). Desktop uses SciPy's BFGS on the summed squared
 * misfit; this port uses Levenberg–Marquardt on the same residuals.
 *
 * **Fix over desktop:** desktop casts the real-world coordinates (and the anchor rotation) to `float32` before
 * `cv2.findHomography`, which loses up to ~0.5 m when anchors are given in UTM. Here everything stays in `Double`.
 */
object ObliqueTransform {
    /** Coordinates of P3 and P4 with P1 = (0, 0) and P2 = (d12, 0) (`optimize_coordinates`). */
    fun trilaterate(d: ObliqueDistances): Trilateration {
        val v: DoubleArray = levenbergMarquardt(d)
        return Trilateration(Point2(v[0], v[1]), Point2(v[2], v[3]))
    }

    /**
     * `trilaterate`, then the mirror image across the 1→2 baseline when needed so the world quadrilateral has the
     * orientation of the view (**fix over desktop**, docs/PLAN.md #18). A camera never sees the water plane
     * mirrored: with the image's y axis down and North up, the signed areas of the pixel and world quadrilaterals
     * have opposite signs. Inputs numbered like the desktop GUI already satisfy this and are unchanged.
     */
    fun orientedTrilateration(d: ObliqueDistances, pixels: List<Point2>): Trilateration {
        val (p3, p4) = trilaterate(d)
        val world: List<Point2> = listOf(Point2(0.0, 0.0), Point2(d.d12, 0.0), p3, p4)
        val pixelArea: Double = signedArea(pixels)
        val worldArea: Double = signedArea(world)
        if (pixelArea * worldArea > 0) {
            return Trilateration(Point2(p3.x, -p3.y), Point2(p4.x, -p4.y))
        }
        return Trilateration(p3, p4)
    }

    /** Shoelace signed area of a polygon (positive counter-clockwise in a y-up frame). */
    internal fun signedArea(points: List<Point2>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a: Point2 = points[i]
            val b: Point2 = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }

    /**
     * Full calibration.
     * @param pixels pixel positions of points 1…4 (frame coordinates, y down).
     * @param distances tape distances.
     * @param anchors optional real-world coordinates of points 1 and 2. Desktop's GUI passes them only when they are
     *   not the default (0,0)/(d12,0), together with `enforceD12` and a tolerance of 1 m (`setControlPoints.ts`).
     * @param enforceD12 when anchors are given, require |anchor2 − anchor1| ≈ d12 within `distanceTolerance`, and
     *   keep using `d12` for the trilateration (desktop behaviour). When false, the anchor distance replaces d12.
     * @param roiPadding fraction added on each side of the ROI (GUI default 0).
     */
    fun solve(
        pixels: List<Point2>, distances: ObliqueDistances, anchors: Pair<Point2, Point2>? = null,
        enforceD12: Boolean = true, distanceTolerance: Double = 1.0, roiPadding: Double = 0.0,
    ): ObliqueSolution {
        if (pixels.size != 4) throw RiverCoreError.NotEnoughPoints(needed = 4, got = pixels.size)
        for (value in distances.values) {
            if (!(value.isFinite() && value >= 0)) throw RiverCoreError.DegenerateGeometry("distancia no válida")
        }
        if (!(distances.d12 > 0)) throw RiverCoreError.DegenerateGeometry("d12 debe ser mayor que cero")

        val world: List<Point2>
        if (anchors != null) {
            val a1: Point2 = anchors.first
            val a2: Point2 = anchors.second
            val anchorD12: Double = a1.distanceTo(a2)
            if (!(anchorD12 > 0)) throw RiverCoreError.DegenerateGeometry("los puntos 1 y 2 coinciden")
            if (enforceD12 && abs(anchorD12 - distances.d12) > distanceTolerance) {
                throw RiverCoreError.DegenerateGeometry(String.format(
                    java.util.Locale.ROOT,
                    "la distancia entre las anclas (%.6f m) difiere de d12 (%.6f m) en más de %.6f m",
                    anchorD12, distances.d12, distanceTolerance))
            }
            var canonical = distances
            if (!enforceD12) canonical = canonical.copy(d12 = anchorD12)
            val (c3, c4) = orientedTrilateration(canonical, pixels)
            val theta: Double = atan2(a2.y - a1.y, a2.x - a1.x)
            val c = cos(theta)
            val s = sin(theta)
            val rotate: (Point2) -> Point2 = { p -> Point2(c * p.x - s * p.y + a1.x, s * p.x + c * p.y + a1.y) }
            world = listOf(a1, a2, rotate(c3), rotate(c4))
        } else {
            val (p3, p4) = orientedTrilateration(distances, pixels)
            world = listOf(Point2(0.0, 0.0), Point2(distances.d12, 0.0), p3, p4)
        }

        val matrix: Matrix3 = Homography.fit(pixels, world)
        val pairs: List<Pair<Int, Int>> = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 0 to 2, 1 to 3)
        val residuals = ArrayList<Double>(6)
        val measured: List<Double> = distances.values
        for ((k, pair) in pairs.withIndex()) {
            residuals.add(measured[k] - world[pair.first].distanceTo(world[pair.second]))
        }
        return ObliqueSolution(matrix = matrix, world = world, roi = roi(pixels, roiPadding),
                               distanceResiduals = residuals)
    }

    /**
     * `calculate_roi`: `[x_min, y_min, width, height]` of the rectangle framing the points, optionally padded by
     * `padding` × size on each side.
     */
    fun roi(pixels: List<Point2>, padding: Double = 0.0): List<Double> {
        val first = pixels.firstOrNull() ?: return listOf(0.0, 0.0, 0.0, 0.0)
        var xMin = first.x
        var xMax = first.x
        var yMin = first.y
        var yMax = first.y
        for (p in pixels) {
            xMin = swiftMin(xMin, p.x)
            xMax = swiftMax(xMax, p.x)
            yMin = swiftMin(yMin, p.y)
            yMax = swiftMax(yMax, p.y)
        }
        var width = xMax - xMin
        var height = yMax - yMin
        if (padding > 0) {
            val px = width * padding
            val py = height * padding
            xMin -= px
            yMin -= py
            width += 2 * px
            height += 2 * py
        }
        return listOf(xMin, yMin, width, height)
    }

    // Least squares

    /**
     * Residuals (solved − measured, order d13, d23, d34, d41, d24) and their 5×4 Jacobian for
     * v = (e3, n3, e4, n4). A residual whose two points coincide gets a zero Jacobian row (the direction is
     * undefined; it happens at the initial guess, where P3 == P4).
     */
    private fun residuals(v: DoubleArray, d: ObliqueDistances): Pair<DoubleArray, Array<DoubleArray>> {
        val p1 = Point2(0.0, 0.0)
        val p2 = Point2(d.d12, 0.0)
        val p3 = Point2(v[0], v[1])
        val p4 = Point2(v[2], v[3])
        // (from, to, index of `from` among unknowns or -1, index of `to`, target)
        data class Term(val from: Point2, val to: Point2, val fromIndex: Int, val toIndex: Int, val target: Double)
        val terms: List<Term> = listOf(
            Term(p1, p3, -1, 0, d.d13), Term(p2, p3, -1, 0, d.d23), Term(p3, p4, 0, 1, d.d34),
            Term(p1, p4, -1, 1, d.d41), Term(p2, p4, -1, 1, d.d24),
        )
        val r = DoubleArray(terms.size)
        val jac = Array(terms.size) { DoubleArray(4) }
        for ((k, term) in terms.withIndex()) {
            val dx = term.to.x - term.from.x
            val dy = term.to.y - term.from.y
            val n: Double = hypot(dx, dy)
            r[k] = n - term.target
            val row = jac[k]
            if (n > 1e-12 * max(1.0, term.target)) {
                val ux = dx / n
                val uy = dy / n
                row[2 * term.toIndex] += ux
                row[2 * term.toIndex + 1] += uy
                if (term.fromIndex >= 0) {
                    row[2 * term.fromIndex] -= ux
                    row[2 * term.fromIndex + 1] -= uy
                }
            }
        }
        return Pair(r, jac)
    }

    private fun levenbergMarquardt(d: ObliqueDistances): DoubleArray {
        var v = DoubleArray(4) { d.d12 / 2 }
        var (r, jac) = residuals(v, d)
        var cost: Double = r.fold(0.0) { acc, x -> acc + x * x }
        var lambda = 1e-3
        for (iteration in 0 until 500) {
            val a = Array(4) { DoubleArray(4) }
            val g = DoubleArray(4)
            for (k in r.indices) {
                for (i in 0 until 4) {
                    g[i] += jac[k][i] * r[k]
                    for (j in 0 until 4) a[i][j] += jac[k][i] * jac[k][j]
                }
            }
            if (g.all { abs(it) < 1e-15 * max(1.0, d.d12) }) break
            val damped = Array(4) { a[it].copyOf() }
            for (i in 0 until 4) damped[i][i] += lambda * max(a[i][i], 1e-12)
            val step: List<Double>
            try {
                step = LinearSolver.solve(damped.map { it.toList() }, g.map { -it })
            } catch (e: RiverCoreError) {
                lambda *= 10
                if (lambda > 1e16) break
                continue
            }
            val candidate = v.copyOf()
            for (i in 0 until 4) candidate[i] += step[i]
            val (rn, jn) = residuals(candidate, d)
            val cn: Double = rn.fold(0.0) { acc, x -> acc + x * x }
            if (cn < cost) {
                val scale: Double = max(1.0, v.map { abs(it) }.maxOrNull() ?: 1.0)
                val small: Boolean = step.all { abs(it) < 1e-13 * scale }
                v = candidate
                r = rn
                jac = jn
                cost = cn
                lambda = max(lambda / 10, 1e-15)
                if (small) break
            } else {
                lambda *= 10
                if (lambda > 1e16) break
            }
        }
        return v
    }

    /** Swift's `min(a, b)` (`b < a ? b : a`), which does not propagate NaN like `kotlin.math.min`. */
    private fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a

    private fun swiftMax(a: Double, b: Double): Double = if (b >= a) b else a
}
