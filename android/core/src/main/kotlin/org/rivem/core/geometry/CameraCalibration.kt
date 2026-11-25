package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A 3-D ground reference point of a fixed-station ("ipcam") setup: real-world X (East), Y (North), Z (elevation,
 * up) in metres and its pixel position (x right, y down). One entry of desktop's `grp_3d.json` lists.
 */
data class GroundPoint3D(val X: Double, val Y: Double, val Z: Double, val pixel: Point2) {
    constructor(X: Double, Y: Double, Z: Double, x: Double, y: Double) : this(X, Y, Z, Point2(x, y))

    /** Desktop marks a point not placed on the image with pixel (0, 0) (`parseGrp3dPoints.ts`). */
    val isMarked: Boolean get() = !(pixel.x == 0.0 && pixel.y == 0.0)

    companion object {
        /** Parallel lists `X, Y, Z, x, y` as in `grp_3d.json` / `full_grp_3d.json`. */
        fun fromLists(X: List<Double>, Y: List<Double>, Z: List<Double>, x: List<Double>, y: List<Double>): List<GroundPoint3D> {
            val n = X.size
            if (!(Y.size == n && Z.size == n && x.size == n && y.size == n)) throw RiverCoreError.MismatchedPointCounts()
            val out = ArrayList<GroundPoint3D>(n)
            for (i in 0 until n) out.add(GroundPoint3D(X[i], Y[i], Z[i], x[i], y[i]))
            return out
        }
    }
}

/**
 * 3×4 pinhole camera matrix P (row-major), `[x, y, w]ᵀ = P · [X, Y, Z, 1]ᵀ`. Same layout as
 * `camera_solution_3d.json` → `camera_matrix`.
 */
class CameraMatrix(p: List<Double>) {
    val p: DoubleArray = p.toDoubleArray()

    init {
        require(this.p.size == 12) { "CameraMatrix needs 12 values" }
    }

    companion object {
        fun rowMajor(vararg v: Double): CameraMatrix = CameraMatrix(v.toList())

        fun fromRows(rows: List<List<Double>>): CameraMatrix {
            require(rows.size == 3 && rows.all { it.size == 4 }) { "CameraMatrix needs 3 rows of 4 values" }
            return CameraMatrix(rows.flatten())
        }
    }

    val rows: List<List<Double>> get() = listOf(p.slice(0..3), p.slice(4..7), p.slice(8..11))

    operator fun get(row: Int, col: Int): Double = p[row * 4 + col]

    /** Pixel position of a world point (`project_points`). */
    fun project(X: Double, Y: Double, Z: Double): Point2 {
        val u = p[0] * X + p[1] * Y + p[2] * Z + p[3]
        val v = p[4] * X + p[5] * Y + p[6] * Z + p[7]
        val w = p[8] * X + p[9] * Y + p[10] * Z + p[11]
        return Point2(u / w, v / w)
    }

    /** Camera centre C = −M⁻¹·p4 with M the left 3×3 block (`get_camera_center`), as [X, Y, Z]. */
    fun center(): List<Double> {
        val m = Matrix3.rowMajor(p[0], p[1], p[2], p[4], p[5], p[6], p[8], p[9], p[10])
        val inv = m.inverse()
        val p4 = doubleArrayOf(p[3], p[7], p[11])
        val c = DoubleArray(3)
        for (i in 0 until 3) c[i] = -(inv[i, 0] * p4[0] + inv[i, 1] * p4[1] + inv[i, 2] * p4[2])
        return c.toList()
    }

    /**
     * World → pixel homography of the horizontal plane Z = `z`:
     * `H = [[P00, P01, z·P02 + P03], [P10, P11, z·P12 + P13], [P20, P21, z·P22 + P23]]`.
     */
    fun levelHomography(z: Double): Matrix3 = Matrix3.rowMajor(
        p[0], p[1], z * p[2] + p[3],
        p[4], p[5], z * p[6] + p[7],
        p[8], p[9], z * p[10] + p[11],
    )

    /**
     * Pixel → world matrix of the water surface at elevation `z`: `inv(H(z))`, exactly what the desktop GUI writes to
     * `transformation_matrix.json` in ipcam mode (`getTransformationFromCameraMatrix`, `gui/commons/coordinates.ts`;
     * cofactor inverse, not normalised).
     */
    fun pixelToWorld(z: Double): Matrix3 = levelHomography(z).inverse()

    override fun equals(other: Any?): Boolean = other is CameraMatrix && p.contentEquals(other.p)

    override fun hashCode(): Int = p.contentHashCode()

    override fun toString(): String = "CameraMatrix(${p.joinToString()})"
}

/** Error ellipse of one control point (`calculate_uncertainty_ellipses`). */
data class UncertaintyEllipse(
    val center: Point2,
    val width: Double,
    val height: Double,
    /**
     * Degrees, direction of the minor axis eigenvector. The eigenvector sign is arbitrary (LAPACK's choice in
     * desktop), so compare angles modulo 180°.
     */
    val angle: Double,
)

