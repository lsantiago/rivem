import Foundation
import XCTest
@testable import RiverCore

// MARK: - Helpers

private func assertClose(_ a: Point2, _ b: Point2, tol: Double, _ message: String = "",
                         file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertEqual(a.x, b.x, accuracy: tol, message, file: file, line: line)
    XCTAssertEqual(a.y, b.y, accuracy: tol, message, file: file, line: line)
}

/// Golden references written by tools/golden/geometry.py (CI, RIVeR's Python core). Skips when not generated yet.
private enum GeometryGolden {
    static func load(_ name: String) throws -> [String: Any] {
        let url = TestData.url("golden/geometry/" + name)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("golden/geometry/\(name) todavía no existe (lo genera .github/workflows/golden.yml)")
        }
        let data = try Data(contentsOf: url)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw XCTSkip("golden/geometry/\(name) no es un objeto JSON")
        }
        return obj
    }

    static func num(_ a: Any?) -> Double? {
        if let d = a as? Double { return d }
        if let i = a as? Int { return Double(i) }
        if let n = a as? NSNumber { return n.doubleValue }
        return nil
    }

    static func nums(_ a: Any?) -> [Double] {
        guard let list = a as? [Any] else { return [] }
        return list.compactMap { num($0) }
    }

    static func matrix(_ a: Any?) -> [[Double]] {
        guard let list = a as? [Any] else { return [] }
        return list.map { nums($0) }
    }

    static func points(_ a: Any?) -> [Point2] {
        matrix(a).map { Point2($0[0], $0[1]) }
    }
}

/// Synthetic frame of tools/golden/geometry.py: R = 255·x / (W−1), G = 255·y / (H−1) (integer division), B = 128.
private func syntheticFrame(width: Int = 1920, height: Int = 1080) -> RGBAImage {
    var img = RGBAImage(width: width, height: height)
    for y in 0..<height {
        let g = UInt8((255 * y) / (height - 1))
        for x in 0..<width {
            let o = 4 * (y * width + x)
            img.bytes[o] = UInt8((255 * x) / (width - 1))
            img.bytes[o + 1] = g
            img.bytes[o + 2] = 128
            img.bytes[o + 3] = 255
        }
    }
    return img
}

/// Compares a rendered image with the golden summary: size exact, opaque count within 0.5 %, sampled pixels within
/// ±2 levels where both are opaque (remap's fixed point), and at most 3 alpha disagreements (edge pixels).
private func assertMatchesSummary(_ img: RGBAImage, _ summary: [String: Any], file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertEqual(img.width, Int(GeometryGolden.num(summary["width"]) ?? -1), file: file, line: line)
    XCTAssertEqual(img.height, Int(GeometryGolden.num(summary["height"]) ?? -1), file: file, line: line)
    guard img.width == Int(GeometryGolden.num(summary["width"]) ?? -1),
          img.height == Int(GeometryGolden.num(summary["height"]) ?? -1) else { return }
    var opaque = 0
    for i in 0..<(img.width * img.height) where img.bytes[4 * i + 3] == 255 { opaque += 1 }
    let expectedOpaque = GeometryGolden.num(summary["opaque_count"]) ?? 0
    XCTAssertEqual(Double(opaque), expectedOpaque, accuracy: max(5, 0.005 * expectedOpaque), file: file, line: line)
    var alphaMismatch = 0
    for s in GeometryGolden.matrix(summary["samples"]) {
        let r = Int(s[0]), c = Int(s[1])
        let o = 4 * (r * img.width + c)
        let alpha = Double(img.bytes[o + 3])
        if alpha != s[5] {
            alphaMismatch += 1
            continue
        }
        if alpha == 255 {
            for k in 0..<3 {
                XCTAssertEqual(Double(img.bytes[o + k]), s[2 + k], accuracy: 2,
                               String(format: "pixel (%ld, %ld) canal %ld", r, c, k), file: file, line: line)
            }
        }
    }
    XCTAssertLessThanOrEqual(alphaMismatch, 3, file: file, line: line)
}

// MARK: - Oblique

final class ObliqueTransformTests: XCTestCase {
    private let quad: [Point2] = [Point2(0, 0), Point2(20, 0), Point2(23, 15), Point2(-2, 17)]

    private func distances(_ q: [Point2]) -> ObliqueDistances {
        ObliqueDistances(d12: q[0].distance(to: q[1]), d23: q[1].distance(to: q[2]), d34: q[2].distance(to: q[3]),
                         d41: q[3].distance(to: q[0]), d13: q[0].distance(to: q[2]), d24: q[1].distance(to: q[3]))
    }

    func testTrilaterationRecoversExactQuadrilateral() {
        let (p3, p4) = ObliqueTransform.trilaterate(distances(quad))
        assertClose(p3, quad[2], tol: 1e-6)
        assertClose(p4, quad[3], tol: 1e-6)
    }

