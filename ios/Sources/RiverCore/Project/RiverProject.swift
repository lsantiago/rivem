import Foundation

public enum ProjectError: Error, Equatable, CustomStringConvertible {
    case noSettingsFile
    case invalidFile(String, String)
    case invalidBathymetry(String)

    public var description: String {
        switch self {
        case .noSettingsFile: return "La carpeta no contiene settings.json: no es un proyecto de RIVeR."
        case let .invalidFile(file, why): return "\(file) no es válido: \(why)."
        case let .invalidBathymetry(why): return "Batimetría no válida: \(why)."
        }
    }
}

/// Something that would stop RIVeR desktop from opening the project (or that the app could not find).
public struct CompatibilityIssue: Equatable, Sendable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// A RIVeR project folder (the dated folder that holds `settings.json`), read and written in the RIVeR desktop
/// 3.8.3 format (docs/desktop-format.md).
///
/// Paths stored in the files are never trusted: like desktop's `relocateProject`, every project-owned file is looked
/// up by its file name inside the project folder, so projects can move between the phone and a PC.
public struct RiverProject: Sendable {
    public static let settingsFile = "settings.json"
    public static let framesFolder = "frames"
    public static let matrixFile = "transformation_matrix.json"
    public static let xsectionsFile = "xsections.json"
    public static let pivResultsFile = "piv_results.json"

    public let folder: URL
    public var settings: ProjectSettings

    public init(folder: URL, settings: ProjectSettings) {
        self.folder = folder
        self.settings = settings
    }

    // MARK: Opening and creating

    /// Opens a project folder. Throws only when `settings.json` is missing or unreadable; desktop-compatibility
    /// problems are reported by `compatibilityIssues()`.
    public static func open(_ folder: URL) throws -> RiverProject {
        let url = folder.appendingPathComponent(settingsFile)
        guard FileManager.default.fileExists(atPath: url.path) else { throw ProjectError.noSettingsFile }
        return RiverProject(folder: folder, settings: try ProjectSettings.read(url))
    }

    /// Creates `<parent>/<name>/<YYYYMMDDTHHMM>/` with `settings.json` and an empty `frames/`, like desktop's
    /// `createFolderStructure`. If that folder exists, a `-2`, `-3`… suffix is added instead of overwriting it.
    public static func create(in parent: URL, name: String, footage: Footage, video: VideoInfo,
                              date: Date = Date(), timeZone: TimeZone = .current) throws -> RiverProject {
        let stamp = folderName(for: date, timeZone: timeZone)
        let base = parent.appendingPathComponent(name, isDirectory: true)
        var folder = base.appendingPathComponent(stamp, isDirectory: true)
        var n = 2
        while FileManager.default.fileExists(atPath: folder.path) {
            folder = base.appendingPathComponent("\(stamp)-\(n)", isDirectory: true)
            n += 1
        }
        try FileManager.default.createDirectory(at: folder.appendingPathComponent(framesFolder), withIntermediateDirectories: true)
        let project = RiverProject(folder: folder, settings: ProjectSettings(creationDate: stamp, footage: footage, video: video))
        try project.save()
        return project
    }

    /// `YYYYMMDDTHHMM` in local time (desktop's project folder name and `creation_date`).
    public static func folderName(for date: Date, timeZone: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return String(format: "%04ld%02ld%02ldT%02ld%02ld", c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0)
    }

    public func save() throws {
        try settings.write(folder.appendingPathComponent(Self.settingsFile))
    }

    // MARK: Paths

    /// File name of a stored path, accepting both `/` and Windows `\` separators.
    public static func basename(_ storedPath: String) -> String {
        storedPath.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? storedPath
    }

    /// Where a stored path lives inside this project folder.
    public func resolve(_ storedPath: String) -> URL {
        folder.appendingPathComponent(Self.basename(storedPath))
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    // MARK: Frames

    /// Desktop frame file name: source frame index, zero-padded to 10 digits (`video_to_frames.py:157`).
    public static func frameFileName(_ index: Int) -> String { String(format: "%010ld.jpg", index) }

    /// `frames/*.jpg` in name order (= time order thanks to the zero padding), without `background.jpg`.
    public var frameFiles: [URL] {
        let dir = folder.appendingPathComponent(Self.framesFolder)
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.lowercased().hasSuffix(".jpg") && $0 != "background.jpg" }
            .sorted()
            .map { dir.appendingPathComponent($0) }
    }

    // MARK: Geometry

    public func readMatrix() throws -> Matrix3? {
        let url = folder.appendingPathComponent(Self.matrixFile)
        guard exists(url) else { return nil }
        guard let rows = try JSON.read(url).arrayValue?.compactMap(\.doubleArray),
              rows.count == 3, rows.allSatisfy({ $0.count == 3 }) else {
            throw ProjectError.invalidFile(Self.matrixFile, "se esperaba una matriz 3×3")
        }
        return Matrix3(rows: rows)
    }