/** What desktop stores in `camera_solution_3d.json` (minus the ortho image fields). */
data class CameraSolution(
    val camera: CameraMatrix,
    val cameraPosition: List<Double>,
    /** Mean reprojection error in pixels (`error`). */
    val meanError: Double,
    val reprojectionErrors: List<Double>,
    val projectedPoints: List<Point2>,
    val uncertaintyEllipses: List<UncertaintyEllipse>,
)

/**
 * Fixed-station ("ipcam") 3-D rectification: the 11-parameter DLT camera matrix from ≥ 6 ground reference points.
 * Port of `solve_c_matrix`, `project_points`, `get_camera_center`, `calculate_uncertainty_ellipses` and the
 * "direct-solve" branch of `get_camera_solution` in `river/core/coordinate_transform.py` (RIVeR 3.8.3).
 *
 * Not ported: the "optimize-solution" random subset search (`optimize_points_comprehensive`). Callers can pass a
 * subset to `solve(points, evaluationPoints)` themselves.
 *
 * **Numerical note (deviation in method, not in objective):** desktop minimises ‖A·p‖ with ‖p‖ = 1 on the raw
 * coordinates with LAPACK's SVD, which is extremely badly scaled. This port (like the Swift one) minimises the same
 * objective with a one-sided Jacobi SVD, which is accurate for column-scaled matrices; results agree with desktop
 * within that noise (golden tolerances: mean error 0.1 px, world positions ~5 cm). The overall sign of P is fixed
 * so that the points have positive homogeneous depth `w` on average.
 */
object CameraCalibration {
    /** DLT camera matrix from ≥ 6 points (`solve_c_matrix`). */
    fun solveCameraMatrix(points: List<GroundPoint3D>): CameraMatrix {
        if (points.size < 6) throw RiverCoreError.NotEnoughPoints(needed = 6, got = points.size)
        val rows = ArrayList<DoubleArray>(2 * points.size)
        for (g in points) {
            val x = g.pixel.x
            val y = g.pixel.y
            rows.add(doubleArrayOf(g.X, g.Y, g.Z, 1.0, 0.0, 0.0, 0.0, 0.0, -x * g.X, -x * g.Y, -x * g.Z, -x))
            rows.add(doubleArrayOf(0.0, 0.0, 0.0, 0.0, g.X, g.Y, g.Z, 1.0, -y * g.X, -y * g.Y, -y * g.Z, -y))
        }
        var v: DoubleArray = JacobiSVD.smallestRightSingularVector(rows)
        var meanW = 0.0
        for (g in points) meanW += v[8] * g.X + v[9] * g.Y + v[10] * g.Z + v[11]
        if (meanW < 0) v = DoubleArray(v.size) { -v[it] }
        val camera = CameraMatrix(v.toList())
        try {
            camera.center()
        } catch (e: RiverCoreError) {
            throw RiverCoreError.DegenerateGeometry("los puntos no definen una cámara (¿todos en un plano?)")
        }
        return camera
    }

    /** Reprojection errors (px) of `points` under `camera`. */
    fun reprojectionErrors(camera: CameraMatrix, points: List<GroundPoint3D>): List<Double> =
        points.map { camera.project(it.X, it.Y, it.Z).distanceTo(it.pixel) }

    /**
     * "direct-solve": solve with `points`, evaluate errors and ellipses on `evaluationPoints` (desktop's
     * `full_grp_dict`, all marked points) or on `points` when null.
     */
    fun solve(points: List<GroundPoint3D>, evaluationPoints: List<GroundPoint3D>? = null,
              confidence: Double = 0.95): CameraSolution {
        val camera = solveCameraMatrix(points)
        val eval: List<GroundPoint3D> = evaluationPoints ?: points
        val projected: List<Point2> = eval.map { camera.project(it.X, it.Y, it.Z) }
        val actual: List<Point2> = eval.map { it.pixel }
        val errors = ArrayList<Double>(eval.size)
        for (i in eval.indices) errors.add(projected[i].distanceTo(actual[i]))
        val mean: Double = if (errors.isEmpty()) 0.0 else errors.fold(0.0) { a, b -> a + b } / errors.size.toDouble()
        val position: List<Double> = camera.center()
        return CameraSolution(
            camera = camera, cameraPosition = position, meanError = mean, reprojectionErrors = errors,
            projectedPoints = projected,
            uncertaintyEllipses = uncertaintyEllipses(actual, projected, confidence),
        )
    }

