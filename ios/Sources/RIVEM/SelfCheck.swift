import Foundation
import RiverCore

/// Quick on-device sanity checks of the core, shown on the home screen and used by the CI autotest.
/// They prove the app links and runs RiverCore on the phone; the full test suite is in Tests/RiverCoreTests.
enum SelfCheck {
    struct Result: Identifiable {
        let name: String
        let passed: Bool
        let detail: String
        var id: String { name }
    }

    static func run() -> [Result] {
        [uavNotMirrored(), homographyRoundTrip()]
    }

    private static func uavNotMirrored() -> Result {
        let name = "UAV de 2 puntos sin espejo"
        do {
            let h = try UAVTransform.matrix(pixel1: Point2(0, 0), pixel2: Point2(100, 0),
                                            world1: Point2(0, 0), world2: Point2(0, 10))
            let p = h.apply(Point2(100, 0))
            let ok = abs(p.x) < 1e-9 && abs(p.y - 10) < 1e-9
            return Result(name: name, passed: ok, detail: String(format: "punto 2 → (%.3f, %.3f), esperado (0, 10)", p.x, p.y))
        } catch {
            return Result(name: name, passed: false, detail: "\(error)")
        }
    }

    private static func homographyRoundTrip() -> Result {
        let name = "Homografía con 4 puntos"
        let truth = Matrix3(rows: [[0.021, -0.004, 500_100.0], [0.002, -0.03, 9_560_200.0], [1e-5, 4e-5, 1]])
        let px = [Point2(100, 800), Point2(1800, 820), Point2(1500, 300), Point2(400, 280)]
        do {
            let h = try Homography.fit(pixel: px, world: px.map(truth.apply))
            let err = Homography.reprojectionErrors(matrix: h, pixel: px, world: px.map(truth.apply)).max() ?? .infinity
            return Result(name: name, passed: err < 1e-3, detail: String(format: "error máximo %.2e m", err))
        } catch {
            return Result(name: name, passed: false, detail: "\(error)")
        }
    }
}

/// CI hook: `simctl launch … -autotest_selfcheck 1` writes the self-check results to Documents and an
/// `autotest_done` file containing `ok` or `fail` (read by ios/ci/simulator-tests.sh).
enum Autotest {
    @MainActor
    static func runIfRequested() async {
        guard UserDefaults.standard.bool(forKey: "autotest_selfcheck") else { return }
        let results = SelfCheck.run()
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let summary = results.map { "\($0.passed ? "PASS" : "FAIL") \($0.name): \($0.detail)" }.joined(separator: "\n")
        try? summary.write(to: docs.appendingPathComponent("autotest_summary.txt"), atomically: true, encoding: .utf8)
        let status = results.allSatisfy(\.passed) ? "ok" : "fail"
        try? status.write(to: docs.appendingPathComponent("autotest_done"), atomically: true, encoding: .utf8)
    }
}
