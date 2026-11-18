import RiverCore
import SwiftUI

/// Step 6: share the project folder as a zip (it unpacks as `<name>/<YYYYMMDDTHHMM>/`, ready for desktop's RIVeR
/// workspace) and the stations CSV.
struct ExportStepView: View {
    let model: WorkflowModel

    @State private var includeVideo = false
    @State private var zipURL: URL? = nil
    @State private var csvURL: URL? = nil
    @State private var busy = false
    @State private var error: String? = nil

    var body: some View {
        Form {
            Section {
                Text("Para abrir el aforo en RIVeR escritorio \(RiverCore.desktopCompatibility), descomprime el ZIP dentro de la carpeta RIVeR del espacio de trabajo del PC (queda RIVeR/nombre/fecha) y ábrelo desde \"Load project\".")
                    .font(.callout)
                if let p = model.project {
                    let issues: [CompatibilityIssue] = p.compatibilityIssues()
                    if issues.isEmpty {
                        Label("Compatible con RIVeR escritorio", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                    } else {
                        ForEach(issues, id: \.message) { issue in
                            Label(issue.message, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                        }
                    }
                }
            }
            Section("Proyecto completo (ZIP)") {
                Toggle("Incluir el video", isOn: $includeVideo)
                    .disabled(!model.hasVideo)
                    .onChange(of: includeVideo) { _, _ in zipURL = nil }
                Button {
                    makeZip()
                } label: {
                    if busy { ProgressView() } else { Label("Preparar ZIP", systemImage: "doc.zipper") }
                }
                .disabled(busy)
                if let url = zipURL {
                    ShareLink(item: url) {
                        Label("Compartir \(url.lastPathComponent)", systemImage: "square.and.arrow.up")
                    }
                }
            }
            Section("Estaciones (CSV)") {
                Button {
                    makeCSV()
                } label: {
                    Label("Preparar CSV de estaciones", systemImage: "tablecells")
                }
                .disabled(!model.hasResults)
                if let url = csvURL {
                    ShareLink(item: url) {
                        Label("Compartir \(url.lastPathComponent)", systemImage: "square.and.arrow.up")
                    }
                }
            }
            Section {
                ErrorBanner(message: error)
            } footer: {
                Text("La carpeta del proyecto también está en la app Archivos: En mi iPhone › RIVEM › RIVeR.")
            }
        }
    }

    private func makeZip() {
        guard let project = model.project else { return }
        let video: Bool = includeVideo
        busy = true
        error = nil
        Task { @MainActor in
            do {
                let url: URL = try await Task.detached(priority: .userInitiated) { () throws -> URL in
                    try ExportPipeline.zip(project, includeVideo: video)
                }.value
                zipURL = url
            } catch {
                self.error = "No se pudo crear el ZIP: \(error.localizedDescription)"
                AppLog.error("Exportar ZIP: \(error)")
            }
            busy = false
        }
    }

    private func makeCSV() {
        guard let project = model.project else { return }
        do {
            csvURL = try ExportPipeline.stationsCSV(project)
            error = nil
        } catch {
            self.error = "\(error)"
        }
    }
}
