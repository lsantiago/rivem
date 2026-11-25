package org.rivem.core.piv

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Vector-field operations of `piv_fftmulti.py` on row-major `rows × cols` tables of `Double` (NaN = missing):
 * outlier filters, NaN replacement, DCT smoothing and the spline predictor between passes.
 *
 * Swift `inout [Double]` parameters are `DoubleArray`s modified in place.
 */
internal object PIVFieldOps {
    // region Outlier filters

    /** `filter_std`: values outside `nanmean ± threshold · nanstd(ddof=1)` become NaN, per component. */
    fun filterStd(t: DoubleArray, threshold: Double) {
        var n = 0
        var sum = 0.0
        for (v in t) {
            if (v.isNaN()) continue
            n += 1
            sum += v
        }
        if (n < 2) return // nanstd(ddof=1) is NaN: every comparison is false upstream
        val mean = sum / n.toDouble()
        var ss = 0.0
        for (v in t) {
            if (v.isNaN()) continue
            val d = v - mean
            ss += d * d
        }
        val std = sqrt(ss / (n - 1).toDouble())
        val lo = mean - threshold * std
        val hi = mean + threshold * std
        for (i in t.indices) {
            val v = t[i]
            if (v < lo || v > hi) t[i] = Double.NaN
        }
    }

