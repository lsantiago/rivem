import Foundation
import RiverCore

/// RIVeR's `pisco` example (oblique camera, 30 frames) installed as a project ready for PIV: frames, georeferencing,
/// cross section with its bathymetry and ROI mask are done with the same pipeline functions as the screens, so the
/// user only runs steps 4 (PIV) and 5 (Results), and can open steps 2 and 3 to see how they were filled.
///
/// Data (RIVeR 3.8.3, commit `ea65612`, AGPL-3.0, © ORUS): the frames `examples/data/frames/pisco` (downloaded into
/// `ios/Examples/pisco/frames` by CI and bundled), `examples/data/bathymetry/pisco_section.csv`, and the parameters
/// of the notebooks `03_cross_sections` / `05_discharge_calculation` (fps 25, step 2, level 1.765 m → 1.76 m here, 15 stations,
/// alpha 1 → Q = 50.52 m³/s in RIVeR). RIVeR publishes only the pixel → world matrix of pisco, not the control
/// points: points 1 and 4 are the section ends of the notebook, 2 and 3 are upstream on each bank, and the six
/// distances are measured on RIVeR's matrix, so the oblique solution reproduces it (up to a rigid motion).
enum ExampleProject {
    static let projectName = "Ejemplo_Pisco"
    static let riverName = "Río Pisco (ejemplo de RIVeR)"
    static let fps: Double = 25
    static let step = 2
    static let width = 1920
    static let height = 1080
    /// 1, 2 on the left bank (near, far), 3, 4 on the right bank (far, near).
    static let pixels: [Point2] = [Point2(49.94, 935.04), Point2(240, 470), Point2(1250, 480), Point2(1800.19, 687.6)]
    static let distances = ObliqueDistances(d12: 29.426, d23: 28.746, d34: 15.83, d41: 22.154, d13: 28.163, d24: 38.029)
    static let sectionName = "Seccion_Pisco"
    static let sectionLeft = Point2(49.94, 935.04)
    static let sectionRight = Point2(1800.19, 687.6)
    /// The notebook uses 1.765 m, the bank elevation itself; desktop's GUI rounds the bed to cm (1.76) and cannot
    /// reopen a level above both banks, so the GUI's own default level (lower bank to cm) is used.
    static let level: Double = 1.76
    static let numStations = 15
    static let alpha: Double = 1
    static let bathymetryFile = "pisco_section.csv"
    /// Discharge of RIVeR's notebook 05 (its own PIV field, interpolation on).
    static let referenceQ: Double = 50.52

    static var bundleFolder: URL? {
        Bundle.main.resourceURL?.appendingPathComponent("Examples/pisco", isDirectory: true)
    }

    static var bundledFrames: [URL] {
        guard let folder = bundleFolder?.appendingPathComponent("frames", isDirectory: true),
              let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return [] }
        return names.filter { $0.lowercased().hasSuffix(".jpg") }.sorted().map { folder.appendingPathComponent($0) }
    }

    /// The example is only offered when the build bundled its frames (CI builds do).
    static var isAvailable: Bool { bundledFrames.count >= 2 }

    /// Creates `Documents/RIVeR/Ejemplo_Pisco/<date>/` and fills steps 1–3. Synchronous: call it off the main thread.
    static func install(root: URL) throws -> RiverProject {
        let frames: [URL] = bundledFrames
        guard frames.count >= 2, let folder = bundleFolder else {
            throw PipelineError("Esta compilación no incluye los frames del ejemplo.")
        }
        let started = Date()
        let info = VideoInfo(filepath: "pisco.mp4", name: "pisco.mp4", duration: Double(frames.count * step) / fps,
                             fps: fps, width: width, height: height)
        var project = try RiverProject.create(in: root, name: projectName, footage: .oblique, video: info)
        project.settings.json["river_name"] = .string(riverName)
        do {
            // 1. Frames: RIVeR's own extraction (every 2nd frame, names = source frame index).
            try FramesPipeline.saveRange(&project, VideoRange(start: 0, end: frames.count * step, step: step, factor: 1))
            let framesFolder: URL = project.folder.appendingPathComponent(RiverProject.framesFolder, isDirectory: true)
            try FileManager.default.createDirectory(at: framesFolder, withIntermediateDirectories: true)
            for f in frames {
                try FileManager.default.copyItem(at: f, to: framesFolder.appendingPathComponent(f.lastPathComponent))
            }

            // 2. Georeferencing: oblique, 4 points + 6 distances.
            let frame: RGBAImage = try GeometryPipeline.loadFirstFrame(project)
            _ = try GeometryPipeline.applyOblique(&project, pixels: pixels, distances: distances, frame: frame)

            // 3. Cross section: bathymetry, level, stations, alpha → xsections.json + ROI mask.
            let imported = try SectionsPipeline.importBathymetry(from: folder.appendingPathComponent(bathymetryFile),
                                                                 into: project.folder, feet: false)
            var draft = SectionDraft(name: sectionName)
            draft.left = sectionLeft
            draft.right = sectionRight
            draft.bathymetryFile = imported.file
            draft.level = level
            draft.numStations = numStations
            draft.alpha = alpha
            _ = try SectionsPipeline.save(&project, drafts: [draft])
        } catch {
            // Do not leave a half-built example in the list.
            try? FileManager.default.removeItem(at: project.folder)
            let parent: URL = project.folder.deletingLastPathComponent()
            if (try? FileManager.default.contentsOfDirectory(atPath: parent.path))?.isEmpty == true {
                try? FileManager.default.removeItem(at: parent)
            }
            throw error
        }
        AppLog.info("Ejemplo Pisco instalado en \(project.folder.lastPathComponent): \(frames.count) frames, "
            + "\(String(format: "%.1f", Date().timeIntervalSince(started))) s")
        return project
    }
}

