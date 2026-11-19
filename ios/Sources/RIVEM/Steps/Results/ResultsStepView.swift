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
    @State private var field: [QuiverVector] = []
    @State private var fieldRange: ClosedRange<Double> = 0...1
    @State private var loaded = false
    @State private var mode: CanvasMode = .section
    @State private var selected: StationRef? = nil

    enum CanvasMode: String, CaseIterable, Identifiable {
        case section = "Sección"
        case vectors = "Vectores"
        var id: String { rawValue }
    }

    var body: some View {
        CanvasStepLayout {
            if model.firstFrame != nil {
                canvas
            } else {
                NoFrameView()
            }
        } controls: {
            resultsForm
        }
        .onAppear(perform: load)
    }

    private var canvas: some View {
        let results: [SectionResult] = self.results
        let range: ClosedRange<Double> = stationRange
        let selected: StationRef? = self.selected
        let layer: ((inout GraphicsContext, CanvasLayout, Double) -> Void)? = mode == .section
            ? { (context: inout GraphicsContext, layout: CanvasLayout, time: Double) in
                ChevronLayer.draw(&context, layout: layout, results: results, range: range, time: time, selected: selected)
            }
            : nil
        return ImageCanvas(image: model.firstFrame, imageSize: model.frameSize,
                           segments: mode == .vectors ? fieldSegments : [],
                           onTap: tap, animated: layer)
            .overlay(alignment: .topLeading) {
                if selected == nil {
                    Picker("Vista", selection: $mode) {
                        ForEach(CanvasMode.allCases) { m in Text(m.rawValue).tag(m) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 190)
                    .padding(8)
                }
            }
            .overlay(alignment: .top) {
                if let ref = selected, ref.section < results.count {
                    StationCard(result: results[ref.section], station: ref.station, units: units) { self.selected = nil }
                }
            }
            .overlay(alignment: .bottomLeading) {
                if mode == .section && !results.isEmpty {
                    VelocityLegend(range: range, units: units)
                } else if mode == .vectors && !field.isEmpty {
                    VelocityLegend(range: fieldRange, units: units)
                }
            }
    }

    /// 0 … the highest station velocity (desktop's results scale).
    private var stationRange: ClosedRange<Double> {
        var top: Double = 0
        for r in results {
            for v in r.velocity where v.isFinite { top = max(top, v) }
        }
        return 0...max(top, 0.01)
    }

    /// Median PIV field coloured by its speed.
    private var fieldSegments: [CanvasSegment] {
        let lo: Double = fieldRange.lowerBound
        let span: Double = max(fieldRange.upperBound - lo, 1e-9)
        return field.map { (f: QuiverVector) -> CanvasSegment in
            CanvasSegment(a: f.a, b: f.b, color: VelocityColors.color((f.speed - lo) / span), width: 1.4, arrow: true)
        }
    }

    private func tap(_ p: Point2) {
        guard mode == .section else { return }
        let radius: Double = 0.04 * Double(max(model.frameSize.width, model.frameSize.height))
        let hit: StationRef? = ChevronLayer.hit(p, results: results, radius: radius)
        withAnimation(.easeInOut(duration: 0.2)) { selected = hit }
    }

    private var units: UnitDisplay { model.units }

    private var resultsForm: some View {
        Form {
            if !results.isEmpty {
                Section {
                    ForEach(results) { r in
                        DischargeHero(result: r, units: units, showName: results.count > 1)
                    }
                    Text("Toca una estación en la imagen para ver su detalle.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
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

    private func load() {
        guard !loaded, let project = model.project else { return }
        loaded = true
        results = ResultsPipeline.read(project)
        if let first = results.first {
            interpolate = first.interpolated
            seeding = first.artificialSeeding
        }
        if let n = Autotest.stationToSelect, let first = results.first, n >= 1, n <= first.stationCount {
            selected = StationRef(section: 0, station: n - 1)
        }
        if Autotest.resultsVectors { mode = .vectors }
        let width: Double = Double(model.frameSize.width)
        Task { @MainActor in
            let computed: QuiverField? = await Task.detached(priority: .utility) { () -> QuiverField? in
                guard let piv = try? project.readPIVResults() else { return nil }
                let matrix: Matrix3? = try? project.readMatrix()
                return Self.quiver(piv, imageWidth: width, matrix: matrix, frameInterval: project.settings.frameInterval)
            }.value
            if let c = computed {
                field = c.vectors
                fieldRange = c.range
            }
        }
    }

    /// Median PIV field as arrows, scaled so the median vector is ~4 % of the frame width, at most ~1200 arrows,
    /// each with its speed in m/s (world displacement through the matrix / time between frames); without a matrix or
    /// frame interval the speed is in pixels per pair. The colour range is the 2nd–98th percentile.
    nonisolated static func quiver(_ piv: PIVResults, imageWidth: Double, matrix: Matrix3?,
                                   frameInterval: Double?) -> QuiverField {
        let n: Int = min(piv.x.count, piv.y.count, piv.uMedian.count, piv.vMedian.count)
        var magnitudes: [Double] = []
        for k in 0..<n {
            let m: Double = hypot(piv.uMedian[k], piv.vMedian[k])
            if m.isFinite { magnitudes.append(m) }
        }
        guard !magnitudes.isEmpty else { return QuiverField(vectors: [], range: 0...1) }
        magnitudes.sort()
        let typical: Double = max(magnitudes[magnitudes.count / 2], 1e-6)
        let scale: Double = min(max(0.04 * imageWidth / typical, 1), 200)
        let stride: Int = max(1, Int((Double(n) / 1200).squareRoot().rounded(.up)))
        var out: [QuiverVector] = []
        for row in Swift.stride(from: 0, to: piv.rows, by: stride) {
            for col in Swift.stride(from: 0, to: piv.columns, by: stride) {
                let k: Int = row * piv.columns + col
                guard k < n else { continue }
                let x: Double = piv.x[k], y: Double = piv.y[k]
                let u: Double = piv.uMedian[k], v: Double = piv.vMedian[k]
                guard x.isFinite, y.isFinite, u.isFinite, v.isFinite else { continue }
                var speed: Double = hypot(u, v)
                if let m = matrix, let dt = frameInterval, dt > 0 {
                    let w0: Point2 = m.apply(Point2(x, y))
                    let w1: Point2 = m.apply(Point2(x + u, y + v))
                    speed = w0.distance(to: w1) / dt
                }
                guard speed.isFinite else { continue }
                out.append(QuiverVector(a: Point2(x, y), b: Point2(x + u * scale, y + v * scale), speed: speed))
            }
        }
        let speeds: [Double] = out.map { $0.speed }.sorted()
        guard !speeds.isEmpty else { return QuiverField(vectors: [], range: 0...1) }
        let lo: Double = speeds[Int(Double(speeds.count - 1) * 0.02)]
        let hi: Double = max(speeds[Int(Double(speeds.count - 1) * 0.98)], lo + 1e-6)
        return QuiverField(vectors: out, range: lo...hi)
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
            Text("Caudal \(units.dischargeText(result.totalQ))")
                .font(.headline)
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

/// One arrow of the median PIV field and its speed (m/s, or px per pair without calibration).
struct QuiverVector {
    let a: Point2
    let b: Point2
    let speed: Double
}

struct QuiverField {
    let vectors: [QuiverVector]
    let range: ClosedRange<Double>
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
