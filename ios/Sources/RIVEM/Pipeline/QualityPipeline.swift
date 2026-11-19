import Foundation
import RiverCore

/// Per-project assumptions of the uncertainty estimate, in `settings.json` under `rivem_uncertainty` (a RIVEM key that
/// desktop ignores). The georeferencing scale error is not an assumption: it comes from the control-point residuals.
struct UncertaintyAssumptions: Equatable, Sendable {
    static let key = "rivem_uncertainty"

    /// Water level read on site (m).
    var levelStd: Double = 0.01
    /// Bathymetry depth (m).
    var depthStd: Double = 0.02
    /// Velocity coefficient α.
    var alphaStd: Double = 0.05

    init() {}

    init(settings: ProjectSettings) {
        guard let o = settings.json[Self.key]?.objectValue else { return }
        if let v = o["level_std"]?.doubleValue, v.isFinite, v >= 0 { levelStd = v }
        if let v = o["depth_std"]?.doubleValue, v.isFinite, v >= 0 { depthStd = v }
        if let v = o["alpha_std"]?.doubleValue, v.isFinite, v >= 0 { alphaStd = v }
    }

    func write(to settings: inout ProjectSettings) {
        settings.json[Self.key] = .object(JSONObject([
            ("level_std", .double(levelStd)),
            ("depth_std", .double(depthStd)),
            ("alpha_std", .double(alphaStd)),
        ]))
    }
}

/// Quality and uncertainty of one section's discharge.
struct SectionAssessment: Identifiable, Sendable {
    let name: String
    var id: String { name }
    let uncertainty: DischargeUncertainty
    let quality: GaugingQuality
    let fit: GeoreferenceFit
    /// Relative scale error used for the georeferencing (from the residuals, at least 0.5 %).
    let scaleStd: Double
}

/// RIVEM addition: traffic light and Q ± U (95 %) of every section, computed from what `xsections.json` holds.
enum QualityPipeline {
    static let minimumScaleStd: Double = 0.005

    static func assess(_ project: RiverProject, results: [SectionResult], pairCount: Int? = nil) -> [SectionAssessment] {
        let assumptions = UncertaintyAssumptions(settings: project.settings)
        let fit: GeoreferenceFit = georeferenceFit(project)
        var scale: Double = minimumScaleStd
        if case let .relativeDistanceResidual(r) = fit, r.isFinite { scale = max(scale, r) }
        let pairs: Int = pairCount ?? ((try? project.readPIVResults())?.pairCount ?? max(project.frameFiles.count - 1, 1))
        return results.map { (r: SectionResult) -> SectionAssessment in
            let st: [GaugingStation] = stations(r)
            let sources = UncertaintySources(levelStd: assumptions.levelStd, depthStd: assumptions.depthStd,
                                             alphaStd: assumptions.alphaStd, scaleRelativeStd: scale, pairCount: pairs)
            let u = DischargeUncertainty.estimate(stations: st, alpha: r.alpha ?? 0.85, sources: sources)
            let q = GaugingQuality.assess(stations: st, measuredFraction: r.measuredQ, uncertainty: u, georeference: fit)
            return SectionAssessment(name: r.name, uncertainty: u, quality: q, fit: fit, scaleStd: scale)
        }
    }

    /// Stations as the core estimates need them. With "interpolate" on, desktop fills and counts unticked stations
    /// and stations without a velocity; those are the interpolated ones.
    static func stations(_ r: SectionResult) -> [GaugingStation] {
        (0..<r.stationCount).map { (i: Int) -> GaugingStation in
            let checked: Bool = i >= r.check.count || r.check[i]
            let raw: Double? = r.value(r.rawVelocity, i)
            var std: Double = .nan
            if let lo = r.value(r.minusStd, i), let hi = r.value(r.plusStd, i) { std = abs(hi - lo) / 2 }
            return GaugingStation(distance: r.value(r.distance, i) ?? .nan,
                                  depth: r.value(r.depth, i) ?? 0,
                                  area: r.value(r.area, i) ?? 0,
                                  velocity: r.value(r.velocity, i) ?? .nan,
                                  velocityStd: std,
                                  discharge: r.value(r.q, i) ?? .nan,
                                  included: checked || r.interpolated,
                                  interpolated: r.interpolated && (!checked || raw == nil))
        }
    }

