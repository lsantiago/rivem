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

/// The screen of one step, as a wizard like desktop's: a numbered stepper on top (tap a step to jump), previous /
/// next at the bottom (next is highlighted once the step is done, and says what is missing when it is not
/// available yet). The project summary is the screen behind (back button). Moving between steps replaces the content in place, so going on
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

/// Stepper of the six steps: numbered circles joined by a line, with a short name under each. The current step is
/// a large filled circle with its name in bold accent colour; done steps show a check on a light accent circle and
/// the line up to them is coloured; pending steps are grey outlines (faded when not available yet). Tap to jump.
private struct StepProgressBar: View {
    let current: WorkflowStep
    let model: WorkflowModel
    let go: (WorkflowStep) -> Void

    private enum StepState { case current, done, pending, locked }

    var body: some View {
        let steps: [WorkflowStep] = WorkflowStep.wizard
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.element) { pair in
                item(pair.element, number: pair.offset + 1, isFirst: pair.offset == 0, isLast: pair.offset == steps.count - 1)
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 2)
        .padding(.bottom, 6)
        .background(.bar)
    }

    private func state(_ s: WorkflowStep) -> StepState {
        if s == current { return .current }
        if model.isDone(s) { return .done }
        return model.isAvailable(s) ? .pending : .locked
    }

    private func item(_ s: WorkflowStep, number: Int, isFirst: Bool, isLast: Bool) -> some View {
        let st: StepState = state(s)
        let size: CGFloat = st == .current ? 30 : 24
        return VStack(spacing: 4) {
            ZStack {
                // Connector halves on each side of the circle (coloured up to the steps already done).
                HStack(spacing: 0) {
                    Rectangle().fill(isFirst ? Color.clear : lineColor(leftOf: s)).frame(height: 2)
                    Rectangle().fill(isLast ? Color.clear : lineColor(rightOf: s)).frame(height: 2)
                }
                circle(st, number: number)
                    .frame(width: size, height: size)
            }
            .frame(height: 32)
            Text(s.stepperTitle)
                .font(.system(size: 10, weight: st == .current ? .bold : .regular))
                .foregroundStyle(st == .current ? Color.accentColor : (st == .locked ? Color.secondary.opacity(0.5) : Color.secondary))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { go(s) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Paso \(number), \(s.shortTitle), \(stateText(st))")
        .accessibilityAddTraits(st == .current ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private func circle(_ st: StepState, number: Int) -> some View {
        switch st {
        case .current:
            Circle()
                .fill(Color.accentColor)
                .overlay(Text("\(number)").font(.system(size: 14, weight: .bold)).foregroundStyle(.white))
                .shadow(color: Color.accentColor.opacity(0.5), radius: 4)
        case .done:
            // Opaque base so the connector line does not show through the check.
            Circle()
                .fill(Color(.systemBackground))
                .overlay(Circle().fill(Color.accentColor.opacity(0.18)))
                .overlay(Circle().stroke(Color.accentColor, lineWidth: 1.5))
                .overlay(Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(Color.accentColor))
        case .pending:
            Circle()
                .fill(Color(.systemBackground))
                .overlay(Circle().stroke(Color.secondary.opacity(0.6), lineWidth: 1.5))
                .overlay(Text("\(number)").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary))
        case .locked:
            Circle()
                .fill(Color(.systemBackground))
                .overlay(Circle().stroke(Color.secondary.opacity(0.25), style: StrokeStyle(lineWidth: 1.5, dash: [3, 2])))
                .overlay(Text("\(number)").font(.system(size: 11)).foregroundStyle(Color.secondary.opacity(0.5)))
        }
    }

    private func lineColor(leftOf s: WorkflowStep) -> Color {
        guard let i = WorkflowStep.wizard.firstIndex(of: s), i > 0 else { return .clear }
        return model.isDone(WorkflowStep.wizard[i - 1]) ? Color.accentColor : Color.secondary.opacity(0.25)
    }

    private func lineColor(rightOf s: WorkflowStep) -> Color {
        model.isDone(s) ? Color.accentColor : Color.secondary.opacity(0.25)
    }

    private func stateText(_ st: StepState) -> String {
        switch st {
        case .current: return "paso actual"
        case .done: return "completado"
        case .pending: return "pendiente"
        case .locked: return "aún no disponible"
        }
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
