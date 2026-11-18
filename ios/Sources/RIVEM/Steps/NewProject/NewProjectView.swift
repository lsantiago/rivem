import CoreTransferable
import PhotosUI
import RiverCore
import SwiftUI
import UniformTypeIdentifiers

/// A movie received from the Photos picker, copied to the temporary folder (the received file is deleted when the
/// import closure returns).
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let folder: URL = FileManager.default.temporaryDirectory.appendingPathComponent("import-" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let dest: URL = folder.appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }
}

/// Step 0, desktop "New project" + "Footage": video source (camera, Photos, Files), footage type and name. The video
/// is copied into the new project folder and `settings.video` is filled from its metadata (fps rounded like desktop).
struct NewProjectView: View {
    let workspace: Workspace
    let onCreated: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    /// The name was proposed by the app (not typed): choosing another video may replace it.
    @State private var nameIsAuto = true
    @State private var river: String = ""
    @State private var site: String = ""
    @State private var footage: Footage = .oblique
    /// Temporary copy owned by the app (Photos, camera or a copy from Files): it is moved into the project.
    @State private var videoURL: URL? = nil
    @State private var metadata: VideoMetadata? = nil
    @State private var photoItem: PhotosPickerItem? = nil
    @State private var showingFiles = false
    @State private var showingCamera = false
    @State private var busy = false
    @State private var error: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section("Video") {
                    #if os(iOS)
                    Button {
                        showingCamera = true
                    } label: {
                        Label("Grabar con la cámara", systemImage: "video.fill")
                    }
                    #endif
                    PhotosPicker(selection: $photoItem, matching: .videos) {
                        Label("Elegir de Fotos", systemImage: "photo.on.rectangle")
                    }
                    Button {
                        showingFiles = true
                    } label: {
                        Label("Elegir de Archivos", systemImage: "folder")
                    }
                    VideoSummary(url: videoURL, metadata: metadata, loading: busy && metadata == nil)
                }
                Section("Tipo de toma") {
                    Picker("Tipo de toma", selection: $footage) {
                        ForEach(Footage.allCases, id: \.self) { f in
                            Label(f.label, systemImage: f.symbol).tag(f)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    Text(footageHint).font(.caption).foregroundStyle(.secondary)
                }
                Section("Nombre") {
                    TextField("Nombre del aforo", text: Binding<String>(
                        get: { name },
                        set: { value in
                            name = value
                            nameIsAuto = false
                        }
                    ))
                    Text("Carpeta: RIVeR/\(ProjectCreation.safeName(name))/fecha").font(.caption).foregroundStyle(.secondary)
                    TextField("Río (opcional)", text: $river)
                    TextField("Sitio (opcional)", text: $site)
                }
                Section {
                    Button {
                        create()
                    } label: {
                        Label("Crear aforo", systemImage: "plus.circle.fill")
                    }
                    .disabled(busy || videoURL == nil || metadata == nil)
                    ErrorBanner(message: error)
                }
            }
            .navigationTitle("Nuevo aforo")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") {
                        discardTemporaryVideo()
                        dismiss()
                    }
                }
            }
            .fileImporter(isPresented: $showingFiles, allowedContentTypes: [.movie, .video, .quickTimeMovie, .mpeg4Movie]) { result in
                importFromFiles(result)
            }
            .onChange(of: photoItem) { _, item in
                if let item = item { importFromPhotos(item) }
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $showingCamera) {
                CameraRecorderView { url in
                    useVideo(url, suggestedName: nil)
                }
            }
            #endif
        }
    }

    private var footageHint: String {
        switch footage {
        case .uav: return "Toma cenital desde dron: 2 puntos con distancia o coordenadas conocidas."
        case .oblique: return "Cámara oblicua desde la orilla: 4 puntos sobre el agua y las 6 distancias entre ellos."
        case .ipcam: return "Estación fija: 6 o más puntos con coordenadas X, Y, Z (archivo de puntos de control)."
        }
    }

    /// `aforo_YYYYMMDD_HHMM`: Photos and the camera only give temporary (UUID) file names.
    static func defaultName(_ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmm"
        return "aforo_" + f.string(from: date)
    }

    private func useVideo(_ url: URL, suggestedName: String?) {
        discardTemporaryVideo()
        videoURL = url
        metadata = nil
        error = nil
        if nameIsAuto || name.isEmpty {
            name = suggestedName ?? Self.defaultName()
            nameIsAuto = true
        }
        busy = true
        Task { @MainActor in
            do {
                metadata = try await VideoMetadata.load(url)
            } catch {
                self.error = "No se pudo leer el video: \(error)"
                videoURL = nil
            }
            busy = false
        }
    }

    private func importFromPhotos(_ item: PhotosPickerItem) {
        busy = true
        error = nil
        Task { @MainActor in
            do {
                guard let movie = try await item.loadTransferable(type: PickedMovie.self) else {
                    throw PipelineError("Fotos no entregó el video.")
                }
                busy = false
                useVideo(movie.url, suggestedName: nil)
            } catch {
                self.error = "No se pudo importar desde Fotos: \(error.localizedDescription)"
                AppLog.error("Importar de Fotos: \(error)")
                busy = false
            }
        }
    }

    private func importFromFiles(_ result: Result<URL, Error>) {
        do {
            let picked: URL = try result.get()
            let scoped: Bool = picked.startAccessingSecurityScopedResource()
            defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
            let folder: URL = FileManager.default.temporaryDirectory.appendingPathComponent("import-" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let dest: URL = folder.appendingPathComponent(picked.lastPathComponent)
            try FileManager.default.copyItem(at: picked, to: dest)
            useVideo(dest, suggestedName: picked.deletingPathExtension().lastPathComponent)
        } catch {
            self.error = "No se pudo copiar el video: \(error.localizedDescription)"
        }
    }

    private func discardTemporaryVideo() {
        if let url = videoURL, url.path.hasPrefix(FileManager.default.temporaryDirectory.path) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func create() {
        guard let url = videoURL, let meta = metadata else { return }
        let projectName: String = name.isEmpty ? Self.defaultName() : name
        let riverName: String = river.trimmingCharacters(in: .whitespacesAndNewlines)
        let siteName: String = site.trimmingCharacters(in: .whitespacesAndNewlines)
        let kind: Footage = footage
        let root: URL = workspace.root
        busy = true
        error = nil
        Task { @MainActor in
            do {
                let project: RiverProject = try await Task.detached(priority: .userInitiated) { () throws -> RiverProject in
                    try ProjectCreation.create(root: root, name: projectName, footage: kind, source: url, metadata: meta, moveSource: true,
                                                 riverName: riverName, site: siteName)
                }.value
                videoURL = nil
                workspace.reload()
                busy = false
                onCreated(project.folder)
                dismiss()
            } catch {
                self.error = "\(error)"
                AppLog.error("Crear proyecto: \(error)")
                busy = false
            }
        }
    }
}

private struct VideoSummary: View {
    let url: URL?
    let metadata: VideoMetadata?
    let loading: Bool

    var body: some View {
        if loading {
            ProgressView("Leyendo el video…")
        } else if let m = metadata, let u = url {
            VStack(alignment: .leading, spacing: 2) {
                Text(u.lastPathComponent).font(.headline)
                Text("\(m.width) × \(m.height) · \(Fmt.num(m.fps, 2)) fps · \(Fmt.num(m.duration, 1)) s")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if abs(m.fps - m.fps.rounded()) > 0.01 {
                    Text("RIVeR guarda los fps redondeados (\(Int(m.fps.rounded()))): el Δt se calcula con ese valor, como en escritorio.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
        } else {
            Text("Ningún video elegido").foregroundStyle(.secondary)
        }
    }
}
