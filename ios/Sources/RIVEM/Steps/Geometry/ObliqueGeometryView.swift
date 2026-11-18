import RiverCore
import SwiftUI
import UniformTypeIdentifiers

/// Oblique camera (desktop "Control Points"): four points on the water plane and the six tape distances
/// d12, d23, d34, d41, d13, d24 → `control_points`, matrix, `transformation{resolution, extent, roi}`,
/// `transformed_image.png`; shows the distance residuals and the reprojection of each point.
struct ObliqueGeometryView: View {
    let model: WorkflowModel

    @State private var points: [Point2?] = [nil, nil, nil, nil]
    @State private var distances: [String] = ["", "", "", "", "", ""]
    @State private var outcome: ObliqueOutcome? = nil
    @State private var error: String? = nil
    @State private var busy = false
    @State private var importing = false
    @State private var loaded = false

    private static let labels: [String] = ["d12", "d23", "d34", "d41", "d13 (diagonal)", "d24 (diagonal)"]
    private static let colors: [Color] = [.red, .yellow, .green, .cyan]

    var body: some View {
        CanvasStepLayout {
            if model.firstFrame != nil {
                ImageCanvas(image: model.firstFrame, imageSize: model.frameSize, points: canvasPoints,
                            segments: canvasSegments, onTap: tap, onMove: move)
            } else {
                NoFrameView()
            }
        } controls: {
            controlsForm
        }
        .onAppear(perform: load)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .tabSeparatedText, .plainText, .data]) { result in
            importDistances(result)
        }
    }

    private var controlsForm: some View {
        Form {
            Section {
                Text(instruction).font(.callout)
                if points.contains(where: { $0 != nil }) {
                    Button("Borrar puntos", role: .destructive) { points = [nil, nil, nil, nil] }
                }
            }
            Section("Distancias medidas (m)") {
                ForEach(0..<6, id: \.self) { i in
                    NumberField(title: Self.labels[i], text: $distances[i])
                }
                Button {
                    importing = true
                } label: {
                    Label("Importar distancias (CSV)", systemImage: "doc.badge.plus")
                }
            }
            Section {
                Button {
                    apply()
                } label: {
                    if busy { ProgressView() } else { Label("Calcular y guardar", systemImage: "checkmark.circle") }
                }
                .disabled(busy || points.contains(where: { $0 == nil }) || parsedDistances == nil)
                ErrorBanner(message: error)
            } footer: {
                Text("Orden de los puntos: 1 y 2 en una orilla, 3 y 4 en la otra, recorriendo el cuadrilátero. Guardar borra secciones, máscara y resultados.")
            }
            if let o = outcome {
                ObliqueResidualsSection(outcome: o)
            }
            RectifiedPreviewSection(model: model, fileName: ProjectFiles.transformedImage)
        }
    }

    private var instruction: String {
        if let next = points.firstIndex(where: { $0 == nil }) {
            return "Toca el punto \(next + 1) sobre la imagen (pellizca para ampliar)."
        }
        return "Arrastra los puntos para ajustarlos."
    }

    private var canvasPoints: [CanvasPoint] {
        var out: [CanvasPoint] = []
        for i in 0..<4 {
            if let p = points[i] { out.append(CanvasPoint(id: i, point: p, label: "\(i + 1)", color: Self.colors[i])) }
        }
        return out
    }

    private var canvasSegments: [CanvasSegment] {
        var out: [CanvasSegment] = []
        let pairs: [(Int, Int)] = [(0, 1), (1, 2), (2, 3), (3, 0), (0, 2), (1, 3)]
        for (k, pair) in pairs.enumerated() {
            if let a = points[pair.0], let b = points[pair.1] {
                out.append(CanvasSegment(a: a, b: b, color: k < 4 ? .yellow : .orange, width: 1.5, dashed: k >= 4))
            }
        }
        return out
    }

    private var parsedDistances: ObliqueDistances? {
        let values: [Double] = distances.compactMap { Fmt.parse($0) }
        guard values.count == 6, values.allSatisfy({ $0 >= 0 }) else { return nil }
        return ObliqueDistances(values)
    }

    private func tap(_ p: Point2) {
        if let next = points.firstIndex(where: { $0 == nil }) { points[next] = p }
    }

    private func move(_ id: Int, _ p: Point2) {
        if id >= 0 && id < 4 { points[id] = p }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let cp = model.settings?.controlPoints, cp.pixels.count == 4, cp.distances.count == 6 else { return }
        points = cp.pixels.map { Optional($0) }
        distances = cp.distances.map { Fmt.num($0, 3) }
    }

    private func importDistances(_ result: Result<URL, Error>) {
        do {
            let url: URL = try result.get()
            let scoped: Bool = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            let text: String = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
            let feet: Bool = model.settings?.unitSystem == .imperial
            let d: ObliqueDistances = try GeometryPipeline.parseDistances(text, feet: feet)
            distances = d.values.map { Fmt.num($0, 3) }
            error = nil
        } catch {
            self.error = "No se pudieron leer las distancias: \(error)"
        }
    }

    private func apply() {
        let pixels: [Point2] = points.compactMap { $0 }
        guard pixels.count == 4, let d = parsedDistances else { return }
        error = nil
        busy = true
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let done: (RiverProject, ObliqueOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, ObliqueOutcome) in
                    var p = project
                    let frame = try GeometryPipeline.loadFirstFrame(p)
                    let o = try GeometryPipeline.applyOblique(&p, pixels: pixels, distances: d, frame: frame)
                    return (p, o)
                }.value
                model.replace(done.0)
                outcome = done.1
            } catch {
                self.error = "\(error)"
                AppLog.error("Geometría oblicua: \(error)")
                model.reload()
            }
            busy = false
        }
    }
}

private struct ObliqueResidualsSection: View {
    let outcome: ObliqueOutcome
    private static let names: [String] = ["d12", "d23", "d34", "d41", "d13", "d24"]

    var body: some View {
        Section("Control de calidad") {
            ForEach(0..<outcome.solution.distanceResiduals.count, id: \.self) { i in
                LabeledContent("Residuo \(Self.names[min(i, 5)])", value: "\(Fmt.num(outcome.solution.distanceResiduals[i] * 100, 1)) cm")
            }
            ForEach(0..<outcome.reprojection.count, id: \.self) { i in
                LabeledContent("Reproyección punto \(i + 1)", value: "\(Fmt.num(outcome.reprojection[i] * 100, 2)) cm")
            }
            Text("Residuos grandes indican distancias inconsistentes con un cuadrilátero plano o puntos mal marcados.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
