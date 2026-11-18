import Foundation

/// Small NumPy equivalents used by the section port, with NumPy's NaN semantics.
enum SectionMath {
    /// `numpy.interp(x, xp, fp)` for increasing `xp` (NumPy >= 1.17 rules: exact hits return `fp[j]`; a NaN result
    /// next to a NaN is retried from the right neighbour; left/right of the range clamp to the end values).
    static func interp(_ x: Double, _ xp: [Double], _ fp: [Double]) -> Double {
        let n = xp.count
        if n == 0 { return .nan }
        if x.isNaN { return x }
        if n == 1 { return fp[0] }
        if x < xp[0] { return fp[0] }
        if x > xp[n - 1] { return fp[n - 1] }
        if x == xp[n - 1] { return fp[n - 1] }
        // Largest j with xp[j] <= x (0...n-2).
        var lo = 0, hi = n - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if xp[mid] <= x { lo = mid } else { hi = mid }
        }
        let j = lo
        if xp[j] == x { return fp[j] }
        let dxp: Double = xp[j + 1] - xp[j]
        let slope: Double = (fp[j + 1] - fp[j]) / dxp
        var r: Double = slope * (x - xp[j]) + fp[j]
        if r.isNaN {
            r = slope * (x - xp[j + 1]) + fp[j + 1]
            if r.isNaN && fp[j] == fp[j + 1] { r = fp[j] }
        }
        return r
    }

    static func interp(_ xs: [Double], _ xp: [Double], _ fp: [Double]) -> [Double] {
        xs.map { interp($0, xp, fp) }
    }

    static func nansum(_ a: [Double]) -> Double {
        var s = 0.0
        for v in a where !v.isNaN { s += v }
        return s
    }

    /// NaN when there is no finite value (NumPy warns and returns NaN).
    static func nanmean(_ a: [Double]) -> Double {
        var s = 0.0
        var c = 0
        for v in a where !v.isNaN {
            s += v
            c += 1
        }
        return c > 0 ? s / Double(c) : .nan
    }

    static func mean(_ a: [Double]) -> Double {
        guard !a.isEmpty else { return .nan }
        var s = 0.0
        for v in a { s += v }
        return s / Double(a.count)
    }

    /// Population standard deviation (`np.std`, ddof 0); NaN if any value is NaN.
    static func std(_ a: [Double]) -> Double {
        guard !a.isEmpty else { return .nan }
        let m = mean(a)
        if m.isNaN { return .nan }
        var s = 0.0
        for v in a {
            let d = v - m
            s += d * d
        }
        return (s / Double(a.count)).squareRoot()
    }

    /// Population standard deviation over the non-NaN values (`np.nanstd`); NaN if there are none.
    static func nanstd(_ a: [Double]) -> Double {
        let f = a.filter { !$0.isNaN }
        return f.isEmpty ? .nan : std(f)
    }

    /// `np.percentile(a, q)` with the default linear method; NaN if any value is NaN.
    static func percentile(_ a: [Double], _ q: Double) -> Double {
        guard !a.isEmpty else { return .nan }
        if a.contains(where: { $0.isNaN }) { return .nan }
        let s = a.sorted()
        let h: Double = Double(s.count - 1) * q / 100.0
        let lo = Int(h.rounded(.down))
        let hi = min(lo + 1, s.count - 1)
        let frac: Double = h - Double(lo)
        let below: Double = s[lo]
        let above: Double = s[hi]
        return below + (above - below) * frac
    }

    /// Fills the entries flagged in `invalid` by linear interpolation over the index (NumPy:
    /// `a[inv] = np.interp(inv.nonzero(), (~inv).nonzero(), a[~inv])`). Unchanged when nothing is valid.
    static func fillByIndex(_ a: [Double], invalid: [Bool]) -> [Double] {
        var xp: [Double] = []
        var fp: [Double] = []
        for i in 0..<a.count where !invalid[i] {
            xp.append(Double(i))
            fp.append(a[i])
        }
        guard !xp.isEmpty else { return a }
        var out = a
        for i in 0..<a.count where invalid[i] {
            out[i] = interp(Double(i), xp, fp)
        }
        return out
    }
}
