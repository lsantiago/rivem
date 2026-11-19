import Foundation
import RiverCore

/// What the Results screen shows for one section, read back from `xsections.json` (works for files written by the
/// app or by desktop's Python).
struct SectionResult: Identifiable {
    let name: String
    var id: String { name }
    let distance: [Double]
    /// Surface velocity used for the discharge (filled / seeded profile when those options are on), m/s.
    let velocity: [Double]
    /// Measured surface velocity (`streamwise_velocity_magnitude`, NaN where none), before filling.
    let rawVelocity: [Double]
    let minusStd: [Double]
    let plusStd: [Double]
    let depth: [Double]
    let q: [Double]
    /// Wetted area per station (m²).
    let area: [Double]
    /// 5th / 95th percentile of the surface velocity over the pairs (m/s).
    let p5: [Double]
    let p95: [Double]
    let check: [Bool]
    let stationX: [Double]
    let stationY: [Double]
    /// Real-world station coordinates and streamwise direction (m), for desktop's chevron glyph.
    let east: [Double]
    let north: [Double]
    let streamEast: [Double]
    let streamNorth: [Double]
    let streamX: [Double]
    let streamY: [Double]
    let totalQ: Double?
    let totalQStd: Double?
    let totalA: Double?
    let totalW: Double?
    let meanV: Double?
    let meanVs: Double?
    let measuredQ: Double?
    let interpolatedQ: Double?
    let level: Double?
    let alpha: Double?
    let interpolated: Bool
    let artificialSeeding: Bool

    init?(section s: CrossSection) {
        let j = s.json
        guard let distance = j["distance"]?.doubleArray else { return nil }
        func arr(_ k: String) -> [Double] { j[k]?.doubleArray ?? [] }
        let interp: Bool = j["interpolated"]?.boolValue ?? false
        let seeding: Bool = j["artificial_seeding"]?.boolValue ?? false
        name = s.name
        self.distance = distance
        interpolated = interp
        artificialSeeding = seeding
        var v: [Double] = arr("streamwise_velocity_magnitude")
        if seeding {
            let seeded: [Double] = interp ? arr("filled_seeded_vel_profile") : arr("seeded_vel_profile")
            if seeded.count == distance.count { v = seeded }
        } else if interp {
            let filled: [Double] = arr("filled_streamwise_velocity_magnitude")
            if filled.count == distance.count { v = filled }
        }
        velocity = v
        rawVelocity = arr("streamwise_velocity_magnitude")
        minusStd = arr("minus_std")
        plusStd = arr("plus_std")
        depth = arr("depth")
        q = arr("Q")
        area = arr("A")
        p5 = arr("5th_percentile")
        p95 = arr("95th_percentile")
        check = (j["check"]?.arrayValue ?? []).map { $0.boolValue ?? (($0.doubleValue ?? 1) != 0) }
        stationX = arr("x")
        stationY = arr("y")
        east = arr("east")
        north = arr("north")
        streamEast = arr("streamwise_east")
        streamNorth = arr("streamwise_north")
        streamX = arr("streamwise_x")
        streamY = arr("streamwise_y")
        totalQ = j["total_Q"]?.doubleValue
        totalQStd = j["total_q_std"]?.doubleValue
        totalA = j["total_A"]?.doubleValue
        totalW = j["total_W"]?.doubleValue
        meanV = j["mean_V"]?.doubleValue
        meanVs = j["mean_Vs"]?.doubleValue
        measuredQ = j["measured_Q"]?.doubleValue
        interpolatedQ = j["interpolated_Q"]?.doubleValue
        level = s.level
        alpha = s.alpha
    }

    var stationCount: Int { distance.count }

    func value(_ a: [Double], _ i: Int) -> Double? {
        guard i < a.count, a[i].isFinite else { return nil }
        return a[i]
    }
}

struct ResultsOutcome {
    let results: [SectionResult]
    let errors: [String]
}

/// Step 5 (desktop "Results" → `update-xsection` per section): discharge of every section from `piv_results.json`
/// (`DischargeCalculator`, frame step = `video_range.step`, fps = `video.fps`), written into `xsections.json`.
enum ResultsPipeline {
    static func compute(_ project: inout RiverProject, interpolate: Bool, artificialSeeding: Bool) throws -> ResultsOutcome {
        guard let piv = try project.readPIVResults() else { throw PipelineError("No hay resultados PIV: ejecuta el procesamiento.") }
        guard let matrix = try project.readMatrix() else { throw PipelineError("Falta la matriz de transformación.") }
        guard var sections = try project.readCrossSections(), !sections.sections.isEmpty else {
            throw PipelineError("No hay secciones definidas.")
        }
        guard let range = project.settings.videoRange, let fps = project.settings.video?.fps, fps > 0 else {
            throw PipelineError("Faltan video_range o video.fps en settings.json.")
        }
        let options = DischargeOptions(frameStep: range.step, fps: fps, interpolate: interpolate, artificialSeeding: artificialSeeding)
        var errors: [String] = []
        for i in sections.sections.indices {
            let s: CrossSection = sections.sections[i]
            guard let url = project.bathymetryURL(for: s) else {
                errors.append("\(s.name): la batimetría no está en la carpeta del proyecto")
                continue
            }
            do {
                let bathymetry: Bathymetry = try Bathymetry.read(url)
                let r: SectionDischarge = try DischargeCalculator.update(&sections, index: i, bathymetry: bathymetry, piv: piv,
                                                                        matrix: matrix, options: options)
                AppLog.info("Caudal \(s.name): Q = \(Fmt.num(r.totalQ, 4)) m3/s, A = \(Fmt.num(r.totalA, 3)) m2, "
                    + "W = \(Fmt.num(r.totalW, 3)) m, Vm = \(Fmt.num(r.meanV, 3)) m/s, medido \(Fmt.percent(r.measuredQ))")
            } catch {
                errors.append("\(s.name): \(error)")
                AppLog.error("Caudal \(s.name): \(error)")
            }
        }
        try project.writeCrossSections(sections)
        let results: [SectionResult] = sections.sections.compactMap { SectionResult(section: $0) }
        return ResultsOutcome(results: results, errors: errors)
    }

