import Charts
import RiverCore
import SwiftUI

/// Step 5, desktop "Results": discharge per section (`DischargeCalculator` with frame step = `video_range.step` and
/// fps = `video.fps`), options "interpolate" and "artificial seeding", totals, velocity and depth profiles, the
/// median PIV field over the first frame and the station table. Values are stored in SI and shown in the project's
/// unit system.
struct ResultsStepView: View {
    let model: WorkflowModel

    @State private var results: [SectionResult] = []
    @State private var interpolate = false
    @State private var seeding = false
    @State private var busy = false
    @State private var error: String? = nil
    @State private var vectors: [CanvasSegment] = []
    @State private var loaded = false

    var body: some View {
        CanvasStepLayout {
            if model.firstFrame != nil {
                ImageCanvas(image: model.firstFrame, imageSize: model.frameSize, segments: vectors + stationSegments)
            } else {
                NoFrameView()
            }
        } controls: {
            resultsForm
        }
        .onAppear(perform: load)
    }

    private var units: UnitDisplay { model.units }

    private var resultsForm: some View {
        Form {
            Section("Opciones de cálculo") {
                Toggle("Interpolar estaciones sin dato", isOn: $interpolate)
                Toggle("Siembra artificial (perfil filtrado por gradiente)", isOn: $seeding)
                Button {
                    recompute()
                } label: {
                    if busy { ProgressView() } else { Label("Recalcular caudal", systemImage: "arrow.clockwise") }
                }
                .disabled(busy)
                UnitPicker(model: model)
                ErrorBanner(message: error)
            }
            if results.isEmpty && !busy {
                Section { Text("Sin resultados de caudal. Pulsa Recalcular.").foregroundStyle(.secondary) }
            }
            ForEach(results) { r in
                SectionResultView(result: r, units: units)
            }
            if results.count > 1 {
                Section("Todas las secciones") {
                    let qs: [Double] = results.compactMap { $0.totalQ }.filter { $0.isFinite }
                    let mean: Double = qs.isEmpty ? .nan : qs.reduce(0, +) / Double(qs.count)
                    LabeledContent("Caudal medio", value: units.dischargeText(mean))
                }
            }
        }
    }

    /// Streamwise surface velocity at the stations (pixels per pair, from `streamwise_x/y`), drawn in white.
    private var stationSegments: [CanvasSegment] {
        var out: [CanvasSegment] = []
        let scale: Double = vectorScale
        for r in results {
            let n: Int = min(r.stationX.count, r.stationY.count, r.streamX.count, r.streamY.count)
            for i in 0..<n {
                let x: Double = r.stationX[i], y: Double = r.stationY[i]
                let dx: Double = r.streamX[i], dy: Double = r.streamY[i]
                guard x.isFinite, y.isFinite, dx.isFinite, dy.isFinite else { continue }
                out.append(CanvasSegment(a: Point2(x, y), b: Point2(x + dx * scale, y + dy * scale), color: .white, width: 2.5, arrow: true))
            }
            if let first = r.stationX.indices.first, let last = r.stationX.indices.last,
               r.stationY.count == r.stationX.count {
                out.append(CanvasSegment(a: Point2(r.stationX[first], r.stationY[first]), b: Point2(r.stationX[last], r.stationY[last]),
                                         color: .yellow, width: 1.5, dashed: true))
            }
        }
        return out
    }

    @State private var vectorScale: Double = 10

    private func load() {
        guard !loaded, let project = model.project else { return }
        loaded = true
        results = ResultsPipeline.read(project)
        if let first = results.first {
            interpolate = first.interpolated
            seeding = first.artificialSeeding
        }
        let width: Double = Double(model.frameSize.width)
        Task { @MainActor in
            let field: (segments: [CanvasSegment], scale: Double)? = await Task.detached(priority: .utility) { () -> (segments: [CanvasSegment], scale: Double)? in
                guard let piv = try? project.readPIVResults() else { return nil }
                return Self.quiver(piv, imageWidth: width)
            }.value
            if let f = field {
                vectors = f.segments
                vectorScale = f.scale
            }
        }
    }

