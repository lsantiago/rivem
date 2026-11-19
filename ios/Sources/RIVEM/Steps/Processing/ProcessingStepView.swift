import RiverCore
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Progress shared between the PIV worker threads and the screen.
@Observable
final class ProcessingProgress {
    var done: Int = 0
    var total: Int = 0
    var started: Date = Date()

    var fraction: Double { total > 0 ? Double(done) / Double(total) : 0 }

    /// Remaining seconds extrapolated from the pairs done so far.
    var eta: Double? {
        guard done > 0, total > done else { return nil }
        let elapsed: Double = Date().timeIntervalSince(started)
        return elapsed / Double(done) * Double(total - done)
    }
}

/// Step 4, desktop "Processing" + "Analyze all": PIV parameters (desktop defaults, advanced filters), full run in the
/// background with progress, ETA and cancel; the screen stays awake meanwhile. Afterwards the discharge of every
/// section is computed.
struct ProcessingStepView: View {
    let model: WorkflowModel

    @State private var ia1: Int = 128
    @State private var clahe = true
    @State private var clipLimit: Double = 5
    @State private var removeBackground = false
    @State private var stdFiltering = true
    @State private var stdThreshold: Double = 4
    @State private var medianFiltering = true
    @State private var medianEpsilon: Double = 0.02
    @State private var medianThreshold: Double = 2
    @State private var progress = ProcessingProgress()
    @State private var cancelFlag: CancellationFlag? = nil
    @State private var summary: String? = nil
    @State private var failed: [PIVFailedPair] = []
    @State private var error: String? = nil
    @State private var loaded = false

    private var running: Bool { cancelFlag != nil }

    var body: some View {
        CanvasStepLayout {
            PairPreview(model: model)
        } controls: {
            form
        }
        .onAppear(perform: load)
        .onDisappear { cancelFlag?.cancel() }
    }

    private var form: some View {
        Form {
            ProcessingInputSection(model: model)
            Section("Ventana de interrogación") {
                Picker("Ventana (paso 1)", selection: $ia1) {
                    ForEach(ProcessingPipeline.interrogationAreas, id: \.self) { v in
                        Text("\(v) px").tag(v)
                    }
                }
                LabeledContent("Ventana (paso 2)", value: "\(ia1 / 2) px")
            }
            Section("Preprocesamiento") {
                Toggle("CLAHE (realce de contraste)", isOn: $clahe)
                if clahe {
                    LabeledContent("Límite de recorte") {
                        TextField("5", value: $clipLimit, format: .number).multilineTextAlignment(.trailing).decimalKeyboard()
                    }
                }
                Toggle("Quitar fondo (media de los frames)", isOn: $removeBackground)
            }
            Section {
                DisclosureGroup("Filtros de vectores (avanzado)") {
                    Toggle("Filtro de desviación estándar", isOn: $stdFiltering)
                    LabeledContent("Umbral (σ)") {
                        TextField("4", value: $stdThreshold, format: .number).multilineTextAlignment(.trailing).decimalKeyboard()
                    }
                    Toggle("Test de la mediana", isOn: $medianFiltering)
                    LabeledContent("Épsilon") {
                        TextField("0,02", value: $medianEpsilon, format: .number).multilineTextAlignment(.trailing).decimalKeyboard()
                    }
                    LabeledContent("Umbral") {
                        TextField("2", value: $medianThreshold, format: .number).multilineTextAlignment(.trailing).decimalKeyboard()
                    }
                }
            }
            runSection
            if !failed.isEmpty {
                Section("Pares fallidos (\(failed.count))") {
                    ForEach(failed, id: \.index) { f in
                        Text("Par \(f.index): \(f.reason)").font(.caption)
                    }
                }
            }
        }
    }

