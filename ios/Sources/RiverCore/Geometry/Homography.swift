import Foundation

/// Planar homography (oblique footage with the water surface treated as a plane), estimated from four or more
/// control points with the normalised DLT (Hartley): points are centred and scaled before solving, which keeps
/// the least-squares system well conditioned for pixel values in the thousands and UTM coordinates.
public enum Homography {
    /// Fits `H` such that `world ≈ H · pixel` (pixel → real world, same direction as desktop's
    /// `transformation_matrix`). Returned with `H[2,2] == 1`.
    public static func fit(pixel: [Point2], world: [Point2]) throws -> Matrix3 {
        guard pixel.count == world.count else { throw RiverCoreError.mismatchedPointCounts }
        guard pixel.count >= 4 else { throw RiverCoreError.notEnoughPoints(needed: 4, got: pixel.count) }
        // With exactly four points any three on a line make the solution undetermined; with more points
        // only a fully collinear set is.
        let degenerate: ([Point2]) -> Bool = { pixel.count == 4 ? minTriangleRatio($0) < 1e-6 : areCollinear($0) }
        guard !degenerate(pixel), !degenerate(world) else {
            throw RiverCoreError.degenerateGeometry("hay puntos alineados: no definen un plano")
        }

        let (tp, np) = try normalization(pixel)
        let (tw, nw) = try normalization(world)

        // Unknowns h11..h32 with h33 = 1 on normalised coordinates. Each point gives two equations.
        var rows: [[Double]] = []
        var rhs: [Double] = []
        rows.reserveCapacity(2 * np.count)
        for (p, w) in zip(np, nw) {
            rows.append([p.x, p.y, 1, 0, 0, 0, -w.x * p.x, -w.x * p.y])
            rhs.append(w.x)
            rows.append([0, 0, 0, p.x, p.y, 1, -w.y * p.x, -w.y * p.y])
            rhs.append(w.y)
        }
        let h = try LinearSolver.leastSquares(rows, rhs)
        let hn = Matrix3(rowMajor: h + [1])
        // Undo the normalisations: H = Tw⁻¹ · Hn · Tp
        return (try tw.inverse() * hn * tp).normalized()
    }

    /// Distance, in real-world units, between each control point's known coordinates and where `matrix`
    /// sends its pixel position (what the desktop Control Points step grades).
    public static func reprojectionErrors(matrix: Matrix3, pixel: [Point2], world: [Point2]) -> [Double] {
        zip(pixel, world).map { matrix.apply($0).distance(to: $1) }
    }

    /// Similarity that moves the centroid to the origin and the mean distance to √2.
    static func normalization(_ pts: [Point2]) throws -> (Matrix3, [Point2]) {
        let n = Double(pts.count)
        let cx = pts.reduce(0) { $0 + $1.x } / n
        let cy = pts.reduce(0) { $0 + $1.y } / n
        let meanDist = pts.reduce(0) { $0 + hypot($1.x - cx, $1.y - cy) } / n
        guard meanDist > 0 else { throw RiverCoreError.degenerateGeometry("todos los puntos coinciden") }
        let s = 2.0.squareRoot() / meanDist
        let t = Matrix3(rowMajor: [s, 0, -s * cx, 0, s, -s * cy, 0, 0, 1])
        return (t, pts.map { Point2(s * ($0.x - cx), s * ($0.y - cy)) })
    }

    /// True when every point lies (numerically) on one line, i.e. the set cannot define a homography.
    static func areCollinear(_ pts: [Point2]) -> Bool {
        triangleAreas(pts).map { $0.max } ?? 0 < 1e-6
    }

    /// Smallest triangle area among all point triples, relative to the squared extent of the set.
    static func minTriangleRatio(_ pts: [Point2]) -> Double {
        triangleAreas(pts)?.min ?? 0
    }

    /// (min, max) triangle area over all triples divided by the squared largest distance; nil if undefined.
    private static func triangleAreas(_ pts: [Point2]) -> (min: Double, max: Double)? {
        guard pts.count >= 3 else { return nil }
        var extent = 0.0
        for a in pts { for b in pts { extent = max(extent, a.distance(to: b)) } }
        guard extent > 0 else { return nil }
        var lo = Double.infinity, hi = 0.0
        for i in 0..<pts.count {
            for j in (i + 1)..<pts.count {
                for k in (j + 1)..<pts.count {
                    let u = pts[j] - pts[i], v = pts[k] - pts[i]
                    let area = abs(u.x * v.y - u.y * v.x) / 2 / (extent * extent)
                    lo = min(lo, area)
                    hi = max(hi, area)
                }
            }
        }
        return (lo, hi)
    }
}
