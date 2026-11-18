import Foundation

/// A 3-D ground reference point of a fixed-station ("ipcam") setup: real-world X (East), Y (North), Z (elevation,
/// up) in metres and its pixel position (x right, y down). One entry of desktop's `grp_3d.json` lists.
public struct GroundPoint3D: Equatable, Sendable {
    public var X: Double
    public var Y: Double
    public var Z: Double
    public var pixel: Point2

    public init(X: Double, Y: Double, Z: Double, x: Double, y: Double) {
        self.X = X
        self.Y = Y
        self.Z = Z
        self.pixel = Point2(x, y)
    }

    /// Desktop marks a point not placed on the image with pixel (0, 0) (`parseGrp3dPoints.ts`).
    public var isMarked: Bool { !(pixel.x == 0 && pixel.y == 0) }

    /// Parallel lists `X, Y, Z, x, y` as in `grp_3d.json` / `full_grp_3d.json`.
    public static func fromLists(X: [Double], Y: [Double], Z: [Double], x: [Double], y: [Double]) throws -> [GroundPoint3D] {
        let n = X.count
        guard Y.count == n, Z.count == n, x.count == n, y.count == n else { throw RiverCoreError.mismatchedPointCounts }
        var out: [GroundPoint3D] = []
        out.reserveCapacity(n)
        for i in 0..<n { out.append(GroundPoint3D(X: X[i], Y: Y[i], Z: Z[i], x: x[i], y: y[i])) }
        return out
    }
}

/// 3×4 pinhole camera matrix P (row-major), `[x, y, w]ᵀ = P · [X, Y, Z, 1]ᵀ`. Same layout as
/// `camera_solution_3d.json` → `camera_matrix`.
public struct CameraMatrix: Equatable, Sendable {
    public var p: [Double]

    public init(rowMajor p: [Double]) {
        precondition(p.count == 12, "CameraMatrix needs 12 values")
        self.p = p
    }

    public init(rows: [[Double]]) {
        precondition(rows.count == 3 && rows.allSatisfy { $0.count == 4 }, "CameraMatrix needs 3 rows of 4 values")
        self.p = rows.flatMap { $0 }
    }

    public var rows: [[Double]] { [Array(p[0..<4]), Array(p[4..<8]), Array(p[8..<12])] }

    public subscript(row: Int, col: Int) -> Double { p[row * 4 + col] }

    /// Pixel position of a world point (`project_points`).
    public func project(X: Double, Y: Double, Z: Double) -> Point2 {
        let u = p[0] * X + p[1] * Y + p[2] * Z + p[3]
        let v = p[4] * X + p[5] * Y + p[6] * Z + p[7]
        let w = p[8] * X + p[9] * Y + p[10] * Z + p[11]
        return Point2(u / w, v / w)
    }

    /// Camera centre C = −M⁻¹·p4 with M the left 3×3 block (`get_camera_center`), as [X, Y, Z].
    public func center() throws -> [Double] {
        let m = Matrix3(rowMajor: [p[0], p[1], p[2], p[4], p[5], p[6], p[8], p[9], p[10]])
        let inv = try m.inverse()
        let p4: [Double] = [p[3], p[7], p[11]]
        var c = [Double](repeating: 0, count: 3)
        for i in 0..<3 { c[i] = -(inv[i, 0] * p4[0] + inv[i, 1] * p4[1] + inv[i, 2] * p4[2]) }
        return c
    }

    /// World → pixel homography of the horizontal plane Z = `z`:
    /// `H = [[P00, P01, z·P02 + P03], [P10, P11, z·P12 + P13], [P20, P21, z·P22 + P23]]`.
    public func levelHomography(z: Double) -> Matrix3 {
        Matrix3(rowMajor: [
            p[0], p[1], z * p[2] + p[3],
            p[4], p[5], z * p[6] + p[7],
            p[8], p[9], z * p[10] + p[11],
        ])
    }

    /// Pixel → world matrix of the water surface at elevation `z`: `inv(H(z))`, exactly what the desktop GUI writes to
    /// `transformation_matrix.json` in ipcam mode (`getTransformationFromCameraMatrix`, `gui/commons/coordinates.ts`;
    /// cofactor inverse, not normalised).
    public func pixelToWorld(z: Double) throws -> Matrix3 {
        try levelHomography(z: z).inverse()
    }
}

/// Error ellipse of one control point (`calculate_uncertainty_ellipses`).
public struct UncertaintyEllipse: Equatable, Sendable {
    public var center: Point2
    public var width: Double
    public var height: Double
    /// Degrees, direction of the minor axis eigenvector. The eigenvector sign is arbitrary (LAPACK's choice in
    /// desktop), so compare angles modulo 180°.
    public var angle: Double
}

