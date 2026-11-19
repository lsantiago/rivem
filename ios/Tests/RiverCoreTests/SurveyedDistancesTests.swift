import XCTest
@testable import RiverCore

/// RIVEM addition: oblique distances from 3D-surveyed points (`Geometry/SurveyedDistances.swift`).
final class SurveyedDistancesTests: XCTestCase {
    func testHorizontalDistancesIgnoreHeight() throws {
        // 10 m × 6 m rectangle on the water plane (y = −2), point 3 surveyed 0.3 m too high.
        let pts: [Point3] = [Point3(0, -2, 0), Point3(10, -2, 0), Point3(10, -1.7, 6), Point3(0, -2, 6)]
        let s = try XCTUnwrap(SurveyedDistances.from(pts))
        let d = s.distances
        XCTAssertEqual(d.d12, 10, accuracy: 1e-12)
        XCTAssertEqual(d.d23, 6, accuracy: 1e-12)
        XCTAssertEqual(d.d34, 10, accuracy: 1e-12)
        XCTAssertEqual(d.d41, 6, accuracy: 1e-12)
        XCTAssertEqual(d.d13, (136.0).squareRoot(), accuracy: 1e-12)
        XCTAssertEqual(d.d24, (136.0).squareRoot(), accuracy: 1e-12)
        XCTAssertEqual(s.heightSpread, 0.3, accuracy: 1e-12)
        // d23: slant √(36 + 0.09) − 6.
        XCTAssertEqual(s.largestSlantExcess, (36.09).squareRoot() - 6, accuracy: 1e-12)
    }

    func testNeedsFourFinitePoints() {
        XCTAssertNil(SurveyedDistances.from([Point3(0, 0, 0), Point3(1, 0, 0), Point3(1, 0, 1)]))
        XCTAssertNil(SurveyedDistances.from([Point3(0, 0, 0), Point3(1, 0, 0), Point3(1, 0, 1), Point3(0, .nan, 1)]))
    }

    func testSurveyedDistancesSolveTheObliqueView() throws {
        // Distances from a survey feed the oblique solution with no residual.
        let pts: [Point3] = [Point3(0, 0, 0), Point3(12, 0, 0.5), Point3(11, 0.05, 8), Point3(-1, 0, 7.5)]
        let s = try XCTUnwrap(SurveyedDistances.from(pts))
        let pixels: [Point2] = [Point2(100, 900), Point2(1800, 880), Point2(1500, 300), Point2(350, 320)]
        let solution = try ObliqueTransform.solve(pixels: pixels, distances: s.distances)
        XCTAssertLessThan(solution.distanceResiduals.map { abs($0) }.max() ?? 1, 1e-3)
    }
}
