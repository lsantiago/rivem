import Foundation

/// Region-of-interest mask for PIV (`mask.npy` / `final_mask.npy`): `height × width` bytes, row-major,
/// non-zero = analysed. Vectors whose position falls on 0 are set to NaN (as `piv_loop.py`).
public struct PIVMask: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var values: [UInt8]

    public init(width: Int, height: Int, values: [UInt8]) {
        precondition(width >= 1 && height >= 1 && values.count == width * height, "PIVMask size mismatch")
        self.width = width
        self.height = height
        self.values = values
    }
}

/// Displacement field of one frame pair (pixels per pair), on a `rows × columns` grid flattened row-major
/// (x fastest), with the same conventions as `piv_results.json`.
public struct PIVPairField: Equatable, Sendable {
    public var rows: Int
    public var columns: Int
    /// Grid positions in frame pixels (see `PIVFFTMulti` for the exact convention).
    public var x: [Double]
    public var y: [Double]
    public var u: [Double]
    public var v: [Double]
    /// Image-gradient indicator per window (upstream's `gradient_sum_result`).
    public var gradient: [Double]

    public init(rows: Int, columns: Int, x: [Double], y: [Double], u: [Double], v: [Double], gradient: [Double]) {
        self.rows = rows
        self.columns = columns
        self.x = x
        self.y = y
        self.u = u
        self.v = v
        self.gradient = gradient
    }

    public static func == (a: PIVPairField, b: PIVPairField) -> Bool {
        func same(_ p: [Double], _ q: [Double]) -> Bool {
            guard p.count == q.count else { return false }
            for i in 0..<p.count where !(p[i] == q[i] || (p[i].isNaN && q[i].isNaN)) { return false }
            return true
        }
        return a.rows == b.rows && a.columns == b.columns && same(a.x, b.x) && same(a.y, b.y)
            && same(a.u, b.u) && same(a.v, b.v) && same(a.gradient, b.gradient)
    }
}

/// Window grid of one pass (`calculate_bounds` in `piv_fftmulti.py`), in upstream's 1-based coordinates of the
/// padded ROI.
struct PIVGrid: Equatable {
    let ia: Int
    let step: Int
    let half: Int
    let miniy: Int
    let minix: Int
    let maxiy: Int
    let maxix: Int
    let nx: Int
    let ny: Int

    init?(width: Int, height: Int, ia: Int, step: Int) {
        guard ia >= 2, step >= 1, width >= 1, height >= 1 else { return nil }
        let half = (ia + 1) / 2
        var miniy = 1 + half
        var minix = 1 + half
        var maxiy = step * (height / step) - (ia - 1) + half
        var maxix = step * (width / step) - (ia - 1) + half
        let nx = Int((Double(maxix - minix) / Double(step) + 1).rounded(.down))
        let ny = Int((Double(maxiy - miniy) / Double(step) + 1).rounded(.down))
        guard nx >= 1, ny >= 1 else { return nil }
        let luy = height - maxiy
        let lux = width - maxix
        let shifty = max(PIVGrid.rvrRound(Double(luy - miniy) / 2), 0)
        let shiftx = max(PIVGrid.rvrRound(Double(lux - minix) / 2), 0)
        miniy += shifty
        maxiy += shifty
        minix += shiftx
        maxix += shiftx
        self.ia = ia
        self.step = step
        self.half = half
        self.miniy = miniy
        self.minix = minix
        self.maxiy = maxiy
        self.maxix = maxix
        self.nx = nx
        self.ny = ny
    }

    var count: Int { nx * ny }

    /// Window top-left in 0-based ROI coordinates (outside the ROI = padding).
    func top(_ iy: Int) -> Int { miniy - 1 - half + iy * step }
    func left(_ ix: Int) -> Int { minix - 1 - half + ix * step }

    /// `xtable` / `ytable` of `process_result_conv` (used as spline nodes between passes).
    func xNode(_ ix: Int) -> Double { Double(minix + ix * step + half) }
    func yNode(_ iy: Int) -> Double { Double(miniy + iy * step + half) }

    /// `rvr_round`: halves away from zero for positive values (floor otherwise).
    static func rvrRound(_ x: Double) -> Int {
        let i = Double(Int(x))
        if x - i >= 0.5 { return Int(x.rounded(.up)) }
        return Int(x.rounded(.down))
    }
}

