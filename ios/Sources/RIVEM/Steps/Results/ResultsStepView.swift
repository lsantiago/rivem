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
    @State private var reportURL: URL? = nil
    /// Inverse of the pixel → world matrix, to project desktop's chevron glyph (built in metres).
    @State private var worldToPixel: Matrix3? = nil
    /// Traffic light and Q ± U of every section (RIVEM addition).
    @State private var assessments: [SectionAssessment] = []
    @State private var assumptions = UncertaintyAssumptions()
    @State private var pairCount: Int? = nil

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
        let inverse: Matrix3? = worldToPixel
        let layer: ((inout GraphicsContext, CanvasLayout, Double) -> Void)? = mode == .section
            ? { (context: inout GraphicsContext, layout: CanvasLayout, time: Double) in
                ChevronLayer.draw(&context, layout: layout, results: results, worldToPixel: inverse, range: range, time: time, selected: selected)
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
                    .environment(\.colorScheme, .dark)
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

    /// 0 … the highest velocity of the stations that carry discharge (desktop's results scale: bank stations with
    /// zero depth are left out, as desktop shows them without value).
    private var stationRange: ClosedRange<Double> {
        var top: Double = 0
        for r in results {
            for i in 0..<r.stationCount where ChevronLayer.contributes(r, i) {
                if let v = r.value(r.velocity, i) { top = max(top, v) }
            }
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
                        DischargeHero(result: r, units: units, showName: results.count > 1,
                                      uncertainty: assessment(r.name)?.uncertainty, quality: assessment(r.name)?.quality.overall)
                    }
                    Text("Toca una estación en la imagen para ver su detalle.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(assessments) { (a: SectionAssessment) in
                QualitySection(assessment: a, units: units, showName: assessments.count > 1) { (station: Int) in
                    select(sectionNamed: a.name, station: station)
                }
            }
            if !results.isEmpty {
                UncertaintyAssumptionsSection(assumptions: $assumptions, units: units, onChange: saveAssumptions)
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
            ForEach(Array(results.enumerated()), id: \.element.id) { pair in
                SectionResultView(result: pair.element, units: units, enabled: !busy,
                                  doubtful: doubtfulStations(pair.element.name)) { (station: Int) in
                    toggleStation(section: pair.offset, station: station)
                }
            }
            if results.count > 1 {
                Section("Todas las secciones") {
                    let qs: [Double] = results.compactMap { $0.totalQ }.filter { $0.isFinite }
                    let mean: Double = qs.isEmpty ? .nan : qs.reduce(0, +) / Double(qs.count)
                    LabeledContent("Caudal medio", value: units.dischargeText(mean))
                }
            }
            if !results.isEmpty {
                Section("Informe") {
                    Button {
                        makeReport()
                    } label: {
                        Label("Generar informe PDF", systemImage: "doc.richtext")
                    }
                    .disabled(busy)
                    if let url = reportURL {
                        ShareLink(item: url) {
                            Label("Compartir \(url.lastPathComponent)", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
        }
    }

    private func load() {
        guard !loaded, let project = model.project else { return }
        loaded = true
        results = ResultsPipeline.read(project)
        worldToPixel = (try? project.readMatrix()).flatMap { try? $0.inverse() }
        assumptions = UncertaintyAssumptions(settings: project.settings)
        reassess()
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
            let computed: (QuiverField, Int)? = await Task.detached(priority: .utility) { () -> (QuiverField, Int)? in
                guard let piv = try? project.readPIVResults() else { return nil }
                let matrix: Matrix3? = try? project.readMatrix()
                return (Self.quiver(piv, imageWidth: width, matrix: matrix, frameInterval: project.settings.frameInterval), piv.pairCount)
            }.value
            if let c = computed {
                field = c.0.vectors
                fieldRange = c.0.range
                pairCount = c.1
            }
        }
    }

    /// Median PIV field as arrows, scaled so the median vector is ~4 % of the frame width, at most ~1200 arrows,
    /// each with its speed in m/s (world displacement through the matrix / time between frames); without a matrix or
    /// frame interval the speed is in pixels per pair. The colour range is the 2nd–98th percentile.
    nonisolated static func quiver(_ piv: PIVResults, imageWidth: Double, matrix: Matrix3?,
                                   frameInterval: Double?) -> QuiverField {
        let scale: Double = QuiverBuilder.scale(piv, imageWidth: imageWidth)
        let vectors: [QuiverVector] = QuiverBuilder.vectors(piv, pair: nil, scale: scale, matrix: matrix, frameInterval: frameInterval)
        return QuiverField(vectors: vectors, range: QuiverBuilder.range(vectors))
    }

    private func recompute() {
        run(nil)
    }

    private func assessment(_ name: String) -> SectionAssessment? {
        assessments.first { $0.name == name }
    }

    private func doubtfulStations(_ name: String) -> Set<Int> {
        Set((assessment(name)?.quality.doubtful ?? []).map { $0.index })
    }

    /// Recomputes the traffic light and Q ± U (Monte Carlo, a few ms) from the results on screen.
    private func reassess() {
        guard let project = model.project, !results.isEmpty else {
            assessments = []
            return
        }
        let current: [SectionResult] = results
        let pairs: Int? = pairCount
        Task { @MainActor in
            let a: [SectionAssessment] = await Task.detached(priority: .utility) { () -> [SectionAssessment] in
                QualityPipeline.assess(project, results: current, pairCount: pairs)
            }.value
            assessments = a
            for s in a {
                let doubtful: [Int] = s.quality.doubtful.map { $0.index + 1 }
                AppLog.info("Calidad \(s.name): \(QualityPipeline.levelText(s.quality.overall)), Q = \(Fmt.num(s.uncertainty.discharge, 3)) "
                    + "± \(Fmt.num(s.uncertainty.expanded, 3)) m3/s (95 %), dudosas \(doubtful)")
            }
        }
    }

    private func saveAssumptions() {
        guard let settings = model.settings, UncertaintyAssumptions(settings: settings) != assumptions else { return }
        let a: UncertaintyAssumptions = assumptions
        do {
            try model.update { (p: inout RiverProject) in
                a.write(to: &p.settings)
                try p.save()
            }
        } catch {
            self.error = "No se pudieron guardar los supuestos: \(error)"
        }
        reassess()
    }

    private func select(sectionNamed name: String, station: Int) {
        guard let si = results.firstIndex(where: { $0.name == name }) else { return }
        mode = .section
        withAnimation(.easeInOut(duration: 0.2)) { selected = StationRef(section: si, station: station) }
    }

    /// Includes or excludes a station (`check`, same field as desktop's table) and recomputes.
    private func toggleStation(section: Int, station: Int) {
        guard section < results.count else { return }
        let r: SectionResult = results[section]
        let included: Bool = station < r.check.count ? r.check[station] : true
        let name: String = r.name
        run { (p: inout RiverProject) in
            try ResultsPipeline.setCheck(&p, section: name, station: station, included: !included)
        }
    }

    private func makeReport() {
        guard let project = model.project else { return }
        do {
            reportURL = try ReportPDF.make(project: project)
            error = nil
        } catch {
            self.error = "No se pudo crear el informe: \(error)"
            AppLog.error("Informe PDF: \(error)")
        }
    }

    private func run(_ edit: (@Sendable (inout RiverProject) throws -> Void)?) {
        let interp: Bool = interpolate
        let seed: Bool = seeding
        error = nil
        busy = true
        reportURL = nil
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let done: (RiverProject, ResultsOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, ResultsOutcome) in
                    var p = project
                    if let edit = edit { try edit(&p) }
                    let o = try ResultsPipeline.compute(&p, interpolate: interp, artificialSeeding: seed)
                    return (p, o)
                }.value
                model.replace(done.0)
                results = done.1.results
                reassess()
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
    let enabled: Bool
    let doubtful: Set<Int>
    let onToggle: (Int) -> Void

    var body: some View {
        Section(result.name) {
            SectionTotals(result: result, units: units)
            DischargeBarsChart(result: result, units: units)
                .frame(height: 150)
            VelocityProfileChart(result: result, units: units)
                .frame(height: 170)
            DepthProfileChart(result: result, units: units)
                .frame(height: 140)
        }
        Section {
            StationTable(result: result, units: units, enabled: enabled, doubtful: doubtful, onToggle: onToggle)
        } header: {
            Text("Estaciones · \(result.name)")
        } footer: {
            Text("Desmarca una estación para sacarla del caudal (con «Interpolar» se rellena desde sus vecinas). Se guarda en el proyecto, igual que en RIVeR escritorio. ⚠︎ = estación dudosa (ver Calidad del aforo).")
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

struct VelocityProfileChart: View {
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

struct DepthProfileChart: View {
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
    let enabled: Bool
    let doubtful: Set<Int>
    let onToggle: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            StationRow(check: nil, cells: ["#", "x", "d", "A", "Vs", "Q"], header: true, flagged: false, onToggle: {})
            ForEach(0..<result.stationCount, id: \.self) { (i: Int) in
                StationRow(check: i < result.check.count ? result.check[i] : true, cells: cells(i), header: false,
                           flagged: doubtful.contains(i)) {
                    onToggle(i)
                }
                .disabled(!enabled)
            }
            Text("x, d en \(units.lengthUnit) · A en \(units.areaUnit) · Vs en \(units.velocityUnit) · Q en \(units.dischargeUnit)")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func cells(_ i: Int) -> [String] {
        let x: String = result.value(result.distance, i).map { Fmt.num(units.length($0), 2) } ?? "—"
        let d: String = result.value(result.depth, i).map { Fmt.num(units.length($0), 2) } ?? "—"
        let a: String = result.value(result.area, i).map { Fmt.num(units.area($0), 2) } ?? "—"
        let v: String = result.value(result.velocity, i).map { Fmt.num(units.velocity($0), 2) } ?? "—"
        let q: String = result.value(result.q, i).map { Fmt.num(units.discharge($0), 2) } ?? "—"
        return ["\(i + 1)", x, d, a, v, q]
    }
}

private struct StationRow: View {
    /// nil for the header row.
    let check: Bool?
    let cells: [String]
    let header: Bool
    /// Doubtful station: number in orange with a warning sign.
    let flagged: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Group {
                if let checked = check {
                    Button(action: onToggle) {
                        Image(systemName: checked ? "checkmark.square.fill" : "square")
                            .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(checked ? "Estación incluida" : "Estación excluida")
                } else {
                    Image(systemName: "square").hidden()
                }
            }
            .frame(width: 24, alignment: .leading)
            ForEach(0..<cells.count, id: \.self) { (k: Int) in
                Text(k == 0 && flagged ? cells[k] + "⚠︎" : cells[k])
                    .foregroundStyle(k == 0 && flagged ? Color.orange : (check == false ? Color.secondary : Color.primary))
                    .frame(maxWidth: .infinity, alignment: k == 0 ? .leading : .trailing)
            }
        }
        .font(header ? Font.caption.bold() : Font.caption.monospacedDigit())
        .foregroundStyle(check == false ? Color.secondary : Color.primary)
    }
}