    func testTrilaterationOfAWideShallowQuadrilateral() {
        let q = [Point2(0, 0), Point2(42.5, 0), Point2(47.1, 6.2), Point2(-3.3, 5.8)]
        let (p3, p4) = ObliqueTransform.trilaterate(distances(q))
        assertClose(p3, q[2], tol: 1e-6)
        assertClose(p4, q[3], tol: 1e-6)
    }

    func testSolveMapsControlPointsToTheirWorldPositions() throws {
        let truth = Matrix3(rows: [[0.0103, 0.0040, -5.80], [-0.0031, -0.0230, 21.78], [5.8e-5, 1.45e-3, -0.348]])
        let inv = try truth.inverse()
        let pixels = quad.map { inv.apply($0) }
        let sol = try ObliqueTransform.solve(pixels: pixels, distances: distances(quad))
        for (p, w) in zip(pixels, quad) { assertClose(sol.matrix.apply(p), w, tol: 1e-6) }
        // Any other pixel too: same homography.
        assertClose(sol.matrix.apply(Point2(800, 900)), truth.apply(Point2(800, 900)), tol: 1e-6)
        for r in sol.distanceResiduals { XCTAssertEqual(r, 0, accuracy: 1e-6) }
        XCTAssertEqual(sol.matrix[2, 2], 1, accuracy: 1e-15)
        let xs = pixels.map(\.x), ys = pixels.map(\.y)
        let expectedROI: [Double] = [xs.min()!, ys.min()!, xs.max()! - xs.min()!, ys.max()! - ys.min()!]
        for (a, b) in zip(sol.roi, expectedROI) { XCTAssertEqual(a, b, accuracy: 1e-12) }
    }

    func testAnchorsRotateAndTranslateTheSolution() throws {
        let pixels = [Point2(50, 930), Point2(1800, 690), Point2(1400, 420), Point2(450, 480)]
        let d = distances(quad)
        let theta = 0.7
        let a1 = Point2(500_100.0, 9_560_200.0)
        let a2 = Point2(a1.x + d.d12 * cos(theta), a1.y + d.d12 * sin(theta))
        let sol = try ObliqueTransform.solve(pixels: pixels, distances: d, anchors: (a1, a2))
        assertClose(sol.world[0], a1, tol: 1e-9)
        assertClose(sol.world[1], a2, tol: 1e-9)
        // Double precision kept with UTM anchors (desktop's float32 would be off by decimetres).
        XCTAssertEqual(sol.world[2].distance(to: a1), d.d13, accuracy: 1e-6)
        XCTAssertEqual(sol.world[3].distance(to: a2), d.d24, accuracy: 1e-6)
        for (p, w) in zip(pixels, sol.world) { assertClose(sol.matrix.apply(p), w, tol: 1e-5) }
    }

    func testAnchorDistanceMismatchThrowsWhenEnforced() {
        let pixels = [Point2(50, 930), Point2(1800, 690), Point2(1400, 420), Point2(450, 480)]
        let d = distances(quad)
        let anchors = (Point2(0, 0), Point2(d.d12 + 2, 0))
        XCTAssertThrowsError(try ObliqueTransform.solve(pixels: pixels, distances: d, anchors: anchors))
        XCTAssertNoThrow(try ObliqueTransform.solve(pixels: pixels, distances: d, anchors: anchors, enforceD12: false))
    }

    func testROIPadding() {
        let roi = ObliqueTransform.roi(pixels: [Point2(10, 20), Point2(110, 20), Point2(110, 70), Point2(10, 70)], padding: 0.1)
        XCTAssertEqual(roi, [0, 15, 120, 60])
    }

