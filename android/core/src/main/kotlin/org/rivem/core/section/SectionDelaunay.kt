package org.rivem.core.section

import org.rivem.core.geometry.Point2
import kotlin.math.max
import kotlin.math.min

/**
 * Piecewise-linear interpolation over the Delaunay triangulation of scattered points: the port of
 * `scipy.interpolate.griddata(points, values, xi, method="linear")` that RIVeR uses to sample the PIV field at
 * the stations (`compute_section.py:609-634`).
 *
 * - The triangulation is an incremental Bowyer-Watson in normalised coordinates (centred, scaled by the largest
 *   extent). For points in general position the Delaunay triangulation is unique, so the result is the same as
 *   Qhull's: on RIVeR's `pisco` example (638 PIV nodes, oblique matrix) both produce the same 1140 triangles and
 *   the interpolated values agree to 1e-16 (checked with scipy 1.11 outside this repository).
 * - Degenerate (cocircular) cells, e.g. a regular grid in pixel space or a pure similarity transform, have more
 *   than one Delaunay triangulation; Qhull's choice of diagonal cannot be reproduced and values inside such a
 *   cell may differ from desktop's unless the field is linear there. RIVeR's discharge only samples the
 *   real-world field (non-degenerate for oblique views); the pixel-space `displacement_x/y` keys are display
 *   only.
 * - As in scipy, a query outside the convex hull gives NaN and a NaN value at any vertex of the containing
 *   triangle gives NaN.
 *
 * The weights depend only on the node and query positions, so one `SectionLinearInterpolator` serves every
 * frame pair of a PIV result.
 */
class SectionLinearInterpolator(nodes: List<Point2>, queries: List<Point2>) {
    /** For each query: the three node indices and barycentric weights, or null when outside the hull. */
    val vertices: List<Triple<Int, Int, Int>?>
    val weights: List<Triple<Double, Double, Double>?>

    init {
        val tri = SectionDelaunay(nodes)
        val v = ArrayList<Triple<Int, Int, Int>?>(queries.size)
        val w = ArrayList<Triple<Double, Double, Double>?>(queries.size)
        for (q in queries) {
            val found = tri.locate(q)
            if (found != null) {
                v.add(found.first)
                w.add(found.second)
            } else {
                v.add(null)
                w.add(null)
            }
        }
        vertices = v
        weights = w
    }

    /** Interpolates `values` (one per node) at every query. */
    fun interpolate(values: List<Double>): List<Double> {
        val out = MutableList(vertices.size) { Double.NaN }
        for (i in vertices.indices) {
            val t = vertices[i] ?: continue
            val l = weights[i] ?: continue
            val a: Double = l.first * values[t.first]
            val b: Double = l.second * values[t.second]
            val c: Double = l.third * values[t.third]
            out[i] = a + b + c
        }
        return out
    }
}

/** Bowyer-Watson Delaunay triangulation (see `SectionLinearInterpolator`). */
internal class SectionDelaunay(points: List<Point2>) {
    data class Triangle(val a: Int, val b: Int, val c: Int)

    var xs: List<Double> = emptyList()
        private set
    var ys: List<Double> = emptyList()
        private set
    var triangles: List<Triangle> = emptyList()
        private set
    private val cx: Double
    private val cy: Double
    private val span: Double

