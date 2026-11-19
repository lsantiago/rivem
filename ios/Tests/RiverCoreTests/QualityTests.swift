import XCTest
@testable import RiverCore

/// RIVEM addition: discharge uncertainty (Monte Carlo) and gauging quality (`Section/GaugingQuality.swift`).
final class QualityTests: XCTestCase {
    /// Ten wet stations of 1 m depth, 2 m² and 1 m/s (α = 0.85 → 1.7 m³/s each), between two dry banks.
    private func channel(std: Double = 0.1) -> [GaugingStation] {
        var out: [GaugingStation] = [GaugingStation(distance: 0, depth: 0, area: 0, velocity: .nan, velocityStd: .nan,
                                                    discharge: 0, included: true, interpolated: false)]
        for i in 1...10 {
            out.append(GaugingStation(distance: Double(i) * 2, depth: 1, area: 2, velocity: 1, velocityStd: std,
                                      discharge: 1.7, included: true, interpolated: false))
        }
        out.append(GaugingStation(distance: 22, depth: 0, area: 0, velocity: .nan, velocityStd: .nan, discharge: 0,
                                  included: true, interpolated: false))
        return out
    }

    private func sources(_ level: Double = 0, _ depth: Double = 0, _ alpha: Double = 0, _ scale: Double = 0,
                         pairs: Int = 50) -> UncertaintySources {
        UncertaintySources(levelStd: level, depthStd: depth, alphaStd: alpha, scaleRelativeStd: scale,
                           interpolatedRelativeStd: 0.2, pairCount: pairs)
    }

    func testNoErrorsGiveNoUncertainty() {
        let u = DischargeUncertainty.estimate(stations: channel(std: 0), alpha: 0.85, sources: sources())
        XCTAssertEqual(u.discharge, 17, accuracy: 1e-9)
        XCTAssertEqual(u.mean, 17, accuracy: 1e-9)
        XCTAssertEqual(u.expanded, 0, accuracy: 1e-9)
    }

    func testAlphaErrorPropagatesLinearly() {
        let u = DischargeUncertainty.estimate(stations: channel(std: 0), alpha: 0.85, sources: sources(0, 0, 0.05))
        XCTAssertEqual(u.relative, 1.96 * 0.05 / 0.85, accuracy: 0.008)
        XCTAssertEqual(u.shares[.alpha] ?? 0, 1, accuracy: 1e-9)
    }

    func testScaleErrorCountsTwice() {
        // Velocity and width both scale: Q ∝ s², so σ_Q / Q ≈ 2 σ_s.
        let u = DischargeUncertainty.estimate(stations: channel(std: 0), alpha: 0.85, sources: sources(0, 0, 0, 0.01))
        XCTAssertEqual(u.relative, 1.96 * 0.02, accuracy: 0.003)
    }

    func testLevelErrorIsCommonAndBathymetryIndependent() {
        // Level: all depths move together → σ_Q / Q = σ_h / d = 2 %. Bathymetry: independent → 2 % / √10.
        let level = DischargeUncertainty.estimate(stations: channel(std: 0), alpha: 0.85, sources: sources(0.02))
        XCTAssertEqual(level.std / 17, 0.02, accuracy: 0.002)
        let bed = DischargeUncertainty.estimate(stations: channel(std: 0), alpha: 0.85, sources: sources(0, 0.02))
        XCTAssertEqual(bed.std / 17, 0.02 / 10.0.squareRoot(), accuracy: 0.0012)
    }

    func testVelocityErrorShrinksWithPairs() {
        let few = DischargeUncertainty.estimate(stations: channel(std: 0.3), alpha: 0.85, sources: sources(pairs: 4))
        let many = DischargeUncertainty.estimate(stations: channel(std: 0.3), alpha: 0.85, sources: sources(pairs: 100))
        XCTAssertGreaterThan(few.std, 4 * many.std)
        // σ_v / √(pairs/2) per station, independent: (0.3 / √50) / √10 relative.
        XCTAssertEqual(many.std / 17, 0.3 / 50.0.squareRoot() / 10.0.squareRoot(), accuracy: 0.002)
    }

