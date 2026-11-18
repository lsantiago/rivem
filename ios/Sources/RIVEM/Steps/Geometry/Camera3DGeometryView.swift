import RiverCore
import SwiftUI
import UniformTypeIdentifiers

/// Fixed station with 3-D control points (desktop "ipcam"): import a GRP file (Label, X, Y, Z [, x, y]), mark the
/// points that have no pixel position, solve the camera (11-parameter DLT), check the reprojection errors and give
/// the water level that defines the matrix. Writes `grp_3d.json`, `full_grp_3d.json`, `camera_solution_3d.json`,
/// `ortho_image.png` and `transformation_matrix.json` like desktop.
struct Camera3DGeometryView: View {
    let model: WorkflowModel

    @State private var points: [GRPPoint] = []
    @State private var activeID: Int? = nil
    @State private var levelText: String = ""
    @State private var outcome: CameraOutcome? = nil
    @State private var error: String? = nil
    @State private var busy = false
    @State private var importing = false
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
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .tabSeparatedText, .plainText, .data]) { result in
            importGRP(result)
        }
    }

    private var controlsForm: some View {
        Form {
            Section {
                Button {
                    importing = true
                } label: {
                    Label(points.isEmpty ? "Importar puntos de control (CSV)" : "Reemplazar puntos de control", systemImage: "doc.badge.plus")
                }
                Text(instruction).font(.callout)
            } footer: {
                Text("Columnas: Label, X, Y, Z y opcionalmente x, y (píxel). Coordenadas en metros; cota Z en el mismo sistema que la batimetría.")
            }
            if !points.isEmpty {
                GRPListSection(points: points, activeID: $activeID, errors: errorsByID) { id in
                    if let i = points.firstIndex(where: { $0.id == id }) { points[i].pixel = nil }
                }
                Section("Nivel del agua") {
                    NumberField(title: "Cota del nivel (Z)", text: $levelText)
                    Text("La matriz depende del nivel: en el paso de secciones se recalcula con el nivel de la primera sección.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section {
                    Button {
                        apply()
                    } label: {
                        if busy { ProgressView() } else { Label("Resolver cámara y guardar", systemImage: "camera.metering.matrix") }
                    }
                    .disabled(busy || markedCount < 6 || Fmt.parse(levelText) == nil)
                    if let o = outcome {
                        LabeledContent("Error medio de reproyección", value: "\(Fmt.num(o.solution.meanError, 2)) px")
                        let pos: [Double] = o.solution.cameraPosition
                        if pos.count == 3 {
                            LabeledContent("Posición de la cámara", value: "\(Fmt.num(pos[0], 2)), \(Fmt.num(pos[1], 2)), \(Fmt.num(pos[2], 2))")
                        }
                    }
                    ErrorBanner(message: error)
                } footer: {
                    Text("Se necesitan al menos 6 puntos marcados que no estén todos en un plano. Guardar borra secciones, máscara y resultados.")
                }
            }
            RectifiedPreviewSection(model: model, fileName: ProjectFiles.orthoImage)
        }
    }

    private var markedCount: Int { points.filter { $0.pixel != nil }.count }

    private var instruction: String {
        if points.isEmpty { return "Importa el archivo de puntos de control." }
        if let id = activeID, let p = points.first(where: { $0.id == id }) {
            return "Toca la imagen para ubicar \(p.label)."
        }
        return "Elige un punto de la lista y tócalo en la imagen. Marcados: \(markedCount) de \(points.count)."
    }

    private var errorsByID: [Int: Double] {
        guard let o = outcome else { return [:] }
        var out: [Int: Double] = [:]
        for (k, id) in o.usedIDs.enumerated() where k < o.solution.reprojectionErrors.count {
            out[id] = o.solution.reprojectionErrors[k]
        }
        return out
    }

    private var canvasPoints: [CanvasPoint] {
        var out: [CanvasPoint] = []
        for p in points {
            if let px = p.pixel {
                let color: Color = p.id == activeID ? .yellow : .red
                out.append(CanvasPoint(id: p.id, point: px, label: p.label, color: color))
            }
        }
        return out
    }

    /// Reprojection residual of each point (from the marked position to where the camera projects it).
    private var canvasSegments: [CanvasSegment] {
        guard let o = outcome else { return [] }
        var out: [CanvasSegment] = []
        for (k, id) in o.usedIDs.enumerated() where k < o.solution.projectedPoints.count {
            if let p = points.first(where: { $0.id == id }), let px = p.pixel {
                out.append(CanvasSegment(a: px, b: o.solution.projectedPoints[k], color: .orange, width: 2, arrow: true))
            }
        }
        return out
    }

    private func tap(_ p: Point2) {
        guard let id = activeID, let i = points.firstIndex(where: { $0.id == id }) else { return }
        points[i].pixel = p
        // Move on to the next point without a position.
        activeID = points.first(where: { $0.pixel == nil })?.id
    }

    private func move(_ id: Int, _ p: Point2) {
        if let i = points.firstIndex(where: { $0.id == id }) { points[i].pixel = p }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let project = model.project else { return }
        if let stored = GeometryPipeline.loadGRP(project) {
            points = stored
        }
        if let m = try? model.project?.readMatrix(), let cam = model.project.flatMap({ ProjectFiles.readCamera($0) }) {
            levelText = Fmt.num(Self.level(of: m, camera: cam), 3)
        } else if !points.isEmpty {
            levelText = Fmt.num(points.map { $0.Z }.reduce(0, +) / Double(points.count), 3)
        }
    }

    /// Level whose plane matrix matches the stored one (it is not stored by desktop): solved from the image of one
    /// pixel, Z = level such that inv(H(Z)) maps like `m`. Falls back to the mean Z of the points.
    private static func level(of m: Matrix3, camera: CameraMatrix) -> Double {
        let probe = Point2(100, 100)
        let world: Point2 = m.apply(probe)
        // P · [X, Y, Z, 1] ∝ [x, y, 1]: solve the x-row (x · w = u) for Z given X, Y.
        let u: Double = camera[0, 0] * world.x + camera[0, 1] * world.y + camera[0, 3]
        let w: Double = camera[2, 0] * world.x + camera[2, 1] * world.y + camera[2, 3]
        let a: Double = camera[0, 2] - probe.x * camera[2, 2]
        guard abs(a) > 1e-15 else { return 0 }
        return (probe.x * w - u) / a
    }

    private func importGRP(_ result: Result<URL, Error>) {
        do {
            let url: URL = try result.get()
            let scoped: Bool = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            let text: String = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
            let feet: Bool = model.settings?.unitSystem == .imperial
            points = try GeometryPipeline.parseGRP(text, feet: feet)
            outcome = nil
            activeID = points.first(where: { $0.pixel == nil })?.id
            if levelText.isEmpty {
                levelText = Fmt.num(points.map { $0.Z }.reduce(0, +) / Double(points.count), 3)
            }
            error = nil
        } catch {
            self.error = "No se pudieron leer los puntos: \(error)"
        }
    }

    private func apply() {
        guard let level = Fmt.parse(levelText) else { return }
        let list: [GRPPoint] = points
        error = nil
        busy = true
        Task { @MainActor in
            do {
                guard let project = model.project else { throw PipelineError("El proyecto no está abierto.") }
                let done: (RiverProject, CameraOutcome) = try await Task.detached(priority: .userInitiated) { () throws -> (RiverProject, CameraOutcome) in
                    var p = project
                    let frame = try GeometryPipeline.loadFirstFrame(p)
                    let o = try GeometryPipeline.applyCamera3D(&p, points: list, waterLevel: level, frame: frame)
                    return (p, o)
                }.value
                model.replace(done.0)
                outcome = done.1
            } catch {
                self.error = "\(error)"
                AppLog.error("Geometría 3D: \(error)")
                model.reload()
            }
            busy = false
        }
    }
}