    func testGoldenPisco() throws {
        let g = try GeometryGolden.load("oblique_pisco.json")
        let pixels = GeometryGolden.points(g["pixels"])
        let d = try XCTUnwrap(ObliqueDistances(GeometryGolden.nums(g["distances"])))
        let sol = try ObliqueTransform.solve(pixels: pixels, distances: d)

        let opt = GeometryGolden.nums(g["optimized_p3_p4"])
        assertClose(sol.world[2], Point2(opt[0], opt[1]), tol: 1e-3, "P3")
        assertClose(sol.world[3], Point2(opt[2], opt[3]), tol: 1e-3, "P4")

        let desktop = Matrix3(rows: GeometryGolden.matrix(g["matrix"]))
        let roi = GeometryGolden.nums(g["roi"])
        for (a, b) in zip(sol.roi, roi) { XCTAssertEqual(a, b, accuracy: 1e-4) }
        let probes = pixels + [Point2(roi[0], roi[1]), Point2(roi[0] + roi[2], roi[1] + roi[3]), Point2(960, 700)]
        for p in probes { assertClose(sol.matrix.apply(p), desktop.apply(p), tol: 2e-3, "pixel \(p)") }

        // Output grid and transformed image, from desktop's own matrix so integer sizes are comparable.
        let grid = try Orthorectifier.obliqueGrid(matrix: desktop, roi: roi)
        let extent = GeometryGolden.nums(g["extent"])
        for (a, b) in zip(grid.extent, extent) { XCTAssertEqual(a, b, accuracy: 1e-6) }
        XCTAssertEqual(grid.resolution, GeometryGolden.num(g["output_resolution"]) ?? 0, accuracy: 1e-9)
        let img = try Orthorectifier.render(syntheticFrame(), grid: grid, matrix: desktop)
        assertMatchesSummary(img, try XCTUnwrap(g["transformed_image"] as? [String: Any]))

        let anchored = try XCTUnwrap(g["anchored"] as? [String: Any])
        let an = GeometryGolden.nums(anchored["anchors"])
        let solA = try ObliqueTransform.solve(pixels: pixels, distances: d,
                                              anchors: (Point2(an[0], an[1]), Point2(an[2], an[3])),
                                              enforceD12: true, distanceTolerance: 1)
        let desktopA = Matrix3(rows: GeometryGolden.matrix(anchored["matrix"]))
        for p in probes { assertClose(solA.matrix.apply(p), desktopA.apply(p), tol: 5e-3, "anclado \(p)") }
    }
}

// MARK: - 3-D camera

final class CameraCalibrationTests: XCTestCase {
    /// K [R | −R C] for a camera at `center` looking roughly north and down, in UTM-sized coordinates.
    private func syntheticCamera(center: [Double]) -> CameraMatrix {
        let f = 1500.0, cx = 960.0, cy = 540.0
        // Camera axes in world coordinates: x right (East-ish), y down, z forward (North and down).
        let pitch = 0.2, yaw = 0.2
        let fwd: [Double] = [sin(yaw) * cos(pitch), cos(yaw) * cos(pitch), -sin(pitch)]
        let right: [Double] = [cos(yaw), -sin(yaw), 0]
        // down = fwd × right
        let down: [Double] = [fwd[1] * right[2] - fwd[2] * right[1], fwd[2] * right[0] - fwd[0] * right[2],
                              fwd[0] * right[1] - fwd[1] * right[0]]
        let r: [[Double]] = [right, down, fwd]
        var p: [Double] = []
        let k: [[Double]] = [[f, 0, cx], [0, f, cy], [0, 0, 1]]
        for i in 0..<3 {
            var row = [Double](repeating: 0, count: 4)
            for j in 0..<3 {
                var kr = 0.0
                for m in 0..<3 { kr += k[i][m] * r[m][j] }
                row[j] = kr
            }
            row[3] = -(row[0] * center[0] + row[1] * center[1] + row[2] * center[2])
            p += row
        }
        return CameraMatrix(rowMajor: p)
    }

    private let center: [Double] = [4_355_880.0, 6_520_240.0, 660.0]

    private func groundPoints(_ cam: CameraMatrix) -> [GroundPoint3D] {
        var pts: [GroundPoint3D] = []
        let offsets: [[Double]] = [[-12, 30, -11], [10, 35, -12], [15, 60, -10.5], [-8, 70, -11.8], [0, 45, -12.5],
                                   [20, 80, -9], [-15, 55, -10], [5, 25, -12.2], [-3, 90, -11]]
        for o in offsets {
            let X = center[0] + o[0], Y = center[1] + o[1], Z = center[2] + o[2]
            let px = cam.project(X: X, Y: Y, Z: Z)
            pts.append(GroundPoint3D(X: X, Y: Y, Z: Z, x: px.x, y: px.y))
        }
        return pts
    }

    func testRecoversKnownCameraFromProjectedPoints() throws {
        let truth = syntheticCamera(center: center)
        let pts = groundPoints(truth)
        for p in pts {
            XCTAssertTrue(p.pixel.x > 0 && p.pixel.x < 1920 && p.pixel.y > 0 && p.pixel.y < 1080, "\(p.pixel)")
        }
        let sol = try CameraCalibration.solve(points: pts)
        XCTAssertLessThan(sol.meanError, 1e-4)
        XCTAssertLessThan(sol.reprojectionErrors.max() ?? 1, 1e-3)
        for k in 0..<3 { XCTAssertEqual(sol.cameraPosition[k], center[k], accuracy: 1e-3) }
        // Same projective camera as the truth: any other world point projects to the same pixel.
        let probe = truth.project(X: center[0] + 7, Y: center[1] + 50, Z: center[2] - 11)
        assertClose(sol.camera.project(X: center[0] + 7, Y: center[1] + 50, Z: center[2] - 11), probe, tol: 1e-3)
        XCTAssertEqual(sol.uncertaintyEllipses.count, pts.count)
    }

