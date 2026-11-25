package org.rivem.core.piv

import org.rivem.core.imaging.GrayImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Region-of-interest mask for PIV (`mask.npy` / `final_mask.npy`): `height × width` bytes, row-major,
 * non-zero = analysed. Vectors whose position falls on 0 are set to NaN (as `piv_loop.py`).
 * Bytes are unsigned (Swift `[UInt8]`); only zero / non-zero matters.
 */
class PIVMask(val width: Int, val height: Int, var values: ByteArray) {
    init {
        require(width >= 1 && height >= 1 && values.size == width * height) { "PIVMask size mismatch" }
    }

    fun copy(): PIVMask = PIVMask(width, height, values.copyOf())

    override fun equals(other: Any?): Boolean =
        other is PIVMask && width == other.width && height == other.height && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * (31 * width + height) + values.contentHashCode()
}

/**
 * Displacement field of one frame pair (pixels per pair), on a `rows × columns` grid flattened row-major
 * (x fastest), with the same conventions as `piv_results.json`.
 */
class PIVPairField(
    var rows: Int,
    var columns: Int,
    /** Grid positions in frame pixels (see [PIVFFTMulti] for the exact convention). */
    var x: DoubleArray,
    var y: DoubleArray,
    var u: DoubleArray,
    var v: DoubleArray,
    /** Image-gradient indicator per window (upstream's `gradient_sum_result`). */
    var gradient: DoubleArray,
) {
    fun copy(): PIVPairField =
        PIVPairField(rows, columns, x.copyOf(), y.copyOf(), u.copyOf(), v.copyOf(), gradient.copyOf())

    /** NaN-aware equality, like Swift's `==`. */
    override fun equals(other: Any?): Boolean {
        if (other !is PIVPairField) return false
        fun same(p: DoubleArray, q: DoubleArray): Boolean {
            if (p.size != q.size) return false
            for (i in p.indices) if (!(p[i] == q[i] || (p[i].isNaN() && q[i].isNaN()))) return false
            return true
        }
        return rows == other.rows && columns == other.columns && same(x, other.x) && same(y, other.y) &&
            same(u, other.u) && same(v, other.v) && same(gradient, other.gradient)
    }

    override fun hashCode(): Int = 31 * rows + columns
}

/** Output grid of [PIVFFTMulti.outputGrid] (Swift's labelled tuple `(rows, columns, x, y)`). */
data class PIVOutputGrid(val rows: Int, val columns: Int, val x: DoubleArray, val y: DoubleArray) {
    override fun equals(other: Any?): Boolean =
        other is PIVOutputGrid && rows == other.rows && columns == other.columns &&
            x.contentEquals(other.x) && y.contentEquals(other.y)

    override fun hashCode(): Int = 31 * rows + columns
}

/**
 * Window grid of one pass (`calculate_bounds` in `piv_fftmulti.py`), in upstream's 1-based coordinates of the
 * padded ROI. Swift's failable `init?` is [PIVGrid.of].
 */
internal data class PIVGrid(
    val ia: Int,
    val step: Int,
    val half: Int,
    val miniy: Int,
    val minix: Int,
    val maxiy: Int,
    val maxix: Int,
    val nx: Int,
    val ny: Int,
) {
    val count: Int get() = nx * ny

    /** Window top-left in 0-based ROI coordinates (outside the ROI = padding). */
    fun top(iy: Int): Int = miniy - 1 - half + iy * step
    fun left(ix: Int): Int = minix - 1 - half + ix * step

    /** `xtable` / `ytable` of `process_result_conv` (used as spline nodes between passes). */
    fun xNode(ix: Int): Double = (minix + ix * step + half).toDouble()
    fun yNode(iy: Int): Double = (miniy + iy * step + half).toDouble()

    companion object {
        fun of(width: Int, height: Int, ia: Int, step: Int): PIVGrid? {
            if (!(ia >= 2 && step >= 1 && width >= 1 && height >= 1)) return null
            val half = (ia + 1) / 2
            var miniy = 1 + half
            var minix = 1 + half
            var maxiy = step * (height / step) - (ia - 1) + half
            var maxix = step * (width / step) - (ia - 1) + half
            val nx = floor((maxix - minix).toDouble() / step.toDouble() + 1).toInt()
            val ny = floor((maxiy - miniy).toDouble() / step.toDouble() + 1).toInt()
            if (!(nx >= 1 && ny >= 1)) return null
            val luy = height - maxiy
            val lux = width - maxix
            val shifty = max(rvrRound((luy - miniy).toDouble() / 2), 0)
            val shiftx = max(rvrRound((lux - minix).toDouble() / 2), 0)
            miniy += shifty
            maxiy += shifty
            minix += shiftx
            maxix += shiftx
            return PIVGrid(ia, step, half, miniy, minix, maxiy, maxix, nx, ny)
        }

        /** `rvr_round`: halves away from zero for positive values (floor otherwise). */
        fun rvrRound(x: Double): Int {
            val i = x.toInt().toDouble()
            if (x - i >= 0.5) return ceil(x).toInt()
            return floor(x).toInt()
        }
    }
}