/// Port of `piv_fftmulti` (PIVlab 2.6 `piv_FFTmulti.m` via RIVeR 3.8.3) plus the masking of `piv_loop`.
///
/// Inputs are already preprocessed frames (`PIVPreprocessing`). The algorithm, step by step as upstream:
/// 1. Crop the bounding box `[x, y, w, h]` (`rvr_round` of x/y, `ceil` of w/h), pad with the minimum of image 1.
/// 2. Pass 1: windows `ia1` every `step` px (`calculate_bounds`, centred), FFT cross-correlation, attenuation of the
///    zero-lag 3×3 block (`mask_auto`), peak search, 3-point Gaussian sub-pixel fit.
/// 3. Std filter, normalised median test, RBF in-painting of NaNs, `smoothn(s = 0.0307)`.
/// 4. Pass 2 (if `multipass`): window `round(ia2/2)·2`, step = half of it; predictor = cubic spline of the pass-1
///    field on the new grid; image 2 is warped with the predictor (`cv2.resize` + `cv2.remap` semantics, including
///    remap's 1/32-px coordinate quantisation); correlation limited to a radius-4 disk around zero lag; the residual
///    is added to the predictor; filters; gradient; nearest-neighbour in-painting; `smoothn`.
/// 5. Positions `x = minix + i·step + bbox.x`, `y = miniy + j·step + bbox.y` (upstream's convention: 1-based padded
///    coordinates, i.e. not the geometric window centre; kept for compatibility). Vectors whose `(Int(x), Int(y))`
///    falls outside `mask` become NaN.
///
/// Deviations from RIVeR 3.8.3 (PLAN.md "Errores de RIVeR corregidos"):
/// - #3 `multipass == false` is honoured: the result is pass 1 (filters, nearest in-painting, `smoothn`) on the
///   pass-1 grid, with the gradient computed on the undeformed pass-1 windows. Upstream always ran pass 2.
/// - #4 The sub-pixel fit uses the Float correlation normalised to [0, 1] instead of its `uint8` quantisation
///   (which caused peak-locking and `log(0)`). If a fit value is ≤ 0, or the Gaussian estimate leaves (−1, 1),
///   the parabolic 3-point fit (clamped to ±1) is used for that axis.
///   A peak on the border row/column of the correlation plane gives NaN (upstream read the neighbouring column /
///   window through flat indexing). A constant correlation plane gives NaN (upstream's peak list then lost
///   alignment with the windows).
/// - Peak loss (RIVeR defect not in PLAN.md's table): in pass 2, upstream's `normalize_to_uint8` runs in float64.
///   With weak correlations (max − min below about 1e6, typical after background subtraction) the window maximum
///   maps to 254, so no peak is found. `process_result_conv` then assigns the remaining peaks in order, which shifts
///   the vectors of every following window by one slot and leaves residual 0 in the last ones. On the canuelas
///   golden crop with `remove_background`, 200 of 437 windows per pair are hit. RIVEM takes the maximum of the Float
///   plane directly, so every window keeps its own peak.
/// - Odd or < 8 px windows are rejected (`PIVParameters.validate`); desktop only offers 64–512.
/// - With a single row/column in pass 1 the predictor is constant along it (upstream's spline raises).
/// - Upstream quirk kept for file parity: `gradient[0]` is always NaN (it indexed with a `uint8` zero array).
public enum PIVFFTMulti {
    static let smoothing: Double = 0.0307

