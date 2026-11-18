import Charts
import RiverCore
import SwiftUI
import UniformTypeIdentifiers

/// Step 3, desktop "Cross Sections": draw each section with two taps (left bank first), import its bathymetry,
/// set level, number of stations, alpha and left-bank station; saving writes `xsections.json`, the bathymetry CSVs,
/// `mask.npy`, `mask.png`, `bbox.json` and `processing.roi_height`.
struct SectionsStepView: View {
    let model: WorkflowModel

    @State private var drafts: [SectionDraft] = []
    @State private var selectedID: UUID? = nil
    @State private var bathymetries: [String: Bathymetry] = [:]
    @State private var importing = false
    @State private var editingBathymetry = false
    @State private var busy = false
    @State private var error: String? = nil
    @State private var message: String? = nil
    @State private var loaded = false

    private static let palette: [Color] = [.yellow, .cyan, .pink, .green, .orange]

    var body: some View {
        CanvasStepLayout {
            if model.firstFrame != nil {
                ImageCanvas(image: model.firstFrame, imageSize: model.frameSize, points: canvasPoints,
                            segments: canvasSegments, overlay: model.maskOverlay, onTap: tap, onMove: move)
            } else {
                NoFrameView()
            }
        } controls: {
            controlsForm
        }
        .onAppear(perform: load)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .tabSeparatedText, .plainText, .data]) { result in
            importBathymetry(result)
        }
        .sheet(isPresented: $editingBathymetry) {
            if let i = selectedIndex {
                BathymetryEditorView(sectionName: drafts[i].name,
                                     initial: drafts[i].bathymetryFile.flatMap { bathymetries[$0] }) { b, level in
                    useEditedBathymetry(b, level: level)
                }
            }
        }
    }

    private var selectedIndex: Int? {
        guard let id = selectedID else { return nil }
        return drafts.firstIndex(where: { $0.id == id })
    }

    private var controlsForm: some View {
        Form {
            SectionPickerSection(drafts: drafts, selectedID: $selectedID, add: addSection, remove: removeSelected)
            if let i = selectedIndex {
                Section {
                    Text(instruction(drafts[i])).font(.callout)
                    if drafts[i].left != nil || drafts[i].right != nil {
                        Button("Borrar línea", role: .destructive) {
                            drafts[i].left = nil
                            drafts[i].right = nil
                        }
                    }
                }
                SectionDraftEditor(draft: $drafts[i],
                                   bathymetry: drafts[i].bathymetryFile.flatMap { bathymetries[$0] },
                                   isIpcam: model.footage == .ipcam,
                                   isFirst: i == 0,
                                   importBathymetry: { importing = true },
                                   editBathymetry: { editingBathymetry = true })
            }
            Section {
                Button {
                    save()
                } label: {
                    if busy { ProgressView() } else { Label("Guardar secciones y máscara", systemImage: "square.and.arrow.down") }
                }
                .disabled(busy || drafts.isEmpty || drafts.contains(where: { !$0.isComplete }))
                if let missing = missingSummary {
                    Text(missing).font(.callout).foregroundStyle(.orange)
                }
                if let m = message { Text(m).font(.callout) }
                ErrorBanner(message: error)
            } footer: {
                Text("La máscara de la región de interés se arma con un rectángulo alrededor de cada sección (altura recomendada como en escritorio). Guardar descarta los resultados PIV anteriores.")
            }
        }
    }

    private func instruction(_ d: SectionDraft) -> String {
        if d.left == nil { return "Toca la margen izquierda de \(d.name) sobre la imagen." }
        if d.right == nil { return "Toca la margen derecha." }
        if d.bathymetryFile == nil {
            return "Falta la batimetría: impórtala (CSV) o créala con el editor (plantilla rectangular/trapezoidal, tabla o CSV pegado)."
        }
        return "Arrastra los extremos para ajustar la línea."
    }

    /// What is still missing before the sections can be saved, per section.
    private var missingSummary: String? {
        var lines: [String] = []
        for d in drafts {
            var missing: [String] = []
            if d.left == nil || d.right == nil { missing.append("la línea (dos toques sobre la imagen)") }
            if d.bathymetryFile == nil { missing.append("la batimetría") }
            if !missing.isEmpty { lines.append("\(d.name): falta " + missing.joined(separator: " y ")) }
        }
        return lines.isEmpty ? nil : lines.joined(separator: "
")
    }

    private func useEditedBathymetry(_ b: Bathymetry, level: Double?) {
        guard let i = selectedIndex, let project = model.project else { return }
        do {
            let name: String = SectionsPipeline.editorFileName(section: drafts[i].name)
            let file: String = try SectionsPipeline.writeBathymetry(b, fileName: name, into: project.folder)
            bathymetries[file] = try SectionsPipeline.readBathymetry(project, file: file)
            drafts[i].bathymetryFile = file
            drafts[i].level = level
            error = nil
        } catch {
            self.error = "No se pudo guardar la batimetría: \(error)"
        }
    }

    private var canvasPoints: [CanvasPoint] {
        guard let i = selectedIndex else { return [] }
        var out: [CanvasPoint] = []
        if let l = drafts[i].left { out.append(CanvasPoint(id: 0, point: l, label: "I", color: .red)) }
        if let r = drafts[i].right { out.append(CanvasPoint(id: 1, point: r, label: "D", color: .red)) }
        return out
    }

    private var canvasSegments: [CanvasSegment] {
        var out: [CanvasSegment] = []
        for (k, d) in drafts.enumerated() {
            guard let l = d.left, let r = d.right else { continue }
            let selected: Bool = d.id == selectedID
            out.append(CanvasSegment(a: l, b: r, color: Self.palette[k % Self.palette.count], width: selected ? 3 : 2, arrow: true))
        }
        return out
    }

    private func tap(_ p: Point2) {
        guard let i = selectedIndex else { return }
        if drafts[i].left == nil {
            drafts[i].left = p
        } else if drafts[i].right == nil {
            drafts[i].right = p
        }
    }

    private func move(_ id: Int, _ p: Point2) {
        guard let i = selectedIndex else { return }
        if id == 0 { drafts[i].left = p } else { drafts[i].right = p }
    }

    private func addSection() {
        let name = "CS_\(drafts.count + 1)"
        var d = SectionDraft(name: name)
        // Reuse the previous bathymetry by default (several sections over the same profile is common).
        d.bathymetryFile = drafts.last?.bathymetryFile
        drafts.append(d)
        selectedID = d.id
    }

    private func removeSelected() {
        guard let i = selectedIndex else { return }
        drafts.remove(at: i)
        selectedID = drafts.first?.id
    }

    private func load() {
        guard !loaded, let project = model.project else { return }
        loaded = true
        if let sections = try? project.readCrossSections(), !sections.sections.isEmpty {
            drafts = SectionsPipeline.drafts(from: sections)
        } else {
            drafts = [SectionDraft(name: "CS_1")]
        }
        selectedID = drafts.first?.id
        for d in drafts {
            if let f = d.bathymetryFile, bathymetries[f] == nil, let b = try? SectionsPipeline.readBathymetry(project, file: f) {
                bathymetries[f] = b
            }
        }
    }

    private func importBathymetry(_ result: Result<URL, Error>) {
        guard let i = selectedIndex, let project = model.project else { return }
        do {
            let url: URL = try result.get()
            let feet: Bool = project.settings.unitSystem == .imperial
            let imported = try SectionsPipeline.importBathymetry(from: url, into: project.folder, feet: feet)
            bathymetries[imported.file] = imported.bathymetry
            drafts[i].bathymetryFile = imported.file
            drafts[i].level = nil
            error = nil
        } catch {
            self.error = "No se pudo importar la batimetría: \(error)"
        }
    }

    private func save() {
        let list: [SectionDraft] = drafts
        error = nil
        message = nil
        busy = true
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let window: Int = ProcessingPipeline.parameters(project.settings).interrogationArea1
                let done: (RiverProject, SectionsOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, SectionsOutcome) in
                    var p = project
                    let o = try SectionsPipeline.save(&p, drafts: list, windowSize: window)
                    return (p, o)
                }.value
                model.replace(done.0)
                let saved: [SectionDraft] = SectionsPipeline.drafts(from: done.1.sections)
                let selectedPosition: Int = selectedIndex ?? 0
                drafts = saved
                selectedID = saved.indices.contains(selectedPosition) ? saved[selectedPosition].id : saved.first?.id
                let box: String = done.1.bbox.map { Fmt.num($0, 0) }.joined(separator: ", ")
                message = "Guardadas \(saved.count) secciones. Altura de la ROI \(Fmt.num(done.1.heightROI, 1)) m, recuadro [\(box)] px."
            } catch {
                self.error = "\(error)"
                AppLog.error("Secciones: \(error)")
                model.reload()
            }
            busy = false
        }
    }
}

