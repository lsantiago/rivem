import AVKit
import RiverCore
import SwiftUI

/// Step 1, desktop "Video Range": the video plays on top (start / end can be set at the paused position); start/end
/// time, step and resize factor → `FrameExtractor` → `frames/` and `video_range`. Without the video (projects made
/// from frames, like the pisco example) the extracted frames play instead.
struct VideoRangeView: View {
    let model: WorkflowModel

    @State private var startText: String = "0"
    @State private var endText: String = ""
    @State private var step: Int = 1
    @State private var factor: Double = 1
    @State private var progress: Double = 0
    @State private var task: Task<Void, Never>? = nil
    @State private var message: String? = nil
    @State private var error: String? = nil
    @State private var loaded = false
    @State private var player: AVPlayer? = nil
    @State private var frames = FrameSequence()

    private static let factors: [Double] = [1, 0.75, 0.5, 0.25]

    var body: some View {
        Group {
            if model.hasVideo || model.frameCount > 0 {
                CanvasStepLayout {
                    media
                } controls: {
                    form
                }
            } else {
                form
            }
        }
        .onAppear(perform: setupMedia)
        .onDisappear {
            player?.pause()
            frames.stop()
        }
    }

    @ViewBuilder
    private var media: some View {
        if let player = player {
            VideoPlayer(player: player)
                .background(Color.black)
        } else {
            ImageCanvas(image: frames.image ?? model.firstFrame, imageSize: model.frameSize)
                .overlay(alignment: .top) {
                    FramePlayerBar(sequence: frames, caption: frameCaption)
                }
        }
    }

    /// Source frame number and its time, from the file name (`{index:010d}.jpg`).
    private var frameCaption: String {
        guard frames.index < frames.count, let n = Int(frames.frames[frames.index].deletingPathExtension().lastPathComponent) else {
            return "\(frames.index + 1)/\(frames.count)"
        }
        return "frame \(n) · \(Fmt.num(Double(n) / fps, 2)) s"
    }

    private func setupMedia() {
        guard let project = model.project else { return }
        if player == nil, let url = ProjectFiles.videoURL(project) {
            player = AVPlayer(url: url)
        } else if player == nil {
            let dt: Double = project.settings.frameInterval ?? 0.1
            frames.configure(frames: project.frameFiles, rate: dt > 0 ? 1 / dt : 10)
        }
    }

    private func mark(start: Bool) {
        guard let p = player else { return }
        p.pause()
        let t: Double = p.currentTime().seconds
        guard t.isFinite else { return }
        if start { startText = Fmt.num(t, 2) } else { endText = Fmt.num(t, 2) }
    }

    private var form: some View {
        Form {
            VideoInfoSection(model: model)
            Section("Rango") {
                LabeledContent("Inicio (s)") {
                    TextField("0", text: $startText).multilineTextAlignment(.trailing).decimalKeyboard()
                }
                LabeledContent("Fin (s)") {
                    TextField("fin", text: $endText).multilineTextAlignment(.trailing).decimalKeyboard()
                }
                if player != nil {
                    HStack {
                        Button { mark(start: true) } label: { Label("Inicio aquí", systemImage: "arrow.right.to.line") }
                            .buttonStyle(.bordered)
                        Spacer()
                        Button { mark(start: false) } label: { Label("Fin aquí", systemImage: "arrow.left.to.line") }
                            .buttonStyle(.bordered)
                    }
                }
                Stepper("Paso: \(step) (uno de cada \(step) frames)", value: $step, in: 1...30)
                Picker("Escala de los frames", selection: $factor) {
                    ForEach(Self.factors, id: \.self) { f in
                        Text(String(format: "%.0f %%", f * 100)).tag(f)
                    }
                }
                RangeSummaryRow(range: currentRange, video: model.settings?.video)
            }
            Section {
                if task != nil {
                    ProgressView(value: progress) {
                        Text(String(format: "Extrayendo frames… %.0f %%", progress * 100))
                    }
                    Button("Cancelar", role: .destructive) { task?.cancel() }
                } else {
                    Button {
                        startExtraction()
                    } label: {
                        Label(model.frameCount > 0 ? "Volver a extraer los frames" : "Extraer frames", systemImage: "film.stack")
                    }
                    .disabled(!model.hasVideo || currentRange == nil)
                }
                if let m = message { Text(m).font(.callout).foregroundStyle(.secondary) }
                ErrorBanner(message: error)
            } footer: {
                Text("Pausa el video donde quieras empezar o terminar y toca «Inicio aquí» / «Fin aquí». Como en RIVeR escritorio, el frame N del video se guarda como frames/N.jpg (10 dígitos) y el intervalo entre frames es paso / fps. La extracción no corrige lente ni estabiliza.")
            }
        }
        .onAppear(perform: loadFromSettings)
        .onDisappear { task?.cancel() }
    }

