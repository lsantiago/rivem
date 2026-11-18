import SwiftUI
import UniformTypeIdentifiers
import RiverCore

@main
struct RIVEMApp: App {
    @State private var workspace = Workspace()

    init() {
        AppLog.logLaunch()
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(workspace)
                .task { await Autotest.runIfRequested(workspace: workspace) }
        }
    }
}

struct HomeView: View {
    @Environment(Workspace.self) private var workspace
    @State private var importing = false
    @State private var creating = false
    @State private var pendingDelete: ProjectSummary?
    @State private var checks: [SelfCheck.Result] = []
    @State private var path: [ProjectSummary] = []
    /// Project opened by a CI screenshot hook, and the step to show.
    @State private var autotestFolder: URL?

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section("Aforos") {
                    if workspace.projects.isEmpty {
                        EmptyWorkspaceView()
                    }
                    ForEach(workspace.projects) { project in
                        NavigationLink(value: project) { ProjectRow(project: project) }
                            .swipeActions {
                                Button("Eliminar", role: .destructive) { pendingDelete = project }
                            }
                    }
                }
                Section {
                    Button {
                        creating = true
                    } label: {
                        Label("Nuevo aforo", systemImage: "plus.circle.fill")
                    }
                    NavigationLink {
                        DiagnosticsView()
                    } label: {
                        Label("Diagnóstico y registro", systemImage: "stethoscope")
                    }
                }
                Section("Núcleo") {
                    LabeledContent("RiverCore", value: RiverCore.version)
                    LabeledContent("Compatible con RIVeR", value: RiverCore.desktopCompatibility)
                }
                Section("Autocomprobación") {
                    ForEach(checks) { check in
                        SelfCheckRow(check: check)
                    }
                }
            }
            .navigationTitle("RIVEM")
            .navigationDestination(for: ProjectSummary.self) { summary in
                WorkflowView(folder: summary.folder, initialStep: initialStep(for: summary), autoOpen: autoOpen(for: summary))
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { creating = true } label: { Label("Nuevo aforo", systemImage: "video.badge.plus") }
                        Button { importing = true } label: { Label("Importar proyecto de escritorio", systemImage: "folder.badge.plus") }
                    } label: {
                        Label("Agregar", systemImage: "plus")
                    }
                }
            }
            .refreshable { workspace.reload() }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.folder]) { result in
                do {
                    try workspace.importProjects(from: result.get())
                } catch {
                    workspace.lastError = "\(error)"
                }
            }
            .sheet(isPresented: $creating) {
                NewProjectView(workspace: workspace) { folder in
                    workspace.reload()
                    path = [ProjectSummary(folder: folder)]
                }
            }
            .alert("No se pudo importar", isPresented: Binding(
                get: { workspace.lastError != nil }, set: { if !$0 { workspace.lastError = nil } }
            )) {
                Button("Aceptar", role: .cancel) {}
            } message: {
                Text(workspace.lastError ?? "")
            }
            .confirmationDialog("¿Eliminar este aforo del iPhone?", isPresented: Binding(
                get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }
            ), titleVisibility: .visible) {
                Button("Eliminar", role: .destructive) {
                    if let p = pendingDelete { try? workspace.delete(p) }
                    pendingDelete = nil
                }
            } message: {
                Text("Se borra la carpeta del proyecto en el iPhone. La copia de escritorio no se toca.")
            }
        }
        .onAppear {
            checks = SelfCheck.run()
            workspace.reload()
            openForAutotest()
        }
    }

    private func initialStep(for summary: ProjectSummary) -> WorkflowStep? {
        guard let folder = autotestFolder, folder.standardizedFileURL == summary.folder.standardizedFileURL else { return nil }
        return Autotest.stepToOpen
    }

    /// `-autotest_step list` shows the step list of the opened project without jumping into a step.
    private func autoOpen(for summary: ProjectSummary) -> Bool {
        guard let folder = autotestFolder, folder.standardizedFileURL == summary.folder.standardizedFileURL else { return true }
        return !Autotest.showStepList
    }

    /// CI screenshot hooks: `-autotest_open_first 1`, or `-autotest_open_project <name> [-autotest_step <step>]`.
    private func openForAutotest() {
        guard path.isEmpty else { return }
        if let name = Autotest.projectToOpen,
           let match = workspace.projects.first(where: { $0.name == name }) {
            autotestFolder = match.folder
            path = [match]
        } else if UserDefaults.standard.bool(forKey: "autotest_open_first"), let first = workspace.projects.first {
            autotestFolder = first.folder
            path = [first]
        }
    }
}

private struct EmptyWorkspaceView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "water.waves")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("Sin aforos todavía").font(.headline)
            Text("Crea un aforo nuevo grabando o eligiendo un video, importa un proyecto de RIVeR escritorio, o cópialo a la carpeta RIVEM › RIVeR en la app Archivos.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }
}

private struct SelfCheckRow: View {
    let check: SelfCheck.Result

    var body: some View {
        Label {
            VStack(alignment: .leading) {
                Text(check.name)
                Text(check.detail).font(.caption).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: check.passed ? "checkmark.circle.fill" : "xmark.octagon.fill")
                .foregroundStyle(check.passed ? .green : .red)
        }
    }
}

struct ProjectRow: View {
    let project: ProjectSummary

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: project.footage?.symbol ?? "questionmark.folder")
                .frame(width: 28)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(project.riverName ?? project.name).font(.headline)
                Text(project.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if project.loadError != nil || !project.issues.isEmpty {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
        }
    }
}

extension ProjectSummary {
    /// "río · sitio" if set, then the folder name and stamp and the stage reached.
    var subtitle: String {
        var parts: [String] = []
        if let s = site, !s.isEmpty { parts.append(s) }
        if riverName != nil { parts.append(name) }
        parts.append(stamp)
        parts.append(stage.label)
        return parts.joined(separator: " · ")
    }
}
