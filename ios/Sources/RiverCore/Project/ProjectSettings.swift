import Foundation

public enum Footage: String, Sendable, CaseIterable {
    case uav, oblique, ipcam
}

public enum UnitSystem: String, Sendable {
    case si, imperial
}

/// Typed view over `settings.json` (RIVeR desktop 3.8.3, docs/desktop-format.md §2.1).
///
/// The underlying `JSONObject` is kept as is: keys this app does not know (processing options, metadata, keys added
/// by newer desktop versions) survive a read/modify/write cycle. Everything on disk is SI; `unit_system` only
/// changes how values are displayed.
public struct ProjectSettings: Equatable, Sendable {
    public var json: JSONObject

    public init(json: JSONObject) { self.json = json }

    /// Settings of a new project (what desktop's `createFolderStructure` writes).
    public init(creationDate: String, footage: Footage, video: VideoInfo, language: String = "es", unitSystem: UnitSystem = .si) {
        json = JSONObject()
        json["creation_date"] = .string(creationDate)
        json["footage"] = .string(footage.rawValue)
        json["language"] = .string(language)
        json["unit_system"] = .string(unitSystem.rawValue)
        json["video"] = .object(video.json)
    }

    public static func read(_ url: URL) throws -> ProjectSettings {
        guard let obj = try JSON.read(url).objectValue else { throw ProjectError.invalidFile(url.lastPathComponent, "no es un objeto JSON") }
        return ProjectSettings(json: obj)
    }

    /// Desktop handlers write settings with 4-space indentation; strict JSON (no NaN).
    public func write(_ url: URL) throws {
        try JSONWriter(indent: 4, nonFinite: .fail).write(.object(json), to: url)
    }

    // MARK: Fields

    public var creationDate: String? { json["creation_date"]?.stringValue }

    public var footage: Footage? {
        get { json["footage"]?.stringValue.flatMap(Footage.init(rawValue:)) }
        set { json["footage"] = newValue.map { .string($0.rawValue) } }
    }

    /// Any value other than "imperial" is treated as SI, like desktop.
    public var unitSystem: UnitSystem {
        get { json["unit_system"]?.stringValue == "imperial" ? .imperial : .si }
        set { json["unit_system"] = .string(newValue.rawValue) }
    }

    public var video: VideoInfo? {
        get { json["video"]?.objectValue.map(VideoInfo.init(json:)) }
        set { json["video"] = newValue.map { .object($0.json) } }
    }

    public var videoRange: VideoRange? {
        get { json["video_range"]?.objectValue.flatMap(VideoRange.init(json:)) }
        set { json["video_range"] = newValue.map { .object($0.json) } }
    }

    public var pixelSize: PixelSizeSetup? {
        get { json["pixel_size"]?.objectValue.flatMap(PixelSizeSetup.init(json:)) }
        set { json["pixel_size"] = newValue.map { .object($0.json) } }
    }

    public var controlPoints: ControlPointsSetup? {
        get { json["control_points"]?.objectValue.flatMap(ControlPointsSetup.init(json:)) }
        set { json["control_points"] = newValue.map { .object($0.json) } }
    }

    /// `transformation.matrix` (path to `transformation_matrix.json`).
    public var transformationMatrixPath: String? {
        json["transformation"]?.objectValue?["matrix"]?.stringValue
    }

    public var xsectionsPath: String? {
        get { json["xsections"]?.stringValue }
        set { json["xsections"] = newValue.map { .string($0) } }
    }

    public var pivResultsPath: String? {
        get { json["piv_results"]?.stringValue }
        set { json["piv_results"] = newValue.map { .string($0) } }
    }

    public var riverName: String? { json["river_name"]?.stringValue }
    public var site: String? { json["site"]?.stringValue }

    /// Time between two consecutive files of `frames/`: `video_range.step / video.fps` (desktop §5.3).
    public var frameInterval: Double? {
        guard let step = videoRange?.step, let fps = video?.fps, fps > 0 else { return nil }
        return Double(step) / fps
    }

    /// Which step of the desktop workflow the project has reached.
    public var stage: ProjectStage {
        if pivResultsPath != nil { return .results }
        if xsectionsPath != nil { return .crossSections }
        if transformationMatrixPath != nil { return .geometry }
        if videoRange != nil { return .frames }
        return .created
    }
}

public enum ProjectStage: Int, Comparable, Sendable {
    case created, frames, geometry, crossSections, results
    public static func < (a: ProjectStage, b: ProjectStage) -> Bool { a.rawValue < b.rawValue }
}

/// `settings.video`.
public struct VideoInfo: Equatable, Sendable {
    public var json: JSONObject

    public init(json: JSONObject) { self.json = json }

    /// Block for a video file. `fps` is rounded to an integer like desktop does (29.97 → 30); `dt` derives from it.
    public init(filepath: String, name: String, duration: Double, fps: Double, width: Int, height: Int) {
        let fpsInt = Int(fps.rounded())
        json = JSONObject()
        json["filepath"] = .string(filepath)
        json["name"] = .string(name)
        json["total_length"] = .double(duration)
        json["time_between_frames"] = .double((1.0 / Double(max(fpsInt, 1)) * 100).rounded() / 100)
        json["fps"] = .int(fpsInt)
        json["resolution"] = .object(JSONObject([("width", .int(width)), ("height", .int(height))]))
    }

