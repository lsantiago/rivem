package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import org.rivem.core.imaging.RGBAImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Binary frame mask, `uint8` values 0/1, row-major `(height, width)` like desktop's `mask.npy`, `usr_mask_<N>.npy`
 * and `final_mask.npy`: `1` = analysed, `0` = excluded. `values` holds unsigned bytes; the subscript reads them as
 * 0…255 `Int`s.
 */
class ROIMask(val width: Int, val height: Int, val values: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && values.size == width * height) { "ROIMask size mismatch" }
    }

    constructor(width: Int, height: Int, fill: Int) : this(width, height, ByteArray(width * height) { fill.toByte() })

    operator fun get(x: Int, y: Int): Int = values[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        values[y * width + x] = value.toByte()
    }

    /** Number of pixels set to 1. */
    val count: Int get() = values.fold(0) { acc, v -> acc + (if (v.toInt() != 0) 1 else 0) }

    /** `[xMin, yMin, xMax, yMax]` (inclusive) of the non-zero pixels; null if none. */
    val nonZeroBounds: List<Int>?
        get() {
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = -1
            var y1 = -1
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (values[row + x].toInt() == 0) continue
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            return if (x1 < 0) null else listOf(x0, y0, x1, y1)
        }

    /** Number of non-zero pixels in each row. */
    val rowCounts: List<Int>
        get() {
            val out = IntArray(height)
            for (y in 0 until height) {
                var c = 0
                val row = y * width
                for (x in 0 until width) if (values[row + x].toInt() != 0) c += 1
                out[y] = c
            }
            return out.toList()
        }

    /** Pixel-wise AND (0 dominant), values normalised to 0/1 (`combine_binary_masks_and`). */
    fun and(other: ROIMask): ROIMask {
        require(other.width == width && other.height == height) { "mask sizes differ" }
        val out = ByteArray(values.size)
        for (i in values.indices) out[i] = if (values[i].toInt() != 0 && other.values[i].toInt() != 0) 1 else 0
        return ROIMask(width, height, out)
    }

    /**
     * Desktop's `mask.png` / `final_mask.png` overlay (`get_png_mask` in `river/cli/commands/define_roi_masks.py`):
     * pixels with value 0 are black with alpha 111, the rest fully transparent (all channels 0).
     */
    fun overlayImage(): RGBAImage {
        val bytes = ByteArray(width * height * 4)
        for (i in values.indices) if (values[i].toInt() == 0) bytes[4 * i + 3] = 111
        return RGBAImage(width, height, bytes)
    }

    fun copy(): ROIMask = ROIMask(width, height, values.copyOf())

    override fun equals(other: Any?): Boolean =
        other is ROIMask && width == other.width && height == other.height && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * (31 * width + height) + values.contentHashCode()

    override fun toString(): String = "ROIMask(${width}x$height, count=$count)"
}

/**
 * One cross-section as used for the ROI: the section line in real-world coordinates and, optionally, the wet
 * segments (stations, m, along the bathymetry) that make dry islands be excluded from the mask.
 *
 * The Swift failable initialiser from a desktop `CrossSection` lives in `ROIMaskSections.kt` (it depends on the
 * `project` package).
 */
class ROISection(
    val left: Point2,
    val right: Point2,
    /**
     * Wet stretches of the bathymetry at the section's water level, in station units (`find_wet_segments` in
     * `compute_section.py`, ported by the section code). Two or more segments create island exclusions; empty
     * means "no island handling".
     */
    val wetSegments: List<ClosedFloatingPointRange<Double>> = emptyList(),
    realWorldLength: Double? = null,
) {
    /** `rw_length` of the section (m); defaults to the length of the line. */
    val realWorldLength: Double = realWorldLength ?: left.distanceTo(right)

    override fun equals(other: Any?): Boolean =
        other is ROISection && left == other.left && right == other.right && wetSegments == other.wetSegments &&
            realWorldLength == other.realWorldLength

    override fun hashCode(): Int = listOf(left, right, wetSegments, realWorldLength).hashCode()

    override fun toString(): String =
        "ROISection(left=$left, right=$right, wetSegments=$wetSegments, realWorldLength=$realWorldLength)"
}

