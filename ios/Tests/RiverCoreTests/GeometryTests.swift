import XCTest
@testable import RiverCore

final class UAVTransformTests: XCTestCase {
    private func assertClose(_ a: Point2, _ b: Point2, tol: Double = 1e-6, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(a.x, b.x, accuracy: tol, file: file, line: line)
        XCTAssertEqual(a.y, b.y, accuracy: tol, file: file, line: line)
    }

    /// Regression for the RIVeR 3.8.3 defect: desktop sends this point to (0, -10).
    func testNorthPointingSegmentIsNotMirrored() throws {
        let h = try UAVTransform.matrix(pixel1: Point2(0, 0), pixel2: Point2(100, 0),
                                        world1: Point2(0, 0), world2: Point2(0, 10))
        assertClose(h.apply(Point2(0, 0)), Point2(0, 0))
        assertClose(h.apply(Point2(100, 0)), Point2(0, 10))
    }

    func testArbitraryRotationInUTMCoordinates() throws {
        let p1 = Point2(120, 340), p2 = Point2(900, 610)
        let w1 = Point2(500_100.0, 9_560_200.0), w2 = Point2(500_131.2, 9_560_180.7)
        let h = try UAVTransform.matrix(pixel1: p1, pixel2: p2, world1: w1, world2: w2)
        assertClose(h.apply(p1), w1, tol: 1e-6)
        assertClose(h.apply(p2), w2, tol: 1e-6)
    }

    /// Image y grows downwards, real-world y (North) upwards: a point below p1 in the image must be south of it
    /// when the 1→2 segment points East.
    func testImageDownIsSouthForEastSegment() throws {
        let h = try UAVTransform.matrix(pixel1: Point2(10, 10), pixel2: Point2(110, 10),
                                        world1: Point2(5, 5), world2: Point2(15, 5))
        XCTAssertLessThan(h.apply(Point2(10, 60)).y, 5)
    }

    /// For an East–West segment the fixed formula equals desktop's matrix: diag(s, -s) plus translation.
    func testEastWestSegmentMatchesDesktop() throws {
        let h = try UAVTransform.matrix(pixel1: Point2(10, 10), pixel2: Point2(110, 10),
                                        world1: Point2(5, 5), world2: Point2(15, 5))
        let desktop = Matrix3(rows: [[0.1, 0, 4], [0, -0.1, 6], [0, 0, 1]])
        for (a, b) in zip(h.m, desktop.m) { XCTAssertEqual(a, b, accuracy: 1e-12) }
    }

    func testPixelSize() throws {
        let s = try UAVTransform.pixelSize(pixel1: Point2(0, 0), pixel2: Point2(300, 400),
                                           world1: Point2(0, 0), world2: Point2(6, 8))
        XCTAssertEqual(s, 0.02, accuracy: 1e-12)
    }

    func testCoincidentPointsThrow() {
        XCTAssertThrowsError(try UAVTransform.matrix(pixel1: Point2(1, 1), pixel2: Point2(1, 1),
                                                     world1: Point2(0, 0), world2: Point2(1, 0)))
    }
}

final class HomographyTests: XCTestCase {
    private let truth = Matrix3(rows: [[0.021, -0.004, 500_100.0], [0.002, -0.03, 9_560_200.0], [1e-5, 4e-5, 1]])
    private let pixels = [Point2(100, 800), Point2(1800, 820), Point2(1500, 300),
                          Point2(400, 280), Point2(950, 550), Point2(1200, 700)]

    func testRecoversKnownHomographyFromSixPoints() throws {
        let world = pixels.map(truth.apply)
        let h = try Homography.fit(pixel: pixels, world: world)
        let errors = Homography.reprojectionErrors(matrix: h, pixel: pixels, world: world)
        XCTAssertLessThan(errors.max() ?? .infinity, 1e-3) // < 1 mm in UTM coordinates
    }

    func testExactFitWithFourPoints() throws {
        let four = Array(pixels.prefix(4))
        let h = try Homography.fit(pixel: four, world: four.map(truth.apply))
        let check = pixels.map(h.apply)
        for (a, b) in zip(check, pixels.map(truth.apply)) { XCTAssertLessThan(a.distance(to: b), 1e-3) }
    }

    func testInverseRoundTrip() throws {
        let inv = try truth.inverse()
        for p in pixels {
            let back = inv.apply(truth.apply(p))
            XCTAssertEqual(back.x, p.x, accuracy: 1e-6)
            XCTAssertEqual(back.y, p.y, accuracy: 1e-6)
        }
    }

    func testThreeAlignedOfFourThrows() {
        let px = [Point2(0, 0), Point2(10, 0), Point2(20, 0), Point2(5, 9)]
        XCTAssertThrowsError(try Homography.fit(pixel: px, world: px))
    }

    func testTooFewPointsThrow() {
        let px = [Point2(0, 0), Point2(10, 0), Point2(5, 9)]
        XCTAssertThrowsError(try Homography.fit(pixel: px, world: px)) { error in
            XCTAssertEqual(error as? RiverCoreError, .notEnoughPoints(needed: 4, got: 3))
        }
    }
}