    func testLevelMatrixSendsPixelsBackToTheWaterPlane() throws {
        let cam = syntheticCamera(center: center)
        let z = 648.3
        let m = try cam.pixelToWorld(z: z)
        for (dx, dy) in [(-10.0, 40.0), (12.0, 70.0), (0.0, 30.0)] {
            let X = center[0] + dx, Y = center[1] + dy
            let px = cam.project(X: X, Y: Y, Z: z)
            assertClose(m.apply(px), Point2(X, Y), tol: 1e-4)
        }
    }

    func testTooFewPointsThrow() {
        let cam = syntheticCamera(center: center)
        let pts = Array(groundPoints(cam).prefix(5))
        XCTAssertThrowsError(try CameraCalibration.solveCameraMatrix(points: pts)) { error in
            XCTAssertEqual(error as? RiverCoreError, .notEnoughPoints(needed: 6, got: 5))
        }
    }

    func testEllipsesOfIdenticalErrorsAreDegenerate() {
        let actual = (0..<5).map { Point2(Double($0), 0) }
        let projected = actual.map { Point2($0.x + 1, $0.y + 1) }
        for e in CameraCalibration.uncertaintyEllipses(actual: actual, projected: projected) {
            XCTAssertEqual(e.width, 0, accuracy: 1e-9)
            XCTAssertEqual(e.height, 0, accuracy: 1e-9)
        }
    }

    func testGoldenFullGRP3D() throws {
        let g = try GeometryGolden.load("camera_full_grp_3d.json")
        let lists = try XCTUnwrap(g["points"] as? [String: Any])
        let pts = try GroundPoint3D.fromLists(X: GeometryGolden.nums(lists["X"]), Y: GeometryGolden.nums(lists["Y"]),
                                              Z: GeometryGolden.nums(lists["Z"]), x: GeometryGolden.nums(lists["x"]),
                                              y: GeometryGolden.nums(lists["y"]))
        let sol = try CameraCalibration.solve(points: pts)

        // Desktop's raw-coordinate DLT is ill-conditioned (see CameraCalibration): agreement within that noise.
        XCTAssertEqual(sol.meanError, GeometryGolden.num(g["error"]) ?? 0, accuracy: 0.1)
        let errs = GeometryGolden.nums(g["reprojection_errors"])
        XCTAssertEqual(errs.count, sol.reprojectionErrors.count)
        for (a, b) in zip(sol.reprojectionErrors, errs) { XCTAssertEqual(a, b, accuracy: 0.5) }
        let pos = GeometryGolden.nums(g["camera_position"])
        for k in 0..<3 { XCTAssertEqual(sol.cameraPosition[k], pos[k], accuracy: 0.05) }

        let z = GeometryGolden.num(g["z_level"]) ?? 0
        let probes = GeometryGolden.points(g["probe_pixels"])
        let probeWorld = GeometryGolden.points(g["probe_world"])
        let ours = try sol.camera.pixelToWorld(z: z)
        for (p, w) in zip(probes, probeWorld) { assertClose(ours.apply(p), w, tol: 0.05, "nivel \(p)") }

        // With desktop's own P the level matrix formula must match exactly.
        let desktopP = CameraMatrix(rows: GeometryGolden.matrix(g["camera_matrix"]))
        let desktopM = Matrix3(rows: GeometryGolden.matrix(g["level_matrix"]))
        let fromDesktopP = try desktopP.pixelToWorld(z: z)
        for (p, w) in zip(probes, probeWorld) {
            assertClose(fromDesktopP.apply(p), w, tol: 1e-6)
            assertClose(desktopM.apply(p), w, tol: 1e-6)
        }

        // Ellipses from desktop's projected points.
        let projected = GeometryGolden.points(g["projected_points"])
        let ellipses = CameraCalibration.uncertaintyEllipses(actual: pts.map(\.pixel), projected: projected)
        let expected = try XCTUnwrap(g["uncertainty_ellipses"] as? [[String: Any]])
        XCTAssertEqual(ellipses.count, expected.count)
        for (e, x) in zip(ellipses, expected) {
            XCTAssertEqual(e.width, GeometryGolden.num(x["width"]) ?? -1, accuracy: 1e-6)
            XCTAssertEqual(e.height, GeometryGolden.num(x["height"]) ?? -1, accuracy: 1e-6)
            var dAngle = (e.angle - (GeometryGolden.num(x["angle"]) ?? 0)).truncatingRemainder(dividingBy: 180)
            if dAngle < 0 { dAngle += 180 }
            XCTAssertTrue(dAngle < 1e-4 || dAngle > 180 - 1e-4, "ángulo \(e.angle)")
        }

        let ortho = try XCTUnwrap(g["ortho"] as? [String: Any])
        let (grid, _) = try Orthorectifier.cameraGrid(points: pts, resolution: GeometryGolden.num(ortho["resolution"]) ?? 0.1)
        for (a, b) in zip(grid.extent, GeometryGolden.nums(ortho["extent"])) { XCTAssertEqual(a, b, accuracy: 1e-6) }
        XCTAssertEqual(grid.width, Int(GeometryGolden.num(ortho["width"]) ?? -1))
        XCTAssertEqual(grid.height, Int(GeometryGolden.num(ortho["height"]) ?? -1))
    }
}