private struct SectionPickerSection: View {
    let drafts: [SectionDraft]
    @Binding var selectedID: UUID?
    let add: () -> Void
    let remove: () -> Void

    var body: some View {
        Section("Secciones") {
            if drafts.count > 1 {
                Picker("Sección", selection: $selectedID) {
                    ForEach(drafts) { d in
                        Text(d.name).tag(Optional(d.id))
                    }
                }
            }
            HStack {
                Button(action: add) { Label("Agregar", systemImage: "plus") }
                    .buttonStyle(.borderless)
                Spacer()
                if drafts.count > 1 {
                    Button(role: .destructive, action: remove) { Label("Eliminar", systemImage: "trash") }
                        .buttonStyle(.borderless)
                }
            }
        }
    }
}

/// Form of one section.
private struct SectionDraftEditor: View {
    @Binding var draft: SectionDraft
    let bathymetry: Bathymetry?
    let isIpcam: Bool
    let isFirst: Bool
    let importBathymetry: () -> Void
    let editBathymetry: () -> Void

    var body: some View {
        Section("Datos de la sección") {
            LabeledContent("Nombre") {
                TextField("CS_1", text: $draft.name).multilineTextAlignment(.trailing)
            }
            if let file = draft.bathymetryFile {
                LabeledContent("Batimetría", value: file)
            } else {
                Text("Sin batimetría").foregroundStyle(.orange)
            }
            Button(action: importBathymetry) {
                Label("Importar batimetría (CSV)", systemImage: "square.and.arrow.down")
            }
            Button(action: editBathymetry) {
                Label(draft.bathymetryFile == nil ? "Crear batimetría (plantilla o tabla)" : "Editar batimetría",
                      systemImage: "chart.xyaxis.line")
            }
            LevelEditor(draft: $draft, defaultLevel: bathymetry.flatMap { SectionsPipeline.defaultLevel($0) })
            if isIpcam && !isFirst {
                Text("En estación fija todas las secciones usan el nivel de la primera (la matriz depende del nivel).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            LabeledContent("Progresiva margen izquierda") {
                TextField("0", value: $draft.leftStation, format: .number)
                    .multilineTextAlignment(.trailing)
                    .decimalKeyboard()
            }
            Stepper("Estaciones: \(draft.numStations)", value: $draft.numStations, in: 2...200)
            LabeledContent("Coeficiente α") {
                TextField("0,85", value: $draft.alpha, format: .number)
                    .multilineTextAlignment(.trailing)
                    .decimalKeyboard()
            }
        }
        if let b = bathymetry {
            Section("Perfil del lecho") {
                BathymetryChart(bathymetry: b, level: draft.level ?? SectionsPipeline.defaultLevel(b))
                    .frame(height: 180)
            }
        }
    }
}

private struct LevelEditor: View {
    @Binding var draft: SectionDraft
    let defaultLevel: Double?

