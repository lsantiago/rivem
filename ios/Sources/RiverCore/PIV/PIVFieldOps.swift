import Foundation

/// Vector-field operations of `piv_fftmulti.py` on row-major `rows × cols` tables of `Double` (NaN = missing):
/// outlier filters, NaN replacement, DCT smoothing and the spline predictor between passes.
enum PIVFieldOps {
    // MARK: - Outlier filters

    /// `filter_std`: values outside `nanmean ± threshold · nanstd(ddof=1)` become NaN, per component.
    static func filterStd(_ t: inout [Double], threshold: Double) {
        var n = 0
        var sum = 0.0
        for v in t where !v.isNaN {
            n += 1
            sum += v
        }
        guard n >= 2 else { return } // nanstd(ddof=1) is NaN: every comparison is false upstream
        let mean = sum / Double(n)
        var ss = 0.0
        for v in t where !v.isNaN {
            let d = v - mean
            ss += d * d
        }
        let std = (ss / Double(n - 1)).squareRoot()
        let lo = mean - threshold * std
        let hi = mean + threshold * std
        for i in 0..<t.count {
            let v = t[i]
            if v < lo || v > hi { t[i] = .nan }
        }
    }

    /// `filter_fluctuations` (normalised median test, b = 1): for every interior node, with `med` = median of the 8
    /// neighbours and `r` = median |neighbour − med|, `nf = |v − med| / (r + epsilon)` per component; the vector is
    /// discarded when `sqrt(nfu² + nfv²) > threshold`. As upstream (`np.median`, not `nanmedian`), a NaN anywhere in
    /// the 3×3 block makes the test NaN, which never flags; the outer ring is never tested.
    static func filterMedian(u: inout [Double], v: inout [Double], rows: Int, cols: Int,
                             epsilon: Double, threshold: Double) {
        guard rows >= 3, cols >= 3 else { return }
        var flag = [Bool](repeating: false, count: rows * cols)
        var nb = [Double](repeating: 0, count: 8)
        for r in 1..<(rows - 1) {
            for c in 1..<(cols - 1) {
                let nfu = normalisedFluctuation(u, r: r, c: c, cols: cols, epsilon: epsilon, buffer: &nb)
                let nfv = normalisedFluctuation(v, r: r, c: c, cols: cols, epsilon: epsilon, buffer: &nb)
                let s = (nfu * nfu + nfv * nfv).squareRoot()
                if s > threshold { flag[r * cols + c] = true }
            }
        }
        for i in 0..<flag.count where flag[i] {
            u[i] = .nan
            v[i] = .nan
        }
    }

    private static func normalisedFluctuation(_ t: [Double], r: Int, c: Int, cols: Int, epsilon: Double,
                                              buffer nb: inout [Double]) -> Double {
        var k = 0
        for dr in -1...1 {
            for dc in -1...1 {
                if dr == 0 && dc == 0 { continue }
                let x = t[(r + dr) * cols + (c + dc)]
                if x.isNaN { return .nan }
                nb[k] = x
                k += 1
            }
        }
        let med = median8(&nb)
        for i in 0..<8 { nb[i] = abs(nb[i] - med) }
        let res = median8(&nb)
        let fluct = t[r * cols + c] - med
        return abs(fluct / (res + epsilon))
    }

    /// Median of 8 values (mean of the 4th and 5th), sorts in place.
    private static func median8(_ a: inout [Double]) -> Double {
        for i in 1..<8 {
            let x = a[i]
            var j = i - 1
            while j >= 0 && a[j] > x {
                a[j + 1] = a[j]
                j -= 1
            }
            a[j + 1] = x
        }
        return (a[3] + a[4]) / 2
    }

    // MARK: - NaN replacement

    /// `nearest_inpaint`: every NaN takes the value of the nearest valid node (grid-index distance). Ties: first
    /// valid node in row-major order (scipy's KD-tree tie order is unspecified). All-NaN tables are left unchanged
    /// (upstream raises and the pair is lost).
    static func nearestInpaint(_ t: inout [Double], rows: Int, cols: Int) {
        var valid: [Int] = []
        var missing: [Int] = []
        for i in 0..<t.count {
            if t[i].isNaN { missing.append(i) } else { valid.append(i) }
        }
        guard !valid.isEmpty, !missing.isEmpty else { return }
        let src = t
        for m in missing {
            t[m] = src[nearest(m, valid: valid, cols: cols)]
        }
    }

    private static func nearest(_ m: Int, valid: [Int], cols: Int) -> Int {
        let mr = m / cols, mc = m % cols
        var best = valid[0]
        var bestD = Int.max
        for p in valid {
            let dr = p / cols - mr, dc = p % cols - mc
            let d = dr * dr + dc * dc
            if d < bestD {
                bestD = d
                best = p
            }
        }
        return best
    }

