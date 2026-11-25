package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.abs

/** Small dense linear algebra helpers (no native libraries, so the core stays portable). */
internal object LinearSolver {
    /** Solves the square system `a * x = b` by Gaussian elimination with partial pivoting. */
    fun solve(a: List<List<Double>>, b: List<Double>): List<Double> {
        val n = b.size
        require(a.size == n && a.all { it.size == n }) { "square system expected" }
        val aug: Array<DoubleArray> = Array(n) { a[it].toDoubleArray() }
        val rhs: DoubleArray = b.toDoubleArray()
        val scale: Double = a.flatten().maxOfOrNull { abs(it) } ?: 0.0
        if (scale <= 0.0) throw RiverCoreError.SingularMatrix()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(aug[row][col]) > abs(aug[pivot][col])) pivot = row
            if (abs(aug[pivot][col]) <= 1e-12 * scale) throw RiverCoreError.SingularMatrix()
            if (pivot != col) {
                val t = aug[pivot]; aug[pivot] = aug[col]; aug[col] = t
                val r = rhs[pivot]; rhs[pivot] = rhs[col]; rhs[col] = r
            }
            for (row in col + 1 until n) {
                val f = aug[row][col] / aug[col][col]
                if (f == 0.0) continue
                for (k in col until n) aug[row][k] -= f * aug[col][k]
                rhs[row] -= f * rhs[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = rhs[row]
            for (k in row + 1 until n) s -= aug[row][k] * x[k]
            x[row] = s / aug[row][row]
        }
        return x.toList()
    }

    /**
     * Least-squares solution of the overdetermined system `a * x ≈ b` through the normal equations. Callers must
     * condition the problem first (see `Homography.fit`, which normalises the points).
     */
    fun leastSquares(a: List<List<Double>>, b: List<Double>): List<Double> {
        val cols = a.firstOrNull()?.size ?: throw RiverCoreError.SingularMatrix()
        val ata = Array(cols) { DoubleArray(cols) }
        val atb = DoubleArray(cols)
        for ((row, value) in a.zip(b)) {
            for (i in 0 until cols) {
                atb[i] += row[i] * value
                for (j in i until cols) ata[i][j] += row[i] * row[j]
            }
        }
        for (i in 0 until cols) for (j in 0 until i) ata[i][j] = ata[j][i]
        return solve(ata.map { it.toList() }, atb.toList())
    }
}