/**
 * Port of `piv_fftmulti` (PIVlab 2.6 `piv_FFTmulti.m` via RIVeR 3.8.3) plus the masking of `piv_loop`.
 *
 * Inputs are already preprocessed frames ([PIVPreprocessing]). The algorithm, step by step as upstream:
 * 1. Crop the bounding box `[x, y, w, h]` (`rvr_round` of x/y, `ceil` of w/h), pad with the minimum of image 1.
 * 2. Pass 1: windows `ia1` every `step` px (`calculate_bounds`, centred), FFT cross-correlation, attenuation of the
 *    zero-lag 3×3 block (`mask_auto`), peak search, 3-point Gaussian sub-pixel fit.
 * 3. Std filter, normalised median test, RBF in-painting of NaNs, `smoothn(s = 0.0307)`.
 * 4. Pass 2 (if `multipass`): window `round(ia2/2)·2`, step = half of it; predictor = cubic spline of the pass-1
 *    field on the new grid; image 2 is warped with the predictor (`cv2.resize` + `cv2.remap` semantics, including
 *    remap's 1/32-px coordinate quantisation); correlation limited to a radius-4 disk around zero lag; the residual
 *    is added to the predictor; filters; gradient; nearest-neighbour in-painting; `smoothn`.
 * 5. Positions `x = minix + i·step + bbox.x`, `y = miniy + j·step + bbox.y` (upstream's convention: 1-based padded
 *    coordinates, i.e. not the geometric window centre; kept for compatibility). Vectors whose `(Int(x), Int(y))`
 *    falls outside `mask` become NaN.
 *
 * Deviations from RIVeR 3.8.3 (PLAN.md "Errores de RIVeR corregidos"): see the Swift `PIVFFTMulti` documentation
 * (#3 `multipass == false` honoured; #4 sub-pixel fit on the Float correlation with parabolic fallback; no pass-2
 * peak loss; odd or < 8 px windows rejected; single row/column predictor constant; `gradient[0]` always NaN).
 */
object PIVFFTMulti {
    internal const val smoothing: Double = 0.0307