    var body: some View {
        Toggle("Nivel automático", isOn: Binding<Bool>(
            get: { draft.level == nil },
            set: { auto in draft.level = auto ? nil : (defaultLevel ?? 0) }
        ))
        if draft.level == nil {
            LabeledContent("Nivel del agua", value: defaultLevel.map { "\(Fmt.num($0, 3)) m" } ?? "—")
        } else {
            LabeledContent("Nivel del agua (m)") {
                TextField("0", value: Binding<Double>(
                    get: { draft.level ?? defaultLevel ?? 0 },
                    set: { draft.level = $0 }
                ), format: .number)
                .multilineTextAlignment(.trailing)
                .decimalKeyboard()
            }
        }
    }
}

private struct ProfilePoint: Identifiable {
    let id: Int
    let station: Double
    let stage: Double
}

/// Bed profile (station vs. elevation) with the water level.
struct BathymetryChart: View {
    let bathymetry: Bathymetry
    let level: Double?

    private var points: [ProfilePoint] {
        var out: [ProfilePoint] = []
        let n: Int = min(bathymetry.stations.count, bathymetry.elevations.count)
        for i in 0..<n {
            out.append(ProfilePoint(id: i, station: bathymetry.stations[i], stage: bathymetry.elevations[i]))
        }
        return out
    }

    var body: some View {
        Chart {
            ForEach(points) { p in
                LineMark(x: .value("Progresiva (m)", p.station), y: .value("Cota (m)", p.stage))
                    .foregroundStyle(Color.brown)
                PointMark(x: .value("Progresiva (m)", p.station), y: .value("Cota (m)", p.stage))
                    .foregroundStyle(Color.brown)
                    .symbolSize(12)
            }
            if let l = level {
                RuleMark(y: .value("Nivel", l))
                    .foregroundStyle(Color.blue)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
            }
        }
        .chartXAxisLabel("Progresiva (m)")
        .chartYAxisLabel("Cota (m)")
    }
}