    public static func run(image1: GrayImage, image2: GrayImage, parameters p: PIVParameters,
                           bbox: [Double]? = nil, mask: PIVMask? = nil) throws -> PIVPairField {
        try p.validate()
        guard image1.width == image2.width, image1.height == image2.height else { throw PIVError.imageSizeMismatch }
        let fullW = image1.width, fullH = image1.height
        var box: [Double] = [0, 0, Double(fullW), Double(fullH)]
        if let b = bbox, b.count == 4, b.allSatisfy({ $0.isFinite }) { box = b }

        // process_roi
        let xroi = min(max(PIVGrid.rvrRound(box[0]), 0), fullW)
        let yroi = min(max(PIVGrid.rvrRound(box[1]), 0), fullH)
        let wroi = Int(box[2].rounded(.up))
        let hroi = Int(box[3].rounded(.up))
        let rw = max(0, min(fullW, xroi + wroi) - xroi)
        let rh = max(0, min(fullH, yroi + hroi) - yroi)
        guard rw > 0, rh > 0 else { throw PIVError.regionTooSmall }
        let roi1 = crop(image1, x: xroi, y: yroi, w: rw, h: rh)
        let roi2 = crop(image2, x: xroi, y: yroi, w: rw, h: rh)
        var fill: Float = .infinity
        var maxValue: Float = -.infinity
        for v in roi1 {
            if v < fill { fill = v }
            if v > maxValue { maxValue = v }
        }

        let ia1 = p.interrogationArea1
        guard let grid1 = PIVGrid(width: rw, height: rh, ia: ia1, step: p.effectiveStep) else {
            throw PIVError.regionTooSmall
        }

        // Pass 1
        let pass1: PassResult = roi1.withUnsafeBufferPointer { (b1: UnsafeBufferPointer<Float>) -> PassResult in
            roi2.withUnsafeBufferPointer { (b2: UnsafeBufferPointer<Float>) -> PassResult in
                correlatePass(grid: grid1,
                              plane1: PIVPlane(data: b1.baseAddress!, width: rw, height: rh,
                                               top: grid1.top(0), left: grid1.left(0)),
                              plane2: PIVPlane(data: b2.baseAddress!, width: rw, height: rh,
                                               top: grid1.top(0), left: grid1.left(0)),
                              fill: fill, filter: p.maskAuto ? .zeroLag : .off,
                              gradientMax: p.multipass ? nil : maxValue)
            }
        }
        var u1 = pass1.u
        var v1 = pass1.v
        applyFilters(&u1, &v1, rows: grid1.ny, cols: grid1.nx, p)

        var finalGrid = grid1
        var u: [Double]
        var v: [Double]
        var gradient: [Double]
        if !p.multipass {
            PIVFieldOps.nearestInpaint(&u1, rows: grid1.ny, cols: grid1.nx)
            PIVFieldOps.nearestInpaint(&v1, rows: grid1.ny, cols: grid1.nx)
            u = PIVFieldOps.smoothn(u1, rows: grid1.ny, cols: grid1.nx, s: smoothing)
            v = PIVFieldOps.smoothn(v1, rows: grid1.ny, cols: grid1.nx, s: smoothing)
            gradient = pass1.gradient
        } else {
            let ia2 = p.effectiveInterrogationArea2
            let half2 = (ia2 + 1) / 2
            guard let grid2 = PIVGrid(width: rw, height: rh, ia: ia2, step: half2) else {
                throw PIVError.regionTooSmall
            }
            finalGrid = grid2
            let n2 = grid2.count
            if u1.allSatisfy({ $0.isNaN }) || v1.allSatisfy({ $0.isNaN }) {
                u = [Double](repeating: .nan, count: n2)
                v = u
                gradient = u
            } else {
                PIVFieldOps.inpaintRBF(&u1, rows: grid1.ny, cols: grid1.nx)
                PIVFieldOps.inpaintRBF(&v1, rows: grid1.ny, cols: grid1.nx)
                let us = PIVFieldOps.smoothn(u1, rows: grid1.ny, cols: grid1.nx, s: smoothing)
                let vs = PIVFieldOps.smoothn(v1, rows: grid1.ny, cols: grid1.nx, s: smoothing)

                // interpolate_tables: spline from the pass-1 nodes to the pass-2 nodes.
                var yData = [Double](repeating: 0, count: grid1.ny)
                for i in 0..<grid1.ny { yData[i] = grid1.yNode(i) }
                var xData = [Double](repeating: 0, count: grid1.nx)
                for i in 0..<grid1.nx { xData[i] = grid1.xNode(i) }
                var yQuery = [Double](repeating: 0, count: grid2.ny)
                for i in 0..<grid2.ny { yQuery[i] = Double(grid2.miniy + i * grid2.step) + Double(ia2) / 2 }
                var xQuery = [Double](repeating: 0, count: grid2.nx)
                for i in 0..<grid2.nx { xQuery[i] = Double(grid2.minix + i * grid2.step) + Double(ia2) / 2 }
                let wy = PIVFieldOps.splineWeights(data: yData, query: yQuery)
                let wx = PIVFieldOps.splineWeights(data: xData, query: xQuery)
                let up = PIVFieldOps.interpolate(us, rows: grid1.ny, cols: grid1.nx, wy: wy, newRows: grid2.ny,
                                                 wx: wx, newCols: grid2.nx)
                let vp = PIVFieldOps.interpolate(vs, rows: grid1.ny, cols: grid1.nx, wy: wy, newRows: grid2.ny,
                                                 wx: wx, newCols: grid2.nx)

                let deformed: Deformed = roi2.withUnsafeBufferPointer { (b2: UnsafeBufferPointer<Float>) -> Deformed in
                    deform(roi: b2.baseAddress!, width: rw, height: rh, fill: fill, pad: half2,
                           up: up, vp: vp, grid: grid2)
                }
                let pass2: PassResult = roi1.withUnsafeBufferPointer { (b1: UnsafeBufferPointer<Float>) -> PassResult in
                    deformed.pixels.withUnsafeBufferPointer { (bd: UnsafeBufferPointer<Float>) -> PassResult in
                        correlatePass(grid: grid2,
                                      plane1: PIVPlane(data: b1.baseAddress!, width: rw, height: rh,
                                                       top: grid2.top(0), left: grid2.left(0)),
                                      plane2: PIVPlane(data: bd.baseAddress!, width: deformed.width,
                                                       height: deformed.height, top: 0, left: 0),
                                      fill: fill, filter: p.maskAuto ? .disk : .off, gradientMax: maxValue)
                    }
                }
                var u2 = [Double](repeating: 0, count: n2)
                var v2 = [Double](repeating: 0, count: n2)
                for i in 0..<n2 {
                    u2[i] = up[i] + pass2.u[i]
                    v2[i] = vp[i] + pass2.v[i]
                }
                applyFilters(&u2, &v2, rows: grid2.ny, cols: grid2.nx, p)
                PIVFieldOps.nearestInpaint(&u2, rows: grid2.ny, cols: grid2.nx)
                PIVFieldOps.nearestInpaint(&v2, rows: grid2.ny, cols: grid2.nx)
                u = PIVFieldOps.smoothn(u2, rows: grid2.ny, cols: grid2.nx, s: smoothing)
                v = PIVFieldOps.smoothn(v2, rows: grid2.ny, cols: grid2.nx, s: smoothing)
                gradient = pass2.gradient
            }
        }
        if !gradient.isEmpty { gradient[0] = .nan }

        // Positions (xtable + bbox[0] - half_ia) and piv_loop's mask.
        let g = finalGrid
        var xs = [Double](repeating: 0, count: g.count)
        var ys = [Double](repeating: 0, count: g.count)
        for iy in 0..<g.ny {
            for ix in 0..<g.nx {
                let k = iy * g.nx + ix
                xs[k] = Double(g.minix + ix * g.step) + box[0]
                ys[k] = Double(g.miniy + iy * g.step) + box[1]
            }
        }
        if let m = mask {
            for k in 0..<g.count {
                let xi = min(max(Int(xs[k]), 0), m.width - 1)
                let yi = min(max(Int(ys[k]), 0), m.height - 1)
                if m.values[yi * m.width + xi] == 0 {
                    u[k] = .nan
                    v[k] = .nan
                }
            }
        }
        return PIVPairField(rows: g.ny, columns: g.nx, x: xs, y: ys, u: u, v: v, gradient: gradient)
    }