    /// Writes the pixel → real-world matrix and, like desktop's `createMatrix`, discards everything computed with
    /// the previous geometry (sections, masks, PIV results, statistics cache). The settings are saved.
    public mutating func setMatrix(_ matrix: Matrix3) throws {
        let fm = FileManager.default
        for name in [Self.xsectionsFile, "bbox.json", "mask.npy", "mask.png", "final_mask.npy", "final_mask.png",
                     Self.pivResultsFile, "_stats_cache.json"] {
            let url = folder.appendingPathComponent(name)
            if exists(url) { try fm.removeItem(at: url) }
        }
        settings.xsectionsPath = nil
        settings.pivResultsPath = nil
        let rows = JSONValue.array(matrix.rows.map(JSONValue.doubles))
        try JSONWriter(indent: nil, nonFinite: .fail).write(rows, to: folder.appendingPathComponent(Self.matrixFile))
        var transformation = settings.json["transformation"]?.objectValue ?? JSONObject()
        transformation["matrix"] = .string(Self.matrixFile)
        settings.json["transformation"] = .object(transformation)
        try save()
    }

    // MARK: Sections and results

    public func readCrossSections() throws -> CrossSections? {
        guard let stored = settings.xsectionsPath else { return nil }
        let url = resolve(stored)
        return exists(url) ? try CrossSections.read(url) : nil
    }

    public mutating func writeCrossSections(_ sections: CrossSections) throws {
        try sections.write(folder.appendingPathComponent(Self.xsectionsFile))
        settings.xsectionsPath = Self.xsectionsFile
        try save()
    }

    public func readPIVResults() throws -> PIVResults? {
        guard settings.pivResultsPath != nil else { return nil }
        // Desktop always reads <project>/piv_results.json; the stored path is only a "results exist" flag.
        let url = folder.appendingPathComponent(Self.pivResultsFile)
        return exists(url) ? try PIVResults.read(url) : nil
    }

    /// Bathymetry CSV of a section: looked up by file name inside the project (desktop copies it there).
    public func bathymetryURL(for section: CrossSection) -> URL? {
        guard let stored = section.bathymetryPath else { return nil }
        let url = resolve(stored)
        return exists(url) ? url : nil
    }

    // MARK: Desktop compatibility

    /// Problems that would make RIVeR desktop 3.8.3 refuse the project (docs/desktop-format.md §2.1 and §7).
    public func compatibilityIssues() -> [CompatibilityIssue] {
        var issues: [CompatibilityIssue] = []
        let s = settings
        if s.footage == nil { issues.append(.init("settings.json: 'footage' falta o no es uav/oblique/ipcam")) }
        if s.video?.filepath == nil { issues.append(.init("settings.json: falta 'video.filepath'")) }
        var isDir: ObjCBool = false
        if !FileManager.default.fileExists(atPath: folder.appendingPathComponent(Self.framesFolder).path, isDirectory: &isDir) || !isDir.boolValue {
            issues.append(.init("falta la carpeta frames/"))
        }
        let needsRange = s.pixelSize != nil || s.controlPoints != nil || s.xsectionsPath != nil || s.pivResultsPath != nil
        if needsRange && s.videoRange == nil { issues.append(.init("settings.json: falta 'video_range'")) }
        if (s.pixelSize != nil || s.controlPoints != nil) && !exists(folder.appendingPathComponent(Self.matrixFile)) {
            issues.append(.init("falta \(Self.matrixFile)"))
        }
        for key in ["xsections", "grp_3d", "camera_solution_3d"] {
            if let p = s.json[key]?.stringValue, !exists(resolve(p)) {
                issues.append(.init("settings.json apunta a \(Self.basename(p)), que no está en la carpeta"))
            }
        }
        if let sections = try? readCrossSections() {
            for sec in sections.sections {
                let missing = sec.missingKeys
                if !missing.isEmpty { issues.append(.init("sección \(sec.name): faltan \(missing.joined(separator: ", "))")) }
                if sec.bathymetryPath != nil && bathymetryURL(for: sec) == nil {
                    issues.append(.init("sección \(sec.name): la batimetría no está en la carpeta"))
                }
            }
        }
        return issues
    }
}

/// Finds projects under a workspace folder: `<root>/<name>/<date>/settings.json` (desktop layout) or
/// `<root>/<name>/settings.json`.
public enum RiverWorkspace {
    public static func projectFolders(in root: URL) -> [URL] {
        let fm = FileManager.default
        func dirs(_ url: URL) -> [URL] {
            let items = (try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
            return items.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
        }
        func isProject(_ url: URL) -> Bool { fm.fileExists(atPath: url.appendingPathComponent(RiverProject.settingsFile).path) }
        var found: [URL] = []
        for named in dirs(root) {
            if isProject(named) { found.append(named); continue }
            found.append(contentsOf: dirs(named).filter(isProject))
        }
        // Newest first: desktop folder names are timestamps.
        return found.sorted { $0.lastPathComponent > $1.lastPathComponent }
    }
}
