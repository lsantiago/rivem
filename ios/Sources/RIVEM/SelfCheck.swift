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
/// `autotest_done` file containing `ok` or `fail` (read by ios/ci/simulator-tests.sh). Every project found in the
/// workspace (the CI copies a desktop fixture there) is opened and checked too. With `-autotest_pipeline 1` the
/// synthetic end-to-end measurement (`PipelineAutotest`) runs first, in the background, and its lines are added.
///
/// Screenshot hooks (no checks): `-autotest_open_first 1` opens the first project of the list, and
/// `-autotest_open_project <name> -autotest_step <step>` opens the project whose name folder is `<name>` at a
/// workflow step (`WorkflowStep` raw values: summary, video, geometry, sections, processing, results, export; `list`
/// shows the step list). `-autotest_video <file>` runs `VideoAutotest` on a real video copied into Documents.
enum Autotest {
    static var isPipelineRequested: Bool { UserDefaults.standard.bool(forKey: "autotest_pipeline") }
    static var isSelfCheckRequested: Bool { UserDefaults.standard.bool(forKey: "autotest_selfcheck") }

    /// `-autotest_video <file in Documents>`: real-video extraction check (`VideoAutotest`).
    static var videoToTest: String? { UserDefaults.standard.string(forKey: "autotest_video") }

    /// `-autotest_example 1`: installs RIVeR's pisco example and runs PIV + discharge (`ExampleAutotest`).
    static var isExampleRequested: Bool { UserDefaults.standard.bool(forKey: "autotest_example") }

    static var projectToOpen: String? { UserDefaults.standard.string(forKey: "autotest_open_project") }

    /// `-autotest_station <n>`: the Results screen opens with station n (1-based) of the first section selected.
    static var stationToSelect: Int? {
        let n: Int = UserDefaults.standard.integer(forKey: "autotest_station")
        return n > 0 ? n : nil
    }

    /// `-autotest_results_vectors 1`: the Results screen opens on the coloured PIV field.
    /// `-autotest_results_scroll quality`: scroll the results form to the quality section.
    static var resultsScroll: String? { UserDefaults.standard.string(forKey: "autotest_results_scroll") }
    static var resultsVectors: Bool { UserDefaults.standard.bool(forKey: "autotest_results_vectors") }

    static var showStepList: Bool { UserDefaults.standard.string(forKey: "autotest_step") == "list" }
    /// `-autotest_step site`: the site screen (rating curve) of the opened project.
    static var showSite: Bool { UserDefaults.standard.string(forKey: "autotest_step") == "site" }

    static var stepToOpen: WorkflowStep? {
        guard let raw = UserDefaults.standard.string(forKey: "autotest_step") else { return nil }
        return WorkflowStep(rawValue: raw)
    }

    @MainActor
    static func runIfRequested(workspace: Workspace) async {
        guard isSelfCheckRequested || isPipelineRequested || videoToTest != nil || isExampleRequested else { return }
        var lines: [String] = []
        if isPipelineRequested {
            let root: URL = workspace.root
            let pipelineLines: [String] = await Task.detached(priority: .userInitiated) { () -> [String] in
                PipelineAutotest.run(root: root)
            }.value
            lines.append(contentsOf: pipelineLines)
        }
        if let video = videoToTest {
            let root: URL = workspace.root
            let videoLines: [String] = await Task.detached(priority: .userInitiated) { () async -> [String] in
                await VideoAutotest.run(root: root, videoName: video)
            }.value
            lines.append(contentsOf: videoLines)
        }
        if isExampleRequested {
            let root: URL = workspace.root
            let exampleLines: [String] = await Task.detached(priority: .userInitiated) { () async -> [String] in
                await ExampleAutotest.run(root: root)
            }.value
            lines.append(contentsOf: exampleLines)
        }
        lines.append(contentsOf: SelfCheck.run().map { "\($0.passed ? "PASS" : "FAIL") \($0.name): \($0.detail)" })
        workspace.reload()
        for summary in workspace.projects {
            lines.append(projectLine(summary))
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? lines.joined(separator: "\n").write(to: docs.appendingPathComponent("autotest_summary.txt"), atomically: true, encoding: .utf8)
        let status = lines.allSatisfy { $0.hasPrefix("PASS") } ? "ok" : "fail"
        for line in lines { AppLog.info("autotest: \(line)") }
        AppLog.flush()
        try? status.write(to: docs.appendingPathComponent("autotest_done"), atomically: true, encoding: .utf8)
    }

    /// Opens the project and checks that its matrix sends each section's pixel end points to the stored real-world
    /// coordinates (desktop stores them rounded to the centimetre).
    private static func projectLine(_ summary: ProjectSummary) -> String {
        let label = "proyecto \(summary.name)/\(summary.stamp)"
        do {
            let p = try RiverProject.open(summary.folder)
            let issues = p.compatibilityIssues()
            let sections = try p.readCrossSections()?.sections ?? []
            var worst = 0.0
            if let m = try p.readMatrix() {
                for s in sections {
                    for (px, w) in [(s.pixelLeft, s.worldLeft), (s.pixelRight, s.worldRight)] {
                        if let px, let w { worst = max(worst, m.apply(px).distance(to: w)) }
                    }
                }
            }
            let ok = issues.isEmpty && worst < 0.01
            return "\(ok ? "PASS" : "FAIL") \(label): \(p.settings.footage?.rawValue ?? "?"), \(sections.count) secciones, "
                + "\(issues.count) problemas de compatibilidad, error matriz \(String(format: "%.4f", worst)) m"
                + (issues.isEmpty ? "" : " [\(issues.map(\.message).joined(separator: "; "))]")
        } catch {
            return "FAIL \(label): \(error)"
        }
    }
}