    /// Output grid (rows, columns, x, y) for frames of this size, without processing (used to shape all-NaN
    /// results of failed pairs).
    public static func outputGrid(width: Int, height: Int, parameters p: PIVParameters,
                                  bbox: [Double]? = nil) throws -> (rows: Int, columns: Int, x: [Double], y: [Double]) {
        try p.validate()
        var box: [Double] = [0, 0, Double(width), Double(height)]
        if let b = bbox, b.count == 4, b.allSatisfy({ $0.isFinite }) { box = b }
        let xroi = min(max(PIVGrid.rvrRound(box[0]), 0), width)
        let yroi = min(max(PIVGrid.rvrRound(box[1]), 0), height)
        let rw = max(0, min(width, xroi + Int(box[2].rounded(.up))) - xroi)
        let rh = max(0, min(height, yroi + Int(box[3].rounded(.up))) - yroi)
        let ia = p.multipass ? p.effectiveInterrogationArea2 : p.interrogationArea1
        let step = p.multipass ? (ia + 1) / 2 : p.effectiveStep
        guard rw > 0, rh > 0,
              PIVGrid(width: rw, height: rh, ia: p.interrogationArea1, step: p.effectiveStep) != nil,
              let g = PIVGrid(width: rw, height: rh, ia: ia, step: step) else { throw PIVError.regionTooSmall }
        var xs = [Double](repeating: 0, count: g.count)
        var ys = [Double](repeating: 0, count: g.count)
        for iy in 0..<g.ny {
            for ix in 0..<g.nx {
                xs[iy * g.nx + ix] = Double(g.minix + ix * g.step) + box[0]
                ys[iy * g.nx + ix] = Double(g.miniy + iy * g.step) + box[1]
            }
        }
        return (g.ny, g.nx, xs, ys)
    }

