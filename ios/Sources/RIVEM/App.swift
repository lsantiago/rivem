import SwiftUI
import RiverCore

@main
struct RIVEMApp: App {
    var body: some Scene {
        WindowGroup {
            HomeView()
                .task { await Autotest.runIfRequested() }
        }
    }
}

struct HomeView: View {
    @State private var checks: [SelfCheck.Result] = []

    var body: some View {
        NavigationStack {
            List {
                Section("Aforos") {
                    ContentUnavailableView(
                        "Sin aforos todavía",
                        systemImage: "water.waves",
                        description: Text("Crear un aforo desde un video llega en la siguiente fase.")
                    )
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
        }
        .onAppear { checks = SelfCheck.run() }
    }
}