/** `ROIMasks.sectionMask` result (Swift's `(mask:, bbox:)` tuple). */
data class SectionMask(val mask: ROIMask, val bbox: List<Double>)

/** `ROIMasks.boundedBrent` result (Swift's `(x:, converged:)` tuple). */
data class BrentResult(val x: Double, val converged: Boolean)

/**
 * ROI masks for PIV. Port of `river/core/define_roi_masks.py` (RIVeR 3.8.3): `create_rw_box`, `rw_box_to_pixel`,
 * `create_mask` (`cv2.fillPoly` reproduced in integer arithmetic, see `fillPolygon`), `create_island_exclusion_mask`,
 * `create_mask_and_bbox`, `recommend_height_roi`, `create_user_polygon_mask` and `compile_masks`.
 */
object ROIMasks {
    /**
     * Corners of the rectangle of height `height` (m) centred on the section line and aligned with it
     * (`create_rw_box`), in order (−w/2,−h/2), (w/2,−h/2), (w/2,h/2), (−w/2,h/2) rotated and translated.
     */
    fun worldBox(left: Point2, right: Point2, height: Double): List<Point2> {
        val length: Double = hypot(right.x - left.x, right.y - left.y)
        val angle: Double = atan2(right.y - left.y, right.x - left.x)
        val cx = (left.x + right.x) / 2
        val cy = (left.y + right.y) / 2
        val c = cos(angle)
        val s = sin(angle)
        val hw = length / 2
        val hh = height / 2
        val local: List<Point2> = listOf(Point2(-hw, -hh), Point2(hw, -hh), Point2(hw, hh), Point2(-hw, hh))
        return local.map { Point2(c * it.x - s * it.y + cx, s * it.x + c * it.y + cy) }
    }

    /** World → pixel through the inverse of the pixel → world `matrix` (`rw_box_to_pixel`). */
    fun pixelBox(worldBox: List<Point2>, matrix: Matrix3): List<Point2> {
        val inv = matrix.inverse()
        return worldBox.map { inv.apply(it) }
    }

    /**
     * ROI mask and bounding box of all sections (`create_mask_and_bbox`).
     * @return the mask (1 inside any section box minus its islands) and `[x, y, width, height]` of the pixel
     *   boxes, each bound clipped to `[0, width−1]` / `[0, height−1]` (desktop's `bbox.json`).
     */
    fun sectionMask(width: Int, height: Int, sections: List<ROISection>, matrix: Matrix3,
                    heightROI: Double): SectionMask {
        if (sections.isEmpty()) throw RiverCoreError.NotEnoughPoints(needed = 1, got = 0)
        val combined = ROIMask(width, height, fill = 0)
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (section in sections) {
            val box: List<Point2> = pixelBox(worldBox(section.left, section.right, heightROI), matrix)
            for (p in box) {
                minX = swiftMin(minX, p.x)
                minY = swiftMin(minY, p.y)
                maxX = swiftMax(maxX, p.x)
                maxY = swiftMax(maxY, p.y)
            }
            val mask = ROIMask(width, height, fill = 0)
            fillPolygon(mask, truncated(box), 1)
            val islands: ROIMask = islandExclusionMask(width, height, section, matrix, heightROI)
            for (i in mask.values.indices) {
                if (mask.values[i].toInt() != 0 && islands.values[i].toInt() != 0) combined.values[i] = 1
            }
        }
        val wMax = maxOf(width - 1, 0).toDouble()
        val hMax = maxOf(height - 1, 0).toDouble()
        val x0 = clamp(minX, 0.0, wMax)
        val x1 = clamp(maxX, 0.0, wMax)
        val y0 = clamp(minY, 0.0, hMax)
        val y1 = clamp(maxY, 0.0, hMax)
        return SectionMask(combined, listOf(x0, y0, x1 - x0, y1 - y0))
    }

