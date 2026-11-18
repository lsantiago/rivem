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

/// The screen of one step.
struct WorkflowStepScreen: View {
    let step: WorkflowStep
    let model: WorkflowModel

    var body: some View {
        content
            .navigationTitle(step.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
    }

    @ViewBuilder
    private var content: some View {
        switch step {
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