    func testDeterministicAndSharesSumToOne() {
        let s = sources(0.01, 0.02, 0.05, 0.005)
        let a = DischargeUncertainty.estimate(stations: channel(), alpha: 0.85, sources: s)
        let b = DischargeUncertainty.estimate(stations: channel(), alpha: 0.85, sources: s)
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.shares.values.reduce(0, +), 1, accuracy: 1e-9)
        XCTAssertLessThan(a.lower, a.discharge)
        XCTAssertGreaterThan(a.upper, a.discharge)
        // α dominates with these inputs.
        XCTAssertEqual(a.shares.max(by: { $0.value < $1.value })?.key, .alpha)
    }

    func testExcludedStationsDoNotCount() {
        var st = channel(std: 0)
        st[3].included = false
        let u = DischargeUncertainty.estimate(stations: st, alpha: 0.85, sources: sources())
        XCTAssertEqual(u.discharge, 15.3, accuracy: 1e-9)
    }

    // MARK: - Quality

    func testCleanGaugingIsGood() {
        let st = channel(std: 0.1)
        let u = DischargeUncertainty.estimate(stations: st, alpha: 0.85, sources: sources(0.01, 0.02, 0.02, 0.005))
        let q = GaugingQuality.assess(stations: st, measuredFraction: 1, uncertainty: u,
                                      georeference: .relativeDistanceResidual(0.002))
        XCTAssertEqual(q.doubtful, [])
        XCTAssertEqual(q.check(.measured)?.level, .good)
        XCTAssertEqual(q.check(.maxStationShare)?.value ?? 0, 0.1, accuracy: 1e-9)
        XCTAssertEqual(q.check(.wetStations)?.value, 10)
        XCTAssertEqual(q.check(.wetStations)?.level, .fair)
        XCTAssertEqual(q.check(.variability)?.value ?? 0, 0.1, accuracy: 1e-9)
        XCTAssertEqual(q.check(.georeference)?.level, .good)
        XCTAssertEqual(q.overall, .fair)
    }

    func testThresholds() {
        let st = channel()
        let q = GaugingQuality.assess(stations: st, measuredFraction: 0.7, uncertainty: nil, georeference: .reprojectionPixels(2))
        XCTAssertEqual(q.check(.measured)?.level, .poor)
        XCTAssertEqual(q.check(.georeference)?.level, .fair)
        XCTAssertNil(q.check(.uncertainty))
        XCTAssertEqual(q.overall, .poor)
        let uav = GaugingQuality.assess(stations: st, measuredFraction: nil, uncertainty: nil, georeference: .none)
        XCTAssertNil(uav.check(.georeference))
        XCTAssertNil(uav.check(.measured))
    }

    func testBankStationFasterThanItsNeighbourIsDoubtful() {
        // Like pisco's station 14 (4.87 m/s next to 2.84 m/s and a dry bank).
        var st = channel(std: 0.1)
        st[10].velocity = 1.8
        XCTAssertEqual(GaugingQuality.doubtfulStations(st).map(\.index), [10])
        XCTAssertEqual(GaugingQuality.doubtfulStations(st).first?.reasons, [.jump])
        // Slower towards the bank is normal.
        st[10].velocity = 0.4
        XCTAssertEqual(GaugingQuality.doubtfulStations(st), [])
    }

    func testDoubtfulStations() {
        var st = channel(std: 0.1)
        st[3].velocity = 2.5                    // jump against 1 and 1
        st[3].discharge = 4.25
        st[5].velocity = -0.8                   // against the flow (and a jump)
        st[7].velocity = .nan                   // wet, counted, no velocity
        st[7].discharge = .nan
        st[9].velocityStd = 0.7                 // σ / v = 0.7
        let d = GaugingQuality.doubtfulStations(st)
        XCTAssertEqual(d.map(\.index), [3, 5, 7, 9])
        XCTAssertEqual(d[0].reasons, [.jump])
        XCTAssertEqual(d[1].reasons, [.reverse, .jump])
        XCTAssertEqual(d[2].reasons, [.noVelocity])
        XCTAssertEqual(d[3].reasons, [.highVariability])
        // Interpolated and excluded stations are not flagged.
        st[7].interpolated = true
        st[3].included = false
        XCTAssertEqual(GaugingQuality.doubtfulStations(st).map(\.index), [5, 9])
        let q = GaugingQuality.assess(stations: st, measuredFraction: 1, uncertainty: nil, georeference: .none)
        XCTAssertEqual(q.check(.doubtful)?.value, 2)
        XCTAssertEqual(q.check(.doubtful)?.level, .fair)
    }
}
