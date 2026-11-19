import Foundation

// RIVEM addition (not in desktop RIVeR): stage–discharge (rating) curve of a site from its gaugings.

/// One gauging of a site: water level (m, same datum as the bathymetry) and discharge (m³/s).
public struct RatingPoint: Equatable, Sendable {
    public var level: Double
    public var discharge: Double

    public init(level: Double, discharge: Double) {
        self.level = level
        self.discharge = discharge
    }
}

/// Q = a · (h − h₀)ᵇ, the usual power-law rating curve (h₀: level of zero flow).
public struct RatingCurve: Equatable, Sendable {
    public var a: Double
    public var b: Double
    public var h0: Double
    /// Coefficient of determination on Q.
    public var r2: Double
    /// RMS of the relative residuals (Q_fit − Q) / Q.
    public var relativeRMS: Double
    public var pointCount: Int

    public static let minimumPoints = 3

    public func discharge(at level: Double) -> Double {
        level > h0 ? a * pow(level - h0, b) : 0
    }

    /// Inverse: the level for a discharge.
    public func level(for discharge: Double) -> Double {
        discharge > 0 ? h0 + pow(discharge / a, 1 / b) : h0
    }

    /// Least squares in log space (relative errors weigh the same at low and high flows). For each candidate h₀
    /// below the lowest level, ln Q = ln a + b · ln(h − h₀) is a straight line; h₀ is searched on a grid down to
    /// three level ranges below the lowest gauging, then refined by golden-section search. Needs at least three
    /// gaugings with positive discharge and two distinct levels.
    public static func fit(_ points: [RatingPoint]) -> RatingCurve? {
        let p: [RatingPoint] = points.filter { $0.level.isFinite && $0.discharge.isFinite && $0.discharge > 0 }
        guard p.count >= minimumPoints else { return nil }
        let levels: [Double] = p.map(\.level)
        guard let hMin = levels.min(), let hMax = levels.max(), hMax - hMin > 1e-6 else { return nil }
        let span: Double = hMax - hMin
        let upper: Double = hMin - span * 1e-4
        let lower: Double = hMin - span * 3 - 0.5

        func line(_ h0: Double) -> (lnA: Double, b: Double, sse: Double)? {
            var sx: Double = 0, sy: Double = 0, sxx: Double = 0, sxy: Double = 0
            let n: Double = Double(p.count)
            var xs: [Double] = []
            var ys: [Double] = []
            for q in p {
                let d: Double = q.level - h0
                guard d > 0 else { return nil }
                let x: Double = log(d)
                let y: Double = log(q.discharge)
                xs.append(x)
                ys.append(y)
                sx += x; sy += y; sxx += x * x; sxy += x * y
            }
            let den: Double = n * sxx - sx * sx
            guard abs(den) > 1e-12 else { return nil }
            let b: Double = (n * sxy - sx * sy) / den
            let lnA: Double = (sy - b * sx) / n
            var sse: Double = 0
            for (x, y) in zip(xs, ys) {
                let r: Double = y - (lnA + b * x)
                sse += r * r
            }
            return (lnA, b, sse)
        }

        let steps: Int = 400
        var best: (h0: Double, sse: Double)? = nil
        for k in 0...steps {
            let h0: Double = lower + (upper - lower) * Double(k) / Double(steps)
            if let l = line(h0), l.b > 0, best == nil || l.sse < best!.sse { best = (h0, l.sse) }
        }
        guard var h0 = best?.h0 else { return nil }
        // Golden-section refinement around the best grid point.
        let cell: Double = (upper - lower) / Double(steps)
        var lo: Double = max(lower, h0 - cell)
        var hi: Double = min(upper, h0 + cell)
        let g: Double = (5.0.squareRoot() - 1) / 2
        func cost(_ h: Double) -> Double { line(h)?.sse ?? .infinity }
        for _ in 0..<60 {
            let c: Double = hi - g * (hi - lo)
            let d: Double = lo + g * (hi - lo)
            if cost(c) < cost(d) { hi = d } else { lo = c }
        }
        if cost((lo + hi) / 2) <= cost(h0) { h0 = (lo + hi) / 2 }
        guard let l = line(h0), l.b > 0 else { return nil }

        let a: Double = exp(l.lnA)
        var curve = RatingCurve(a: a, b: l.b, h0: h0, r2: .nan, relativeRMS: .nan, pointCount: p.count)
        let mean: Double = p.reduce(0) { $0 + $1.discharge } / Double(p.count)
        var ssRes: Double = 0, ssTot: Double = 0, rel: Double = 0
        for q in p {
            let f: Double = curve.discharge(at: q.level)
            ssRes += (q.discharge - f) * (q.discharge - f)
            ssTot += (q.discharge - mean) * (q.discharge - mean)
            rel += ((f - q.discharge) / q.discharge) * ((f - q.discharge) / q.discharge)
        }
        curve.r2 = ssTot > 0 ? 1 - ssRes / ssTot : .nan
        curve.relativeRMS = (rel / Double(p.count)).squareRoot()
        return curve
    }
}
