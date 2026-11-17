import Foundation
import Observation
import RiverCore

/// Summary of a project folder for the list (read once, cheap to display).
struct ProjectSummary: Identifiable, Hashable {
    let folder: URL
    let name: String
    let stamp: String
    private(set) var footage: Footage?
    private(set) var stage: ProjectStage = .created
    private(set) var riverName: String?
    private(set) var sectionCount = 0
    private(set) var issues: [String] = []
    private(set) var loadError: String?

    var id: URL { folder }

    init(folder: URL) {
        self.folder = folder
        stamp = folder.lastPathComponent
        name = folder.deletingLastPathComponent().lastPathComponent
        do {
            let p = try RiverProject.open(folder)
            footage = p.settings.footage
            stage = p.settings.stage
            riverName = p.settings.riverName
            sectionCount = (try? p.readCrossSections()?.sections.count) ?? 0
            issues = p.compatibilityIssues().map(\.message)
        } catch {
            loadError = "\(error)"
        }
    }
}

/// Projects live in `Documents/RIVeR/<name>/<YYYYMMDDTHHMM>/`, the same layout as RIVeR desktop's workspace, and the
/// folder is visible in the Files app (Info.plist: UIFileSharingEnabled), so projects can be copied either way.
@Observable
final class Workspace {
    private(set) var projects: [ProjectSummary] = []
    var lastError: String?

    let root: URL

    init(root: URL? = nil) {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.root = root ?? docs.appendingPathComponent("RIVeR", isDirectory: true)
        try? FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
        reload()
    }

    func reload() {
        projects = RiverWorkspace.projectFolders(in: root).map(ProjectSummary.init(folder:))
    }

    /// Copies a project picked in the Files app into the workspace. Accepts a dated project folder (with
    /// `settings.json`) or a project-name folder that contains dated folders. Returns how many were imported.
    @discardableResult
    func importProjects(from picked: URL) throws -> Int {
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let fm = FileManager.default
        var sources: [URL] = []
        if fm.fileExists(atPath: picked.appendingPathComponent(RiverProject.settingsFile).path) {
            sources = [picked]
        } else {
            sources = RiverWorkspace.projectFolders(in: picked.deletingLastPathComponent())
                .filter { $0.deletingLastPathComponent().standardizedFileURL == picked.standardizedFileURL }
        }
        guard !sources.isEmpty else { throw ProjectError.noSettingsFile }
        for src in sources {
            let nameFolder = root.appendingPathComponent(src.deletingLastPathComponent().lastPathComponent, isDirectory: true)
            try fm.createDirectory(at: nameFolder, withIntermediateDirectories: true)
            var dest = nameFolder.appendingPathComponent(src.lastPathComponent, isDirectory: true)
            var n = 2
            while fm.fileExists(atPath: dest.path) {
                dest = nameFolder.appendingPathComponent("\(src.lastPathComponent)-\(n)", isDirectory: true)
                n += 1
            }
            try fm.copyItem(at: src, to: dest)
            // Desktop needs frames/ even when empty; some copies (e.g. through git or zip tools) drop empty folders.
            try fm.createDirectory(at: dest.appendingPathComponent(RiverProject.framesFolder), withIntermediateDirectories: true)
        }
        reload()
        return sources.count
    }

    func delete(_ project: ProjectSummary) throws {
        try FileManager.default.removeItem(at: project.folder)
        let parent = project.folder.deletingLastPathComponent()
        if (try? FileManager.default.contentsOfDirectory(atPath: parent.path))?.isEmpty == true {
            try? FileManager.default.removeItem(at: parent)
        }
        reload()
    }
}

extension Footage {
    var label: String {
        switch self {
        case .uav: return "Dron (UAV)"
        case .oblique: return "Oblicua"
        case .ipcam: return "Estación fija (3D)"
        }
    }

    var symbol: String {
        switch self {
        case .uav: return "airplane"
        case .oblique: return "camera"
        case .ipcam: return "video"
        }
    }
}

extension ProjectStage {
    var label: String {
        switch self {
        case .created: return "Creado"
        case .frames: return "Frames extraídos"
        case .geometry: return "Georreferenciado"
        case .crossSections: return "Secciones definidas"
        case .results: return "Con resultados"
        }
    }
}