// MARK: - UAV (golden only; the analytic tests live in GeometryTests.swift)

final class UAVGoldenTests: XCTestCase {
    func testGoldenUAV() throws {
        let g = try GeometryGolden.load("uav.json")
        let ew = try XCTUnwrap(g["east_west"] as? [String: Any])
        let pix = GeometryGolden.nums(ew["pix"]), rw = GeometryGolden.nums(ew["rw"])
        let m = try UAVTransform.matrix(pixel1: Point2(pix[0], pix[1]), pixel2: Point2(pix[2], pix[3]),
                                        world1: Point2(rw[0], rw[1]), world2: Point2(rw[2], rw[3]))
        let desktop = Matrix3(rows: GeometryGolden.matrix(ew["matrix"]))
        for (a, b) in zip(m.m, desktop.m) { XCTAssertEqual(a, b, accuracy: 1e-9) }
        let grid = try Orthorectifier.uavGrid(matrix: desktop, imageWidth: 1920, imageHeight: 1080)
        for (a, b) in zip(grid.extent, GeometryGolden.nums(ew["extent"])) { XCTAssertEqual(a, b, accuracy: 1e-6) }
        XCTAssertEqual(grid.resolution, GeometryGolden.num(ew["output_resolution"]) ?? 0, accuracy: 1e-9)
        let img = try Orthorectifier.render(syntheticFrame(), grid: grid, matrix: desktop)
        assertMatchesSummary(img, try XCTUnwrap(ew["transformed_image"] as? [String: Any]))

        // Documents desktop defect #1: for a North–South segment desktop sends point 2 elsewhere; RIVEM does not.
        let ns = try XCTUnwrap(g["north_south"] as? [String: Any])
        let npix = GeometryGolden.nums(ns["pix"]), nrw = GeometryGolden.nums(ns["rw"])
        let desktopP2 = GeometryGolden.nums(ns["desktop_point2_world"])
        XCTAssertGreaterThan(Point2(desktopP2[0], desktopP2[1]).distance(to: Point2(nrw[2], nrw[3])), 1)
        let fixed = try UAVTransform.matrix(pixel1: Point2(npix[0], npix[1]), pixel2: Point2(npix[2], npix[3]),
                                            world1: Point2(nrw[0], nrw[1]), world2: Point2(nrw[2], nrw[3]))
        assertClose(fixed.apply(Point2(npix[2], npix[3])), Point2(nrw[2], nrw[3]), tol: 1e-9)
    }
}

// MARK: - ROI masks

final class ROIMaskTests: XCTestCase {
    /// Pixel → world with world = (x, 100 − y): one metre per pixel, north up.
    private let flipMatrix = Matrix3(rows: [[1, 0, 0], [0, -1, 100], [0, 0, 1]])

    func testHorizontalSectionGivesTheExpectedRectangle() throws {
        let section = ROISection(left: Point2(10, 50), right: Point2(60, 50))
        let (mask, bbox) = try ROIMasks.sectionMask(width: 100, height: 80, sections: [section], matrix: flipMatrix,
                                                    heightROI: 10)
        // World box x ∈ [10, 60], y ∈ [45, 55] → pixel columns 10…60, rows 45…55, outline included (cv2.fillPoly).
        XCTAssertEqual(mask.count, 51 * 11)
        XCTAssertEqual(mask.nonZeroBounds, [10, 45, 60, 55])
        XCTAssertEqual(mask[10, 45], 1)
        XCTAssertEqual(mask[60, 55], 1)
        XCTAssertEqual(mask[9, 50], 0)
        XCTAssertEqual(mask[30, 56], 0)
        XCTAssertEqual(bbox, [10, 45, 50, 10])
    }

    func testBoundingBoxIsClippedToTheFrame() throws {
        let section = ROISection(left: Point2(-20, 50), right: Point2(150, 50))
        let (mask, bbox) = try ROIMasks.sectionMask(width: 100, height: 80, sections: [section], matrix: flipMatrix,
                                                    heightROI: 10)
        XCTAssertEqual(bbox, [0, 45, 99, 10])
        XCTAssertEqual(mask.count, 100 * 11)
    }

    func testIslandBetweenWetSegmentsIsExcluded() throws {
        // Wet 0–10 m and 20–50 m along a 50 m section: the 10–20 m stretch is dry.
        let section = ROISection(left: Point2(10, 50), right: Point2(60, 50), wetSegments: [0...10, 20...50])
        let (mask, _) = try ROIMasks.sectionMask(width: 100, height: 80, sections: [section], matrix: flipMatrix,
                                                 heightROI: 10)
        XCTAssertEqual(mask[15, 50], 1)
        XCTAssertEqual(mask[25, 50], 0)
        XCTAssertEqual(mask[40, 50], 1)
    }

