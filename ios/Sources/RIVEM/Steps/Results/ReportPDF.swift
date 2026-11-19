import Charts
import RiverCore
import SwiftUI

/// Discharge per station as bars coloured by surface velocity (desktop's "Discharge per station"); unchecked stations
/// in grey.
struct DischargeBarsChart: View {
    let result: SectionResult
    let units: UnitDisplay

    private struct Bar: Identifiable {
        let id: Int
        let x: Double
        let q: Double
        let color: Color
    }

    private var bars: [Bar] {
        var top: Double = 0
        for i in 0..<result.stationCount where ChevronLayer.contributes(result, i) {
            if let v = result.value(result.velocity, i) { top = max(top, v) }
        }
        var out: [Bar] = []
        for i in 0..<result.stationCount {
            guard let d = result.value(result.distance, i), let q = result.value(result.q, i) else { continue }
            let checked: Bool = i >= result.check.count || result.check[i]
            let v: Double = result.value(result.velocity, i) ?? 0
            let color: Color = checked ? VelocityColors.color(top > 0 ? v / top : 0) : .gray
            out.append(Bar(id: i, x: units.length(d), q: units.discharge(q), color: color))
        }
        return out
    }

    var body: some View {
        let xLabel: String = "Distancia (\(units.lengthUnit))"
        let yLabel: String = "Caudal (\(units.dischargeUnit))"
        Chart(bars) { (b: Bar) in
            BarMark(x: .value(xLabel, b.x), y: .value(yLabel, b.q), width: .fixed(10))
                .foregroundStyle(b.color)
        }
        .chartXAxisLabel(xLabel)
        .chartYAxisLabel(yLabel)
    }
}

/// The first frame with the section, banks and still chevrons (time 0), for the report.
struct ReportFrameView: View {
    let image: CGImage
    let imageSize: CGSize
    let results: [SectionResult]
    let worldToPixel: Matrix3?

    var body: some View {
        let range: ClosedRange<Double> = ReportPDF.stationRange(results)
        Canvas { (context: inout GraphicsContext, size: CGSize) in
            let layout = CanvasLayout(viewSize: size, imageSize: imageSize, zoom: 1, offset: .zero)
            context.draw(Image(decorative: image, scale: 1), in: layout.imageRect)
            ChevronLayer.draw(&context, layout: layout, results: results, worldToPixel: worldToPixel, range: range, time: 0, selected: nil)
        }
    }
}

/// One-page PDF report (desktop's "Summary"): header, total discharge, per section the frame with the section, the
/// discharge / velocity / depth charts and the station table, and how it was measured. Always light (printed).
enum ReportPDF {
    static func stationRange(_ results: [SectionResult]) -> ClosedRange<Double> {
        var top: Double = 0
        for r in results {
            for i in 0..<r.stationCount where ChevronLayer.contributes(r, i) {
                if let v = r.value(r.velocity, i) { top = max(top, v) }
            }
        }
        return 0...max(top, 0.01)
    }

    @MainActor
    static func make(project: RiverProject) throws -> URL {
        let results: [SectionResult] = ResultsPipeline.read(project)
        guard !results.isEmpty else { throw PipelineError("No hay resultados de caudal para el informe.") }
        let units = UnitDisplay(system: project.settings.unitSystem)
        var frame: CGImage? = nil
        var frameSize: CGSize = .zero
        if let first = project.frameFiles.first {
            frame = ImageCoding.thumbnail(contentsOf: first, maxPixel: 1400)
            if let s = ImageCoding.pixelSize(of: first) { frameSize = CGSize(width: s.width, height: s.height) }
        }
        let page = ReportPage(project: project, results: results, units: units, frame: frame, frameSize: frameSize)
            .frame(width: 595)
            .environment(\.colorScheme, .light)
        let renderer = ImageRenderer(content: page)
        renderer.proposedSize = ProposedViewSize(width: 595, height: nil)

        try FileManager.default.createDirectory(at: ExportPipeline.exportFolder, withIntermediateDirectories: true)
        let name: String = project.folder.deletingLastPathComponent().lastPathComponent
        let url: URL = ExportPipeline.exportFolder.appendingPathComponent("\(name)_\(project.folder.lastPathComponent)_informe.pdf")
        try? FileManager.default.removeItem(at: url)
        var failure: String? = nil
        renderer.render { (size: CGSize, draw: (CGContext) -> Void) in
            var box = CGRect(origin: .zero, size: size)
            guard let context = CGContext(url as CFURL, mediaBox: &box, nil) else {
                failure = "no se pudo crear el PDF"
                return
            }
            context.beginPDFPage(nil)
            draw(context)
            context.endPDFPage()
            context.closePDF()
        }
        if let f = failure { throw PipelineError(f) }
        guard FileManager.default.fileExists(atPath: url.path) else { throw PipelineError("no se pudo crear el PDF") }
        AppLog.info("Informe PDF: \(url.lastPathComponent)")
        return url
    }
}

private struct ReportPage: View {
    let project: RiverProject
    let results: [SectionResult]
    let units: UnitDisplay
    let frame: CGImage?
    let frameSize: CGSize