    /// Includes or excludes one station of a section (`check` in `xsections.json`, as desktop's results table does).
    /// Call `compute` afterwards.
    static func setCheck(_ project: inout RiverProject, section name: String, station: Int, included: Bool) throws {
        guard var sections = try project.readCrossSections(),
              let index = sections.sections.firstIndex(where: { $0.name == name }) else {
            throw PipelineError("No se encontró la sección \(name).")
        }
        var s: CrossSection = sections.sections[index]
        let count: Int = s.json["distance"]?.doubleArray?.count ?? 0
        var check: [Bool] = (s.json["check"]?.arrayValue ?? []).map { $0.boolValue ?? (($0.doubleValue ?? 1) != 0) }
        if check.count != count { check = [Bool](repeating: true, count: count) }
        guard station >= 0, station < count else { throw PipelineError("Estación fuera de rango.") }
        check[station] = included
        s.json["check"] = .array(check.map { JSONValue.bool($0) })
        sections.sections[index] = s
        try project.writeCrossSections(sections)
        AppLog.info("Sección \(name): estación \(station + 1) \(included ? "incluida" : "excluida")")
    }

    static func read(_ project: RiverProject) -> [SectionResult] {
        guard let sections = try? project.readCrossSections() else { return [] }
        return sections.sections.compactMap { SectionResult(section: $0) }
    }

    /// One row per station of every section (SI units, dot decimals).
    static func stationsCSV(_ results: [SectionResult]) -> String {
        var out = "section,station,distance_m,depth_m,velocity_ms,minus_std_ms,plus_std_ms,Q_m3s,check,x_px,y_px\n"
        for r in results {
            for i in 0..<r.stationCount {
                let cols: [String] = [
                    r.name, "\(i + 1)",
                    csvNumber(r.value(r.distance, i)), csvNumber(r.value(r.depth, i)), csvNumber(r.value(r.velocity, i)),
                    csvNumber(r.value(r.minusStd, i)), csvNumber(r.value(r.plusStd, i)), csvNumber(r.value(r.q, i)),
                    i < r.check.count ? (r.check[i] ? "1" : "0") : "",
                    csvNumber(r.value(r.stationX, i)), csvNumber(r.value(r.stationY, i)),
                ]
                out += cols.joined(separator: ",") + "\n"
            }
        }
        out += "\nsection,total_Q_m3s,total_q_std_m3s,total_A_m2,total_W_m,mean_V_ms,mean_Vs_ms,measured_Q\n"
        for r in results {
            let cols: [String] = [r.name, csvNumber(r.totalQ), csvNumber(r.totalQStd), csvNumber(r.totalA), csvNumber(r.totalW),
                                  csvNumber(r.meanV), csvNumber(r.meanVs), csvNumber(r.measuredQ)]
            out += cols.joined(separator: ",") + "\n"
        }
        return out
    }

    private static func csvNumber(_ v: Double?) -> String {
        guard let v = v, v.isFinite else { return "" }
        return String(format: "%.6g", v)
    }
}

/// Step 6 (desktop "Report"/export): the project folder as a zip that unpacks as `<name>/<YYYYMMDDTHHMM>/` (drop it
/// in desktop's RIVeR workspace), and the stations CSV.
enum ExportPipeline {
    static var exportFolder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
    }

    static func zip(_ project: RiverProject, includeVideo: Bool) throws -> URL {
        let fm = FileManager.default
        let nameFolder: String = project.folder.deletingLastPathComponent().lastPathComponent
        let stamp: String = project.folder.lastPathComponent
        let staging: URL = exportFolder.appendingPathComponent("staging-" + UUID().uuidString, isDirectory: true)
        let top: URL = staging.appendingPathComponent(nameFolder, isDirectory: true)
        try fm.createDirectory(at: top, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        let copy: URL = top.appendingPathComponent(stamp, isDirectory: true)
        try fm.copyItem(at: project.folder, to: copy)
        if !includeVideo, let video = ProjectFiles.videoURL(project) {
            try? fm.removeItem(at: copy.appendingPathComponent(video.lastPathComponent))
        }
        let dest: URL = exportFolder.appendingPathComponent("\(nameFolder)_\(stamp).zip")
        if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }

        var coordinationError: NSError?
        var innerError: Error?
        NSFileCoordinator().coordinate(readingItemAt: top, options: [.forUploading], error: &coordinationError) { (zipURL: URL) in
            do {
                try fm.copyItem(at: zipURL, to: dest)
            } catch {
                innerError = error
            }
        }
        if let e = coordinationError { throw e }
        if let e = innerError { throw e }
        AppLog.info("Proyecto exportado: \(dest.lastPathComponent)")
        return dest
    }

    static func stationsCSV(_ project: RiverProject) throws -> URL {
        let results: [SectionResult] = ResultsPipeline.read(project)
        guard !results.isEmpty else { throw PipelineError("No hay resultados de caudal para exportar.") }
        try FileManager.default.createDirectory(at: exportFolder, withIntermediateDirectories: true)
        let name: String = project.folder.deletingLastPathComponent().lastPathComponent
        let url: URL = exportFolder.appendingPathComponent("\(name)_\(project.folder.lastPathComponent)_estaciones.csv")
        try Data(ResultsPipeline.stationsCSV(results).utf8).write(to: url, options: .atomic)
        return url
    }
}