    private var fps: Double { model.settings?.video?.fps ?? 30 }

    private var currentRange: VideoRange? {
        guard let start = Fmt.parse(startText), let end = Fmt.parse(endText), end > start else { return nil }
        let r = FramesPipeline.range(startTime: start, endTime: end, step: step, factor: factor, fps: fps)
        return r.frameIndices.isEmpty ? nil : r
    }

    private func loadFromSettings() {
        guard !loaded, let s = model.settings else { return }
        loaded = true
        let range: VideoRange? = s.videoRange ?? s.video.map { FramesPipeline.defaultRange($0) }
        guard let r = range else { return }
        startText = Fmt.num(Double(r.start) / fps, 2)
        endText = Fmt.num(Double(r.end) / fps, 2)
        step = r.step
        factor = Self.factors.contains(r.factor) ? r.factor : 1
    }

    private func startExtraction() {
        guard let range = currentRange, let project = model.project else { return }
        error = nil
        message = nil
        progress = 0
        task = Task { @MainActor in
            do {
                let outcome = try await FramesPipeline.extract(project, range: range) { (value: Double) in
                    Task { @MainActor in progress = value }
                }
                model.replace(outcome.project)
                var text = "\(outcome.written) frames extraídos."
                if outcome.sizeChanged {
                    text += " El tamaño de los frames cambió: vuelve a georreferenciar y a dibujar las secciones."
                }
                message = text
            } catch is CancellationError {
                message = "Extracción cancelada."
                model.reload()
            } catch {
                self.error = "\(error)"
                AppLog.error("Extracción de frames: \(error)")
                model.reload()
            }
            task = nil
        }
    }
}

private struct VideoInfoSection: View {
    let model: WorkflowModel

    var body: some View {
        Section("Video") {
            if let v = model.settings?.video {
                LabeledContent("Archivo", value: v.name ?? "—")
                if let w = v.width, let h = v.height { LabeledContent("Resolución", value: "\(w) × \(h)") }
                LabeledContent("FPS (redondeado como escritorio)", value: Fmt.num(v.fps, 0))
                LabeledContent("Duración", value: "\(Fmt.num(v.duration, 2)) s")
            }
            LabeledContent("Frames en el proyecto", value: "\(model.frameCount)")
            if !model.hasVideo {
                Text("El video no está en la carpeta del proyecto: no se pueden extraer frames.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}

private struct RangeSummaryRow: View {
    let range: VideoRange?
    let video: VideoInfo?

    var body: some View {
        if let r = range, let fps = video?.fps, fps > 0 {
            let count: Int = r.frameIndices.count
            let dt: Double = Double(r.step) / fps
            VStack(alignment: .leading, spacing: 2) {
                Text("Frames \(r.start)–\(r.end) · \(count) imágenes · \(max(count - 1, 0)) pares")
                Text("Δt entre frames: \(Fmt.num(dt * 1000, 1)) ms")
            }
            .font(.callout)
            .foregroundStyle(.secondary)
        } else {
            Text("Rango no válido").font(.callout).foregroundStyle(.red)
        }
    }
}