    private var runSection: some View {
        Section {
            if running {
                ProgressView(value: progress.fraction) {
                    Text("Pares procesados: \(progress.done) de \(progress.total)")
                }
                if let eta = progress.eta {
                    Text("Tiempo restante estimado: \(Self.duration(eta))").font(.callout).foregroundStyle(.secondary)
                }
                Button("Cancelar", role: .destructive) { cancelFlag?.cancel() }
            } else {
                Button {
                    run()
                } label: {
                    Label(model.hasResults ? "Volver a procesar" : "Procesar todos los frames", systemImage: "play.fill")
                }
                .disabled(model.frameCount < 2 || !model.hasMask)
            }
            if let s = summary { Text(s).font(.callout) }
            ErrorBanner(message: error)
        } footer: {
            Text("La pantalla no se apaga mientras procesa. Con frames grandes (4K) los pares se procesan de a uno para no agotar la memoria.")
        }
    }

    static func duration(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        if s < 60 { return "\(s) s" }
        return String(format: "%ld min %02ld s", s / 60, s % 60)
    }

    private var parameters: PIVParameters {
        PIVParameters(interrogationArea1: ia1, removeBackground: removeBackground, clahe: clahe, clipLimit: clipLimit,
                      stdFiltering: stdFiltering, stdThreshold: stdThreshold, medianTestFiltering: medianFiltering,
                      medianTestEpsilon: medianEpsilon, medianTestThreshold: medianThreshold)
    }

    private func load() {
        guard !loaded, let s = model.settings else { return }
        loaded = true
        let p: PIVParameters = ProcessingPipeline.parameters(s)
        ia1 = ProcessingPipeline.interrogationAreas.contains(p.interrogationArea1) ? p.interrogationArea1 : 128
        clahe = p.clahe
        clipLimit = p.clipLimit
        removeBackground = p.removeBackground
        stdFiltering = p.stdFiltering
        stdThreshold = p.stdThreshold
        medianFiltering = p.medianTestFiltering
        medianEpsilon = p.medianTestEpsilon
        medianThreshold = p.medianTestThreshold
    }

    private func setScreenAwake(_ awake: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = awake
        #endif
    }

    private func run() {
        guard let project = model.project else { return }
        let params: PIVParameters = parameters
        do {
            try params.validate()
        } catch {
            self.error = "\(error)"
            return
        }
        let flag = CancellationFlag()
        let tracker = ProcessingProgress()
        tracker.total = max(model.frameCount - 1, 0)
        tracker.started = Date()
        progress = tracker
        cancelFlag = flag
        error = nil
        summary = nil
        failed = []
        setScreenAwake(true)
        Task { @MainActor in
            do {
                let done: (RiverProject, ProcessingOutcome, ResultsOutcome?) = try await Task.detached(priority: .userInitiated) {
                    () throws -> (RiverProject, ProcessingOutcome, ResultsOutcome?) in
                    var p = project
                    let o = try ProcessingPipeline.run(&p, parameters: params, progress: { (d: Int, t: Int) in
                        DispatchQueue.main.async {
                            tracker.done = d
                            tracker.total = t
                        }
                    }, cancel: flag)
                    // Discharge with desktop's default options (no interpolation, no artificial seeding).
                    let r: ResultsOutcome? = try? ResultsPipeline.compute(&p, interpolate: false, artificialSeeding: false)
                    return (p, o, r)
                }.value
                model.replace(done.0)
                failed = done.1.failed
                var text = "\(done.1.pairs) pares en \(Self.duration(done.1.seconds)), rejilla \(done.1.rows)×\(done.1.columns)"
                text += done.1.failed.isEmpty ? ", sin pares fallidos." : ", \(done.1.failed.count) pares fallidos."
                if let r = done.2 {
                    let total: Double = r.results.compactMap { $0.totalQ }.reduce(0, +)
                    if !r.results.isEmpty { text += " Caudal calculado: \(model.units.dischargeText(total))." }
                    if !r.errors.isEmpty { text += " " + r.errors.joined(separator: "; ") }
                }
                summary = text
            } catch PIVError.cancelled {
                summary = "Procesamiento cancelado: no se guardaron resultados."
                model.reload()
            } catch {
                self.error = "\(error)"
                AppLog.error("Procesamiento: \(error)")
                model.reload()
            }
            cancelFlag = nil
            setScreenAwake(false)
        }
    }
}