    /// Above this many valid nodes the global RBF systems are replaced by local ones (cost control, see `inpaintRBF`).
    static let rbfGlobalLimit = 1000
    static let rbfLocalNeighbours = 128

    /// `inpaint_nans` (between passes): NaNs ← 0.75 · RBF(cubic, r³) + 0.24 · RBF(inverse, ε = 1.5) + 0.01 · nearest,
    /// with scipy `Rbf` semantics (no polynomial term, smooth = 0, exact interpolation of the valid nodes, coordinates
    /// = (row, column) indices). Solved with dense LU in Double.
    ///
    /// Deviation (cost): with more than `rbfGlobalLimit` valid nodes (e.g. 4K frames with small windows) each NaN is
    /// interpolated from its `rbfLocalNeighbours` nearest valid nodes instead of all of them. This only feeds the
    /// pass-2 predictor, which is smoothed afterwards. A singular system falls back to the nearest value.
    static func inpaintRBF(_ t: inout [Double], rows: Int, cols: Int) {
        var valid: [Int] = []
        var missing: [Int] = []
        for i in 0..<t.count {
            if t[i].isNaN { missing.append(i) } else { valid.append(i) }
        }
        guard !valid.isEmpty, !missing.isEmpty else { return }
        let src = t
        if valid.count <= rbfGlobalLimit {
            let cubic = rbfInterpolate(nodes: valid, values: src, targets: missing, cols: cols, kernel: .cubic)
            let inverse = rbfInterpolate(nodes: valid, values: src, targets: missing, cols: cols, kernel: .inverse)
            for (k, m) in missing.enumerated() {
                let nn = src[nearest(m, valid: valid, cols: cols)]
                if let c = cubic, let iv = inverse {
                    t[m] = 0.75 * c[k] + 0.24 * iv[k] + 0.01 * nn
                } else {
                    t[m] = nn
                }
            }
        } else {
            for m in missing {
                let local = nearestNodes(m, valid: valid, cols: cols, count: rbfLocalNeighbours)
                let nn = src[local[0]]
                let c = rbfInterpolate(nodes: local, values: src, targets: [m], cols: cols, kernel: .cubic)
                let iv = rbfInterpolate(nodes: local, values: src, targets: [m], cols: cols, kernel: .inverse)
                if let c = c, let iv = iv {
                    t[m] = 0.75 * c[0] + 0.24 * iv[0] + 0.01 * nn
                } else {
                    t[m] = nn
                }
            }
        }
    }

    private enum RBFKernel { case cubic, inverse }

    @inline(__always)
    private static func phi(_ r: Double, _ k: RBFKernel) -> Double {
        switch k {
        case .cubic: return r * r * r
        case .inverse:
            let q = r / 1.5
            return 1.0 / (q * q + 1.0).squareRoot()
        }
    }

    private static func rbfInterpolate(nodes: [Int], values: [Double], targets: [Int], cols: Int,
                                       kernel: RBFKernel) -> [Double]? {
        let n = nodes.count
        var a = [Double](repeating: 0, count: n * n)
        var rhs = [Double](repeating: 0, count: n)
        for i in 0..<n {
            let ri = Double(nodes[i] / cols), ci = Double(nodes[i] % cols)
            rhs[i] = values[nodes[i]]
            for j in 0..<n {
                let dr = ri - Double(nodes[j] / cols), dc = ci - Double(nodes[j] % cols)
                a[i * n + j] = phi((dr * dr + dc * dc).squareRoot(), kernel)
            }
        }
        guard let piv = PIVLinear.luFactor(&a, n: n) else { return nil }
        PIVLinear.luSolve(a, piv, n: n, &rhs)
        var out = [Double](repeating: 0, count: targets.count)
        for (k, m) in targets.enumerated() {
            let mr = Double(m / cols), mc = Double(m % cols)
            var s = 0.0
            for j in 0..<n {
                let dr = mr - Double(nodes[j] / cols), dc = mc - Double(nodes[j] % cols)
                s += rhs[j] * phi((dr * dr + dc * dc).squareRoot(), kernel)
            }
            out[k] = s
        }
        return out
    }

    private static func nearestNodes(_ m: Int, valid: [Int], cols: Int, count: Int) -> [Int] {
        let mr = m / cols, mc = m % cols
        var scored: [(Int, Int)] = []
        scored.reserveCapacity(valid.count)
        for p in valid {
            let dr = p / cols - mr, dc = p % cols - mc
            scored.append((dr * dr + dc * dc, p))
        }
        scored.sort { (a: (Int, Int), b: (Int, Int)) -> Bool in a.0 < b.0 || (a.0 == b.0 && a.1 < b.1) }
        var out: [Int] = []
        for k in 0..<min(count, scored.count) { out.append(scored[k].1) }
        return out
    }