    /**
     * `filter_fluctuations` (normalised median test, b = 1): for every interior node, with `med` = median of the 8
     * neighbours and `r` = median |neighbour − med|, `nf = |v − med| / (r + epsilon)` per component; the vector is
     * discarded when `sqrt(nfu² + nfv²) > threshold`. As upstream (`np.median`, not `nanmedian`), a NaN anywhere in
     * the 3×3 block makes the test NaN, which never flags; the outer ring is never tested.
     */
    fun filterMedian(u: DoubleArray, v: DoubleArray, rows: Int, cols: Int, epsilon: Double, threshold: Double) {
        if (rows < 3 || cols < 3) return
        val flag = BooleanArray(rows * cols)
        val nb = DoubleArray(8)
        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val nfu = normalisedFluctuation(u, r, c, cols, epsilon, nb)
                val nfv = normalisedFluctuation(v, r, c, cols, epsilon, nb)
                val s = sqrt(nfu * nfu + nfv * nfv)
                if (s > threshold) flag[r * cols + c] = true
            }
        }
        for (i in flag.indices) {
            if (flag[i]) {
                u[i] = Double.NaN
                v[i] = Double.NaN
            }
        }
    }

    private fun normalisedFluctuation(t: DoubleArray, r: Int, c: Int, cols: Int, epsilon: Double,
                                      nb: DoubleArray): Double {
        var k = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val x = t[(r + dr) * cols + (c + dc)]
                if (x.isNaN()) return Double.NaN
                nb[k] = x
                k += 1
            }
        }
        val med = median8(nb)
        for (i in 0 until 8) nb[i] = abs(nb[i] - med)
        val res = median8(nb)
        val fluct = t[r * cols + c] - med
        return abs(fluct / (res + epsilon))
    }

    /** Median of 8 values (mean of the 4th and 5th), sorts in place. */
    private fun median8(a: DoubleArray): Double {
        for (i in 1 until 8) {
            val x = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > x) {
                a[j + 1] = a[j]
                j -= 1
            }
            a[j + 1] = x
        }
        return (a[3] + a[4]) / 2
    }

    // endregion

    // region NaN replacement

    /** Indices of the valid (non-NaN) and missing (NaN) entries, in order. */
    private fun split(t: DoubleArray): Pair<IntArray, IntArray> {
        var nMissing = 0
        for (x in t) if (x.isNaN()) nMissing += 1
        val valid = IntArray(t.size - nMissing)
        val missing = IntArray(nMissing)
        var iv = 0
        var im = 0
        for (i in t.indices) {
            if (t[i].isNaN()) missing[im++] = i else valid[iv++] = i
        }
        return Pair(valid, missing)
    }

    /**
     * `nearest_inpaint`: every NaN takes the value of the nearest valid node (grid-index distance). Ties: first
     * valid node in row-major order (scipy's KD-tree tie order is unspecified). All-NaN tables are left unchanged
     * (upstream raises and the pair is lost).
     */
    fun nearestInpaint(t: DoubleArray, rows: Int, cols: Int) {
        val (valid, missing) = split(t)
        if (valid.isEmpty() || missing.isEmpty()) return
        val src = t.copyOf()
        for (m in missing) {
            t[m] = src[nearest(m, valid, cols)]
        }
    }

    private fun nearest(m: Int, valid: IntArray, cols: Int): Int {
        val mr = m / cols
        val mc = m % cols
        var best = valid[0]
        var bestD = Int.MAX_VALUE
        for (p in valid) {
            val dr = p / cols - mr
            val dc = p % cols - mc
            val d = dr * dr + dc * dc
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best
    }

    /** Above this many valid nodes the global RBF systems are replaced by local ones (cost control, see [inpaintRBF]). */
    const val rbfGlobalLimit = 1000
    const val rbfLocalNeighbours = 128

    /**
     * `inpaint_nans` (between passes): NaNs ← 0.75 · RBF(cubic, r³) + 0.24 · RBF(inverse, ε = 1.5) + 0.01 · nearest,
     * with scipy `Rbf` semantics (no polynomial term, smooth = 0, exact interpolation of the valid nodes, coordinates
     * = (row, column) indices). Solved with dense LU in Double.
     *
     * Deviation (cost): with more than [rbfGlobalLimit] valid nodes (e.g. 4K frames with small windows) each NaN is
     * interpolated from its [rbfLocalNeighbours] nearest valid nodes instead of all of them. This only feeds the
     * pass-2 predictor, which is smoothed afterwards. A singular system falls back to the nearest value.
     */
    fun inpaintRBF(t: DoubleArray, rows: Int, cols: Int) {
        val (valid, missing) = split(t)
        if (valid.isEmpty() || missing.isEmpty()) return
        val src = t.copyOf()
        if (valid.size <= rbfGlobalLimit) {
            val cubic = rbfInterpolate(valid, src, missing, cols, RBFKernel.Cubic)
            val inverse = rbfInterpolate(valid, src, missing, cols, RBFKernel.Inverse)
            for ((k, m) in missing.withIndex()) {
                val nn = src[nearest(m, valid, cols)]
                if (cubic != null && inverse != null) {
                    t[m] = 0.75 * cubic[k] + 0.24 * inverse[k] + 0.01 * nn
                } else {
                    t[m] = nn
                }
            }
        } else {
            val target = IntArray(1)
            for (m in missing) {
                val local = nearestNodes(m, valid, cols, rbfLocalNeighbours)
                val nn = src[local[0]]
                target[0] = m
                val c = rbfInterpolate(local, src, target, cols, RBFKernel.Cubic)
                val iv = rbfInterpolate(local, src, target, cols, RBFKernel.Inverse)
                if (c != null && iv != null) {
                    t[m] = 0.75 * c[0] + 0.24 * iv[0] + 0.01 * nn
                } else {
                    t[m] = nn
                }
            }
        }
    }

    private enum class RBFKernel { Cubic, Inverse }

    private fun phi(r: Double, k: RBFKernel): Double = when (k) {
        RBFKernel.Cubic -> r * r * r
        RBFKernel.Inverse -> {
            val q = r / 1.5
            1.0 / sqrt(q * q + 1.0)
        }
    }

    private fun rbfInterpolate(nodes: IntArray, values: DoubleArray, targets: IntArray, cols: Int,
                               kernel: RBFKernel): DoubleArray? {
        val n = nodes.size
        val a = DoubleArray(n * n)
        val rhs = DoubleArray(n)
        for (i in 0 until n) {
            val ri = (nodes[i] / cols).toDouble()
            val ci = (nodes[i] % cols).toDouble()
            rhs[i] = values[nodes[i]]
            for (j in 0 until n) {
                val dr = ri - (nodes[j] / cols).toDouble()
                val dc = ci - (nodes[j] % cols).toDouble()
                a[i * n + j] = phi(sqrt(dr * dr + dc * dc), kernel)
            }
        }
        val piv = PIVLinear.luFactor(a, n) ?: return null
        PIVLinear.luSolve(a, piv, n, rhs)
        val out = DoubleArray(targets.size)
        for ((k, m) in targets.withIndex()) {
            val mr = (m / cols).toDouble()
            val mc = (m % cols).toDouble()
            var s = 0.0
            for (j in 0 until n) {
                val dr = mr - (nodes[j] / cols).toDouble()
                val dc = mc - (nodes[j] % cols).toDouble()
                s += rhs[j] * phi(sqrt(dr * dr + dc * dc), kernel)
            }
            out[k] = s
        }
        return out
    }

    /** The `count` valid nodes nearest to `m`, ordered by (distance², index) like Swift's tuple sort. */
    private fun nearestNodes(m: Int, valid: IntArray, cols: Int, count: Int): IntArray {
        val mr = m / cols
        val mc = m % cols
        // Key = (d² << 32) | index: both non-negative, so sorting the keys sorts by (d², index).
        val scored = LongArray(valid.size)
        for ((i, p) in valid.withIndex()) {
            val dr = (p / cols - mr).toLong()
            val dc = (p % cols - mc).toLong()
            scored[i] = ((dr * dr + dc * dc) shl 32) or p.toLong()
        }
        scored.sort()
        val n = min(count, scored.size)
        return IntArray(n) { (scored[it] and 0xFFFF_FFFFL).toInt() }
    }

    // endregion

    // region smoothn

    /**
     * `smoothn(y, s)` as upstream (not Garcia's iterative robust version): `z = IDCT2(Γ · DCT2(y))` with orthonormal
     * DCT-II over both axes, `Γ = 1 / (1 + (s·|Λ|)^order)`, `Λ = −2·(2 − cos(πi/rows) − cos(πj/cols))`.
     * NaNs are replaced by 0 before the transform and restored afterwards (as upstream). Grids are small, so the
     * DCT is done as dense matrix products. Returns a new array (`t` is not modified).
     */
    fun smoothn(t: DoubleArray, rows: Int, cols: Int, s: Double, order: Double = 2.0): DoubleArray {
        if (rows == 0 || cols == 0) return t.copyOf()
        val cy = dctMatrix(rows)
        val cx = dctMatrix(cols)
        val y = t.copyOf()
        for (i in y.indices) if (!y[i].isFinite()) y[i] = 0.0
        // D = Cy · Y · Cxᵀ
        val tmp = matMul(cy, y, rows, rows, cols)                  // rows × cols
        val d = matMulTransposedRight(tmp, cx, rows, cols, cols)   // rows × cols
        for (i in 0 until rows) {
            val li = cos(PI * i.toDouble() / rows.toDouble())
            for (j in 0 until cols) {
                val lj = cos(PI * j.toDouble() / cols.toDouble())
                val lambda = -2.0 * (2.0 - (li + lj))
                val g = 1.0 / (1.0 + (s * abs(lambda)).pow(order))
                d[i * cols + j] *= g
            }
        }
        // Z = Cyᵀ · D · Cx
        val tmp2 = matMulTransposedLeft(cy, d, rows, rows, cols)
        val z = matMul(tmp2, cx, rows, cols, cols)
        for (i in z.indices) if (!t[i].isFinite()) z[i] = Double.NaN
        return z
    }

    /** Orthonormal DCT-II matrix: `C[k][j] = s(k)·cos(π·k·(2j+1)/(2n))`. */
    fun dctMatrix(n: Int): DoubleArray {
        val c = DoubleArray(n * n)
        for (k in 0 until n) {
            val s: Double = if (k == 0) sqrt(1.0 / n.toDouble()) else sqrt(2.0 / n.toDouble())
            for (j in 0 until n) {
                c[k * n + j] = s * cos(PI * k.toDouble() * (2 * j + 1).toDouble() / (2 * n).toDouble())
            }
        }
        return c
    }

    /** (m × k) · (k × n) */
    fun matMul(a: DoubleArray, b: DoubleArray, m: Int, k: Int, n: Int): DoubleArray {
        val out = DoubleArray(m * n)
        for (i in 0 until m) {
            for (p in 0 until k) {
                val x = a[i * k + p]
                if (x == 0.0) continue
                for (j in 0 until n) out[i * n + j] += x * b[p * n + j]
            }
        }
        return out
    }

    /** (m × k) · (n × k)ᵀ */
    fun matMulTransposedRight(a: DoubleArray, b: DoubleArray, m: Int, k: Int, n: Int): DoubleArray {
        val out = DoubleArray(m * n)
        for (i in 0 until m) {
            for (j in 0 until n) {
                var s = 0.0
                for (p in 0 until k) s += a[i * k + p] * b[j * k + p]
                out[i * n + j] = s
            }
        }
        return out
    }

    /** (k × m)ᵀ · (k × n) */
    fun matMulTransposedLeft(a: DoubleArray, b: DoubleArray, k: Int, m: Int, n: Int): DoubleArray {
        val out = DoubleArray(m * n)
        for (p in 0 until k) {
            for (i in 0 until m) {
                val x = a[p * m + i]
                if (x == 0.0) continue
                for (j in 0 until n) out[i * n + j] += x * b[p * n + j]
            }
        }
        return out
    }

    // endregion

    // region Spline predictor

    /**
     * Weights `W` (query × data, row-major) of the 1-D interpolant used by `interpolate_tables`'
     * `RectBivariateSpline(..., s=0)`: with ≥ 4 points FITPACK's interpolating cubic with interior knots
     * `x[2]…x[m−3]` (= not-a-knot cubic spline), extrapolated with the end polynomials; with 3 points a quadratic,
     * 2 points a line (degree `m − 1`, `interpgrade`). 1 point: constant (upstream raises).
     */
    fun splineWeights(x: DoubleArray, q: DoubleArray): DoubleArray {
        val m = x.size
        val nq = q.size
        val w = DoubleArray(nq * m)
        if (m == 0) return w
        if (m <= 3) {
            for ((k, xq) in q.withIndex()) {
                for (j in 0 until m) {
                    var l = 1.0
                    for (i in 0 until m) {
                        if (i != j) l *= (xq - x[i]) / (x[j] - x[i])
                    }
                    w[k * m + j] = l
                }
            }
            return w
        }
        // Second derivatives M = S · y, from the not-a-knot system A · M = B · y.
        val a = DoubleArray(m * m)
        val b = DoubleArray(m * m)
        val h = DoubleArray(m - 1)
        for (i in 0 until m - 1) h[i] = x[i + 1] - x[i]
        // Row 0: third derivative continuous at x[1].
        a[0 * m + 0] = h[1]
        a[0 * m + 1] = -(h[0] + h[1])
        a[0 * m + 2] = h[0]
        for (i in 1 until m - 1) {
            a[i * m + i - 1] = h[i - 1]
            a[i * m + i] = 2 * (h[i - 1] + h[i])
            a[i * m + i + 1] = h[i]
            b[i * m + i - 1] = 6 / h[i - 1]
            b[i * m + i] = -6 / h[i - 1] - 6 / h[i]
            b[i * m + i + 1] = 6 / h[i]
        }
        // Row m-1: third derivative continuous at x[m-2].
        a[(m - 1) * m + m - 3] = h[m - 2]
        a[(m - 1) * m + m - 2] = -(h[m - 3] + h[m - 2])
        a[(m - 1) * m + m - 1] = h[m - 3]
        // Cannot fail for distinct sorted nodes; fall back to linear weights.
        val piv = PIVLinear.luFactor(a, m) ?: return linearWeights(x, q)
        // S columns: solve A · s_j = B[:, j].
        val s = DoubleArray(m * m) // s[i * m + j] = dM_i / dy_j
        val col = DoubleArray(m)
        for (j in 0 until m) {
            for (i in 0 until m) col[i] = b[i * m + j]
            PIVLinear.luSolve(a, piv, m, col)
            for (i in 0 until m) s[i * m + j] = col[i]
        }
        for ((k, xq) in q.withIndex()) {
            var i = 0
            if (xq >= x[m - 2]) {
                i = m - 2
            } else {
                while (i < m - 2 && xq >= x[i + 1]) i += 1
            }
            val hi = h[i]
            val t1 = x[i + 1] - xq
            val t0 = xq - x[i]
            val c0: Double = t1 * t1 * t1 / (6 * hi) - hi * t1 / 6   // coefficient of M_i
            val c1: Double = t0 * t0 * t0 / (6 * hi) - hi * t0 / 6   // coefficient of M_{i+1}
            for (j in 0 until m) {
                var v = c0 * s[i * m + j] + c1 * s[(i + 1) * m + j]
                if (j == i) v += t1 / hi
                if (j == i + 1) v += t0 / hi
                w[k * m + j] = v
            }
        }
        return w
    }

    private fun linearWeights(x: DoubleArray, q: DoubleArray): DoubleArray {
        val m = x.size
        val w = DoubleArray(q.size * m)
        for ((k, xq) in q.withIndex()) {
            var i = 0
            while (i < m - 2 && xq >= x[i + 1]) i += 1
            val f = (xq - x[i]) / (x[i + 1] - x[i])
            w[k * m + i] = 1 - f
            w[k * m + i + 1] = f
        }
        return w
    }

    /** Tensor-product interpolation of a `rows × cols` table: `Wy · T · Wxᵀ`. */
    fun interpolate(t: DoubleArray, rows: Int, cols: Int, wy: DoubleArray, newRows: Int,
                    wx: DoubleArray, newCols: Int): DoubleArray {
        val tmp = matMul(wy, t, newRows, rows, cols)                   // newRows × cols
        return matMulTransposedRight(tmp, wx, newRows, cols, newCols)  // newRows × newCols
    }

    // endregion
}

