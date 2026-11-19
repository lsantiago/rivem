import RiverCore
import SwiftUI

/// Guided workflow of one project: the list of steps with their state; opening a project jumps to the step to
/// continue with (or to the step requested by the CI screenshot hooks).
struct WorkflowView: View {
    let folder: URL
    let initialStep: WorkflowStep?
    /// Jump to `initialStep` (or the suggested step) when the project opens.
    let autoOpen: Bool
    @State private var model: WorkflowModel
    @State private var selected: WorkflowStep?
    @State private var didAutoOpen = false

    static let formatNote: String = "Cada paso se guarda en la carpeta del proyecto con el formato de RIVeR escritorio "
        + RiverCore.desktopCompatibility
        + ": copiando la carpeta al espacio de trabajo RIVeR del PC, el aforo se abre allí en el paso alcanzado."

    init(folder: URL, initialStep: WorkflowStep? = nil, autoOpen: Bool = true) {
        self.folder = folder
        self.initialStep = initialStep
        self.autoOpen = autoOpen
        _model = State(initialValue: WorkflowModel(folder: folder))
    }

    var body: some View {
        List {
            WorkflowHeader(model: model)
            Section("Pasos") {
                ForEach(WorkflowStep.allCases) { step in
                    WorkflowStepRow(step: step, model: model) { selected = step }
                }
            }
            Section {
                Text(Self.formatNote)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(model.displayName)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(item: $selected) { step in
            WorkflowStepScreen(step: step, model: model)
        }
        .onAppear {
            model.reload()
            if !didAutoOpen {
                didAutoOpen = true
                if autoOpen, let step = initialStep ?? model.suggestedStep, model.isAvailable(step) {
                    selected = step
                }
            }
        }
    }
}

private struct WorkflowHeader: View {
    let model: WorkflowModel

    var body: some View {
        Section {
            if let error = model.loadError {
                Label(error, systemImage: "xmark.octagon").foregroundStyle(.red)
            } else if let s = model.settings {
                if let river = s.riverName, !river.isEmpty { LabeledContent("Río", value: river) }
                if let site = s.site, !site.isEmpty { LabeledContent("Sitio", value: site) }
                LabeledContent("Carpeta", value: model.folder.deletingLastPathComponent().lastPathComponent + "/" + model.folder.lastPathComponent)
                LabeledContent("Tipo de toma", value: s.footage?.label ?? "desconocido")
                LabeledContent("Etapa", value: s.stage.label)
                LabeledContent("Frames", value: "\(model.frameCount)")
                if !model.hasVideo {
                    Text("El video no está en la carpeta del proyecto: se pueden usar los frames existentes pero no volver a extraerlos.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct WorkflowStepRow: View {
    let step: WorkflowStep
    let model: WorkflowModel
    let open: () -> Void

    var body: some View {
        let available: Bool = model.isAvailable(step)
        let done: Bool = model.isDone(step)
        Button(action: open) {
            HStack(spacing: 12) {
                Image(systemName: step.symbol)
                    .frame(width: 28)
                    .foregroundStyle(available ? Color.accentColor : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(step.title).foregroundStyle(available ? Color.primary : Color.secondary)
                    if !available {
                        Text(model.requirement(step)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if done {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                }
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
        }
        .disabled(!available)
    }
}

/// The screen of one step, as a wizard like desktop's: a progress bar on top (tap a segment to jump), previous /
/// next at the bottom (next is highlighted once the step is done, and says what is missing when it is not
/// available yet), and a menu with every step. Moving between steps replaces the content in place, so going on
/// never needs a trip back to the step list.
struct WorkflowStepScreen: View {
    let model: WorkflowModel
    @State private var current: WorkflowStep

    init(step: WorkflowStep, model: WorkflowModel) {
        self.model = model
        _current = State(initialValue: step)
    }

    var body: some View {
        content
            .id(current)
            .transition(.opacity)
            .navigationTitle(current.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) { stepMenu }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if WorkflowStep.wizard.contains(current) {
                    StepProgressBar(current: current, model: model, go: go)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if WorkflowStep.wizard.contains(current) {
                    StepNavigationBar(current: current, model: model, go: go)
                }
            }
    }

    private func go(_ step: WorkflowStep) {
        model.reload()
        guard step != current, model.isAvailable(step) else { return }
        withAnimation(.easeInOut(duration: 0.2)) { current = step }
    }

    private var stepMenu: some View {
        Menu {
            ForEach(WorkflowStep.allCases) { (s: WorkflowStep) in
                Button {
                    go(s)
                } label: {
                    if s == current {
                        Label(s.title, systemImage: "arrowtriangle.right.fill")
                    } else if model.isDone(s) {
                        Label(s.title, systemImage: "checkmark.circle.fill")
                    } else {
                        Label(s.title, systemImage: s.symbol)
                    }
                }
                .disabled(!model.isAvailable(s))
            }
        } label: {
            Label("Pasos", systemImage: "list.bullet")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch current {
        case .summary:
            ProjectDetailView(summary: ProjectSummary(folder: model.folder))
        case .video:
            VideoRangeView(model: model)
        case .geometry:
            GeometryStepView(model: model)
        case .sections:
            SectionsStepView(model: model)
        case .processing:
            ProcessingStepView(model: model)
        case .results:
            ResultsStepView(model: model)
        case .export:
            ExportStepView(model: model)
        }
    }
}

/// Six segments (done, current, pending) with "Paso n de 6" and the next step; tapping a segment jumps to it.
private struct StepProgressBar: View {
    let current: WorkflowStep
    let model: WorkflowModel
    let go: (WorkflowStep) -> Void

    var body: some View {
        let steps: [WorkflowStep] = WorkflowStep.wizard
        let position: Int = (steps.firstIndex(of: current) ?? 0) + 1
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                ForEach(steps) { (s: WorkflowStep) in
                    Capsule()
                        .fill(color(s))
                        .frame(height: 5)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                        .onTapGesture { go(s) }
                        .accessibilityElement()
                        .accessibilityLabel(s.title)
                        .accessibilityAddTraits(.isButton)
                }
            }
            HStack {
                Text("Paso \(position) de \(steps.count)").font(.caption.bold())
                Spacer()
                if position < steps.count {
                    Text("Siguiente: \(steps[position].shortTitle)").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
        .background(.bar)
    }

    private func color(_ s: WorkflowStep) -> Color {
        if s == current { return Color.primary }
        if model.isDone(s) { return Color.accentColor }
        return model.isAvailable(s) ? Color.secondary.opacity(0.45) : Color.secondary.opacity(0.2)
    }
}

/// Previous / next buttons. Next is prominent when the current step is done; when the next step is not available
/// yet, it is disabled and the requirement is shown.
private struct StepNavigationBar: View {
    let current: WorkflowStep
    let model: WorkflowModel
    let go: (WorkflowStep) -> Void

    var body: some View {
        let steps: [WorkflowStep] = WorkflowStep.wizard
        let i: Int = steps.firstIndex(of: current) ?? 0
        let previous: WorkflowStep? = i > 0 ? steps[i - 1] : nil
        let next: WorkflowStep? = i + 1 < steps.count ? steps[i + 1] : nil
        HStack(alignment: .center, spacing: 12) {
            if let p = previous {
                Button {
                    go(p)
                } label: {
                    Label(p.shortTitle, systemImage: "chevron.left").lineLimit(1)
                }
                .buttonStyle(.bordered)
            }
            Spacer(minLength: 4)
            if let n = next {
                let available: Bool = model.isAvailable(n)
                VStack(alignment: .trailing, spacing: 2) {
                    if model.isDone(current) && available {
                        Button { go(n) } label: { nextLabel(n) }
                            .buttonStyle(.borderedProminent)
                    } else {
                        Button { go(n) } label: { nextLabel(n) }
                            .buttonStyle(.bordered)
                            .disabled(!available)
                    }
                    if !available {
                        Text(model.requirement(n))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.trailing)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func nextLabel(_ n: WorkflowStep) -> some View {
        HStack(spacing: 4) {
            Text(n.shortTitle).lineLimit(1)
            Image(systemName: "chevron.right")
        }
    }
}

/// Error banner used by the step screens.
struct ErrorBanner: View {
    let message: String?

    var body: some View {
        if let m = message {
            Label(m, systemImage: "exclamationmark.triangle.fill")
                .font(.callout)
                .foregroundStyle(.red)
        }
    }
}