    public var filepath: String? { json["filepath"]?.stringValue }
    public var name: String? { json["name"]?.stringValue }
    public var fps: Double? { json["fps"]?.doubleValue }
    public var duration: Double? { json["total_length"]?.doubleValue }
    public var width: Int? { json["resolution"]?.objectValue?["width"]?.intValue }
    public var height: Int? { json["resolution"]?.objectValue?["height"]?.intValue }
    /// `"frames"` for projects made from a folder of images; absent (= video) otherwise.
    public var isFramesFolder: Bool { json["source"]?.stringValue == "frames" }
}

/// `settings.video_range`: source frame indices (`end` exclusive), extraction step and resize factor.
public struct VideoRange: Equatable, Sendable {
    public var start: Int
    public var end: Int
    public var step: Int
    public var factor: Double

    public init(start: Int, end: Int, step: Int, factor: Double = 1) {
        self.start = start
        self.end = end
        self.step = step
        self.factor = factor
    }

    init?(json: JSONObject) {
        guard let start = json["start"]?.intValue, let end = json["end"]?.intValue,
              let step = json["step"]?.intValue, step > 0 else { return nil }
        self.init(start: start, end: end, step: step, factor: json["factor"]?.doubleValue ?? 1)
    }

    var json: JSONObject {
        let factorValue: JSONValue = factor == factor.rounded() ? .int(Int(factor)) : .double(factor)
        return JSONObject([("start", .int(start)), ("end", .int(end)), ("step", .int(step)), ("factor", factorValue)])
    }

    /// Source indices that are extracted: multiples of `step` in [start, end) (desktop uses `frame % step`).
    public var frameIndices: [Int] {
        guard end > start else { return [] }
        let first = start % step == 0 ? start : start + (step - start % step)
        return Array(stride(from: first, to: end, by: step))
    }
}

/// `settings.pixel_size` (UAV): two pixel points, their real-world coordinates and the resulting size (m/px).
public struct PixelSizeSetup: Equatable, Sendable {
    public var size: Double
    public var realWorldLength: Double
    public var pixel1: Point2, pixel2: Point2
    public var world1: Point2, world2: Point2

    public init(size: Double, realWorldLength: Double, pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2) {
        self.size = size
        self.realWorldLength = realWorldLength
        self.pixel1 = pixel1
        self.pixel2 = pixel2
        self.world1 = world1
        self.world2 = world2
    }

    init?(json o: JSONObject) {
        func d(_ k: String) -> Double? { o[k]?.doubleValue }
        guard let size = d("size"), let x1 = d("x1"), let y1 = d("y1"), let x2 = d("x2"), let y2 = d("y2") else { return nil }
        let length = d("rw_length") ?? 0
        self.init(size: size, realWorldLength: length,
                  pixel1: Point2(x1, y1), pixel2: Point2(x2, y2),
                  world1: Point2(d("east1") ?? 0, d("north1") ?? 0),
                  world2: Point2(d("east2") ?? length, d("north2") ?? 0))
    }

    var json: JSONObject {
        JSONObject([
            ("size", .double(size)), ("rw_length", .double(realWorldLength)),
            ("x1", .double(pixel1.x)), ("y1", .double(pixel1.y)), ("x2", .double(pixel2.x)), ("y2", .double(pixel2.y)),
            ("east1", .double(world1.x)), ("north1", .double(world1.y)),
            ("east2", .double(world2.x)), ("north2", .double(world2.y)),
        ])
    }
}

/// `settings.control_points` (oblique): four pixel points and the six distances between them (m).
public struct ControlPointsSetup: Equatable, Sendable {
    public var pixels: [Point2]
    /// d12, d23, d34, d41, d13, d24
    public var distances: [Double]

    public static let distanceKeys = ["d12", "d23", "d34", "d41", "d13", "d24"]

    public init(pixels: [Point2], distances: [Double]) {
        self.pixels = pixels
        self.distances = distances
    }

    init?(json o: JSONObject) {
        guard let c = o["coordinates"]?.objectValue, let d = o["distances"]?.objectValue else { return nil }
        var px: [Point2] = []
        for i in 1...4 {
            guard let x = c["x\(i)"]?.doubleValue, let y = c["y\(i)"]?.doubleValue else { return nil }
            px.append(Point2(x, y))
        }
        let ds = Self.distanceKeys.compactMap { d[$0]?.doubleValue }
        guard ds.count == 6 else { return nil }
        self.init(pixels: px, distances: ds)
    }

    var json: JSONObject {
        var c = JSONObject()
        for (i, p) in pixels.enumerated() {
            c["x\(i + 1)"] = .double(p.x)
            c["y\(i + 1)"] = .double(p.y)
        }
        var d = JSONObject()
        for (k, v) in zip(Self.distanceKeys, distances) { d[k] = .double(v) }
        return JSONObject([("coordinates", .object(c)), ("distances", .object(d))])
    }
}
