import RiverCore
import SwiftUI

/// Step 2: desktop "Pixel Size" (UAV), "Control Points" (oblique) or the 3-D fixed station (ipcam).
struct GeometryStepView: View {
    let model: WorkflowModel

    var body: some View {
        switch model.footage {
        case .some(.uav):
            UAVGeometryView(model: model)
        case .some(.oblique):
            ObliqueGeometryView(model: model)
        case .some(.ipcam):
            Camera3DGeometryView(model: model)
        case .none:
            ContentUnavailableView("Tipo de toma desconocido", systemImage: "questionmark.folder",
                                   description: Text("settings.json no indica uav, oblique ni ipcam."))
        }
    }
}

/// The rectified image written with the geometry, shown on demand.
struct RectifiedPreviewSection: View {
    let model: WorkflowModel
    let fileName: String
    @State private var showing = false

    var body: some View {
        if let project = model.project, ProjectFiles.exists(project, fileName) {
            Section("Imagen rectificada") {
                Button {
                    showing = true
                } label: {
                    Label("Ver \(fileName)", systemImage: "photo")
                }
                if let t = project.settings.json["transformation"]?.objectValue, let r = t["resolution"]?.doubleValue {
                    LabeledContent("Resolución", value: "\(Fmt.num(r * 100, 2)) cm/px")
                }
            }
            .sheet(isPresented: $showing) {
                RectifiedImageSheet(url: ProjectFiles.url(project, fileName))
            }
        }
    }
}

private struct RectifiedImageSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var image: CGImage?

    var body: some View {
        NavigationStack {
            Group {
                if let img = image {
                    ImageCanvas(image: img, imageSize: CGSize(width: img.width, height: img.height))
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Vista rectificada (norte arriba)")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { dismiss() } }
            }
        }
        .task { image = ImageCoding.thumbnail(contentsOf: url, maxPixel: 2048) }
    }
}

/// Placeholder when the first frame cannot be shown.
struct NoFrameView: View {
    var body: some View {
        ContentUnavailableView("Sin frames", systemImage: "photo",
                               description: Text("Extrae los frames del video en el paso 1."))
    }
}

/// A labelled numeric text field bound to a String (accepts comma or dot).
struct NumberField: View {
    let title: String
    @Binding var text: String
    var unit: String = "m"

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: 4) {
                TextField("0", text: $text)
                    .multilineTextAlignment(.trailing)
                    .decimalKeyboard()
                Text(unit).foregroundStyle(.secondary)
            }
        }
    }
}