private struct GRPListSection: View {
    let points: [GRPPoint]
    @Binding var activeID: Int?
    let errors: [Int: Double]
    let clear: (Int) -> Void

    var body: some View {
        Section("Puntos de control") {
            ForEach(points) { p in
                GRPRow(point: p, active: p.id == activeID, error: errors[p.id]) {
                    activeID = p.id
                } clear: {
                    clear(p.id)
                }
            }
        }
    }
}

private struct GRPRow: View {
    let point: GRPPoint
    let active: Bool
    let error: Double?
    let select: () -> Void
    let clear: () -> Void

    var body: some View {
        Button(action: select) {
            HStack {
                Image(systemName: point.pixel == nil ? "circle.dashed" : "mappin.circle.fill")
                    .foregroundStyle(point.pixel == nil ? Color.secondary : Color.red)
                VStack(alignment: .leading, spacing: 2) {
                    Text(point.label).foregroundStyle(Color.primary)
                    Text("X \(Fmt.num(point.X, 2))  Y \(Fmt.num(point.Y, 2))  Z \(Fmt.num(point.Z, 2))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let e = error {
                    Text("\(Fmt.num(e, 1)) px").font(.caption).foregroundStyle(e > 5 ? Color.orange : Color.secondary)
                }
                if active { Image(systemName: "hand.tap.fill").foregroundStyle(.yellow) }
            }
        }
        .swipeActions {
            if point.pixel != nil {
                Button("Quitar", role: .destructive, action: clear)
            }
        }
    }
}