    /**
     * 0 over each dry stretch between consecutive wet segments, 1 elsewhere (`create_island_exclusion_mask`).
     * Stations are placed on the section line measured from the left point, with the first wet station at the
     * left point (desktop's convention).
     */
    fun islandExclusionMask(width: Int, height: Int, section: ROISection, matrix: Matrix3,
                            heightROI: Double): ROIMask {
        val mask = ROIMask(width, height, fill = 1)
        val wet = section.wetSegments
        if (wet.size < 2) return mask
        val total: Double = section.left.distanceTo(section.right)
        if (!(total > 0)) return mask
        val ux = (section.right.x - section.left.x) / total
        val uy = (section.right.y - section.left.y) / total
        val first: Double = wet[0].start
        val place: (Double) -> Point2 = { station ->
            val off = station - first
            Point2(section.left.x + off * ux, section.left.y + off * uy)
        }
        for (i in 0 until wet.size - 1) {
            val a = place(wet[i].endInclusive)
            val b = place(wet[i + 1].start)
            val box: List<Point2> = pixelBox(worldBox(a, b, heightROI), matrix)
            val island = ROIMask(width, height, fill = 0)
            fillPolygon(island, truncated(box), 1)
            for (k in mask.values.indices) if (island.values[k].toInt() != 0) mask.values[k] = 0
        }
        return mask
    }

    /**
     * User exclusion polygon (`create_user_polygon_mask`): vertices rounded to the nearest pixel (half to even,
     * like Python's `round`) and clipped to the frame; 0 inside the polygon (and on its outline), 1 elsewhere.
     */
    fun userPolygonMask(width: Int, height: Int, points: List<Point2>): ROIMask {
        if (points.size < 3) throw RiverCoreError.NotEnoughPoints(needed = 3, got = points.size)
        val poly = ArrayList<Pair<Long, Long>>(points.size)
        for (p in points) {
            if (!(p.x.isFinite() && p.y.isFinite())) throw RiverCoreError.DegenerateGeometry("vértice no finito")
            val rx: Double = Math.rint(p.x)
            val ry: Double = Math.rint(p.y)
            val x = clamp(rx, 0.0, maxOf(width - 1, 0).toDouble()).toLong()
            val y = clamp(ry, 0.0, maxOf(height - 1, 0).toDouble()).toLong()
            poly.add(Pair(x, y))
        }
        val mask = ROIMask(width, height, fill = 1)
        fillPolygon(mask, poly, 0)
        return mask
    }

    /** `compile_masks`: ROI AND every user mask. */
    fun compile(roi: ROIMask, userMasks: List<ROIMask>): ROIMask {
        var out = roi.and(roi)
        for (m in userMasks) out = out.and(m)
        return out
    }

    /**
     * `recommend_height_roi`: the box height (m, rounded up to an integer) whose smallest pixel side is closest to
     * 1.5 × `windowSize` px, searched with the same bounded Brent minimiser as SciPy's
     * `minimize_scalar(method="Bounded")` on [0.1, rw_length]. The desktop GUI uses `windowSize` 128.
     *
     * **Fix over desktop:** the upper bound is meant to be the shortest section, but desktop's loop keeps only
     * the *last* section's `rw_length` (`max_rw_length` is never updated). Here it is the minimum over all
     * sections; for a single section both are equal.
     */
    fun recommendHeightROI(sections: List<ROISection>, matrix: Matrix3, windowSize: Int = 128): Double {
        if (sections.isEmpty()) throw RiverCoreError.NotEnoughPoints(needed = 1, got = 0)
        val upper: Double = sections.map { it.realWorldLength }.minOrNull() ?: 0.0
        if (!(upper > 0.1)) throw RiverCoreError.DegenerateGeometry("sección demasiado corta")
        val inv = matrix.inverse()
        val desired = 1.5 * windowSize.toDouble()
        val objective: (Double) -> Double = { h ->
            var minSide = Double.POSITIVE_INFINITY
            for (s in sections) {
                val box: List<Point2> = worldBox(s.left, s.right, h).map { inv.apply(it) }
                for (i in box.indices) minSide = swiftMin(minSide, box[i].distanceTo(box[(i + 1) % box.size]))
            }
            abs(desired - minSide)
        }
        val result = boundedBrent(objective, lower = 0.1, upper = upper)
        return if (result.converged) ceil(result.x) else 1.0
    }