    /** Throws [PIVError]. `bbox` is `bbox.json`'s `[x, y, w, h]` (null or non-finite = whole frame). */
    fun run(
        image1: GrayImage, image2: GrayImage, parameters: PIVParameters,
        bbox: List<Double>? = null, mask: PIVMask? = null,
    ): PIVPairField {
        val p = parameters
        p.validate()
        if (!(image1.width == image2.width && image1.height == image2.height)) throw PIVError.ImageSizeMismatch()
        val fullW = image1.width
        val fullH = image1.height
        var box = doubleArrayOf(0.0, 0.0, fullW.toDouble(), fullH.toDouble())
        if (bbox != null && bbox.size == 4 && bbox.all { it.isFinite() }) box = bbox.toDoubleArray()

        // process_roi
        val xroi = min(max(PIVGrid.rvrRound(box[0]), 0), fullW)
        val yroi = min(max(PIVGrid.rvrRound(box[1]), 0), fullH)
        val wroi = ceil(box[2]).toInt()
        val hroi = ceil(box[3]).toInt()
        val rw = max(0, min(fullW, xroi + wroi) - xroi)
        val rh = max(0, min(fullH, yroi + hroi) - yroi)
        if (!(rw > 0 && rh > 0)) throw PIVError.RegionTooSmall()
        val roi1 = crop(image1, xroi, yroi, rw, rh)
        val roi2 = crop(image2, xroi, yroi, rw, rh)
        var fill: Float = Float.POSITIVE_INFINITY
        var maxValue: Float = Float.NEGATIVE_INFINITY
        for (v in roi1) {
            if (v < fill) fill = v
            if (v > maxValue) maxValue = v
        }

        val ia1 = p.interrogationArea1
        val grid1 = PIVGrid.of(rw, rh, ia1, p.effectiveStep) ?: throw PIVError.RegionTooSmall()

        // Pass 1
        val pass1 = correlatePass(
            grid1,
            PIVPlane(roi1, rw, rh, grid1.top(0), grid1.left(0)),
            PIVPlane(roi2, rw, rh, grid1.top(0), grid1.left(0)),
            fill, if (p.maskAuto) CorrelationFilter.ZeroLag else CorrelationFilter.Off,
            if (p.multipass) null else maxValue,
        )
        val u1 = pass1.u
        val v1 = pass1.v
        applyFilters(u1, v1, grid1.ny, grid1.nx, p)

        var finalGrid = grid1
        var u: DoubleArray
        var v: DoubleArray
        var gradient: DoubleArray
        if (!p.multipass) {
            PIVFieldOps.nearestInpaint(u1, grid1.ny, grid1.nx)
            PIVFieldOps.nearestInpaint(v1, grid1.ny, grid1.nx)
            u = PIVFieldOps.smoothn(u1, grid1.ny, grid1.nx, smoothing)
            v = PIVFieldOps.smoothn(v1, grid1.ny, grid1.nx, smoothing)
            gradient = pass1.gradient
        } else {
            val ia2 = p.effectiveInterrogationArea2
            val half2 = (ia2 + 1) / 2
            val grid2 = PIVGrid.of(rw, rh, ia2, half2) ?: throw PIVError.RegionTooSmall()
            finalGrid = grid2
            val n2 = grid2.count
            if (u1.all { it.isNaN() } || v1.all { it.isNaN() }) {
                u = DoubleArray(n2) { Double.NaN }
                v = DoubleArray(n2) { Double.NaN }
                gradient = DoubleArray(n2) { Double.NaN }
            } else {
                PIVFieldOps.inpaintRBF(u1, grid1.ny, grid1.nx)
                PIVFieldOps.inpaintRBF(v1, grid1.ny, grid1.nx)
                val us = PIVFieldOps.smoothn(u1, grid1.ny, grid1.nx, smoothing)
                val vs = PIVFieldOps.smoothn(v1, grid1.ny, grid1.nx, smoothing)

                // interpolate_tables: spline from the pass-1 nodes to the pass-2 nodes.
                val yData = DoubleArray(grid1.ny) { grid1.yNode(it) }
                val xData = DoubleArray(grid1.nx) { grid1.xNode(it) }
                val yQuery = DoubleArray(grid2.ny) { (grid2.miniy + it * grid2.step).toDouble() + ia2.toDouble() / 2 }
                val xQuery = DoubleArray(grid2.nx) { (grid2.minix + it * grid2.step).toDouble() + ia2.toDouble() / 2 }
                val wy = PIVFieldOps.splineWeights(yData, yQuery)
                val wx = PIVFieldOps.splineWeights(xData, xQuery)
                val up = PIVFieldOps.interpolate(us, grid1.ny, grid1.nx, wy, grid2.ny, wx, grid2.nx)
                val vp = PIVFieldOps.interpolate(vs, grid1.ny, grid1.nx, wy, grid2.ny, wx, grid2.nx)

                val deformed = deform(roi2, rw, rh, fill, half2, up, vp, grid2)
                val pass2 = correlatePass(
                    grid2,
                    PIVPlane(roi1, rw, rh, grid2.top(0), grid2.left(0)),
                    PIVPlane(deformed.pixels, deformed.width, deformed.height, 0, 0),
                    fill, if (p.maskAuto) CorrelationFilter.Disk else CorrelationFilter.Off, maxValue,
                )
                val u2 = DoubleArray(n2)
                val v2 = DoubleArray(n2)
                for (i in 0 until n2) {
                    u2[i] = up[i] + pass2.u[i]
                    v2[i] = vp[i] + pass2.v[i]
                }
                applyFilters(u2, v2, grid2.ny, grid2.nx, p)
                PIVFieldOps.nearestInpaint(u2, grid2.ny, grid2.nx)
                PIVFieldOps.nearestInpaint(v2, grid2.ny, grid2.nx)
                u = PIVFieldOps.smoothn(u2, grid2.ny, grid2.nx, smoothing)
                v = PIVFieldOps.smoothn(v2, grid2.ny, grid2.nx, smoothing)
                gradient = pass2.gradient
            }
        }
        if (gradient.isNotEmpty()) gradient[0] = Double.NaN

        // Positions (xtable + bbox[0] - half_ia) and piv_loop's mask.
        val g = finalGrid
        val xs = DoubleArray(g.count)
        val ys = DoubleArray(g.count)
        for (iy in 0 until g.ny) {
            for (ix in 0 until g.nx) {
                val k = iy * g.nx + ix
                xs[k] = (g.minix + ix * g.step).toDouble() + box[0]
                ys[k] = (g.miniy + iy * g.step).toDouble() + box[1]
            }
        }
        if (mask != null) {
            val m = mask
            for (k in 0 until g.count) {
                val xi = min(max(xs[k].toInt(), 0), m.width - 1)
                val yi = min(max(ys[k].toInt(), 0), m.height - 1)
                if (m.values[yi * m.width + xi].toInt() == 0) {
                    u[k] = Double.NaN
                    v[k] = Double.NaN
                }
            }
        }
        return PIVPairField(g.ny, g.nx, xs, ys, u, v, gradient)
    }