/// `-autotest_example 1`: installs the example, runs PIV and discharge with the app's defaults and compares Q with
/// RIVeR's notebook (different PIV field and ROI, so a loose tolerance).
enum ExampleAutotest {
    static let tolerance: Double = 0.15

    static func run(root: URL) async -> [String] {
        var lines: [String] = []
        func report(_ name: String, _ ok: Bool, _ detail: String) {
            let line = "\(ok ? "PASS" : "FAIL") ejemplo \(name): \(detail)"
            lines.append(line)
            if ok { AppLog.info(line) } else { AppLog.error(line) }
        }
        guard ExampleProject.isAvailable else {
            report("disponible", false, "la app no incluye los frames de Examples/pisco")
            return lines
        }
        do {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(ExampleProject.projectName))
            var project: RiverProject = try ExampleProject.install(root: root)
            let reopened = try RiverProject.open(project.folder)
            report("instalado", reopened.settings.stage == .crossSections && reopened.frameFiles.count == ExampleProject.bundledFrames.count,
                   "\(reopened.frameFiles.count) frames, etapa \(reopened.settings.stage)")

            let piv: ProcessingOutcome = try ProcessingPipeline.run(&project, parameters: PIVParameters(),
                                                                    progress: { _, _ in }, cancel: CancellationFlag())
            report("PIV", piv.failed.isEmpty, "\(piv.pairs) pares, \(piv.failed.count) fallidos, rejilla \(piv.rows)x\(piv.columns), "
                   + String(format: "%.1f s", piv.seconds))

            let outcome: ResultsOutcome = try ResultsPipeline.compute(&project, interpolate: true, artificialSeeding: false)
            guard let r = outcome.results.first, let q = r.totalQ else {
                throw PipelineError("sin caudal: \(outcome.errors.joined(separator: "; "))")
            }
            let diff: Double = abs(q - ExampleProject.referenceQ) / ExampleProject.referenceQ
            report("caudal", diff <= tolerance,
                   String(format: "Q = %.2f m3/s, RIVeR (notebook 05) %.2f m3/s, diferencia %.1f %% (tolerancia %.0f %%)",
                          q, ExampleProject.referenceQ, diff * 100, tolerance * 100))
            // Station velocities, for the comparison with desktop's own run of this example.
            let vs: String = (0..<r.stationCount).map { (i: Int) -> String in Fmt.num(r.value(r.velocity, i), 2) }.joined(separator: " ")
            report("estaciones", r.stationCount > 0, "Vs (m/s): \(vs)")

            // Station toggle (desktop's check) and report.
            var toggled: RiverProject = project
            try ResultsPipeline.setCheck(&toggled, section: r.name, station: 13, included: false)
            let without: ResultsOutcome = try ResultsPipeline.compute(&toggled, interpolate: true, artificialSeeding: false)
            let qWithout: Double = without.results.first?.totalQ ?? .nan
            try ResultsPipeline.setCheck(&toggled, section: r.name, station: 13, included: true)
            let back: ResultsOutcome = try ResultsPipeline.compute(&toggled, interpolate: true, artificialSeeding: false)
            let qBack: Double = back.results.first?.totalQ ?? .nan
            report("estacion desmarcada", qWithout.isFinite && abs(qBack - q) < 1e-9 && abs(qWithout - q) > 1e-6,
                   String(format: "sin la estación 14 (interpolada): Q = %.2f m3/s; marcada de nuevo: %.2f m3/s", qWithout, qBack))
            project = toggled
            let folder: URL = project.folder
            let pdf: Result<URL, Error> = await MainActor.run { () -> Result<URL, Error> in
                Result { try ReportPDF.make(project: (try? RiverProject.open(folder)) ?? toggled) }
            }
            switch pdf {
            case let .success(url):
                let size: Int = ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.intValue ?? 0
                let docs: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let copy: URL = docs.appendingPathComponent("Ejemplo_Pisco_informe.pdf")
                try? FileManager.default.removeItem(at: copy)
                try? FileManager.default.copyItem(at: url, to: copy)
                report("informe PDF", size > 20_000, "\(url.lastPathComponent), \(size / 1024) KB")
            case let .failure(e):
                report("informe PDF", false, "\(e)")
            }
            let issues = try RiverProject.open(project.folder).compatibilityIssues()
            report("compatibilidad escritorio", issues.isEmpty, issues.isEmpty ? "sin problemas" : issues.map { $0.message }.joined(separator: "; "))
        } catch {
            report("ejecucion", false, "\(error)")
        }
        return lines
    }
}