    // Rasterisation (cv2.fillPoly, LINE_8, shift 0). Swift's Int is 64-bit: Long here, so the 16.16 fixed point of
    // coordinates up to ±10⁶ cannot overflow.

    /**
     * `np.array(box, dtype=np.int32)`: truncation toward zero. Coordinates are clamped to ±10⁶ px first, which
     * leaves any realistic frame unchanged and keeps the integer arithmetic below from overflowing.
     */
    internal fun truncated(pts: List<Point2>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>(pts.size)
        for (p in pts) {
            if (!(p.x.isFinite() && p.y.isFinite())) {
                throw RiverCoreError.DegenerateGeometry("la caja de la sección cae fuera del plano de la imagen")
            }
            out.add(Pair(clamp(p.x, -1e6, 1e6).toLong(), clamp(p.y, -1e6, 1e6).toLong()))
        }
        return out
    }

    /**
     * Fills an integer polygon the way OpenCV's `fillPoly` does with `LINE_8` and `shift = 0`: every edge is drawn
     * with OpenCV's 8-connected line iterator (after `clipLine`), then each scanline y in [y0, y1) of every
     * non-horizontal edge is filled between pairs of edge crossings computed in 16.16 fixed point, from
     * ceil(x_left) to floor(x_right). Matches OpenCV pixel for pixel on polygons inside the frame (checked
     * against cv2 4.11 on random quadrilaterals); polygons clipped by the frame differ in ~0.05 % of pixels.
     */
    internal fun fillPolygon(mask: ROIMask, polygon: List<Pair<Long, Long>>, value: Int) {
        val n = polygon.size
        if (n < 1) return
        val w = mask.width
        val h = mask.height
        if (!(w > 0 && h > 0)) return
        val shift = 16
        val one = 1L shl shift
        // (yStart, yEnd, xStartFixed, dxFixed)
        val edges = ArrayList<LongArray>()
        for (i in 0 until n) {
            val p0 = polygon[i]
            val p1 = polygon[(i + 1) % n]
            drawLine(mask, p0.first, p0.second, p1.first, p1.second, value)
            if (p0.second == p1.second) continue
            val fx0 = p0.first shl shift
            val fx1 = p1.first shl shift
            val dx: Long = (fx1 - fx0) / (p1.second - p0.second) // truncates toward zero, like C++
            if (p0.second < p1.second) {
                edges.add(longArrayOf(p0.second, p1.second, fx0, dx))
            } else {
                edges.add(longArrayOf(p1.second, p0.second, fx1, dx))
            }
        }
        if (edges.isEmpty()) return
        var yMin = Long.MAX_VALUE
        var yMax = Long.MIN_VALUE
        for (e in edges) {
            yMin = minOf(yMin, e[0])
            yMax = maxOf(yMax, e[1])
        }
        val yStart = maxOf(yMin, 0L)
        val yStop = minOf(yMax, h.toLong())
        if (yStart >= yStop) return
        val xs = ArrayList<Long>()
        val b8 = value.toByte()
        for (y in yStart until yStop) {
            xs.clear()
            for (e in edges) if (e[0] <= y && y < e[1]) xs.add(e[2] + (y - e[0]) * e[3])
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                var a = (xs[k] + one - 1) shr shift
                var b = xs[k + 1] shr shift
                if (a < w && b >= 0) {
                    a = maxOf(a, 0L)
                    b = minOf(b, (w - 1).toLong())
                    if (a <= b) {
                        val row = y.toInt() * w
                        for (x in a.toInt()..b.toInt()) mask.values[row + x] = b8
                    }
                }
                k += 2
            }
        }
    }

    /**
     * OpenCV `Line` with 8-connectivity: clip to the frame (`clipLine`), then the `LineIterator` stepping from the
     * left end point.
     */
    internal fun drawLine(mask: ROIMask, ax: Long, ay: Long, bx: Long, by: Long, value: Int) {
        val w = mask.width.toLong()
        val h = mask.height.toLong()
        var x0 = ax
        var y0 = ay
        var x1 = bx
        var y1 = by
        val inside: (Long, Long) -> Boolean = { x, y -> x >= 0 && x < w && y >= 0 && y < h }
        if (!(inside(x0, y0) && inside(x1, y1))) {
            val clipped = clipLine(w, h, x0, y0, x1, y1) ?: return
            x0 = clipped[0]
            y0 = clipped[1]
            x1 = clipped[2]
            y1 = clipped[3]
        }
        if (x1 < x0) {
            var t = x0; x0 = x1; x1 = t
            t = y0; y0 = y1; y1 = t
        }
        val dx = x1 - x0
        var dy = y1 - y0
        val sy = if (dy >= 0) 1L else -1L
        dy = abs(dy)
        val xMajor = dx >= dy
        val dMajor = if (xMajor) dx else dy
        val dMinor = if (xMajor) dy else dx
        var err = dMajor - 2 * dMinor
        var x = x0
        var y = y0
        val b8 = value.toByte()
        for (step in 0L..dMajor) {
            if (inside(x, y)) mask.values[(y * w + x).toInt()] = b8
            if (err < 0) {
                err += 2 * dMajor - 2 * dMinor
                x += 1
                y += sy
            } else {
                err -= 2 * dMinor
                if (xMajor) x += 1 else y += sy
            }
        }
    }

    /** OpenCV `clipLine` (integer Cohen–Sutherland variant). Null when the segment misses the frame. */
    internal fun clipLine(width: Long, height: Long, ax: Long, ay: Long, bx: Long, by: Long): LongArray? {
        val right = width - 1
        val bottom = height - 1
        var x1 = ax
        var y1 = ay
        var x2 = bx
        var y2 = by
        val code: (Long, Long) -> Int = { x, y ->
            (if (x < 0) 1 else 0) + (if (x > right) 2 else 0) + (if (y < 0) 4 else 0) + (if (y > bottom) 8 else 0)
        }
        var c1 = code(x1, y1)
        var c2 = code(x2, y2)
        if ((c1 and c2) == 0 && (c1 or c2) != 0) {
            if ((c1 and 12) != 0) {
                val a = if (c1 < 8) 0L else bottom
                x1 += ((a - y1).toDouble() * (x2 - x1).toDouble() / (y2 - y1).toDouble()).toLong()
                y1 = a
                c1 = (if (x1 < 0) 1 else 0) + (if (x1 > right) 2 else 0)
            }
            if ((c2 and 12) != 0) {
                val a = if (c2 < 8) 0L else bottom
                x2 += ((a - y2).toDouble() * (x2 - x1).toDouble() / (y2 - y1).toDouble()).toLong()
                y2 = a
                c2 = (if (x2 < 0) 1 else 0) + (if (x2 > right) 2 else 0)
            }
            if ((c1 and c2) == 0 && (c1 or c2) != 0) {
                if (c1 != 0) {
                    val a = if (c1 == 1) 0L else right
                    y1 += ((a - x1).toDouble() * (y2 - y1).toDouble() / (x2 - x1).toDouble()).toLong()
                    x1 = a
                    c1 = 0
                }
                if (c2 != 0) {
                    val a = if (c2 == 1) 0L else right
                    y2 += ((a - x2).toDouble() * (y2 - y1).toDouble() / (x2 - x1).toDouble()).toLong()
                    x2 = a
                    c2 = 0
                }
            }
        }
        return if ((c1 or c2) == 0) longArrayOf(x1, y1, x2, y2) else null
    }

    // Helpers

    internal fun clamp(v: Double, lo: Double, hi: Double): Double = swiftMin(swiftMax(v, lo), hi)

    /** Swift's `min(a, b)` (`b < a ? b : a`). */
    private fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a

    /** Swift's `max(a, b)` (`b >= a ? b : a`). */
    private fun swiftMax(a: Double, b: Double): Double = if (b >= a) b else a

    /**
     * SciPy's `_minimize_scalar_bounded` (Brent's method with golden-section fallback, `xatol = 1e-5`,
     * `maxiter = 500`).
     */
    fun boundedBrent(f: (Double) -> Double, lower: Double, upper: Double,
                     xatol: Double = 1e-5, maxIter: Int = 500): BrentResult {
        val sqrtEps: Double = sqrt(2.220446049250313e-16)
        val goldenMean: Double = 0.5 * (3.0 - sqrt(5.0))
        var a = lower
        var b = upper
        var fulc = a + goldenMean * (b - a)
        var nfc = fulc
        var xf = fulc
        var rat = 0.0
        var e = 0.0
        var x = xf
        var fx = f(x)
        var num = 1
        var ffulc = fx
        var fnfc = fx
        var xm = 0.5 * (a + b)
        var tol1 = sqrtEps * abs(xf) + xatol / 3.0
        var tol2 = 2.0 * tol1
        var converged = true
        while (abs(xf - xm) > (tol2 - 0.5 * (b - a))) {
            var golden = true
            if (abs(e) > tol1) {
                golden = false
                var r = (xf - nfc) * (fx - ffulc)
                var q = (xf - fulc) * (fx - fnfc)
                var p = (xf - fulc) * q - (xf - nfc) * r
                q = 2.0 * (q - r)
                if (q > 0.0) p = -p
                q = abs(q)
                r = e
                e = rat
                if (abs(p) < abs(0.5 * q * r) && p > q * (a - xf) && p < q * (b - xf)) {
                    rat = (p + 0.0) / q
                    x = xf + rat
                    if ((x - a) < tol2 || (b - x) < tol2) {
                        val d = xm - xf
                        val si: Double = if (d > 0) 1.0 else (if (d < 0) -1.0 else 1.0)
                        rat = tol1 * si
                    }
                } else {
                    golden = true
                }
            }
            if (golden) {
                e = if (xf >= xm) a - xf else b - xf
                rat = goldenMean * e
            }
            val si: Double = if (rat > 0) 1.0 else (if (rat < 0) -1.0 else 1.0)
            x = xf + si * swiftMax(abs(rat), tol1)
            val fu = f(x)
            num += 1
            if (fu <= fx) {
                if (x >= xf) a = xf else b = xf
                fulc = nfc
                ffulc = fnfc
                nfc = xf
                fnfc = fx
                xf = x
                fx = fu
            } else {
                if (x < xf) a = x else b = x
                if (fu <= fnfc || nfc == xf) {
                    fulc = nfc
                    ffulc = fnfc
                    nfc = x
                    fnfc = fu
                } else if (fu <= ffulc || fulc == xf || fulc == nfc) {
                    fulc = x
                    ffulc = fu
                }
            }
            xm = 0.5 * (a + b)
            tol1 = sqrtEps * abs(xf) + xatol / 3.0
            tol2 = 2.0 * tol1
            if (num >= maxIter) {
                converged = false
                break
            }
        }
        return BrentResult(xf, converged)
    }
}
