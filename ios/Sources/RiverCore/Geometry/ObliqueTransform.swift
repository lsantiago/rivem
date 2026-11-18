import Foundation

/// The six tape distances of the oblique setup, in metres: four sides and the two diagonals of the quadrilateral
/// 1-2-3-4 (desktop order `d12, d23, d34, d41, d13, d24`).
public struct ObliqueDistances: Equatable, Sendable {
    public var d12: Double
    public var d23: Double
    public var d34: Double
    public var d41: Double
    public var d13: Double
    public var d24: Double

    public init(d12: Double, d23: Double, d34: Double, d41: Double, d13: Double, d24: Double) {
        self.d12 = d12
        self.d23 = d23
        self.d34 = d34
        self.d41 = d41
        self.d13 = d13
        self.d24 = d24
    }

    /// From a list in desktop order (`ControlPointsSetup.distances`); nil unless it has exactly six values.
    public init?(_ values: [Double]) {
        guard values.count == 6 else { return nil }
        self.init(d12: values[0], d23: values[1], d34: values[2], d41: values[3], d13: values[4], d24: values[5])
    }

    /// Desktop order `d12, d23, d34, d41, d13, d24`.
    public var values: [Double] { [d12, d23, d34, d41, d13, d24] }
}

/// Result of the oblique (4 points + 6 distances) calibration.
public struct ObliqueSolution: Equatable, Sendable {
    /// Pixel → real-world matrix (same direction as `transformation_matrix.json`), scaled so `m[2,2] == 1`.
    /// Desktop writes `inv(findHomography(world, pixel))` without normalising; both describe the same mapping.
    public var matrix: Matrix3
    /// Real-world (East, North) coordinates assigned to points 1…4.
    public var world: [Point2]
    /// `[x, y, width, height]` in pixels of the rectangle framing the four points (`calculate_roi`), stored by
    /// desktop as `settings.transformation.roi`.
    public var roi: [Double]
    /// Measured minus solved distance for `d12, d23, d34, d41, d13, d24` (m). Large values mean the tape
    /// distances are inconsistent with a planar quadrilateral.
    public var distanceResiduals: [Double]
}

/// Oblique footage calibrated with four points on the water plane and the six distances between them. Port of
/// `oblique_view_transformation_matrix`, `optimize_coordinates` and `calculate_roi` in
/// `river/core/coordinate_transform.py` (RIVeR 3.8.3).
///
/// Points 1 and 2 define the frame: P1 = (0, 0), P2 = (d12, 0). P3 and P4 are found by least squares on the
/// other five distances, starting from desktop's initial guess `[d12/2, d12/2, d12/2, d12/2]` so the solver lands
/// on the same branch (P3 and P4 north of the 1→2 baseline). Desktop uses SciPy's BFGS on the summed squared
/// misfit; this port uses Levenberg–Marquardt on the same residuals, which converges to the same minimum for
/// ordinary (convex, non-degenerate) quadrilaterals. For self-intersecting or nearly degenerate inputs two
/// local minimisers can land on different branches.
///
/// **Fix over desktop:** desktop casts the real-world coordinates (and the anchor rotation) to `float32` before
/// `cv2.findHomography`, which loses up to ~0.5 m when anchors are given in UTM (values around 10⁶–10⁷). Here
/// everything stays in `Double`. Without anchors (local coordinates of a few tens of metres) the difference is
/// below 10⁻⁵ m.
public enum ObliqueTransform {
    /// Coordinates of P3 and P4 with P1 = (0, 0) and P2 = (d12, 0) (`optimize_coordinates`).
    public static func trilaterate(_ d: ObliqueDistances) -> (p3: Point2, p4: Point2) {
        let v: [Double] = levenbergMarquardt(d)
        return (Point2(v[0], v[1]), Point2(v[2], v[3]))
    }

    /// Full calibration.
    /// - Parameters:
    ///   - pixels: pixel positions of points 1…4 (frame coordinates, y down).
    ///   - distances: tape distances.
    ///   - anchors: optional real-world coordinates of points 1 and 2 (`east1, north1, east2, north2`). Desktop's
    ///     GUI passes them only when they are not the default (0,0)/(d12,0), together with `enforceD12` and a
    ///     tolerance of 1 m (`setControlPoints.ts`).
    ///   - enforceD12: when anchors are given, require |anchor2 − anchor1| ≈ d12 within `distanceTolerance`, and keep
    ///     using `d12` for the trilateration (desktop behaviour). When false, the anchor distance replaces d12.
    ///   - roiPadding: fraction added on each side of the ROI (GUI default 0).
    public static func solve(
        pixels: [Point2], distances: ObliqueDistances, anchors: (Point2, Point2)? = nil,
        enforceD12: Bool = true, distanceTolerance: Double = 1, roiPadding: Double = 0
    ) throws -> ObliqueSolution {
        guard pixels.count == 4 else { throw RiverCoreError.notEnoughPoints(needed: 4, got: pixels.count) }
        for value in distances.values {
            guard value.isFinite, value >= 0 else { throw RiverCoreError.degenerateGeometry("distancia no válida") }
        }
        guard distances.d12 > 0 else { throw RiverCoreError.degenerateGeometry("d12 debe ser mayor que cero") }

        var world: [Point2]
        if let anchorPair = anchors {
            let a1: Point2 = anchorPair.0
            let a2: Point2 = anchorPair.1
            let anchorD12: Double = a1.distance(to: a2)
            guard anchorD12 > 0 else { throw RiverCoreError.degenerateGeometry("los puntos 1 y 2 coinciden") }
            if enforceD12 && abs(anchorD12 - distances.d12) > distanceTolerance {
                throw RiverCoreError.degenerateGeometry(String(
                    format: "la distancia entre las anclas (%.6f m) difiere de d12 (%.6f m) en más de %.6f m",
                    anchorD12, distances.d12, distanceTolerance))
            }
            var canonical = distances
            if !enforceD12 { canonical.d12 = anchorD12 }
            let (c3, c4) = trilaterate(canonical)
            let theta: Double = atan2(a2.y - a1.y, a2.x - a1.x)
            let c = cos(theta), s = sin(theta)
            let rotate: (Point2) -> Point2 = { p in Point2(c * p.x - s * p.y + a1.x, s * p.x + c * p.y + a1.y) }
            world = [a1, a2, rotate(c3), rotate(c4)]
        } else {
            let (p3, p4) = trilaterate(distances)
            world = [Point2(0, 0), Point2(distances.d12, 0), p3, p4]
        }

        let matrix: Matrix3 = try Homography.fit(pixel: pixels, world: world)
        let pairs: [(Int, Int)] = [(0, 1), (1, 2), (2, 3), (3, 0), (0, 2), (1, 3)]
        var residuals: [Double] = []
        let measured: [Double] = distances.values
        for (k, pair) in pairs.enumerated() {
            residuals.append(measured[k] - world[pair.0].distance(to: world[pair.1]))
        }
        return ObliqueSolution(matrix: matrix, world: world, roi: roi(pixels: pixels, padding: roiPadding),
                               distanceResiduals: residuals)
    }