/// What desktop stores in `camera_solution_3d.json` (minus the ortho image fields).
public struct CameraSolution: Equatable, Sendable {
    public var camera: CameraMatrix
    public var cameraPosition: [Double]
    /// Mean reprojection error in pixels (`error`).
    public var meanError: Double
    public var reprojectionErrors: [Double]
    public var projectedPoints: [Point2]
    public var uncertaintyEllipses: [UncertaintyEllipse]
}

/// Fixed-station ("ipcam") 3-D rectification: the 11-parameter DLT camera matrix from ≥ 6 ground reference points.
/// Port of `solve_c_matrix`, `project_points`, `get_camera_center`, `calculate_uncertainty_ellipses` and the
/// "direct-solve" branch of `get_camera_solution` in `river/core/coordinate_transform.py` (RIVeR 3.8.3).
///
/// Not ported: the "optimize-solution" random subset search (`optimize_points_comprehensive`). Callers can pass a
/// subset to `solve(points:evaluationPoints:)` themselves.
///
/// **Numerical note (deviation in method, not in objective):** desktop minimises ‖A·p‖ with ‖p‖ = 1 on the raw
/// coordinates (UTM values ~10⁶ next to the constant 1) with LAPACK's SVD. That matrix is extremely badly scaled
/// (singular values from ~10¹⁰ down to ~10⁻⁷ on `examples/data/grps/full_grp_3d.json`), so the LAPACK answer moves by
/// ~0.02 px of mean reprojection error under 10⁻¹⁵ relative perturbations of the input. This port minimises the same
/// objective with a one-sided Jacobi SVD, which is accurate for column-scaled matrices; results agree with desktop
/// within that noise (golden tolerances: mean error 0.1 px, world positions ~5 cm). The overall sign of P is fixed
/// so that the points have positive homogeneous depth `w` on average (desktop's sign is whatever LAPACK returns;
/// P and −P are the same camera).
public enum CameraCalibration {
    /// DLT camera matrix from ≥ 6 points (`solve_c_matrix`).
    public static func solveCameraMatrix(points: [GroundPoint3D]) throws -> CameraMatrix {
        guard points.count >= 6 else { throw RiverCoreError.notEnoughPoints(needed: 6, got: points.count) }
        var rows: [[Double]] = []
        rows.reserveCapacity(2 * points.count)
        for g in points {
            let x = g.pixel.x, y = g.pixel.y
            rows.append([g.X, g.Y, g.Z, 1, 0, 0, 0, 0, -x * g.X, -x * g.Y, -x * g.Z, -x])
            rows.append([0, 0, 0, 0, g.X, g.Y, g.Z, 1, -y * g.X, -y * g.Y, -y * g.Z, -y])
        }
        var v: [Double] = try JacobiSVD.smallestRightSingularVector(rows)
        var meanW = 0.0
        for g in points { meanW += v[8] * g.X + v[9] * g.Y + v[10] * g.Z + v[11] }
        if meanW < 0 { v = v.map { -$0 } }
        let camera = CameraMatrix(rowMajor: v)
        guard (try? camera.center()) != nil else {
            throw RiverCoreError.degenerateGeometry("los puntos no definen una cámara (¿todos en un plano?)")
        }
        return camera
    }

    /// Reprojection errors (px) of `points` under `camera`.
    public static func reprojectionErrors(camera: CameraMatrix, points: [GroundPoint3D]) -> [Double] {
        points.map { camera.project(X: $0.X, Y: $0.Y, Z: $0.Z).distance(to: $0.pixel) }
    }

    /// "direct-solve": solve with `points`, evaluate errors and ellipses on `evaluationPoints` (desktop's
    /// `full_grp_dict`, all marked points) or on `points` when nil.
    public static func solve(points: [GroundPoint3D], evaluationPoints: [GroundPoint3D]? = nil,
                             confidence: Double = 0.95) throws -> CameraSolution {
        let camera = try solveCameraMatrix(points: points)
        let eval: [GroundPoint3D] = evaluationPoints ?? points
        let projected: [Point2] = eval.map { camera.project(X: $0.X, Y: $0.Y, Z: $0.Z) }
        let actual: [Point2] = eval.map { $0.pixel }
        var errors: [Double] = []
        for i in 0..<eval.count { errors.append(projected[i].distance(to: actual[i])) }
        let mean: Double = errors.isEmpty ? 0 : errors.reduce(0, +) / Double(errors.count)
        let position: [Double] = try camera.center()
        return CameraSolution(
            camera: camera, cameraPosition: position, meanError: mean, reprojectionErrors: errors,
            projectedPoints: projected,
            uncertaintyEllipses: uncertaintyEllipses(actual: actual, projected: projected, confidence: confidence))
    }