    /**
     * Output grid (rows, columns, x, y) for frames of this size, without processing (used to shape all-NaN
     * results of failed pairs). Throws [PIVError].
     */
    fun outputGrid(width: Int, height: Int, parameters: PIVParameters, bbox: List<Double>? = null): PIVOutputGrid {
        val p = parameters
        p.validate()
        var box = doubleArrayOf(0.0, 0.0, width.toDouble(), height.toDouble())
        if (bbox != null && bbox.size == 4 && bbox.all { it.isFinite() }) box = bbox.toDoubleArray()
        val xroi = min(max(PIVGrid.rvrRound(box[0]), 0), width)
        val yroi = min(max(PIVGrid.rvrRound(box[1]), 0), height)
        val rw = max(0, min(width, xroi + ceil(box[2]).toInt()) - xroi)
        val rh = max(0, min(height, yroi + ceil(box[3]).toInt()) - yroi)
        val ia = if (p.multipass) p.effectiveInterrogationArea2 else p.interrogationArea1
        val step = if (p.multipass) (ia + 1) / 2 else p.effectiveStep
        if (!(rw > 0 && rh > 0)) throw PIVError.RegionTooSmall()
        PIVGrid.of(rw, rh, p.interrogationArea1, p.effectiveStep) ?: throw PIVError.RegionTooSmall()
        val g = PIVGrid.of(rw, rh, ia, step) ?: throw PIVError.RegionTooSmall()
        val xs = DoubleArray(g.count)
        val ys = DoubleArray(g.count)
        for (iy in 0 until g.ny) {
            for (ix in 0 until g.nx) {
                xs[iy * g.nx + ix] = (g.minix + ix * g.step).toDouble() + box[0]
                ys[iy * g.nx + ix] = (g.miniy + iy * g.step).toDouble() + box[1]
            }
        }
        return PIVOutputGrid(g.ny, g.nx, xs, ys)
    }

    // region Internals