    func testUserPolygonIsZeroInsideAndCompileIsAnd() throws {
        let user = try ROIMasks.userPolygonMask(width: 100, height: 80,
                                                points: [Point2(20, 40), Point2(39.6, 40), Point2(40, 60.4), Point2(20, 60)])
        XCTAssertEqual(user.count, 100 * 80 - 21 * 21)
        XCTAssertEqual(user[30, 50], 0)
        XCTAssertEqual(user[50, 50], 1)
        let section = ROISection(left: Point2(10, 50), right: Point2(60, 50))
        let roi = try ROIMasks.sectionMask(width: 100, height: 80, sections: [section], matrix: flipMatrix, heightROI: 10).mask
        let finalMask = ROIMasks.compile(roi: roi, userMasks: [user])
        XCTAssertEqual(finalMask.count, 51 * 11 - 21 * 11)
        XCTAssertEqual(finalMask[30, 50], 0)
        XCTAssertEqual(finalMask[50, 50], 1)
        XCTAssertThrowsError(try ROIMasks.userPolygonMask(width: 10, height: 10, points: [Point2(1, 1), Point2(2, 2)]))
    }

    func testRecommendedHeight() throws {
        // 0.01 m per pixel: a 192 px short side (1.5 × 128) is 1.92 m → rounded up to 2.
        let m = Matrix3(rows: [[0.01, 0, 0], [0, -0.01, 10], [0, 0, 1]])
        let s = ROISection(left: Point2(1, 5), right: Point2(11, 5))
        XCTAssertEqual(try ROIMasks.recommendHeightROI(sections: [s], matrix: m, windowSize: 128), 2)
    }

    func testOverlayPNGColours() {
        let mask = ROIMask(width: 2, height: 1, values: [0, 1])
        XCTAssertEqual(mask.overlayImage().bytes, [0, 0, 0, 111, 0, 0, 0, 0])
    }

    func testBoundedBrentFindsParabolaMinimum() {
        let r = ROIMasks.boundedBrent({ ($0 - 2.345) * ($0 - 2.345) }, lower: 0.1, upper: 10)
        XCTAssertTrue(r.converged)
        XCTAssertEqual(r.x, 2.345, accuracy: 1e-4)
    }

    private func assertMask(_ mask: ROIMask, _ summary: [String: Any], exact: Bool,
                            file: StaticString = #filePath, line: UInt = #line) {
        let count = GeometryGolden.num(summary["count"]) ?? -1
        if exact {
            XCTAssertEqual(Double(mask.count), count, file: file, line: line)
        } else {
            XCTAssertEqual(Double(mask.count), count, accuracy: max(10, 0.002 * count), file: file, line: line)
        }
        let bounds = GeometryGolden.nums(summary["bounds"])
        if let ours = mask.nonZeroBounds, bounds.count == 4 {
            for k in 0..<4 { XCTAssertEqual(Double(ours[k]), bounds[k], accuracy: exact ? 0 : 1, file: file, line: line) }
        } else {
            XCTAssertTrue(bounds.isEmpty && mask.nonZeroBounds == nil, file: file, line: line)
        }
        let rows = mask.rowCounts
        for rc in GeometryGolden.matrix(summary["row_counts"]) {
            XCTAssertEqual(Double(rows[Int(rc[0])]), rc[1], accuracy: exact ? 0 : 2,
                           String(format: "fila %ld", Int(rc[0])), file: file, line: line)
        }
    }

    func testGoldenPisco() throws {
        let g = try GeometryGolden.load("roi_pisco.json")
        let matrix = Matrix3(rows: GeometryGolden.matrix(g["matrix"]))
        let s = try XCTUnwrap(g["section"] as? [String: Any])
        let section = ROISection(left: Point2(GeometryGolden.num(s["east_l"]) ?? 0, GeometryGolden.num(s["north_l"]) ?? 0),
                                 right: Point2(GeometryGolden.num(s["east_r"]) ?? 0, GeometryGolden.num(s["north_r"]) ?? 0),
                                 realWorldLength: GeometryGolden.num(s["rw_length"]))
        let height = GeometryGolden.num(g["height_roi"]) ?? 0
        let (mask, bbox) = try ROIMasks.sectionMask(width: 1920, height: 1080, sections: [section], matrix: matrix,
                                                    heightROI: height)
        for (a, b) in zip(bbox, GeometryGolden.nums(g["bbox"])) { XCTAssertEqual(a, b, accuracy: 1e-6) }
        // Inside the frame the rasteriser reproduces cv2.fillPoly; leave a margin for boxes clipped by the frame.
        assertMask(mask, try XCTUnwrap(g["mask"] as? [String: Any]), exact: false)

        let recommended = try ROIMasks.recommendHeightROI(sections: [section], matrix: matrix,
                                                          windowSize: Int(GeometryGolden.num(g["window_size"]) ?? 128))
        XCTAssertEqual(recommended, GeometryGolden.num(g["recommended_height_roi"]) ?? -1)

        let user = try ROIMasks.userPolygonMask(width: 1920, height: 1080, points: GeometryGolden.points(g["user_polygon"]))
        assertMask(user, try XCTUnwrap(g["user_mask"] as? [String: Any]), exact: true)
        assertMask(ROIMasks.compile(roi: mask, userMasks: [user]), try XCTUnwrap(g["final_mask"] as? [String: Any]),
                   exact: false)
    }
}