    private var settings: ProjectSettings { project.settings }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            Divider()
            ForEach(results) { (r: SectionResult) in
                section(r)
            }
            method
            Text("Generado con RIVEM \(appVersion) · formato compatible con RIVeR escritorio \(RiverCore.desktopCompatibility)")
                .font(.system(size: 8))
                .foregroundStyle(.secondary)
        }
        .padding(28)
        .background(Color.white)
        .foregroundStyle(Color.black)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(settings.riverName ?? project.folder.deletingLastPathComponent().lastPathComponent)
                    .font(.system(size: 20, weight: .bold))
                Spacer()
                Text(settings.footage?.label ?? "").font(.system(size: 11)).foregroundStyle(.secondary)
            }
            if let site = settings.site, !site.isEmpty { Text(site).font(.system(size: 11)) }
            Text("Aforo \(project.folder.lastPathComponent)").font(.system(size: 10)).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 18) {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(Fmt.num(totalQ.map { units.discharge($0) }, 2)).font(.system(size: 34, weight: .bold, design: .rounded))
                    Text(units.dischargeUnit).font(.system(size: 13)).foregroundStyle(.secondary)
                }
                fact("Secciones", "\(results.count)")
                fact("Estaciones", "\(results.first?.stationCount ?? 0)")
                fact("Medido", Fmt.percent(results.first?.measuredQ, 1))
            }
        }
    }

    /// Mean over sections (one section: its discharge), like desktop's summary.
    private var totalQ: Double? {
        let qs: [Double] = results.compactMap { $0.totalQ }.filter { $0.isFinite }
        return qs.isEmpty ? nil : qs.reduce(0, +) / Double(qs.count)
    }

    private func fact(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title).font(.system(size: 8)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 11, weight: .semibold))
        }
    }

    private func section(_ r: SectionResult) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(r.name).font(.system(size: 13, weight: .bold))
                Text("\(units.dischargeText(r.totalQ, 2))" + (r.totalQStd.map { " ± \(units.dischargeText($0, 2))" } ?? ""))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.blue)
                Spacer()
                Text("\(Fmt.percent(r.measuredQ, 1)) medido · α \(Fmt.num(r.alpha, 2))").font(.system(size: 9)).foregroundStyle(.secondary)
            }
            if let image = frame, frameSize.width > 0 {
                ReportFrameView(image: image, imageSize: frameSize, results: [r],
                                worldToPixel: (try? project.readMatrix()).flatMap { try? $0.inverse() })
                    .frame(width: 539, height: 539 * frameSize.height / frameSize.width)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            HStack(alignment: .top, spacing: 12) {
                DischargeBarsChart(result: r, units: units).frame(height: 130)
                VelocityProfileChart(result: r, units: units).frame(height: 130)
            }
            .font(.system(size: 8))
            DepthProfileChart(result: r, units: units)
                .frame(height: 110)
                .font(.system(size: 8))
            stationTable(r)
        }
    }

    private func stationTable(_ r: SectionResult) -> some View {
        VStack(spacing: 2) {
            row(["#", "x \(units.lengthUnit)", "d \(units.lengthUnit)", "A \(units.areaUnit)", "Vs \(units.velocityUnit)", "Q \(units.dischargeUnit)"], bold: true)
            ForEach(0..<r.stationCount, id: \.self) { (i: Int) in
                let unchecked: Bool = i < r.check.count && !r.check[i]
                row(["\(i + 1)" + (unchecked ? "*" : ""),
                     Fmt.num(r.value(r.distance, i).map { units.length($0) }, 2),
                     Fmt.num(r.value(r.depth, i).map { units.length($0) }, 2),
                     Fmt.num(r.value(r.area, i).map { units.area($0) }, 2),
                     Fmt.num(r.value(r.velocity, i).map { units.velocity($0) }, 2),
                     Fmt.num(r.value(r.q, i).map { units.discharge($0) }, 2)], bold: false)
            }
            if r.check.contains(false) {
                Text("* estación excluida del caudal").font(.system(size: 7)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func row(_ cells: [String], bold: Bool) -> some View {
        HStack(spacing: 4) {
            ForEach(0..<cells.count, id: \.self) { (k: Int) in
                Text(cells[k]).frame(maxWidth: .infinity, alignment: k == 0 ? .leading : .trailing)
            }
        }
        .font(.system(size: 8, weight: bold ? .semibold : .regular).monospacedDigit())
    }

    private var method: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Cómo se midió").font(.system(size: 12, weight: .bold))
            let processing: JSONObject? = settings.json["processing"]?.objectValue
            let ia1: String = processing?["interrogation_area_1"]?.doubleValue.map { Fmt.num($0, 0) } ?? "—"
            let ia2: String = processing?["interrogation_area_2"]?.doubleValue.map { Fmt.num($0, 0) } ?? "—"
            line("Toma", settings.footage?.label ?? "—")
            line("Frames", "\(project.frameFiles.count), paso \(settings.videoRange?.step ?? 1), "
                 + "\(Fmt.num(settings.video?.fps, 2)) fps, Δt \(Fmt.num(settings.frameInterval, 3)) s")
            line("PIV", "LSPIV, ventanas \(ia1) / \(ia2) px")
            ForEach(results) { (r: SectionResult) in
                line(r.name, "nivel \(units.lengthText(r.level, 3)), ancho \(units.lengthText(r.totalW)), área \(units.areaText(r.totalA)), "
                     + "velocidad media \(units.velocityText(r.meanV, 2))")
            }
        }
        .font(.system(size: 9))
    }

    private func line(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title).foregroundStyle(.secondary).frame(width: 90, alignment: .leading)
            Text(value)
        }
    }

    private var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "?"
    }
}