    private fun applyFilters(u: DoubleArray, v: DoubleArray, rows: Int, cols: Int, p: PIVParameters) {
        if (p.stdFiltering) {
            PIVFieldOps.filterStd(u, p.stdThreshold)
            PIVFieldOps.filterStd(v, p.stdThreshold)
        }
        if (p.medianTestFiltering) {
            PIVFieldOps.filterMedian(u, v, rows, cols, p.medianTestEpsilon, p.medianTestThreshold)
        }
    }

    private fun crop(img: GrayImage, x: Int, y: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (r in 0 until h) {
            System.arraycopy(img.pixels, (y + r) * img.width + x, out, r * w, w)
        }
        return out
    }

    /** An image plane and the top-left of window (0, 0) in it; window (iy, ix) starts `step` px further per index. */
    internal class PIVPlane(val data: FloatArray, val width: Int, val height: Int, val top: Int, val left: Int)

    internal enum class CorrelationFilter { Off, ZeroLag, Disk }

    internal class PassResult(val u: DoubleArray, val v: DoubleArray, val gradient: DoubleArray)

    /**
     * Correlates every window of `grid` and returns the (sub-pixel) peak displacement per window, plus the gradient
     * indicator when `gradientMax` is given (`calculate_gradient`: Σ |∂x| + |∂y| of `(w1 + w2) / max(image 1)`).
     */
    internal fun correlatePass(
        grid: PIVGrid, plane1: PIVPlane, plane2: PIVPlane, fill: Float,
        filter: CorrelationFilter, gradientMax: Float?,
    ): PassResult {
        val n = grid.ia
        val nn = n * n
        val count = grid.count
        val u = DoubleArray(count) { Double.NaN }
        val v = DoubleArray(count) { Double.NaN }
        val gradient = DoubleArray(if (gradientMax == null) 0 else count) { Double.NaN }
        val correlator = PIVCorrelator(n)
        val a1 = FloatArray(nn)
        val b1 = FloatArray(nn)
        val a2 = FloatArray(nn)
        val b2 = FloatArray(nn)
        val o1 = FloatArray(nn)
        val o2 = FloatArray(nn)
        val filterTable = makeFilter(filter, n)
        val peakOut = DoubleArray(1) // v of peak() (Swift returns a tuple)
        var k = 0
        while (k < count) {
            val hasSecond = k + 1 < count
            extract(plane1, k, grid, n, fill, a1)
            extract(plane2, k, grid, n, fill, b1)
            if (hasSecond) {
                extract(plane1, k + 1, grid, n, fill, a2)
                extract(plane2, k + 1, grid, n, fill, b2)
                correlator.correlate(a1, b1, o1, a2, b2, o2)
            } else {
                correlator.correlate(a1, b1, o1, null, null, null)
            }
            val d1u = peak(o1, n, filterTable, peakOut)
            u[k] = d1u
            v[k] = peakOut[0]
            if (gradientMax != null) gradient[k] = gradientSum(a1, b1, n, gradientMax)
            if (hasSecond) {
                val d2u = peak(o2, n, filterTable, peakOut)
                u[k + 1] = d2u
                v[k + 1] = peakOut[0]
                if (gradientMax != null) gradient[k + 1] = gradientSum(a2, b2, n, gradientMax)
            }
            k += 2
        }
        return PassResult(u, v, gradient)
    }

    private fun extract(plane: PIVPlane, k: Int, grid: PIVGrid, n: Int, fill: Float, dst: FloatArray) {
        val iy = k / grid.nx
        val ix = k % grid.nx
        val top = plane.top + iy * grid.step
        val left = plane.left + ix * grid.step
        val inside = left >= 0 && left + n <= plane.width
        val data = plane.data
        for (i in 0 until n) {
            val r = top + i
            val row = i * n
            if (r < 0 || r >= plane.height) {
                java.util.Arrays.fill(dst, row, row + n, fill)
                continue
            }
            val src = r * plane.width
            if (inside) {
                System.arraycopy(data, src + left, dst, row, n)
            } else {
                for (j in 0 until n) {
                    val c = left + j
                    dst[row + j] = if (c < 0 || c >= plane.width) fill else data[src + c]
                }
            }
        }
    }