    /// Oblique: RMS of the distance residuals over the mean distance. 3D camera: mean reprojection error (px). UAV:
    /// nothing to check (two points fit exactly).
    static func georeferenceFit(_ project: RiverProject) -> GeoreferenceFit {
        switch project.settings.footage {
        case .oblique:
            guard let cp = project.settings.controlPoints, let d = ObliqueDistances(cp.distances), cp.pixels.count == 4,
                  let solution = try? ObliqueTransform.solve(pixels: cp.pixels, distances: d) else { return .none }
            let res: [Double] = solution.distanceResiduals
            guard !res.isEmpty else { return .none }
            let rms: Double = (res.reduce(0) { $0 + $1 * $1 } / Double(res.count)).squareRoot()
            let mean: Double = d.values.reduce(0, +) / Double(d.values.count)
            return mean > 0 ? .relativeDistanceResidual(rms / mean) : .none
        case .ipcam:
            let url: URL = ProjectFiles.url(project, ProjectFiles.cameraSolution)
            guard let error = (try? JSONValue.read(url))?.objectValue?["error"]?.doubleValue, error.isFinite else { return .none }
            return .reprojectionPixels(error)
        default:
            return .none
        }
    }

    // MARK: Texts

    static func levelText(_ l: QualityLevel) -> String {
        switch l {
        case .good: return "Buena"
        case .fair: return "Aceptable"
        case .poor: return "Revisar"
        }
    }

    static func title(_ c: QualityCriterion) -> String {
        switch c {
        case .measured: return "Caudal medido"
        case .maxStationShare: return "Mayor aporte de una estación"
        case .wetStations: return "Estaciones con agua y velocidad"
        case .variability: return "Variabilidad entre pares"
        case .doubtful: return "Estaciones dudosas"
        case .uncertainty: return "Incertidumbre (95 %)"
        case .georeference: return "Ajuste de la georreferenciación"
        }
    }

    static func valueText(_ c: QualityCheck) -> String {
        switch c.criterion {
        case .measured, .maxStationShare, .variability, .uncertainty: return Fmt.percent(c.value, 1)
        case .wetStations, .doubtful: return Fmt.num(c.value, 0)
        case .georeference: return c.value < 0.5 ? Fmt.percent(c.value, 2) : "\(Fmt.num(c.value, 2)) px"
        }
    }

    /// What the criterion means and its thresholds (good / fair).
    static func hint(_ c: QualityCriterion, georeference: GeoreferenceFit) -> String {
        switch c {
        case .measured: return "Parte del caudal con velocidad medida, no interpolada. Buena ≥ 90 %, aceptable ≥ 75 %."
        case .maxStationShare: return "ISO 748 recomienda que ninguna estación lleve más del 10 % del caudal. Aceptable ≤ 20 %; si no, usa más estaciones."
        case .wetStations: return "Buena ≥ 12, aceptable ≥ 8. Con pocas estaciones el perfil queda mal descrito."
        case .variability: return "Mediana de σ/v entre pares. Buena ≤ 25 %, aceptable ≤ 50 %. Alta: pocos trazadores, reflejos u oleaje."
        case .doubtful: return "Saltos respecto a las vecinas, contraflujo, mucha variabilidad o sin velocidad. Revísalas en la tabla."
        case .uncertainty: return "Mitad del intervalo del 95 % (Monte Carlo) sobre Q. Buena ≤ 10 %, aceptable ≤ 20 %."
        case .georeference:
            if case .reprojectionPixels = georeference { return "Error medio de reproyección de los puntos. Bueno ≤ 1 px, aceptable ≤ 3 px." }
            return "Residuo RMS de las 6 distancias sobre su media. Bueno ≤ 0,5 %, aceptable ≤ 2 %. Alto: distancia mal medida o punto mal marcado."
        }
    }

    static func sourceText(_ s: UncertaintySource) -> String {
        switch s {
        case .velocity: return "Velocidad (promedio de pares)"
        case .alpha: return "Coeficiente α"
        case .level: return "Nivel del agua"
        case .bathymetry: return "Batimetría"
        case .georeference: return "Georreferenciación (escala)"
        case .interpolation: return "Estaciones interpoladas"
        }
    }

    static func reasonText(_ r: DoubtReason) -> String {
        switch r {
        case .noVelocity: return "sin velocidad"
        case .highVariability: return "muy variable entre pares"
        case .jump: return "salto respecto a sus vecinas"
        case .reverse: return "contraflujo"
        }
    }
}
