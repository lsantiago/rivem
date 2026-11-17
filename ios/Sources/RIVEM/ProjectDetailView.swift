import SwiftUI
import RiverCore

/// Read-only view of a project: what desktop stored, and whether desktop would open it as it is.
struct ProjectDetailView: View {
    let summary: ProjectSummary
    @State private var project: RiverProject?
    @State private var sections: CrossSections?
    @State private var matrix: Matrix3?
    @State private var frameCount = 0
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Section { Label(error, systemImage: "xmark.octagon").foregroundStyle(.red) }
            }
            if let p = project {
                let s = p.settings
                Section("Proyecto") {
                    LabeledContent("Carpeta", value: "\(summary.name)/\(summary.stamp)")
                    LabeledContent("Tipo", value: s.footage?.label ?? "desconocido")
                    LabeledContent("Etapa", value: s.stage.label)
                    if let river = s.riverName { LabeledContent("Río", value: river) }
                    if let site = s.site { LabeledContent("Sitio", value: site) }
                    LabeledContent("Unidades", value: s.unitSystem == .imperial ? "Imperial (se guarda en SI)" : "SI")
                }
                Section("Video y frames") {
                    if let v = s.video {
                        LabeledContent("Video", value: v.name ?? "—")
                        if let w = v.width, let h = v.height { LabeledContent("Resolución", value: "\(w) × \(h)") }
                        if let fps = v.fps { LabeledContent("FPS", value: fps.formatted(.number.precision(.fractionLength(0...2)))) }
                    }
                    if let r = s.videoRange {
                        LabeledContent("Rango", value: "\(r.start)–\(r.end), paso \(r.step)")
                    }
                    if let dt = s.frameInterval {
                        LabeledContent("Δt entre frames", value: "\(dt.formatted(.number.precision(.fractionLength(4)))) s")
                    }
                    LabeledContent("Frames en el proyecto", value: "\(frameCount)")
                }
                if let m = matrix {
                    Section("Georreferenciación (píxel → m)") {
                        ForEach(0..<3, id: \.self) { r in
                            Text(m.rows[r].map { $0.formatted(.number.precision(.significantDigits(6))) }.joined(separator: "   "))
                                .font(.caption.monospaced())
                        }
                    }
                }
                if let xs = sections, !xs.sections.isEmpty {
                    Section("Secciones") {
                        ForEach(xs.sections, id: \.name) { sec in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(sec.name).font(.headline)
                                Text(sectionDetail(sec)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("Compatibilidad con RIVeR escritorio") {
                    let issues = p.compatibilityIssues()
                    if issues.isEmpty {
                        Label("Se abre en RIVeR \(RiverCore.desktopCompatibility)", systemImage: "checkmark.seal.fill")
                            .foregroundStyle(.green)
                    }
                    ForEach(issues, id: \.message) { issue in
                        Label(issue.message, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                }
            }
        }
        .navigationTitle(summary.riverName ?? summary.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { load() }
    }

    private func sectionDetail(_ s: CrossSection) -> String {
        var parts: [String] = []
        if let n = s.numStations { parts.append("\(n) estaciones") }
        if let l = s.level { parts.append("nivel \(l.formatted(.number.precision(.fractionLength(3)))) m") }
        if let a = s.alpha { parts.append("α \(a.formatted())") }
        if let w = s.realWorldLength { parts.append("ancho \(w.formatted(.number.precision(.fractionLength(2)))) m") }
        if let q = s.totalDischarge, q.isFinite { parts.append("Q \(q.formatted(.number.precision(.fractionLength(3)))) m³/s") }
        return parts.joined(separator: " · ")
    }

    private func load() {
        do {
            let p = try RiverProject.open(summary.folder)
            project = p
            frameCount = p.frameFiles.count
            matrix = try p.readMatrix()
            sections = try p.readCrossSections()
        } catch {
            self.error = "\(error)"
        }
    }
}