/** Small dense LU with partial pivoting (row-major, in place). */
internal object PIVLinear {
    fun luFactor(a: DoubleArray, n: Int): IntArray? {
        val piv = IntArray(n)
        for (k in 0 until n) {
            var p = k
            var best = abs(a[k * n + k])
            for (i in k + 1 until n) {
                val v = abs(a[i * n + k])
                if (v > best) {
                    best = v
                    p = i
                }
            }
            if (!(best > 0) || !best.isFinite()) return null
            piv[k] = p
            if (p != k) {
                for (j in 0 until n) {
                    val tmp = a[k * n + j]
                    a[k * n + j] = a[p * n + j]
                    a[p * n + j] = tmp
                }
            }
            val d = a[k * n + k]
            for (i in k + 1 until n) {
                val f = a[i * n + k] / d
                a[i * n + k] = f
                if (f == 0.0) continue
                for (j in k + 1 until n) a[i * n + j] -= f * a[k * n + j]
            }
        }
        return piv
    }

    fun luSolve(a: DoubleArray, piv: IntArray, n: Int, b: DoubleArray) {
        for (k in 0 until n) {
            val p = piv[k]
            if (p != k) {
                val tmp = b[k]
                b[k] = b[p]
                b[p] = tmp
            }
        }
        for (i in 0 until n) {
            var s = b[i]
            for (j in 0 until i) s -= a[i * n + j] * b[j]
            b[i] = s
        }
        var i = n - 1
        while (i >= 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i * n + j] * b[j]
            b[i] = s / a[i * n + i]
            i -= 1
        }
    }
}