    // MARK: - smoothn

    /// `smoothn(y, s)` as upstream (not Garcia's iterative robust version): `z = IDCT2(Γ · DCT2(y))` with orthonormal
    /// DCT-II over both axes, `Γ = 1 / (1 + (s·|Λ|)^order)`, `Λ = −2·(2 − cos(πi/rows) − cos(πj/cols))`.
    /// NaNs are replaced by 0 before the transform and restored afterwards (as upstream). Grids are small, so the
    /// DCT is done as dense matrix products.
    static func smoothn(_ t: [Double], rows: Int, cols: Int, s: Double, order: Double = 2) -> [Double] {
        if rows == 0 || cols == 0 { return t }
        let cy = dctMatrix(rows)
        let cx = dctMatrix(cols)
        var y = t
        for i in 0..<y.count where !y[i].isFinite { y[i] = 0 }
        // D = Cy · Y · Cxᵀ
        let tmp = matMul(cy, y, rows, rows, cols)                    // rows × cols
        var d = matMulTransposedRight(tmp, cx, rows, cols, cols)     // rows × cols
        for i in 0..<rows {
            let li = cos(Double.pi * Double(i) / Double(rows))
            for j in 0..<cols {
                let lj = cos(Double.pi * Double(j) / Double(cols))
                let lambda = -2.0 * (2.0 - (li + lj))
                let g = 1.0 / (1.0 + pow(s * abs(lambda), order))
                d[i * cols + j] *= g
            }
        }
        // Z = Cyᵀ · D · Cx
        let tmp2 = matMulTransposedLeft(cy, d, rows, rows, cols)
        var z = matMul(tmp2, cx, rows, cols, cols)
        for i in 0..<z.count where !t[i].isFinite { z[i] = .nan }
        return z
    }

    /// Orthonormal DCT-II matrix: `C[k][j] = s(k)·cos(π·k·(2j+1)/(2n))`.
    static func dctMatrix(_ n: Int) -> [Double] {
        var c = [Double](repeating: 0, count: n * n)
        for k in 0..<n {
            let s: Double = k == 0 ? (1.0 / Double(n)).squareRoot() : (2.0 / Double(n)).squareRoot()
            for j in 0..<n {
                c[k * n + j] = s * cos(Double.pi * Double(k) * Double(2 * j + 1) / Double(2 * n))
            }
        }
        return c
    }

    /// (m × k) · (k × n)
    static func matMul(_ a: [Double], _ b: [Double], _ m: Int, _ k: Int, _ n: Int) -> [Double] {
        var out = [Double](repeating: 0, count: m * n)
        for i in 0..<m {
            for p in 0..<k {
                let x = a[i * k + p]
                if x == 0 { continue }
                for j in 0..<n { out[i * n + j] += x * b[p * n + j] }
            }
        }
        return out
    }

    /// (m × k) · (n × k)ᵀ
    static func matMulTransposedRight(_ a: [Double], _ b: [Double], _ m: Int, _ k: Int, _ n: Int) -> [Double] {
        var out = [Double](repeating: 0, count: m * n)
        for i in 0..<m {
            for j in 0..<n {
                var s = 0.0
                for p in 0..<k { s += a[i * k + p] * b[j * k + p] }
                out[i * n + j] = s
            }
        }
        return out
    }

    /// (k × m)ᵀ · (k × n)
    static func matMulTransposedLeft(_ a: [Double], _ b: [Double], _ k: Int, _ m: Int, _ n: Int) -> [Double] {
        var out = [Double](repeating: 0, count: m * n)
        for p in 0..<k {
            for i in 0..<m {
                let x = a[p * m + i]
                if x == 0 { continue }
                for j in 0..<n { out[i * n + j] += x * b[p * n + j] }
            }
        }
        return out
    }

    // MARK: - Spline predictor

