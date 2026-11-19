import XCTest
@testable import RiverCore

/// RIVEM addition: stage–discharge curve (`Section/RatingCurve.swift`).
final class RatingCurveTests: XCTestCase {
    private func exact(_ levels: [Double], a: Double = 20, b: Double = 1.6, h0: Double = 1.0) -> [RatingPoint] {
        levels.map { RatingPoint(level: $0, discharge: a * pow($0 - h0, b)) }
    }

    func testRecoversExactCurve() throws {
        let c = try XCTUnwrap(RatingCurve.fit(exact([1.4, 1.6, 1.76, 2.0, 2.3, 2.8])))
        XCTAssertEqual(c.h0, 1.0, accuracy: 0.01)
        XCTAssertEqual(c.b, 1.6, accuracy: 0.02)
        XCTAssertEqual(c.a, 20, accuracy: 0.3)
        XCTAssertEqual(c.r2, 1, accuracy: 1e-6)
        XCTAssertLessThan(c.relativeRMS, 1e-3)
        XCTAssertEqual(c.discharge(at: 2.5), 20 * pow(1.5, 1.6), accuracy: 0.05)
        XCTAssertEqual(c.level(for: c.discharge(at: 2.1)), 2.1, accuracy: 1e-9)
        XCTAssertEqual(c.discharge(at: 0.5), 0)
    }

    func testNoisyGaugingsStayClose() throws {
        let noise: [Double] = [0.04, -0.03, 0.02, -0.05, 0.03, -0.01, 0.02, -0.02]
        let levels: [Double] = [1.3, 1.45, 1.6, 1.8, 2.0, 2.2, 2.5, 2.9]
        var pts = exact(levels)
        for i in pts.indices { pts[i].discharge *= 1 + noise[i] }
        let c = try XCTUnwrap(RatingCurve.fit(pts))
        XCTAssertGreaterThan(c.r2, 0.98)
        XCTAssertLessThan(c.relativeRMS, 0.05)
        XCTAssertEqual(c.discharge(at: 2.0), 20, accuracy: 1.5)
        XCTAssertLessThan(c.h0, 1.3)
    }

    func testNeedsThreeGaugingsAtDistinctLevels() {
        XCTAssertNil(RatingCurve.fit(exact([1.5, 2.0])))
        XCTAssertNil(RatingCurve.fit([RatingPoint(level: 1.5, discharge: 3), RatingPoint(level: 1.5, discharge: 3.2),
                                      RatingPoint(level: 1.5, discharge: 2.9)]))
        XCTAssertNil(RatingCurve.fit(exact([1.5, 2.0]) + [RatingPoint(level: 1.7, discharge: .nan)]))
        XCTAssertNotNil(RatingCurve.fit(exact([1.5, 1.7, 2.0])))
    }
}