    /// Median PIV field as arrows, scaled so the median vector is ~4 % of the frame width, at most ~1200 arrows.
    nonisolated static func quiver(_ piv: PIVResults, imageWidth: Double) -> (segments: [CanvasSegment], scale: Double) {
        let n: Int = min(piv.x.count, piv.y.count, piv.uMedian.count, piv.vMedian.count)
        var magnitudes: [Double] = []
        for k in 0..<n {
            let m: Double = hypot(piv.uMedian[k], piv.vMedian[k])
            if m.isFinite { magnitudes.append(m) }
        }
        guard !magnitudes.isEmpty else { return ([], 10) }
        magnitudes.sort()
        let typical: Double = max(magnitudes[magnitudes.count / 2], 1e-6)
        let scale: Double = min(max(0.04 * imageWidth / typical, 1), 200)
        let stride: Int = max(1, Int((Double(n) / 1200).squareRoot().rounded(.up)))
        var out: [CanvasSegment] = []
        for row in Swift.stride(from: 0, to: piv.rows, by: stride) {
            for col in Swift.stride(from: 0, to: piv.columns, by: stride) {
                let k: Int = row * piv.columns + col
                guard k < n else { continue }
                let x: Double = piv.x[k], y: Double = piv.y[k]
                let u: Double = piv.uMedian[k], v: Double = piv.vMedian[k]
                guard x.isFinite, y.isFinite, u.isFinite, v.isFinite else { continue }
                out.append(CanvasSegment(a: Point2(x, y), b: Point2(x + u * scale, y + v * scale), color: .cyan, width: 1.2, arrow: true))
            }
        }
        return (out, scale)
    }

    private func recompute() {
        let interp: Bool = interpolate
        let seed: Bool = seeding
        error = nil
        busy = true
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let done: (RiverProject, ResultsOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, ResultsOutcome) in
                    var p = project
                    let o = try ResultsPipeline.compute(&p, interpolate: interp, artificialSeeding: seed)
                    return (p, o)
                }.value
                model.replace(done.0)
                results = done.1.results
                if !done.1.errors.isEmpty { error = done.1.errors.joined(separator: "\n") }
            } catch {
                self.error = "\(error)"
                AppLog.error("Resultados: \(error)")
                model.reload()
            }
            busy = false
        }
    }
}

/// SI / imperial display preference (`settings.unit_system`).
private struct UnitPicker: View {
    let model: WorkflowModel

    var body: some View {
        Picker("Unidades", selection: Binding<UnitSystem>(
            get: { model.settings?.unitSystem ?? .si },
            set: { system in
                try? model.update { p in
                    p.settings.unitSystem = system
                    try p.save()
                }
            }
        )) {
            Text("SI (m, m³/s)").tag(UnitSystem.si)
            Text("Imperial (ft, ft³/s)").tag(UnitSystem.imperial)
        }
    }
}

/// Totals, charts and table of one section.
private struct SectionResultView: View {
    let result: SectionResult
    let units: UnitDisplay

    var body: some View {
        Section(result.name) {
            SectionTotals(result: result, units: units)
            VelocityProfileChart(result: result, units: units)
                .frame(height: 170)
            DepthProfileChart(result: result, units: units)
                .frame(height: 140)
            DisclosureGroup("Tabla de estaciones") {
                StationTable(result: result, units: units)
            }
        }
    }
}

private struct SectionTotals: View {
    let result: SectionResult
    let units: UnitDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(units.dischargeText(result.totalQ))
                .font(.title2.bold())
            if let std = result.totalQStd, std.isFinite {
                Text("± \(units.dischargeText(std)) (desvío entre pares)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        LabeledContent("Área", value: units.areaText(result.totalA))
        LabeledContent("Ancho", value: units.lengthText(result.totalW))
        LabeledContent("Velocidad media", value: units.velocityText(result.meanV, 3))
        LabeledContent("Velocidad superficial media", value: units.velocityText(result.meanVs, 3))
        LabeledContent("Caudal medido", value: Fmt.percent(result.measuredQ))
        if let a = result.alpha { LabeledContent("α", value: Fmt.num(a, 2)) }
        if let l = result.level { LabeledContent("Nivel", value: units.lengthText(l, 3)) }
    }
}

private struct ChartSample: Identifiable {
    let id: Int
    let x: Double
    let y: Double
    let low: Double
    let high: Double
}

private struct VelocityProfileChart: View {
    let result: SectionResult
    let units: UnitDisplay

