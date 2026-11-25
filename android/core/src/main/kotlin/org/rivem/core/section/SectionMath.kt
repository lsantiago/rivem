package org.rivem.core.section

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Small NumPy equivalents used by the section port, with NumPy's NaN semantics (Swift `SectionMath`). */
internal object SectionMath {
    /**
     * `numpy.interp(x, xp, fp)` for increasing `xp` (NumPy >= 1.17 rules: exact hits return `fp[j]`; a NaN result
     * next to a NaN is retried from the right neighbour; left/right of the range clamp to the end values).
     */
    fun interp(x: Double, xp: List<Double>, fp: List<Double>): Double {
        val n = xp.size
        if (n == 0) return Double.NaN
        if (x.isNaN()) return x
        if (n == 1) return fp[0]
        if (x < xp[0]) return fp[0]
        if (x > xp[n - 1]) return fp[n - 1]
        if (x == xp[n - 1]) return fp[n - 1]
        // Largest j with xp[j] <= x (0...n-2).
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val j = lo
        if (xp[j] == x) return fp[j]
        val dxp: Double = xp[j + 1] - xp[j]
        val slope: Double = (fp[j + 1] - fp[j]) / dxp
        var r: Double = slope * (x - xp[j]) + fp[j]
        if (r.isNaN()) {
            r = slope * (x - xp[j + 1]) + fp[j + 1]
            if (r.isNaN() && fp[j] == fp[j + 1]) r = fp[j]
        }
        return r
    }

    fun interp(xs: List<Double>, xp: List<Double>, fp: List<Double>): List<Double> = xs.map { interp(it, xp, fp) }

    fun nansum(a: List<Double>): Double {
        var s = 0.0
        for (v in a) if (!v.isNaN()) s += v
        return s
    }

    /** NaN when there is no finite value (NumPy warns and returns NaN). */
    fun nanmean(a: List<Double>): Double {
        var s = 0.0
        var c = 0
        for (v in a) {
            if (v.isNaN()) continue
            s += v
            c += 1
        }
        return if (c > 0) s / c.toDouble() else Double.NaN
    }

    fun mean(a: List<Double>): Double {
        if (a.isEmpty()) return Double.NaN
        var s = 0.0
        for (v in a) s += v
        return s / a.size.toDouble()
    }

    /** Population standard deviation (`np.std`, ddof 0); NaN if any value is NaN. */
    fun std(a: List<Double>): Double {
        if (a.isEmpty()) return Double.NaN
        val m = mean(a)
        if (m.isNaN()) return Double.NaN
        var s = 0.0
        for (v in a) {
            val d = v - m
            s += d * d
        }
        return sqrt(s / a.size.toDouble())
    }

    /** Population standard deviation over the non-NaN values (`np.nanstd`); NaN if there are none. */
    fun nanstd(a: List<Double>): Double {
        val f = a.filter { !it.isNaN() }
        return if (f.isEmpty()) Double.NaN else std(f)
    }

    /** `np.percentile(a, q)` with the default linear method; NaN if any value is NaN. */
    fun percentile(a: List<Double>, q: Double): Double {
        if (a.isEmpty()) return Double.NaN
        if (a.any { it.isNaN() }) return Double.NaN
        val s = a.sorted()
        val h: Double = (s.size - 1).toDouble() * q / 100.0
        val lo = floor(h).toInt()
        val hi = min(lo + 1, s.size - 1)
        val frac: Double = h - lo.toDouble()
        val below: Double = s[lo]
        val above: Double = s[hi]
        return below + (above - below) * frac
    }

    /**
     * Fills the entries flagged in `invalid` by linear interpolation over the index (NumPy:
     * `a[inv] = np.interp(inv.nonzero(), (~inv).nonzero(), a[~inv])`). Unchanged when nothing is valid.
     */
    fun fillByIndex(a: List<Double>, invalid: List<Boolean>): List<Double> {
        val xp = ArrayList<Double>()
        val fp = ArrayList<Double>()
        for (i in a.indices) {
            if (invalid[i]) continue
            xp.add(i.toDouble())
            fp.add(a[i])
        }
        if (xp.isEmpty()) return a
        val out = ArrayList(a)
        for (i in a.indices) {
            if (invalid[i]) out[i] = interp(i.toDouble(), xp, fp)
        }
        return out
    }
}
