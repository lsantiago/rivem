import Foundation

/// Piecewise-linear interpolation over the Delaunay triangulation of scattered points: the port of
/// `scipy.interpolate.griddata(points, values, xi, method="linear")` that RIVeR uses to sample the PIV field at
/// the stations (`compute_section.py:609-634`).
///
/// - The triangulation is an incremental Bowyer-Watson in normalised coordinates (centred, scaled by the largest
///   extent). For points in general position the Delaunay triangulation is unique, so the result is the same as
///   Qhull's: on RIVeR's `pisco` example (638 PIV nodes, oblique matrix) both produce the same 1140 triangles and
///   the interpolated values agree to 1e-16 (checked with scipy 1.11 outside this repository).
/// - Degenerate (cocircular) cells, e.g. a regular grid in pixel space or a pure similarity transform, have more
///   than one Delaunay triangulation; Qhull's choice of diagonal cannot be reproduced and values inside such a
///   cell may differ from desktop's unless the field is linear there. RIVeR's discharge only samples the
///   real-world field (non-degenerate for oblique views); the pixel-space `displacement_x/y` keys are display
///   only.
/// - As in scipy, a query outside the convex hull gives NaN and a NaN value at any vertex of the containing
///   triangle gives NaN.
///
/// The weights depend only on the node and query positions, so one `SectionLinearInterpolator` serves every
/// frame pair of a PIV result.
public struct SectionLinearInterpolator: Sendable {
    /// For each query: the three node indices and barycentric weights, or nil when outside the hull.
    public let vertices: [(Int, Int, Int)?]
    public let weights: [(Double, Double, Double)?]

    public init(nodes: [Point2], queries: [Point2]) {
        let tri = SectionDelaunay(points: nodes)
        var v: [(Int, Int, Int)?] = []
        var w: [(Double, Double, Double)?] = []
        v.reserveCapacity(queries.count)
        w.reserveCapacity(queries.count)
        for q in queries {
            if let found = tri.locate(q) {
                v.append(found.0)
                w.append(found.1)
            } else {
                v.append(nil)
                w.append(nil)
            }
        }
        self.vertices = v
        self.weights = w
    }

    /// Interpolates `values` (one per node) at every query.
    public func interpolate(_ values: [Double]) -> [Double] {
        var out = [Double](repeating: .nan, count: vertices.count)
        for i in 0..<vertices.count {
            guard let t = vertices[i], let l = weights[i] else { continue }
            let a: Double = l.0 * values[t.0]
            let b: Double = l.1 * values[t.1]
            let c: Double = l.2 * values[t.2]
            out[i] = a + b + c
        }
        return out
    }
}

/// Bowyer-Watson Delaunay triangulation (see `SectionLinearInterpolator`).
struct SectionDelaunay {
    struct Triangle {
        var a: Int
        var b: Int
        var c: Int
    }

    private(set) var xs: [Double] = []
    private(set) var ys: [Double] = []
    private(set) var triangles: [Triangle] = []
    private let cx: Double
    private let cy: Double
    private let span: Double