    private var samples: [ChartSample] {
        var out: [ChartSample] = []
        for i in 0..<result.stationCount {
            guard let d = result.value(result.distance, i), let v = result.value(result.velocity, i) else { continue }
            let lo: Double = result.value(result.minusStd, i) ?? v
            let hi: Double = result.value(result.plusStd, i) ?? v
            out.append(ChartSample(id: i, x: units.length(d), y: units.velocity(v), low: units.velocity(lo), high: units.velocity(hi)))
        }
        return out
    }

    var body: some View {
        let xLabel: String = "Distancia (\(units.lengthUnit))"
        let yLabel: String = "Velocidad (\(units.velocityUnit))"
        Chart(samples) { s in
            AreaMark(x: .value(xLabel, s.x), yStart: .value("mín", s.low), yEnd: .value("máx", s.high))
                .foregroundStyle(Color.blue.opacity(0.15))
            LineMark(x: .value(xLabel, s.x), y: .value(yLabel, s.y))
                .foregroundStyle(Color.blue)
            PointMark(x: .value(xLabel, s.x), y: .value(yLabel, s.y))
                .foregroundStyle(Color.blue)
                .symbolSize(16)
        }
        .chartXAxisLabel(xLabel)
        .chartYAxisLabel(yLabel)
    }
}

private struct DepthProfileChart: View {
    let result: SectionResult
    let units: UnitDisplay

    private var samples: [ChartSample] {
        var out: [ChartSample] = []
        for i in 0..<result.stationCount {
            guard let d = result.value(result.distance, i), let h = result.value(result.depth, i) else { continue }
            out.append(ChartSample(id: i, x: units.length(d), y: -units.length(h), low: -units.length(h), high: 0))
        }
        return out
    }

    var body: some View {
        let xLabel: String = "Distancia (\(units.lengthUnit))"
        let yLabel: String = "Profundidad (\(units.lengthUnit))"
        Chart(samples) { s in
            AreaMark(x: .value(xLabel, s.x), yStart: .value("lecho", s.low), yEnd: .value("superficie", s.high))
                .foregroundStyle(Color.cyan.opacity(0.3))
            LineMark(x: .value(xLabel, s.x), y: .value(yLabel, s.y))
                .foregroundStyle(Color.brown)
        }
        .chartXAxisLabel(xLabel)
        .chartYAxisLabel(yLabel)
    }
}

private struct StationTable: View {
    let result: SectionResult
    let units: UnitDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            StationRow(cells: ["#", "dist", "prof", "V", "Q"], header: true)
            ForEach(0..<result.stationCount, id: \.self) { i in
                StationRow(cells: cells(i), header: false)
            }
            Text("dist y prof en \(units.lengthUnit), V en \(units.velocityUnit), Q en \(units.dischargeUnit).")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func cells(_ i: Int) -> [String] {
        let d: String = result.value(result.distance, i).map { Fmt.num(units.length($0), 2) } ?? "—"
        let h: String = result.value(result.depth, i).map { Fmt.num(units.length($0), 2) } ?? "—"
        let v: String = result.value(result.velocity, i).map { Fmt.num(units.velocity($0), 3) } ?? "—"
        let q: String = result.value(result.q, i).map { Fmt.num(units.discharge($0), 3) } ?? "—"
        let unchecked: Bool = i < result.check.count && !result.check[i]
        return ["\(i + 1)" + (unchecked ? "*" : ""), d, h, v, q]
    }
}

private struct StationRow: View {
    let cells: [String]
    let header: Bool

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<cells.count, id: \.self) { k in
                Text(cells[k])
                    .frame(maxWidth: .infinity, alignment: k == 0 ? .leading : .trailing)
            }
        }
        .font(header ? Font.caption.bold() : Font.caption.monospacedDigit())
    }
}