    /** Radius-4 disk of `limit_peak_search_area` (MATLAB `fspecial('disk', 4)` normalised to 1 at the centre). */
    internal val disk9: DoubleArray = doubleArrayOf(
        0.0, 0.0, 0.0477750257157819, 0.361469237632242, 0.489558781987489, 0.361469237632242, 0.0477750257157819, 0.0, 0.0,
        0.0, 0.208018589368669, 0.900152358153484, 1.0, 1.0, 1.0, 0.900152358153484, 0.208018589368669, 0.0,
        0.0477750257157819, 0.900152358153484, 1.0, 1.0, 1.0, 1.0, 1.0, 0.900152358153484, 0.0477750257157819,
        0.361469237632242, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.361469237632242,
        0.489558781987489, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.489558781987489,
        0.361469237632242, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.361469237632242,
        0.0477750257157819, 0.900152358153484, 1.0, 1.0, 1.0, 1.0, 1.0, 0.900152358153484, 0.0477750257157819,
        0.0, 0.208018589368669, 0.900152358153484, 1.0, 1.0, 1.0, 0.900152358153484, 0.208018589368669, 0.0,
        0.0, 0.0, 0.0477750257157819, 0.361469237632242, 0.489558781987489, 0.361469237632242, 0.0477750257157819, 0.0, 0.0,
    )