    init(points: [Point2]) {
        let n = points.count
        var minX = Double.infinity, maxX = -Double.infinity, minY = Double.infinity, maxY = -Double.infinity
        var sx = 0.0, sy = 0.0
        for p in points {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
            sx += p.x
            sy += p.y
        }
        // Locals first: closures must not capture `self` before every stored property is set.
        let centreX: Double = n > 0 ? sx / Double(n) : 0
        let centreY: Double = n > 0 ? sy / Double(n) : 0
        var extent: Double = max(maxX - minX, maxY - minY)
        if !(extent > 0) || !extent.isFinite { extent = 1 }
        let scale: Double = extent
        cx = centreX
        cy = centreY
        span = scale
        var xs: [Double] = points.map { ($0.x - centreX) / scale }
        var ys: [Double] = points.map { ($0.y - centreY) / scale }
        guard n >= 3 else {
            self.xs = xs
            self.ys = ys
            return
        }
        let big = 100.0
        xs.append(-big); ys.append(-big)
        xs.append(big); ys.append(-big)
        xs.append(0); ys.append(big)
        var tris: [Triangle] = [Triangle(a: n, b: n + 1, c: n + 2)]

        // Deterministic shuffle of the insertion order (a randomised order keeps the cavities small).
        var order: [Int] = Array(0..<n)
        var seed: Int = 12345
        var i = n - 1
        while i > 0 {
            seed = (seed &* 1103515245 &+ 12345) % 2147483648
            if seed < 0 { seed += 2147483648 }
            let j = seed % (i + 1)
            order.swapAt(i, j)
            i -= 1
        }

        let keyBase = n + 3
        for p in order {
            let x = xs[p], y = ys[p]
            guard x.isFinite, y.isFinite else { continue }
            var bad = [Bool](repeating: false, count: tris.count)
            var start = -1
            for k in 0..<tris.count {
                let t = tris[k]
                bad[k] = Self.incircle(xs[t.a], ys[t.a], xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) > 0
                if start < 0,
                   Self.orient(xs[t.a], ys[t.a], xs[t.b], ys[t.b], x, y) >= 0,
                   Self.orient(xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) >= 0,
                   Self.orient(xs[t.c], ys[t.c], xs[t.a], ys[t.a], x, y) >= 0 {
                    start = k
                }
            }
            if start < 0 { continue }
            bad[start] = true

            // Connected component of "bad" triangles that contains the point (keeps the cavity valid under
            // rounding noise).
            var owners: [Int: [Int]] = [:]
            for k in 0..<tris.count where bad[k] {
                let t = tris[k]
                for e in [(t.a, t.b), (t.b, t.c), (t.c, t.a)] {
                    let key = min(e.0, e.1) * keyBase + max(e.0, e.1)
                    owners[key, default: []].append(k)
                }
            }
            var inside = Set<Int>([start])
            var stack: [Int] = [start]
            while let k = stack.popLast() {
                let t = tris[k]
                for e in [(t.a, t.b), (t.b, t.c), (t.c, t.a)] {
                    let key = min(e.0, e.1) * keyBase + max(e.0, e.1)
                    for k2 in owners[key] ?? [] where !inside.contains(k2) {
                        inside.insert(k2)
                        stack.append(k2)
                    }
                }
            }
            var edgeCount: [Int: Int] = [:]
            for k in inside {
                let t = tris[k]
                for e in [(t.a, t.b), (t.b, t.c), (t.c, t.a)] {
                    let key = min(e.0, e.1) * keyBase + max(e.0, e.1)
                    edgeCount[key, default: 0] += 1
                }
            }
            var next: [Triangle] = []
            next.reserveCapacity(tris.count + 2)
            for k in 0..<tris.count where !inside.contains(k) { next.append(tris[k]) }
            for k in inside.sorted() {
                let t = tris[k]
                for e in [(t.a, t.b), (t.b, t.c), (t.c, t.a)] {
                    let key = min(e.0, e.1) * keyBase + max(e.0, e.1)
                    if edgeCount[key] == 1 { next.append(Triangle(a: e.0, b: e.1, c: p)) }
                }
            }
            tris = next
        }
        self.triangles = tris.filter { $0.a < n && $0.b < n && $0.c < n }
        self.xs = xs
        self.ys = ys
    }

    /// Twice the signed area of (a, b, c); > 0 when counter-clockwise.
    static func orient(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double, _ cx: Double, _ cy: Double) -> Double {
        let l: Double = (bx - ax) * (cy - ay)
        let r: Double = (by - ay) * (cx - ax)
        return l - r
    }

    /// > 0 when d lies inside the circumcircle of the counter-clockwise triangle (a, b, c).
    static func incircle(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double, _ cx: Double, _ cy: Double,
                         _ dx: Double, _ dy: Double) -> Double {
        let adx = ax - dx, ady = ay - dy
        let bdx = bx - dx, bdy = by - dy
        let cdx = cx - dx, cdy = cy - dy
        let ad: Double = adx * adx + ady * ady
        let bd: Double = bdx * bdx + bdy * bdy
        let cd: Double = cdx * cdx + cdy * cdy
        let t1: Double = adx * (bdy * cd - bd * cdy)
        let t2: Double = ady * (bdx * cd - bd * cdx)
        let t3: Double = ad * (bdx * cdy - bdy * cdx)
        return t1 - t2 + t3
    }

    /// Containing triangle and barycentric weights of `q` (original coordinates), nil outside the hull.
    func locate(_ q: Point2) -> ((Int, Int, Int), (Double, Double, Double))? {
        let x = (q.x - cx) / span
        let y = (q.y - cy) / span
        guard x.isFinite, y.isFinite else { return nil }
        let eps = 1e-12
        for t in triangles {
            let det = Self.orient(xs[t.a], ys[t.a], xs[t.b], ys[t.b], xs[t.c], ys[t.c])
            if det == 0 { continue }
            let l1 = Self.orient(xs[t.b], ys[t.b], xs[t.c], ys[t.c], x, y) / det
            let l2 = Self.orient(xs[t.c], ys[t.c], xs[t.a], ys[t.a], x, y) / det
            let l3 = 1.0 - l1 - l2
            if l1 >= -eps && l2 >= -eps && l3 >= -eps {
                return ((t.a, t.b, t.c), (l1, l2, l3))
            }
        }
        return nil
    }
}
