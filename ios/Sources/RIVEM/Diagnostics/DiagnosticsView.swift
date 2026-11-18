import RiverCore
import SwiftUI

/// In-app log viewer with export (to debug on the phone without a Mac).
struct DiagnosticsView: View {
    @State private var text: String = ""
    @State private var confirmClear = false

    var body: some View {
        List {
            Section("Aplicación") {
                LabeledContent("Versión", value: Self.appVersion)
                LabeledContent("RiverCore", value: RiverCore.version)
                LabeledContent("Compatible con RIVeR", value: RiverCore.desktopCompatibility)
                LabeledContent("Núcleos", value: "\(ProcessInfo.processInfo.activeProcessorCount)")
            }
            Section {
                ShareLink(item: AppLog.fileURL) {
                    Label("Exportar registro", systemImage: "square.and.arrow.up")
                }
                .disabled(text.isEmpty)
                Button("Actualizar") { refresh() }
                Button("Borrar registro", role: .destructive) { confirmClear = true }
            }
            Section("Registro (últimas líneas)") {
                if text.isEmpty {
                    Text("Vacío").foregroundStyle(.secondary)
                } else {
                    Text(text)
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Diagnóstico")
        .onAppear(perform: refresh)
        .confirmationDialog("¿Borrar el registro?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) {
                AppLog.clear()
                refresh()
            }
        }
    }

    private func refresh() {
        text = AppLog.tail(maxCharacters: 20_000)
    }

    static var appVersion: String {
        let info: [String: Any] = Bundle.main.infoDictionary ?? [:]
        let v: String = info["CFBundleShortVersionString"] as? String ?? "?"
        let b: String = info["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}
