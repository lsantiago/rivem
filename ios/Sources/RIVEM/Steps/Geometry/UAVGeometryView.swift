import RiverCore
import SwiftUI

/// UAV / drone (desktop "Pixel Size"): two points on the frame and either their distance or their East/North
/// coordinates → `pixel_size`, `transformation_matrix.json`, `transformation{resolution, extent}`,
/// `transformed_image.png`.
struct UAVGeometryView: View {
    let model: WorkflowModel

    @State private var p1: Point2? = nil
    @State private var p2: Point2? = nil
    @State private var useCoordinates = false
    @State private var lengthText = ""
    @State private var e1 = "0"
    @State private var n1 = "0"
    @State private var e2 = ""
    @State private var n2 = "0"
    @State private var result: String? = nil
    @State private var error: String? = nil
    @State private var busy = false
    @State private var loaded = false

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
    }

    private var controlsForm: some View {
        Form {
            Section {
                Text(instruction).font(.callout)
                if p1 != nil || p2 != nil {
                    Button("Borrar puntos", role: .destructive) {
                        p1 = nil
                        p2 = nil
                    }
                }
            }
            Section("Medida real") {
                Picker("Referencia", selection: $useCoordinates) {
                    Text("Distancia").tag(false)
                    Text("Coordenadas E/N").tag(true)
                }
                .pickerStyle(.segmented)
                if useCoordinates {
                    NumberField(title: "Este 1", text: $e1)
                    NumberField(title: "Norte 1", text: $n1)
                    NumberField(title: "Este 2", text: $e2)
                    NumberField(title: "Norte 2", text: $n2)
                } else {
                    NumberField(title: "Distancia 1–2", text: $lengthText)
                }
                if let size = previewPixelSize {
                    LabeledContent("Tamaño de píxel", value: "\(Fmt.num(size * 100, 3)) cm/px")
                }
            }
            Section {
                Button {
                    apply()
                } label: {
                    if busy { ProgressView() } else { Label("Calcular y guardar", systemImage: "checkmark.circle") }
                }
                .disabled(busy || p1 == nil || p2 == nil || worldPoints == nil)
                if let r = result { Text(r).font(.callout) }
                ErrorBanner(message: error)
            } footer: {
                Text("Guardar una geometría nueva borra las secciones, la máscara y los resultados PIV, como en escritorio.")
            }
            RectifiedPreviewSection(model: model, fileName: ProjectFiles.transformedImage)
        }
    }

    private var instruction: String {
        if p1 == nil { return "Toca el punto 1 sobre la imagen (pellizca para ampliar)." }
        if p2 == nil { return "Toca el punto 2." }
        return "Arrastra los puntos para ajustarlos."
    }

    private var canvasPoints: [CanvasPoint] {
        var out: [CanvasPoint] = []
        if let a = p1 { out.append(CanvasPoint(id: 1, point: a, label: "1", color: .red)) }
        if let b = p2 { out.append(CanvasPoint(id: 2, point: b, label: "2", color: .yellow)) }
        return out
    }

    private var canvasSegments: [CanvasSegment] {
        guard let a = p1, let b = p2 else { return [] }
        return [CanvasSegment(a: a, b: b, color: .yellow, width: 2, dashed: true)]
    }

    private var worldPoints: (Point2, Point2)? {
        if useCoordinates {
            guard let a = Fmt.parse(e1), let b = Fmt.parse(n1), let c = Fmt.parse(e2), let d = Fmt.parse(n2) else { return nil }
            let w1 = Point2(a, b)
            let w2 = Point2(c, d)
            return w1.distance(to: w2) > 0 ? (w1, w2) : nil
        }
        guard let l = Fmt.parse(lengthText), l > 0 else { return nil }
        return (Point2(0, 0), Point2(l, 0))
    }

    private var previewPixelSize: Double? {
        guard let a = p1, let b = p2, let w = worldPoints else { return nil }
        return try? UAVTransform.pixelSize(pixel1: a, pixel2: b, world1: w.0, world2: w.1)
    }

    private func tap(_ p: Point2) {
        if p1 == nil {
            p1 = p
        } else if p2 == nil {
            p2 = p
        }
    }

    private func move(_ id: Int, _ p: Point2) {
        if id == 1 { p1 = p } else { p2 = p }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let ps = model.settings?.pixelSize else { return }
        p1 = ps.pixel1
        p2 = ps.pixel2
        let defaultWorld: Bool = ps.world1 == Point2(0, 0) && abs(ps.world2.y) < 1e-12
        useCoordinates = !defaultWorld
        lengthText = Fmt.num(ps.realWorldLength, 3)
        e1 = Fmt.num(ps.world1.x, 3)
        n1 = Fmt.num(ps.world1.y, 3)
        e2 = Fmt.num(ps.world2.x, 3)
        n2 = Fmt.num(ps.world2.y, 3)
    }

    private func apply() {
        guard let a = p1, let b = p2, let w = worldPoints else { return }
        error = nil
        result = nil
        busy = true
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let outcome: (RiverProject, UAVOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, UAVOutcome) in
                    var p = project
                    let frame = try GeometryPipeline.loadFirstFrame(p)
                    let o = try GeometryPipeline.applyUAV(&p, pixel1: a, pixel2: b, world1: w.0, world2: w.1, frame: frame)
                    return (p, o)
                }.value
                model.replace(outcome.0)
                result = "Tamaño de píxel \(Fmt.num(outcome.1.pixelSize * 100, 3)) cm/px. Imagen rectificada guardada."
            } catch {
                self.error = "\(error)"
                AppLog.error("Geometría UAV: \(error)")
                model.reload()
            }
            busy = false
        }
    }
}
