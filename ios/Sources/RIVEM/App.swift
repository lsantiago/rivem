import SwiftUI
import UniformTypeIdentifiers
import RiverCore

@main
struct RIVEMApp: App {
    @State private var workspace = Workspace()

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
    @State private var pendingDelete: ProjectSummary?
    @State private var checks: [SelfCheck.Result] = []
    @State private var path: [ProjectSummary] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section("Aforos") {
                    if workspace.projects.isEmpty {
                        VStack(spacing: 8) {
                            Image(systemName: "water.waves")
                                .font(.system(size: 40))
                                .foregroundStyle(.secondary)
                            Text("Sin aforos todavía").font(.headline)
                            Text("Importa un proyecto de RIVeR escritorio con el botón +, o cópialo a la carpeta RIVEM › RIVeR en la app Archivos.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                    ForEach(workspace.projects) { project in
                        NavigationLink(value: project) { ProjectRow(project: project) }
                            .swipeActions {
                                Button("Eliminar", role: .destructive) { pendingDelete = project }
                            }
                    }
                }
                Section("Núcleo") {
                    LabeledContent("RiverCore", value: RiverCore.version)
                    LabeledContent("Compatible con RIVeR", value: RiverCore.desktopCompatibility)
                }
                Section("Autocomprobación") {
                    ForEach(checks) { check in
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
            }
            .navigationTitle("RIVEM")
            .navigationDestination(for: ProjectSummary.self) { ProjectDetailView(summary: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { importing = true } label: { Label("Importar proyecto", systemImage: "plus") }
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
            // CI screenshot of the detail screen: `simctl launch … -autotest_open_first 1`
            if UserDefaults.standard.bool(forKey: "autotest_open_first"), let first = workspace.projects.first {
                path = [first]
            }
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
                Text("\(project.stamp) · \(project.stage.label)")
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