    /// `calculate_uncertainty_ellipses`: for each point, covariance (ddof 1) of the errors of points i−2…i+2,
    /// scaled by the χ²(2) quantile; width from the smaller eigenvalue, height from the larger one.
    public static func uncertaintyEllipses(actual: [Point2], projected: [Point2], confidence: Double = 0.95) -> [UncertaintyEllipse] {
        // χ² with 2 degrees of freedom has the closed-form quantile −2·ln(1 − p).
        let chi2: Double = -2 * log(1 - confidence)
        let n = min(actual.count, projected.count)
        var out: [UncertaintyEllipse] = []
        for i in 0..<n {
            let lo = max(0, i - 2), hi = min(n, i + 3)
            var ex: [Double] = [], ey: [Double] = []
            for k in lo..<hi {
                ex.append(projected[k].x - actual[k].x)
                ey.append(projected[k].y - actual[k].y)
            }
            let m = Double(ex.count)
            let mx = ex.reduce(0, +) / m, my = ey.reduce(0, +) / m
            var sxx = 0.0, syy = 0.0, sxy = 0.0
            for k in 0..<ex.count {
                sxx += (ex[k] - mx) * (ex[k] - mx)
                syy += (ey[k] - my) * (ey[k] - my)
                sxy += (ex[k] - mx) * (ey[k] - my)
            }
            let denom: Double = m > 1 ? m - 1 : .nan
            sxx /= denom
            syy /= denom
            sxy /= denom
            let half = (sxx + syy) / 2
            let rad: Double = hypot((sxx - syy) / 2, sxy)
            let l1 = half - rad, l2 = half + rad
            // Eigenvector of the smaller eigenvalue.
            var vx: Double, vy: Double
            if sxy == 0 {
                if sxx <= syy { vx = 1; vy = 0 } else { vx = 0; vy = 1 }
            } else {
                let ax = sxy, ay = l1 - sxx
                let bx = l1 - syy, by = sxy
                if hypot(ax, ay) >= hypot(bx, by) { vx = ax; vy = ay } else { vx = bx; vy = by }
            }
            let angle: Double = atan2(vy, vx) * 180 / Double.pi
            out.append(UncertaintyEllipse(center: actual[i], width: 2 * (chi2 * abs(l1)).squareRoot(),
                                          height: 2 * (chi2 * abs(l2)).squareRoot(), angle: angle))
        }
        return out
    }
}

/// One-sided Jacobi SVD (Hestenes), enough to get the right singular vector of the smallest singular value of a tall
/// matrix. Accurate for badly column-scaled matrices, no Accelerate.
enum JacobiSVD {
    static func smallestRightSingularVector(_ a: [[Double]]) throws -> [Double] {
        guard let n = a.first?.count, n > 0, a.count >= n - 1 else { throw RiverCoreError.singularMatrix }
        let m = a.count
        // Column-major copy.
        var cols = [[Double]](repeating: [Double](repeating: 0, count: m), count: n)
        for i in 0..<m { for j in 0..<n { cols[j][i] = a[i][j] } }
        var v = [[Double]](repeating: [Double](repeating: 0, count: n), count: n) // columns of V
        for j in 0..<n { v[j][j] = 1 }
        for _ in 0..<80 {
            var rotated = false
            for p in 0..<(n - 1) {
                for q in (p + 1)..<n {
                    var alpha = 0.0, beta = 0.0, gamma = 0.0
                    for i in 0..<m {
                        alpha += cols[p][i] * cols[p][i]
                        beta += cols[q][i] * cols[q][i]
                        gamma += cols[p][i] * cols[q][i]
                    }
                    if gamma == 0 || abs(gamma) <= 1e-15 * (alpha * beta).squareRoot() { continue }
                    rotated = true
                    let zeta = (beta - alpha) / (2 * gamma)
                    let sign: Double = zeta >= 0 ? 1 : -1
                    let t = sign / (abs(zeta) + (1 + zeta * zeta).squareRoot())
                    let c = 1 / (1 + t * t).squareRoot()
                    let s = c * t
                    for i in 0..<m {
                        let xp = cols[p][i], xq = cols[q][i]
                        cols[p][i] = c * xp - s * xq
                        cols[q][i] = s * xp + c * xq
                    }
                    for i in 0..<n {
                        let xp = v[p][i], xq = v[q][i]
                        v[p][i] = c * xp - s * xq
                        v[q][i] = s * xp + c * xq
                    }
                }
            }
            if !rotated { break }
        }
        var best = 0
        var bestNorm = Double.infinity
        for j in 0..<n {
            var s = 0.0
            for i in 0..<m { s += cols[j][i] * cols[j][i] }
            if s < bestNorm {
                bestNorm = s
                best = j
            }
        }
        let out: [Double] = v[best]
        guard out.allSatisfy({ $0.isFinite }) else { throw RiverCoreError.singularMatrix }
        return out
    }
}