// MARK: - Orthorectification

final class OrthorectifyTests: XCTestCase {
    private let uav = Matrix3(rows: [[0.1, 0, 0], [0, -0.1, 5], [0, 0, 1]]) // 100×50 px frame → 10 m × 5 m

    func testSizeLimitedGridLikeDesktop() throws {
        let grid = try Orthorectifier.uavGrid(matrix: uav, imageWidth: 100, imageHeight: 50)
        XCTAssertEqual(grid.extent[0], -0.5, accuracy: 1e-12)
        XCTAssertEqual(grid.extent[1], 10.5, accuracy: 1e-12)
        XCTAssertEqual(grid.extent[2], -0.25, accuracy: 1e-12)
        XCTAssertEqual(grid.extent[3], 5.25, accuracy: 1e-12)
        // resolution = 11 m / 500 = 0.022 m/px
        XCTAssertLessThanOrEqual(grid.width, 500)
        XCTAssertGreaterThanOrEqual(grid.width, 499)
        XCTAssertEqual(grid.height, 250)
        XCTAssertTrue(grid.flipY)
        // North up: row 0 is the northern edge.
        XCTAssertEqual(grid.world(column: 0, row: 0).y, 5.25, accuracy: 1e-12)
        XCTAssertEqual(grid.world(column: 0, row: grid.height - 1).y, -0.25, accuracy: 1e-12)
    }

    func testMinimumResolutionCapsTheSize() throws {
        let grid = try Orthorectifier.uavGrid(matrix: uav, imageWidth: 100, imageHeight: 50, minResolution: 0.1)
        XCTAssertEqual(grid.resolution, 0.1, accuracy: 0.002)
        XCTAssertEqual(grid.width, 110)
    }

    func testRenderCopiesColoursAndMarksOutsideTransparent() throws {
        var src = RGBAImage(width: 100, height: 50)
        for i in 0..<(100 * 50) {
            src.bytes[4 * i] = 200
            src.bytes[4 * i + 1] = 100
            src.bytes[4 * i + 2] = 50
            src.bytes[4 * i + 3] = 255
        }
        let grid = try Orthorectifier.uavGrid(matrix: uav, imageWidth: 100, imageHeight: 50, minResolution: 0.1)
        let out = try Orthorectifier.render(src, grid: grid, matrix: uav)
        let mid = 4 * ((grid.height / 2) * grid.width + grid.width / 2)
        XCTAssertEqual(Array(out.bytes[mid..<(mid + 4)]), [200, 100, 50, 255])
        XCTAssertEqual(out.bytes[3], 0) // corner lies in the 5 % margin, outside the frame
    }
}

// MARK: - NPY and PNG

final class NPYPNGTests: XCTestCase {
    func testNPYHeaderMatchesNumPy() throws {
        let values: [UInt8] = [0, 1, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1]
        let data = NPY.encodeUInt8(width: 5, height: 3, values: values)
        let bytes = [UInt8](data)
        XCTAssertEqual(Array(bytes[0..<8]), [0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59, 1, 0])
        let headerLength = Int(bytes[8]) | (Int(bytes[9]) << 8)
        XCTAssertEqual(headerLength, 118) // what numpy 1.26 writes for a (3, 5) uint8 array
        XCTAssertEqual((10 + headerLength) % 64, 0)
        let header = String(decoding: bytes[10..<(10 + headerLength)], as: UTF8.self)
        XCTAssertTrue(header.hasPrefix("{'descr': '|u1', 'fortran_order': False, 'shape': (3, 5), }"))
        XCTAssertTrue(header.hasSuffix(" \n"))
        XCTAssertEqual(bytes.count, 128 + 15)
        XCTAssertEqual(Array(bytes[128...]), values)
        let back = try NPY.decodeUInt8(data)
        XCTAssertEqual(back.width, 5)
        XCTAssertEqual(back.height, 3)
        XCTAssertEqual(back.values, values)
    }