    /**
     * Multiplicative filter of the correlation plane: null = none, else n×n factors.
     * ZeroLag (`apply_gaussian_filter`): the 3×3 block around zero lag times `1 − exp(−d²/4.5)` (Gaussian σ = 1.5
     * normalised to 1 at the centre). Disk (`limit_peak_search_area`): `disk9` around zero lag, 0 elsewhere.
     */
    private fun makeFilter(f: CorrelationFilter, n: Int): FloatArray? {
        val c = n / 2
        return when (f) {
            CorrelationFilter.Off -> null
            CorrelationFilter.ZeroLag -> {
                val t = FloatArray(n * n) { 1f }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val d2 = (dx * dx + dy * dy).toDouble()
                        t[(c + dy) * n + (c + dx)] = (1.0 - exp(-d2 / 4.5)).toFloat()
                    }
                }
                t
            }
            CorrelationFilter.Disk -> {
                val t = FloatArray(n * n)
                for (i in 0 until 9) {
                    for (j in 0 until 9) {
                        val r = c - 4 + i
                        val q = c - 4 + j
                        if (r >= 0 && r < n && q >= 0 && q < n) t[r * n + q] = disk9[i * 9 + j].toFloat()
                    }
                }
                t
            }
        }
    }

    /**
     * Peak search and 3-point sub-pixel fit on one (fftshifted) correlation plane (filtered in place). Returns u in
     * px and writes v into `vOut[0]` (Swift returns the `(u, v)` tuple); NaN when there is no usable peak. Ties keep
     * the first maximum in column-major order (upstream's order).
     */
    internal fun peak(corr: FloatArray, n: Int, filter: FloatArray?, vOut: DoubleArray): Double {
        val nn = n * n
        if (filter != null) {
            for (i in 0 until nn) corr[i] *= filter[i]
        }
        var minV: Float = Float.POSITIVE_INFINITY
        var maxV: Float = Float.NEGATIVE_INFINITY
        var br = -1
        var bc = -1
        for (r in 0 until n) {
            for (c in 0 until n) {
                val x = corr[r * n + c]
                if (x.isNaN()) continue
                if (x < minV) minV = x
                if (x > maxV || (x == maxV && (c < bc || (c == bc && r < br)))) {
                    maxV = x
                    br = r
                    bc = c
                }
            }
        }
        vOut[0] = Double.NaN
        if (!(br >= 0 && maxV > minV)) return Double.NaN
        if (!(br > 0 && br < n - 1 && bc > 0 && bc < n - 1)) return Double.NaN
        val lo = minV.toDouble()
        val range = maxV.toDouble() - lo
        val f0 = (corr[br * n + bc].toDouble() - lo) / range
        val dy = subpixel((corr[(br - 1) * n + bc].toDouble() - lo) / range, f0,
                          (corr[(br + 1) * n + bc].toDouble() - lo) / range)
        val dx = subpixel((corr[br * n + bc - 1].toDouble() - lo) / range, f0,
                          (corr[br * n + bc + 1].toDouble() - lo) / range)
        val half = n / 2
        vOut[0] = (br - half).toDouble() + dy
        return (bc - half).toDouble() + dx
    }

    /** Gaussian 3-point estimator `(ln f1 − ln f2) / (2 ln f1 − 4 ln f0 + 2 ln f2)`; parabolic when a value is ≤ 0. */
    internal fun subpixel(f1: Double, f0: Double, f2: Double): Double {
        if (f1 > 0 && f0 > 0 && f2 > 0) {
            val l1 = ln(f1)
            val l0 = ln(f0)
            val l2 = ln(f2)
            val den = 2 * l1 - 4 * l0 + 2 * l2
            if (den != 0.0 && den.isFinite()) {
                val d = (l1 - l2) / den
                if (abs(d) <= 1) return d
            }
        }
        val den = 2 * f1 - 4 * f0 + 2 * f2
        if (den == 0.0 || !den.isFinite()) return 0.0
        val d = (f1 - f2) / den
        return min(max(d, -1.0), 1.0)
    }

    /** `calculate_gradient` for one window. */
    internal fun gradientSum(a: FloatArray, b: FloatArray, n: Int, maxValue: Float): Double {
        if (n < 2) return 0.0
        var s = 0.0
        for (r in 0 until n) {
            for (c in 0 until n) {
                val k = r * n + c
                val here: Float = (a[k] + b[k]) / maxValue
                if (c < n - 1) {
                    val right: Float = (a[k + 1] + b[k + 1]) / maxValue
                    val d = abs(right - here).toDouble()
                    s += d
                    if (c == n - 2) s += d // np.pad(..., 'edge') repeats the last difference
                }
                if (r < n - 1) {
                    val below: Float = (a[k + n] + b[k + n]) / maxValue
                    val d = abs(below - here).toDouble()
                    s += d
                    if (r == n - 2) s += d
                }
            }
        }
        return s
    }

    internal class Deformed(val pixels: FloatArray, val width: Int, val height: Int)

    /** cv2.resize INTER_LINEAR coefficients (Swift's nested `coefficients(dst:src:)`). */
    private class Coefficients(dst: Int, src: Int) {
        val idx = IntArray(dst)
        val idx1 = IntArray(dst)
        val frac = FloatArray(dst)

        init {
            val inv: Double = dst.toDouble() / src.toDouble()
            val scale: Double = 1.0 / inv
            for (d in 0 until dst) {
                var f = ((d.toDouble() + 0.5) * scale - 0.5).toFloat()
                var s = floor(f).toInt()
                f -= s.toFloat()
                if (s < 0) {
                    s = 0
                    f = 0f
                }
                if (s >= src - 1) {
                    s = src - 1
                    f = 0f
                }
                idx[d] = s
                idx1[d] = min(s + 1, src - 1)
                frac[d] = f
            }
        }
    }

    /**
     * `deform_window`: warps image 2 (padded by `pad` with `fill`) with the predictor field. The field (edge-padded
     * by one node) is upsampled to the pixel grid with `cv2.resize(INTER_LINEAR)` semantics (stretching the node
     * array over the whole span, not node-aligned, as upstream), then `cv2.remap(INTER_LINEAR, BORDER_REPLICATE)`
     * samples `(x + U − 1, y + V − 1)`, with remap's fixed-point coordinates (1/32 px) and float weights.
     */
    internal fun deform(roi: FloatArray, w: Int, h: Int, fill: Float, pad: Int,
                        up: DoubleArray, vp: DoubleArray, grid: PIVGrid): Deformed {
        val nx = grid.nx
        val ny = grid.ny
        val ws = nx + 2
        val hs = ny + 2
        // Edge-padded predictor in Float (U.astype(np.float32)).
        val ue = FloatArray(ws * hs)
        val ve = FloatArray(ws * hs)
        for (r in 0 until hs) {
            val sr = min(max(r - 1, 0), ny - 1)
            for (c in 0 until ws) {
                val sc = min(max(c - 1, 0), nx - 1)
                ue[r * ws + c] = up[sr * nx + sc].toFloat()
                ve[r * ws + c] = vp[sr * nx + sc].toFloat()
            }
        }
        // Pixel span: first node − step … last node + step (exclusive).
        val firstX = grid.minix + grid.half
        val firstY = grid.miniy + grid.half
        val x0 = firstX - grid.step
        val x1 = firstX + (nx - 1) * grid.step + grid.step
        val y0 = firstY - grid.step
        val y1 = firstY + (ny - 1) * grid.step + grid.step
        val wd = max(x1 - x0, 1)
        val hd = max(y1 - y0, 1)

        val cx = Coefficients(wd, ws)
        val cy = Coefficients(hd, hs)
        // Horizontal pass for every source row.
        val hu = FloatArray(hs * wd)
        val hv = FloatArray(hs * wd)
        for (r in 0 until hs) {
            for (c in 0 until wd) {
                val a0: Float = 1 - cx.frac[c]
                val a1: Float = cx.frac[c]
                hu[r * wd + c] = ue[r * ws + cx.idx[c]] * a0 + ue[r * ws + cx.idx1[c]] * a1
                hv[r * wd + c] = ve[r * ws + cx.idx[c]] * a0 + ve[r * ws + cx.idx1[c]] * a1
            }
        }

        val pw = w + 2 * pad
        val ph = h + 2 * pad
        val out = FloatArray(wd * hd)
        for (r in 0 until hd) {
            val b0: Float = 1 - cy.frac[r]
            val b1: Float = cy.frac[r]
            val rowA = cy.idx[r] * wd
            val rowB = cy.idx1[r] * wd
            val yy = (y0 + r).toFloat()
            for (c in 0 until wd) {
                val uf: Float = hu[rowA + c] * b0 + hu[rowB + c] * b1
                val vf: Float = hv[rowA + c] * b0 + hv[rowB + c] * b1
                val mx: Float = ((x0 + c).toFloat() + uf) - 1
                val my: Float = (yy + vf) - 1
                out[r * wd + c] = remapSample(roi, w, h, pad, pw, ph, fill, mx, my)
            }
        }
        return Deformed(out, wd, hd)
    }

    private fun remapSample(roi: FloatArray, w: Int, h: Int, pad: Int, pw: Int, ph: Int,
                            fill: Float, mx: Float, my: Float): Float {
        if (!(mx.isFinite() && my.isFinite() && abs(mx) < 1.0e7f && abs(my) < 1.0e7f)) return fill
        val xq = Math.rint((mx * 32).toDouble()).toInt()
        val yq = Math.rint((my * 32).toDouble()).toInt()
        val sx = xq shr 5
        val sy = yq shr 5
        val fx: Float = (xq and 31).toFloat() * (1.0f / 32.0f)
        val fy: Float = (yq and 31).toFloat() * (1.0f / 32.0f)
        val w00: Float = (1 - fy) * (1 - fx)
        val w01: Float = (1 - fy) * fx
        val w10: Float = fy * (1 - fx)
        val w11: Float = fy * fx
        val v00 = padded(roi, w, h, pad, pw, ph, fill, sx, sy)
        val v01 = padded(roi, w, h, pad, pw, ph, fill, sx + 1, sy)
        val v10 = padded(roi, w, h, pad, pw, ph, fill, sx, sy + 1)
        val v11 = padded(roi, w, h, pad, pw, ph, fill, sx + 1, sy + 1)
        val s0: Float = v00 * w00 + v01 * w01
        val s1: Float = s0 + v10 * w10
        return s1 + v11 * w11
    }

    /** Pixel of the padded ROI with BORDER_REPLICATE outside the padded image. */
    private fun padded(roi: FloatArray, w: Int, h: Int, pad: Int, pw: Int, ph: Int, fill: Float, x: Int, y: Int): Float {
        val cx = min(max(x, 0), pw - 1) - pad
        val cy = min(max(y, 0), ph - 1) - pad
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return fill
        return roi[cy * w + cx]
    }

    // endregion
}