    // MARK: - Internals

    private static func applyFilters(_ u: inout [Double], _ v: inout [Double], rows: Int, cols: Int, _ p: PIVParameters) {
        if p.stdFiltering {
            PIVFieldOps.filterStd(&u, threshold: p.stdThreshold)
            PIVFieldOps.filterStd(&v, threshold: p.stdThreshold)
        }
        if p.medianTestFiltering {
            PIVFieldOps.filterMedian(u: &u, v: &v, rows: rows, cols: cols,
                                     epsilon: p.medianTestEpsilon, threshold: p.medianTestThreshold)
        }
    }

    private static func crop(_ img: GrayImage, x: Int, y: Int, w: Int, h: Int) -> [Float] {
        var out = [Float](repeating: 0, count: w * h)
        img.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                for r in 0..<h {
                    let s = src.baseAddress! + (y + r) * img.width + x
                    (dst.baseAddress! + r * w).update(from: s, count: w)
                }
            }
        }
        return out
    }

    /// An image plane and the top-left of window (0, 0) in it; window (iy, ix) starts `step` px further per index.
    struct PIVPlane {
        let data: UnsafePointer<Float>
        let width: Int
        let height: Int
        let top: Int
        let left: Int
    }

    enum CorrelationFilter { case off, zeroLag, disk }

    struct PassResult {
        var u: [Double]
        var v: [Double]
        var gradient: [Double]
    }

    /// Correlates every window of `grid` and returns the (sub-pixel) peak displacement per window, plus the gradient
    /// indicator when `gradientMax` is given (`calculate_gradient`: Σ |∂x| + |∂y| of `(w1 + w2) / max(image 1)`).
    static func correlatePass(grid: PIVGrid, plane1: PIVPlane, plane2: PIVPlane, fill: Float,
                              filter: CorrelationFilter, gradientMax: Float?) -> PassResult {
        let n = grid.ia
        let nn = n * n
        let count = grid.count
        var u = [Double](repeating: .nan, count: count)
        var v = [Double](repeating: .nan, count: count)
        var gradient = [Double](repeating: .nan, count: gradientMax == nil ? 0 : count)
        let correlator = PIVCorrelator(size: n)
        let buf = UnsafeMutablePointer<Float>.allocate(capacity: 6 * nn)
        defer { buf.deallocate() }
        let a1 = buf, b1 = buf + nn, a2 = buf + 2 * nn, b2 = buf + 3 * nn, o1 = buf + 4 * nn, o2 = buf + 5 * nn
        let filterTable = makeFilter(filter, n: n)
        var k = 0
        while k < count {
            let hasSecond = k + 1 < count
            extract(plane1, window: k, grid: grid, n: n, fill: fill, into: a1)
            extract(plane2, window: k, grid: grid, n: n, fill: fill, into: b1)
            if hasSecond {
                extract(plane1, window: k + 1, grid: grid, n: n, fill: fill, into: a2)
                extract(plane2, window: k + 1, grid: grid, n: n, fill: fill, into: b2)
            }
            correlator.correlate(a1: UnsafePointer(a1), b1: UnsafePointer(b1), out1: o1,
                                 a2: hasSecond ? UnsafePointer(a2) : nil, b2: hasSecond ? UnsafePointer(b2) : nil,
                                 out2: hasSecond ? o2 : nil)
            let d1 = peak(o1, n: n, filter: filterTable)
            u[k] = d1.0
            v[k] = d1.1
            if let gm = gradientMax { gradient[k] = gradientSum(a1, b1, n: n, maxValue: gm) }
            if hasSecond {
                let d2 = peak(o2, n: n, filter: filterTable)
                u[k + 1] = d2.0
                v[k + 1] = d2.1
                if let gm = gradientMax { gradient[k + 1] = gradientSum(a2, b2, n: n, maxValue: gm) }
            }
            k += 2
        }
        return PassResult(u: u, v: v, gradient: gradient)
    }

    private static func extract(_ plane: PIVPlane, window k: Int, grid: PIVGrid, n: Int, fill: Float,
                                into dst: UnsafeMutablePointer<Float>) {
        let iy = k / grid.nx
        let ix = k % grid.nx
        let top = plane.top + iy * grid.step
        let left = plane.left + ix * grid.step
        let inside = left >= 0 && left + n <= plane.width
        for i in 0..<n {
            let r = top + i
            let row = dst + i * n
            if r < 0 || r >= plane.height {
                row.update(repeating: fill, count: n)
                continue
            }
            let src = plane.data + r * plane.width
            if inside {
                row.update(from: src + left, count: n)
            } else {
                for j in 0..<n {
                    let c = left + j
                    row[j] = (c < 0 || c >= plane.width) ? fill : src[c]
                }
            }
        }
    }

    /// Radius-4 disk of `limit_peak_search_area` (MATLAB `fspecial('disk', 4)` normalised to 1 at the centre).
    static let disk9: [Double] = [
        0, 0, 0.0477750257157819, 0.361469237632242, 0.489558781987489, 0.361469237632242, 0.0477750257157819, 0, 0,
        0, 0.208018589368669, 0.900152358153484, 1, 1, 1, 0.900152358153484, 0.208018589368669, 0,
        0.0477750257157819, 0.900152358153484, 1, 1, 1, 1, 1, 0.900152358153484, 0.0477750257157819,
        0.361469237632242, 1, 1, 1, 1, 1, 1, 1, 0.361469237632242,
        0.489558781987489, 1, 1, 1, 1, 1, 1, 1, 0.489558781987489,
        0.361469237632242, 1, 1, 1, 1, 1, 1, 1, 0.361469237632242,
        0.0477750257157819, 0.900152358153484, 1, 1, 1, 1, 1, 0.900152358153484, 0.0477750257157819,
        0, 0.208018589368669, 0.900152358153484, 1, 1, 1, 0.900152358153484, 0.208018589368669, 0,
        0, 0, 0.0477750257157819, 0.361469237632242, 0.489558781987489, 0.361469237632242, 0.0477750257157819, 0, 0,
    ]

    /// Multiplicative filter of the correlation plane: nil = none, else n×n factors.
    /// zeroLag (`apply_gaussian_filter`): the 3×3 block around zero lag times `1 − exp(−d²/4.5)` (Gaussian σ = 1.5
    /// normalised to 1 at the centre). disk (`limit_peak_search_area`): `disk9` around zero lag, 0 elsewhere.
    private static func makeFilter(_ f: CorrelationFilter, n: Int) -> [Float]? {
        let c = n / 2
        switch f {
        case .off:
            return nil
        case .zeroLag:
            var t = [Float](repeating: 1, count: n * n)
            for dy in -1...1 {
                for dx in -1...1 {
                    let d2 = Double(dx * dx + dy * dy)
                    t[(c + dy) * n + (c + dx)] = Float(1.0 - exp(-d2 / 4.5))
                }
            }
            return t
        case .disk:
            var t = [Float](repeating: 0, count: n * n)
            for i in 0..<9 {
                for j in 0..<9 {
                    let r = c - 4 + i, q = c - 4 + j
                    if r >= 0 && r < n && q >= 0 && q < n { t[r * n + q] = Float(disk9[i * 9 + j]) }
                }
            }
            return t
        }
    }

    /// Peak search and 3-point sub-pixel fit on one (fftshifted) correlation plane. Returns (u, v) in px, NaN when
    /// there is no usable peak. Ties keep the first maximum in column-major order (upstream's order).
    static func peak(_ corr: UnsafeMutablePointer<Float>, n: Int, filter: [Float]?) -> (Double, Double) {
        let nn = n * n
        if let f = filter {
            for i in 0..<nn { corr[i] *= f[i] }
        }
        var minV: Float = .infinity
        var maxV: Float = -.infinity
        var br = -1, bc = -1
        for r in 0..<n {
            for c in 0..<n {
                let x = corr[r * n + c]
                if x.isNaN { continue }
                if x < minV { minV = x }
                if x > maxV || (x == maxV && (c < bc || (c == bc && r < br))) {
                    maxV = x
                    br = r
                    bc = c
                }
            }
        }
        guard br >= 0, maxV > minV else { return (.nan, .nan) }
        guard br > 0, br < n - 1, bc > 0, bc < n - 1 else { return (.nan, .nan) }
        let range = Double(maxV) - Double(minV)
        func norm(_ x: Float) -> Double { (Double(x) - Double(minV)) / range }
        let f0 = norm(corr[br * n + bc])
        let dy = subpixel(norm(corr[(br - 1) * n + bc]), f0, norm(corr[(br + 1) * n + bc]))
        let dx = subpixel(norm(corr[br * n + bc - 1]), f0, norm(corr[br * n + bc + 1]))
        let half = n / 2
        return (Double(bc - half) + dx, Double(br - half) + dy)
    }

    /// Gaussian 3-point estimator `(ln f1 − ln f2) / (2 ln f1 − 4 ln f0 + 2 ln f2)`; parabolic when a value is ≤ 0.
    @inline(__always)
    static func subpixel(_ f1: Double, _ f0: Double, _ f2: Double) -> Double {
        if f1 > 0 && f0 > 0 && f2 > 0 {
            let l1 = log(f1), l0 = log(f0), l2 = log(f2)
            let den = 2 * l1 - 4 * l0 + 2 * l2
            if den != 0 && den.isFinite {
                let d = (l1 - l2) / den
                if abs(d) <= 1 { return d }
            }
        }
        let den = 2 * f1 - 4 * f0 + 2 * f2
        if den == 0 || !den.isFinite { return 0 }
        let d = (f1 - f2) / den
        return min(max(d, -1), 1)
    }

    /// `calculate_gradient` for one window.
    static func gradientSum(_ a: UnsafeMutablePointer<Float>, _ b: UnsafeMutablePointer<Float>, n: Int,
                            maxValue: Float) -> Double {
        if n < 2 { return 0 }
        var s = 0.0
        for r in 0..<n {
            for c in 0..<n {
                let k = r * n + c
                let here: Float = (a[k] + b[k]) / maxValue
                if c < n - 1 {
                    let right: Float = (a[k + 1] + b[k + 1]) / maxValue
                    let d = Double(abs(right - here))
                    s += d
                    if c == n - 2 { s += d } // np.pad(..., 'edge') repeats the last difference
                }
                if r < n - 1 {
                    let below: Float = (a[k + n] + b[k + n]) / maxValue
                    let d = Double(abs(below - here))
                    s += d
                    if r == n - 2 { s += d }
                }
            }
        }
        return s
    }

    struct Deformed {
        var pixels: [Float]
        var width: Int
        var height: Int
    }

    /// `deform_window`: warps image 2 (padded by `pad` with `fill`) with the predictor field. The field (edge-padded
    /// by one node) is upsampled to the pixel grid with `cv2.resize(INTER_LINEAR)` semantics (stretching the node
    /// array over the whole span, not node-aligned, as upstream), then `cv2.remap(INTER_LINEAR, BORDER_REPLICATE)`
    /// samples `(x + U − 1, y + V − 1)`, with remap's fixed-point coordinates (1/32 px) and float weights.
    static func deform(roi: UnsafePointer<Float>, width w: Int, height h: Int, fill: Float, pad: Int,
                       up: [Double], vp: [Double], grid: PIVGrid) -> Deformed {
        let nx = grid.nx, ny = grid.ny
        let ws = nx + 2, hs = ny + 2
        // Edge-padded predictor in Float (U.astype(np.float32)).
        var ue = [Float](repeating: 0, count: ws * hs)
        var ve = [Float](repeating: 0, count: ws * hs)
        for r in 0..<hs {
            let sr = min(max(r - 1, 0), ny - 1)
            for c in 0..<ws {
                let sc = min(max(c - 1, 0), nx - 1)
                ue[r * ws + c] = Float(up[sr * nx + sc])
                ve[r * ws + c] = Float(vp[sr * nx + sc])
            }
        }
        // Pixel span: first node − step … last node + step (exclusive).
        let firstX = grid.minix + grid.half
        let firstY = grid.miniy + grid.half
        let x0 = firstX - grid.step
        let x1 = firstX + (nx - 1) * grid.step + grid.step
        let y0 = firstY - grid.step
        let y1 = firstY + (ny - 1) * grid.step + grid.step
        let wd = max(x1 - x0, 1)
        let hd = max(y1 - y0, 1)

        // cv2.resize INTER_LINEAR coefficients.
        func coefficients(dst: Int, src: Int) -> (idx: [Int], idx1: [Int], frac: [Float]) {
            var idx = [Int](repeating: 0, count: dst)
            var idx1 = [Int](repeating: 0, count: dst)
            var frac = [Float](repeating: 0, count: dst)
            let inv: Double = Double(dst) / Double(src)
            let scale: Double = 1.0 / inv
            for d in 0..<dst {
                var f = Float((Double(d) + 0.5) * scale - 0.5)
                var s = Int(f.rounded(.down))
                f -= Float(s)
                if s < 0 {
                    s = 0
                    f = 0
                }
                if s >= src - 1 {
                    s = src - 1
                    f = 0
                }
                idx[d] = s
                idx1[d] = min(s + 1, src - 1)
                frac[d] = f
            }
            return (idx, idx1, frac)
        }
        let cx = coefficients(dst: wd, src: ws)
        let cy = coefficients(dst: hd, src: hs)
        // Horizontal pass for every source row.
        var hu = [Float](repeating: 0, count: hs * wd)
        var hv = [Float](repeating: 0, count: hs * wd)
        for r in 0..<hs {
            for c in 0..<wd {
                let a0: Float = 1 - cx.frac[c]
                let a1: Float = cx.frac[c]
                hu[r * wd + c] = ue[r * ws + cx.idx[c]] * a0 + ue[r * ws + cx.idx1[c]] * a1
                hv[r * wd + c] = ve[r * ws + cx.idx[c]] * a0 + ve[r * ws + cx.idx1[c]] * a1
            }
        }

        let pw = w + 2 * pad
        let ph = h + 2 * pad
        var out = [Float](repeating: 0, count: wd * hd)
        out.withUnsafeMutableBufferPointer { ob in
            hu.withUnsafeBufferPointer { hub in
                hv.withUnsafeBufferPointer { hvb in
                    for r in 0..<hd {
                        let b0: Float = 1 - cy.frac[r]
                        let b1: Float = cy.frac[r]
                        let rowA = cy.idx[r] * wd
                        let rowB = cy.idx1[r] * wd
                        let yy = Float(y0 + r)
                        for c in 0..<wd {
                            let uf: Float = hub[rowA + c] * b0 + hub[rowB + c] * b1
                            let vf: Float = hvb[rowA + c] * b0 + hvb[rowB + c] * b1
                            let mx: Float = (Float(x0 + c) + uf) - 1
                            let my: Float = (yy + vf) - 1
                            ob[r * wd + c] = remapSample(roi, w: w, h: h, pad: pad, pw: pw, ph: ph,
                                                         fill: fill, mx: mx, my: my)
                        }
                    }
                }
            }
        }
        return Deformed(pixels: out, width: wd, height: hd)
    }

    @inline(__always)
    private static func remapSample(_ roi: UnsafePointer<Float>, w: Int, h: Int, pad: Int, pw: Int, ph: Int,
                                    fill: Float, mx: Float, my: Float) -> Float {
        guard mx.isFinite, my.isFinite, abs(mx) < 1.0e7, abs(my) < 1.0e7 else { return fill }
        let xq = Int((mx * 32).rounded(.toNearestOrEven))
        let yq = Int((my * 32).rounded(.toNearestOrEven))
        let sx = xq >> 5
        let sy = yq >> 5
        let fx: Float = Float(xq & 31) * (1.0 / 32.0)
        let fy: Float = Float(yq & 31) * (1.0 / 32.0)
        let w00: Float = (1 - fy) * (1 - fx)
        let w01: Float = (1 - fy) * fx
        let w10: Float = fy * (1 - fx)
        let w11: Float = fy * fx
        let v00 = padded(roi, w: w, h: h, pad: pad, pw: pw, ph: ph, fill: fill, x: sx, y: sy)
        let v01 = padded(roi, w: w, h: h, pad: pad, pw: pw, ph: ph, fill: fill, x: sx + 1, y: sy)
        let v10 = padded(roi, w: w, h: h, pad: pad, pw: pw, ph: ph, fill: fill, x: sx, y: sy + 1)
        let v11 = padded(roi, w: w, h: h, pad: pad, pw: pw, ph: ph, fill: fill, x: sx + 1, y: sy + 1)
        let s0: Float = v00 * w00 + v01 * w01
        let s1: Float = s0 + v10 * w10
        return s1 + v11 * w11
    }

    /// Pixel of the padded ROI with BORDER_REPLICATE outside the padded image.
    @inline(__always)
    private static func padded(_ roi: UnsafePointer<Float>, w: Int, h: Int, pad: Int, pw: Int, ph: Int,
                               fill: Float, x: Int, y: Int) -> Float {
        let cx = min(max(x, 0), pw - 1) - pad
        let cy = min(max(y, 0), ph - 1) - pad
        if cx < 0 || cy < 0 || cx >= w || cy >= h { return fill }
        return roi[cy * w + cx]
    }
}
