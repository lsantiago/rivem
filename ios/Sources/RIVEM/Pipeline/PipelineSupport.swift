import Foundation
import RiverCore

/// Error with a message for the user (Spanish), raised by the pipeline steps.
struct PipelineError: Error, CustomStringConvertible, LocalizedError {
    let description: String
    init(_ message: String) { description = message }
    var errorDescription: String? { description }
}

/// Thread-safe cancellation flag shared between the UI and a background computation.
final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func cancel() {
        lock.lock()
        value = true
        lock.unlock()
    }
}

/// Names and readers of the project files the pipeline writes besides those `RiverProject` already knows
/// (docs/desktop-format.md §1.2).
enum ProjectFiles {
    static let transformedImage = "transformed_image.png"
    static let orthoImage = "ortho_image.png"
    static let grp3D = "grp_3d.json"
    static let fullGRP3D = "full_grp_3d.json"
    static let cameraSolution = "camera_solution_3d.json"
    static let maskNPY = "mask.npy"
    static let maskPNG = "mask.png"
    static let bbox = "bbox.json"
    static let finalMaskNPY = "final_mask.npy"
    static let finalMaskPNG = "final_mask.png"

    static func url(_ project: RiverProject, _ name: String) -> URL {
        project.folder.appendingPathComponent(name)
    }

    static func exists(_ project: RiverProject, _ name: String) -> Bool {
        FileManager.default.fileExists(atPath: url(project, name).path)
    }

    static func remove(_ project: RiverProject, _ names: [String]) {
        for n in names {
            let u = url(project, n)
            if FileManager.default.fileExists(atPath: u.path) { try? FileManager.default.removeItem(at: u) }
        }
    }

    /// The video copied into the project folder (looked up by file name, like every stored path).
    static func videoURL(_ project: RiverProject) -> URL? {
        guard let stored = project.settings.video?.filepath else { return nil }
        let u = project.resolve(stored)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    /// Size of the extracted frames (first frame), the pixel space of every geometry file.
    static func frameSize(_ project: RiverProject) -> (width: Int, height: Int)? {
        guard let first = project.frameFiles.first else { return nil }
        return ImageCoding.pixelSize(of: first)
    }

    /// `bbox.json` → `[x, y, w, h]`.
    static func readBBox(_ project: RiverProject) -> [Double]? {
        let u = url(project, bbox)
        guard let value = try? JSON.read(u), let list = value.doubleArray, list.count == 4 else { return nil }
        return list
    }

    /// The mask PIV uses: `final_mask.npy` if present, else `mask.npy` (desktop's search order).
    static func readMask(_ project: RiverProject) -> ROIMask? {
        for name in [finalMaskNPY, maskNPY] {
            let u = url(project, name)
            if let data = try? Data(contentsOf: u), let mask = try? NPY.decodeMask(data) { return mask }
        }
        return nil
    }

    /// Camera of a fixed-station (ipcam) project, from `camera_solution_3d.json`.
    static func readCamera(_ project: RiverProject) -> CameraMatrix? {
        let u = url(project, cameraSolution)
        guard let obj = (try? JSON.read(u))?.objectValue,
              let rows = obj["camera_matrix"]?.arrayValue?.compactMap({ $0.doubleArray }),
              rows.count == 3, rows.allSatisfy({ $0.count == 4 }) else { return nil }
        return CameraMatrix(rows: rows)
    }
}

/// Number formatting shared by the screens and the reports.
enum Fmt {
    static func num(_ v: Double?, _ digits: Int = 2) -> String {
        guard let v = v, v.isFinite else { return "—" }
        return String(format: "%.\(digits)f", v)
    }

    static func percent(_ fraction: Double?, _ digits: Int = 0) -> String {
        guard let f = fraction, f.isFinite else { return "—" }
        return String(format: "%.\(digits)f %%", f * 100)
    }

    /// Parses a number typed with either decimal separator.
    static func parse(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return Double(t)
    }
}

/// Display units. Everything on disk is SI (desktop §2.0); `unit_system` only changes what is shown.
struct UnitDisplay {
    let system: UnitSystem

    private static let foot: Double = 0.3048

    var lengthUnit: String { system == .imperial ? "ft" : "m" }
    var velocityUnit: String { system == .imperial ? "ft/s" : "m/s" }
    var areaUnit: String { system == .imperial ? "ft²" : "m²" }
    var dischargeUnit: String { system == .imperial ? "ft³/s" : "m³/s" }

    func length(_ m: Double) -> Double { system == .imperial ? m / Self.foot : m }
    func velocity(_ ms: Double) -> Double { system == .imperial ? ms / Self.foot : ms }
    func area(_ m2: Double) -> Double { system == .imperial ? m2 / (Self.foot * Self.foot) : m2 }
    func discharge(_ m3s: Double) -> Double { system == .imperial ? m3s / (Self.foot * Self.foot * Self.foot) : m3s }

    func lengthText(_ m: Double?, _ digits: Int = 2) -> String {
        guard let m = m else { return "—" }
        return "\(Fmt.num(length(m), digits)) \(lengthUnit)"
    }

    func velocityText(_ v: Double?, _ digits: Int = 2) -> String {
        guard let v = v else { return "—" }
        return "\(Fmt.num(velocity(v), digits)) \(velocityUnit)"
    }

    func areaText(_ a: Double?, _ digits: Int = 2) -> String {
        guard let a = a else { return "—" }
        return "\(Fmt.num(area(a), digits)) \(areaUnit)"
    }

    func dischargeText(_ q: Double?, _ digits: Int = 3) -> String {
        guard let q = q else { return "—" }
        return "\(Fmt.num(discharge(q), digits)) \(dischargeUnit)"
    }
}

extension JSONValue {
    /// A number for JSON files read by desktop's Node code with `JSON.parse` (no NaN tokens): non-finite → null.
    static func finiteOrNull(_ d: Double) -> JSONValue { d.isFinite ? .double(d) : .null }
}