/// The frames with the PIV vectors (desktop's processing carousel): play / scrub the frames; "Mediana" shows the
/// median field, "Par" the field of the pair starting at the frame on screen. Colours on the median field's range.
private struct PairPreview: View {
    let model: WorkflowModel
    @State private var frames = FrameSequence()
    @State private var piv: PIVResults? = nil
    @State private var matrix: Matrix3? = nil
    @State private var scale: Double = 10
    @State private var range: ClosedRange<Double> = 0...1
    @State private var median = true

    var body: some View {
        ImageCanvas(image: frames.image ?? model.firstFrame, imageSize: model.frameSize, segments: segments)
            .overlay(alignment: .top) {
                FramePlayerBar(sequence: frames, caption: caption) {
                    if piv != nil {
                        Button {
                            median.toggle()
                        } label: {
                            Text(median ? "Mediana" : "Par").font(.caption.bold())
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .accessibilityHint("Cambia entre el campo mediano y el del par en pantalla")
                    }
                }
            }
            .overlay(alignment: .bottomLeading) {
                if piv != nil { VelocityLegend(range: range, units: model.units) }
            }
            .task(id: model.revision) { await reload() }
            .onDisappear { frames.stop() }
    }

    private var caption: String {
        let n: Int = frames.count
        guard let p = piv, !median else { return "\(frames.index + 1)/\(n)" }
        return "par \(min(frames.index, max(p.pairCount - 1, 0)) + 1)/\(p.pairCount)"
    }

    private var segments: [CanvasSegment] {
        guard let p = piv else { return [] }
        let pair: Int? = median ? nil : min(frames.index, p.pairCount - 1)
        let vectors: [QuiverVector] = QuiverBuilder.vectors(p, pair: pair, scale: scale, matrix: matrix,
                                                            frameInterval: model.settings?.frameInterval)
        return QuiverBuilder.segments(vectors, range: range)
    }

    private func reload() async {
        guard let project = model.project else { return }
        let dt: Double = project.settings.frameInterval ?? 0.1
        frames.configure(frames: project.frameFiles, rate: dt > 0 ? 1 / dt : 10)
        guard model.hasResults else {
            piv = nil
            return
        }
        let width: Double = Double(model.frameSize.width)
        let loaded: (PIVResults, Matrix3?, Double, ClosedRange<Double>)? = await Task.detached(priority: .utility) {
            () -> (PIVResults, Matrix3?, Double, ClosedRange<Double>)? in
            guard let p = try? project.readPIVResults() else { return nil }
            let m: Matrix3? = try? project.readMatrix()
            let s: Double = QuiverBuilder.scale(p, imageWidth: width)
            let r: ClosedRange<Double> = QuiverBuilder.range(QuiverBuilder.vectors(p, pair: nil, scale: s, matrix: m,
                                                                                   frameInterval: project.settings.frameInterval))
            return (p, m, s, r)
        }.value
        if let l = loaded {
            piv = l.0
            matrix = l.1
            scale = l.2
            range = l.3
        }
    }
}

private struct ProcessingInputSection: View {
    let model: WorkflowModel

    var body: some View {
        Section("Entrada") {
            LabeledContent("Frames", value: "\(model.frameCount) (\(max(model.frameCount - 1, 0)) pares)")
            if model.frameSize.width > 0 {
                LabeledContent("Tamaño", value: "\(Int(model.frameSize.width)) × \(Int(model.frameSize.height)) px")
            }
            if let dt = model.settings?.frameInterval {
                LabeledContent("Δt entre frames", value: "\(Fmt.num(dt * 1000, 1)) ms")
            }
            LabeledContent("Máscara de secciones", value: model.hasMask ? "sí" : "falta (guarda las secciones)")
            if model.hasResults {
                Label("Hay resultados PIV guardados", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            }
        }
    }
}