    func testNPYRejectsOtherDTypes() {
        var bytes: [UInt8] = [0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59, 1, 0]
        var header = "{'descr': '<f8', 'fortran_order': False, 'shape': (1, 1), }"
        header += String(repeating: " ", count: 64 - (10 + header.utf8.count + 1) % 64) + "\n"
        bytes += [UInt8(header.utf8.count & 0xFF), UInt8(header.utf8.count >> 8)]
        bytes += Array(header.utf8) + [UInt8](repeating: 0, count: 8)
        XCTAssertThrowsError(try NPY.decodeUInt8(Data(bytes))) { error in
            XCTAssertEqual(error as? NPYError, .unsupportedDType("<f8"))
        }
    }

    func testGoldenNPYBytes() throws {
        let g = try GeometryGolden.load("npy_small.json")
        let values: [UInt8] = GeometryGolden.nums(g["values"]).map { UInt8($0) }
        let data = NPY.encodeUInt8(width: Int(GeometryGolden.num(g["width"]) ?? 0),
                                   height: Int(GeometryGolden.num(g["height"]) ?? 0), values: values)
        let hex = data.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hex, g["hex"] as? String)
    }

    func testChecksums() {
        XCTAssertEqual(PNG.crc32(Array("123456789".utf8)), 0xCBF4_3926)
        XCTAssertEqual(PNG.crc32(Array("IEND".utf8)), 0xAE42_6082)
        XCTAssertEqual(PNG.adler32(Array("Wikipedia".utf8)), 0x11E6_0398)
    }

    private func be32(_ b: [UInt8], _ i: Int) -> UInt32 {
        (UInt32(b[i]) << 24) | (UInt32(b[i + 1]) << 16) | (UInt32(b[i + 2]) << 8) | UInt32(b[i + 3])
    }

    func testPNGStructureAndStoredDeflate() throws {
        // 200×100 RGBA = 80 100 raw bytes with filter bytes → two stored blocks.
        var img = RGBAImage(width: 200, height: 100)
        for i in 0..<img.bytes.count { img.bytes[i] = UInt8(i % 251) }
        let png = [UInt8](PNG.encode(img))
        XCTAssertEqual(Array(png[0..<8]), PNG.signature)

        // Walk the chunks, checking each CRC.
        var pos = 8
        var chunks: [(String, [UInt8])] = []
        while pos < png.count {
            let len = Int(be32(png, pos))
            let type = String(decoding: png[(pos + 4)..<(pos + 8)], as: UTF8.self)
            let body = Array(png[(pos + 8)..<(pos + 8 + len)])
            XCTAssertEqual(be32(png, pos + 8 + len), PNG.crc32(Array(png[(pos + 4)..<(pos + 8 + len)])), type)
            chunks.append((type, body))
            pos += 12 + len
        }
        XCTAssertEqual(chunks.map { $0.0 }, ["IHDR", "IDAT", "IEND"])
        let ihdr = chunks[0].1
        XCTAssertEqual(ihdr.count, 13)
        XCTAssertEqual(be32(ihdr, 0), 200)
        XCTAssertEqual(be32(ihdr, 4), 100)
        XCTAssertEqual(Array(ihdr[8..<13]), [8, 6, 0, 0, 0])

        // Inflate the stored blocks by hand.
        let z = chunks[1].1
        XCTAssertEqual(Array(z[0..<2]), [0x78, 0x01])
        XCTAssertEqual((Int(z[0]) * 256 + Int(z[1])) % 31, 0)
        var raw: [UInt8] = []
        var p = 2
        var blocks = 0
        while true {
            let header = z[p]
            let len = Int(z[p + 1]) | (Int(z[p + 2]) << 8)
            let nlen = Int(z[p + 3]) | (Int(z[p + 4]) << 8)
            XCTAssertEqual(len ^ 0xFFFF, nlen)
            XCTAssertEqual(header & 0x06, 0) // BTYPE 00
            raw += z[(p + 5)..<(p + 5 + len)]
            p += 5 + len
            blocks += 1
            if header & 1 == 1 { break }
        }
        XCTAssertEqual(blocks, 2)
        XCTAssertEqual(be32(z, p), PNG.adler32(raw))
        XCTAssertEqual(p + 4, z.count)
        XCTAssertEqual(raw.count, 100 * (1 + 800))
        for y in 0..<100 {
            XCTAssertEqual(raw[y * 801], 0)
            XCTAssertEqual(Array(raw[(y * 801 + 1)..<((y + 1) * 801)]), Array(img.bytes[(y * 800)..<((y + 1) * 800)]))
        }
    }

    func testMaskPNGRoundTripsThroughOverlay() {
        let mask = ROIMask(width: 3, height: 2, values: [1, 0, 1, 0, 0, 1])
        let png = [UInt8](PNG.encode(mask.overlayImage()))
        XCTAssertEqual(Array(png[0..<8]), PNG.signature)
        XCTAssertGreaterThan(png.count, 8 + 25 + 12 + 12 + 6 * 4)
    }
}