    /// `calculate_roi`: `[x_min, y_min, width, height]` of the rectangle framing the points, optionally padded by
    /// `padding` × size on each side.
    public static func roi(pixels: [Point2], padding: Double = 0) -> [Double] {
        guard let first = pixels.first else { return [0, 0, 0, 0] }
        var xMin = first.x, xMax = first.x, yMin = first.y, yMax = first.y
        for p in pixels {
            xMin = min(xMin, p.x)
            xMax = max(xMax, p.x)
            yMin = min(yMin, p.y)
            yMax = max(yMax, p.y)
        }
        var width = xMax - xMin
        var height = yMax - yMin
        if padding > 0 {
            let px = width * padding, py = height * padding
            xMin -= px
            yMin -= py
            width += 2 * px
            height += 2 * py
        }
        return [xMin, yMin, width, height]
    }

    // MARK: - Least squares

    /// Residuals (solved − measured, order d13, d23, d34, d41, d24) and their 5×4 Jacobian for
    /// v = (e3, n3, e4, n4). A residual whose two points coincide gets a zero Jacobian row (the direction is
    /// undefined; it happens at the initial guess, where P3 == P4).
    private static func residuals(_ v: [Double], _ d: ObliqueDistances) -> ([Double], [[Double]]) {
        let p1 = Point2(0, 0), p2 = Point2(d.d12, 0)
        let p3 = Point2(v[0], v[1]), p4 = Point2(v[2], v[3])
        // (from, to, index of `from` among unknowns or -1, index of `to`, target)
        let terms: [(Point2, Point2, Int, Int, Double)] = [
            (p1, p3, -1, 0, d.d13), (p2, p3, -1, 0, d.d23), (p3, p4, 0, 1, d.d34),
            (p1, p4, -1, 1, d.d41), (p2, p4, -1, 1, d.d24),
        ]
        var r: [Double] = []
        var jac: [[Double]] = []
        for term in terms {
            let dx = term.1.x - term.0.x, dy = term.1.y - term.0.y
            let n: Double = hypot(dx, dy)
            r.append(n - term.4)
            var row = [Double](repeating: 0, count: 4)
            if n > 1e-12 * max(1, term.4) {
                let ux = dx / n, uy = dy / n
                row[2 * term.3] += ux
                row[2 * term.3 + 1] += uy
                if term.2 >= 0 {
                    row[2 * term.2] -= ux
                    row[2 * term.2 + 1] -= uy
                }
            }
            jac.append(row)
        }
        return (r, jac)
    }

    private static func levenbergMarquardt(_ d: ObliqueDistances) -> [Double] {
        var v = [Double](repeating: d.d12 / 2, count: 4)
        var (r, jac) = residuals(v, d)
        var cost: Double = r.reduce(0) { $0 + $1 * $1 }
        var lambda = 1e-3
        for _ in 0..<500 {
            var a = [[Double]](repeating: [Double](repeating: 0, count: 4), count: 4)
            var g = [Double](repeating: 0, count: 4)
            for k in 0..<r.count {
                for i in 0..<4 {
                    g[i] += jac[k][i] * r[k]
                    for j in 0..<4 { a[i][j] += jac[k][i] * jac[k][j] }
                }
            }
            if g.allSatisfy({ abs($0) < 1e-15 * max(1, d.d12) }) { break }
            var damped = a
            for i in 0..<4 { damped[i][i] += lambda * max(a[i][i], 1e-12) }
            let step: [Double]
            do {
                step = try LinearSolver.solve(damped, g.map { -$0 })
            } catch {
                lambda *= 10
                if lambda > 1e16 { break }
                continue
            }
            var candidate = v
            for i in 0..<4 { candidate[i] += step[i] }
            let (rn, jn) = residuals(candidate, d)
            let cn: Double = rn.reduce(0) { $0 + $1 * $1 }
            if cn < cost {
                let scale: Double = max(1, v.map { abs($0) }.max() ?? 1)
                let small: Bool = step.allSatisfy { abs($0) < 1e-13 * scale }
                v = candidate
                r = rn
                jac = jn
                cost = cn
                lambda = max(lambda / 10, 1e-15)
                if small { break }
            } else {
                lambda *= 10
                if lambda > 1e16 { break }
            }
        }
        return v
    }
}