    /// Weights `W` (query × data, row-major) of the 1-D interpolant used by `interpolate_tables`'
    /// `RectBivariateSpline(..., s=0)`: with ≥ 4 points FITPACK's interpolating cubic with interior knots
    /// `x[2]…x[m−3]` (= not-a-knot cubic spline), extrapolated with the end polynomials; with 3 points a quadratic,
    /// 2 points a line (degree `m − 1`, `interpgrade`). 1 point: constant (upstream raises).
    static func splineWeights(data x: [Double], query q: [Double]) -> [Double] {
        let m = x.count
        let nq = q.count
        var w = [Double](repeating: 0, count: nq * m)
        if m == 0 { return w }
        if m <= 3 {
            for (k, xq) in q.enumerated() {
                for j in 0..<m {
                    var l = 1.0
                    for i in 0..<m where i != j { l *= (xq - x[i]) / (x[j] - x[i]) }
                    w[k * m + j] = l
                }
            }
            return w
        }
        // Second derivatives M = S · y, from the not-a-knot system A · M = B · y.
        var a = [Double](repeating: 0, count: m * m)
        var b = [Double](repeating: 0, count: m * m)
        var h = [Double](repeating: 0, count: m - 1)
        for i in 0..<(m - 1) { h[i] = x[i + 1] - x[i] }
        // Row 0: third derivative continuous at x[1].
        a[0 * m + 0] = h[1]
        a[0 * m + 1] = -(h[0] + h[1])
        a[0 * m + 2] = h[0]
        for i in 1..<(m - 1) {
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
        guard let piv = PIVLinear.luFactor(&a, n: m) else {
            // Cannot happen for distinct sorted nodes; fall back to linear weights.
            return linearWeights(data: x, query: q)
        }
        // S columns: solve A · s_j = B[:, j].
        var s = [Double](repeating: 0, count: m * m) // s[i * m + j] = dM_i / dy_j
        var col = [Double](repeating: 0, count: m)
        for j in 0..<m {
            for i in 0..<m { col[i] = b[i * m + j] }
            PIVLinear.luSolve(a, piv, n: m, &col)
            for i in 0..<m { s[i * m + j] = col[i] }
        }
        for (k, xq) in q.enumerated() {
            var i = 0
            if xq >= x[m - 2] {
                i = m - 2
            } else {
                while i < m - 2 && xq >= x[i + 1] { i += 1 }
            }
            let hi = h[i]
            let t1 = x[i + 1] - xq
            let t0 = xq - x[i]
            let c0: Double = t1 * t1 * t1 / (6 * hi) - hi * t1 / 6   // coefficient of M_i
            let c1: Double = t0 * t0 * t0 / (6 * hi) - hi * t0 / 6   // coefficient of M_{i+1}
            for j in 0..<m {
                var v = c0 * s[i * m + j] + c1 * s[(i + 1) * m + j]
                if j == i { v += t1 / hi }
                if j == i + 1 { v += t0 / hi }
                w[k * m + j] = v
            }
        }
        return w
    }

    private static func linearWeights(data x: [Double], query q: [Double]) -> [Double] {
        let m = x.count
        var w = [Double](repeating: 0, count: q.count * m)
        for (k, xq) in q.enumerated() {
            var i = 0
            while i < m - 2 && xq >= x[i + 1] { i += 1 }
            let f = (xq - x[i]) / (x[i + 1] - x[i])
            w[k * m + i] = 1 - f
            w[k * m + i + 1] = f
        }
        return w
    }

    /// Tensor-product interpolation of a `rows × cols` table: `Wy · T · Wxᵀ`.
    static func interpolate(_ t: [Double], rows: Int, cols: Int, wy: [Double], newRows: Int,
                            wx: [Double], newCols: Int) -> [Double] {
        let tmp = matMul(wy, t, newRows, rows, cols)                       // newRows × cols
        return matMulTransposedRight(tmp, wx, newRows, cols, newCols)      // newRows × newCols
    }
}

/// Small dense LU with partial pivoting (row-major, in place).
enum PIVLinear {
    static func luFactor(_ a: inout [Double], n: Int) -> [Int]? {
        var piv = [Int](repeating: 0, count: n)
        for k in 0..<n {
            var p = k
            var best = abs(a[k * n + k])
            for i in (k + 1)..<max(n, k + 1) {
                let v = abs(a[i * n + k])
                if v > best {
                    best = v
                    p = i
                }
            }
            if !(best > 0) || !best.isFinite { return nil }
            piv[k] = p
            if p != k {
                for j in 0..<n {
                    let tmp = a[k * n + j]
                    a[k * n + j] = a[p * n + j]
                    a[p * n + j] = tmp
                }
            }
            let d = a[k * n + k]
            if k + 1 < n {
                for i in (k + 1)..<n {
                    let f = a[i * n + k] / d
                    a[i * n + k] = f
                    if f == 0 { continue }
                    for j in (k + 1)..<n { a[i * n + j] -= f * a[k * n + j] }
                }
            }
        }
        return piv
    }

    static func luSolve(_ a: [Double], _ piv: [Int], n: Int, _ b: inout [Double]) {
        for k in 0..<n {
            let p = piv[k]
            if p != k {
                let tmp = b[k]
                b[k] = b[p]
                b[p] = tmp
            }
        }
        for i in 0..<n {
            var s = b[i]
            for j in 0..<i { s -= a[i * n + j] * b[j] }
            b[i] = s
        }
        var i = n - 1
        while i >= 0 {
            var s = b[i]
            if i + 1 < n {
                for j in (i + 1)..<n { s -= a[i * n + j] * b[j] }
            }
            b[i] = s / a[i * n + i]
            i -= 1
        }
    }
}