    /**
     * `calculate_uncertainty_ellipses`: for each point, covariance (ddof 1) of the errors of points i−2…i+2,
     * scaled by the χ²(2) quantile; width from the smaller eigenvalue, height from the larger one.
     */
    fun uncertaintyEllipses(actual: List<Point2>, projected: List<Point2>, confidence: Double = 0.95): List<UncertaintyEllipse> {
        // χ² with 2 degrees of freedom has the closed-form quantile −2·ln(1 − p).
        val chi2: Double = -2 * ln(1 - confidence)
        val n = min(actual.size, projected.size)
        val out = ArrayList<UncertaintyEllipse>(n)
        for (i in 0 until n) {
            val lo = max(0, i - 2)
            val hi = min(n, i + 3)
            val ex = ArrayList<Double>()
            val ey = ArrayList<Double>()
            for (k in lo until hi) {
                ex.add(projected[k].x - actual[k].x)
                ey.add(projected[k].y - actual[k].y)
            }
            val m = ex.size.toDouble()
            val mx = ex.fold(0.0) { a, b -> a + b } / m
            val my = ey.fold(0.0) { a, b -> a + b } / m
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (k in ex.indices) {
                sxx += (ex[k] - mx) * (ex[k] - mx)
                syy += (ey[k] - my) * (ey[k] - my)
                sxy += (ex[k] - mx) * (ey[k] - my)
            }
            val denom: Double = if (m > 1) m - 1 else Double.NaN
            sxx /= denom
            syy /= denom
            sxy /= denom
            val half = (sxx + syy) / 2
            val rad: Double = hypot((sxx - syy) / 2, sxy)
            val l1 = half - rad
            val l2 = half + rad
            // Eigenvector of the smaller eigenvalue.
            val vx: Double
            val vy: Double
            if (sxy == 0.0) {
                if (sxx <= syy) {
                    vx = 1.0; vy = 0.0
                } else {
                    vx = 0.0; vy = 1.0
                }
            } else {
                val ax = sxy
                val ay = l1 - sxx
                val bx = l1 - syy
                val by = sxy
                if (hypot(ax, ay) >= hypot(bx, by)) {
                    vx = ax; vy = ay
                } else {
                    vx = bx; vy = by
                }
            }
            val angle: Double = atan2(vy, vx) * 180 / Math.PI
            out.add(UncertaintyEllipse(center = actual[i], width = 2 * sqrt(chi2 * abs(l1)),
                                       height = 2 * sqrt(chi2 * abs(l2)), angle = angle))
        }
        return out
    }
}

/**
 * One-sided Jacobi SVD (Hestenes), enough to get the right singular vector of the smallest singular value of a tall
 * matrix. Accurate for badly column-scaled matrices, no native libraries.
 */
internal object JacobiSVD {
    fun smallestRightSingularVector(a: List<DoubleArray>): DoubleArray {
        val n = a.firstOrNull()?.size ?: throw RiverCoreError.SingularMatrix()
        if (!(n > 0 && a.size >= n - 1)) throw RiverCoreError.SingularMatrix()
        val m = a.size
        // Column-major copy.
        val cols = Array(n) { DoubleArray(m) }
        for (i in 0 until m) for (j in 0 until n) cols[j][i] = a[i][j]
        val v = Array(n) { DoubleArray(n) } // columns of V
        for (j in 0 until n) v[j][j] = 1.0
        for (sweep in 0 until 80) {
            var rotated = false
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    var alpha = 0.0
                    var beta = 0.0
                    var gamma = 0.0
                    for (i in 0 until m) {
                        alpha += cols[p][i] * cols[p][i]
                        beta += cols[q][i] * cols[q][i]
                        gamma += cols[p][i] * cols[q][i]
                    }
                    if (gamma == 0.0 || abs(gamma) <= 1e-15 * sqrt(alpha * beta)) continue
                    rotated = true
                    val zeta = (beta - alpha) / (2 * gamma)
                    val sign: Double = if (zeta >= 0) 1.0 else -1.0
                    val t = sign / (abs(zeta) + sqrt(1 + zeta * zeta))
                    val c = 1 / sqrt(1 + t * t)
                    val s = c * t
                    for (i in 0 until m) {
                        val xp = cols[p][i]
                        val xq = cols[q][i]
                        cols[p][i] = c * xp - s * xq
                        cols[q][i] = s * xp + c * xq
                    }
                    for (i in 0 until n) {
                        val xp = v[p][i]
                        val xq = v[q][i]
                        v[p][i] = c * xp - s * xq
                        v[q][i] = s * xp + c * xq
                    }
                }
            }
            if (!rotated) break
        }
        var best = 0
        var bestNorm = Double.POSITIVE_INFINITY
        for (j in 0 until n) {
            var s = 0.0
            for (i in 0 until m) s += cols[j][i] * cols[j][i]
            if (s < bestNorm) {
                bestNorm = s
                best = j
            }
        }
        val out: DoubleArray = v[best].copyOf()
        if (!out.all { it.isFinite() }) throw RiverCoreError.SingularMatrix()
        return out
    }
}