    init {
        val n = points.size
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var sx = 0.0
        var sy = 0.0
        for (p in points) {
            minX = swiftMin(minX, p.x); maxX = swiftMax(maxX, p.x)
            minY = swiftMin(minY, p.y); maxY = swiftMax(maxY, p.y)
            sx += p.x
            sy += p.y
        }
        val centreX: Double = if (n > 0) sx / n.toDouble() else 0.0
        val centreY: Double = if (n > 0) sy / n.toDouble() else 0.0
        var extent: Double = swiftMax(maxX - minX, maxY - minY)
        if (!(extent > 0) || !extent.isFinite()) extent = 1.0
        val scale: Double = extent
        cx = centreX
        cy = centreY
        span = scale
        val xs = ArrayList(points.map { (it.x - centreX) / scale })
        val ys = ArrayList(points.map { (it.y - centreY) / scale })
        if (n < 3) {
            this.xs = xs
            this.ys = ys
        } else {
            val big = 100.0
            xs.add(-big); ys.add(-big)
            xs.add(big); ys.add(-big)
            xs.add(0.0); ys.add(big)
            var tris = ArrayList<Triangle>()
            tris.add(Triangle(n, n + 1, n + 2))

            // Deterministic shuffle of the insertion order (a randomised order keeps the cavities small).
            val order = IntArray(n) { it }
            var seed: Long = 12345
            var i = n - 1
            while (i > 0) {
                seed = (seed * 1103515245L + 12345L) % 2147483648L
                if (seed < 0) seed += 2147483648L
                val j = (seed % (i + 1).toLong()).toInt()
                val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                i -= 1
            }

            val keyBase = (n + 3).toLong()
            fun key(e0: Int, e1: Int): Long = min(e0, e1).toLong() * keyBase + max(e0, e1).toLong()
            for (p in order) {
                val x = xs[p]
                val y = ys[p]
                if (!x.isFinite() || !y.isFinite()) continue
                val bad = BooleanArray(tris.size)
                var start = -1
                for (k in tris.indices) {
                    val t = tris[k]
                    bad[k] = incircle(xs[t.a], ys[t.a], xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) > 0
                    if (start < 0 &&
                        orient(xs[t.a], ys[t.a], xs[t.b], ys[t.b], x, y) >= 0 &&
                        orient(xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) >= 0 &&
                        orient(xs[t.c], ys[t.c], xs[t.a], ys[t.a], x, y) >= 0
                    ) {
                        start = k
                    }
                }
                if (start < 0) continue
                bad[start] = true

                // Connected component of "bad" triangles that contains the point (keeps the cavity valid under
                // rounding noise).
                val owners = HashMap<Long, ArrayList<Int>>()
                for (k in tris.indices) {
                    if (!bad[k]) continue
                    val t = tris[k]
                    owners.getOrPut(key(t.a, t.b)) { ArrayList() }.add(k)
                    owners.getOrPut(key(t.b, t.c)) { ArrayList() }.add(k)
                    owners.getOrPut(key(t.c, t.a)) { ArrayList() }.add(k)
                }
                val inside = HashSet<Int>()
                inside.add(start)
                val stack = ArrayList<Int>()
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val k = stack.removeAt(stack.size - 1)
                    val t = tris[k]
                    for (e in arrayOf(key(t.a, t.b), key(t.b, t.c), key(t.c, t.a))) {
                        for (k2 in owners[e] ?: emptyList<Int>()) {
                            if (inside.contains(k2)) continue
                            inside.add(k2)
                            stack.add(k2)
                        }
                    }
                }
                val edgeCount = HashMap<Long, Int>()
                for (k in inside) {
                    val t = tris[k]
                    for (e in arrayOf(key(t.a, t.b), key(t.b, t.c), key(t.c, t.a))) {
                        edgeCount[e] = (edgeCount[e] ?: 0) + 1
                    }
                }
                val next = ArrayList<Triangle>(tris.size + 2)
                for (k in tris.indices) if (!inside.contains(k)) next.add(tris[k])
                for (k in inside.sorted()) {
                    val t = tris[k]
                    for ((e0, e1) in arrayOf(Pair(t.a, t.b), Pair(t.b, t.c), Pair(t.c, t.a))) {
                        if (edgeCount[key(e0, e1)] == 1) next.add(Triangle(e0, e1, p))
                    }
                }
                tris = next
            }
            this.triangles = tris.filter { it.a < n && it.b < n && it.c < n }
            this.xs = xs
            this.ys = ys
        }
    }

    companion object {
        /** Twice the signed area of (a, b, c); > 0 when counter-clockwise. */
        fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            val l: Double = (bx - ax) * (cy - ay)
            val r: Double = (by - ay) * (cx - ax)
            return l - r
        }

        /** > 0 when d lies inside the circumcircle of the counter-clockwise triangle (a, b, c). */
        fun incircle(
            ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
            dx: Double, dy: Double,
        ): Double {
            val adx = ax - dx
            val ady = ay - dy
            val bdx = bx - dx
            val bdy = by - dy
            val cdx = cx - dx
            val cdy = cy - dy
            val ad: Double = adx * adx + ady * ady
            val bd: Double = bdx * bdx + bdy * bdy
            val cd: Double = cdx * cdx + cdy * cdy
            val t1: Double = adx * (bdy * cd - bd * cdy)
            val t2: Double = ady * (bdx * cd - bd * cdx)
            val t3: Double = ad * (bdx * cdy - bdy * cdx)
            return t1 - t2 + t3
        }

        /** Swift `min(a, b)`: `b < a ? b : a` (NaN handling differs from `Math.min`). */
        private fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a

        /** Swift `max(a, b)`: `b >= a ? b : a`. */
        private fun swiftMax(a: Double, b: Double): Double = if (b >= a) b else a
    }

    /** Containing triangle and barycentric weights of `q` (original coordinates), null outside the hull. */
    fun locate(q: Point2): Pair<Triple<Int, Int, Int>, Triple<Double, Double, Double>>? {
        val x = (q.x - cx) / span
        val y = (q.y - cy) / span
        if (!x.isFinite() || !y.isFinite()) return null
        val eps = 1e-12
        for (t in triangles) {
            val det = orient(xs[t.a], ys[t.a], xs[t.b], ys[t.b], xs[t.c], ys[t.c])
            if (det == 0.0) continue
            val l1 = orient(xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) / det
            val l2 = orient(xs[t.c], ys[t.c], xs[t.a], ys[t.a], x, y) / det
            val l3 = 1.0 - l1 - l2
            if (l1 >= -eps && l2 >= -eps && l3 >= -eps) {
                return Pair(Triple(t.a, t.b, t.c), Triple(l1, l2, l3))
            }
        }
        return null
    }
}
