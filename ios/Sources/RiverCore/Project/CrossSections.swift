import Foundation

/// One cross-section of `xsections.json` (docs/desktop-format.md §2.4). Typed access to the 22 "basic" keys the
/// desktop GUI writes in the Cross Sections step; the rest (per-station results added by Python, STIV/iWave
/// profiles) stays in `json` untouched.
public struct CrossSection: Equatable, Sendable {
    public var name: String
    public var json: JSONObject

    public init(name: String, json: JSONObject) {
        self.name = name
        self.json = json
    }

    private func d(_ k: String) -> Double? { json[k]?.doubleValue }

    /// Path of the bathymetry CSV as stored (desktop relocates it by file name into the project).
    public var bathymetryPath: String? { json["bath"]?.stringValue }
    /// Water level (m, same datum as the bathymetry).
    public var level: Double? { d("level") }
    public var leftStation: Double? { d("left_station") }
    public var numStations: Int? { json["num_stations"]?.intValue }
    /// Surface-to-depth-averaged velocity coefficient.
    public var alpha: Double? { d("alpha") }
    public var realWorldLength: Double? { d("rw_length") }

    /// Bathymetry end points on the drawn line, pixels.
    public var pixelLeft: Point2? { point("xl", "yl") }
    public var pixelRight: Point2? { point("xr", "yr") }
    /// Same points in real-world metres.
    public var worldLeft: Point2? { point("east_l", "north_l") }
    public var worldRight: Point2? { point("east_r", "north_r") }

    /// Results written by desktop's Python step, if present.
    public var totalDischarge: Double? { d("total_Q") }
    public var hasResults: Bool { json["distance"] != nil }

    private func point(_ kx: String, _ ky: String) -> Point2? {
        guard let x = d(kx), let y = d(ky) else { return nil }
        return Point2(x, y)
    }

    /// Keys every section must have for desktop's Python (`compute_section.py:1399-1458`).
    public static let requiredKeys = ["bath", "left_station", "east_l", "north_l", "east_r", "north_r", "level", "alpha", "num_stations"]

    public var missingKeys: [String] { Self.requiredKeys.filter { json[$0] == nil } }
}

/// `xsections.json`: ordered sections (Python uses the key position as section index) plus an optional `summary`.
public struct CrossSections: Equatable, Sendable {
    public var sections: [CrossSection]
    /// Desktop's statistics over all sections (`summary` key), kept verbatim.
    public var summary: JSONValue?

    public init(sections: [CrossSection] = [], summary: JSONValue? = nil) {
        self.sections = sections
        self.summary = summary
    }

    public init(json: JSONObject) throws {
        var list: [CrossSection] = []
        var summary: JSONValue?
        for entry in json {
            if entry.key == "summary" { summary = entry.value; continue }
            guard let obj = entry.value.objectValue else {
                throw ProjectError.invalidFile("xsections.json", "la sección \(entry.key) no es un objeto")
            }
            list.append(CrossSection(name: entry.key, json: obj))
        }
        self.init(sections: list, summary: summary)
    }

    public static func read(_ url: URL) throws -> CrossSections {
        guard let obj = try JSON.read(url).objectValue else { throw ProjectError.invalidFile("xsections.json", "no es un objeto JSON") }
        return try CrossSections(json: obj)
    }

    public var json: JSONObject {
        var o = JSONObject()
        for s in sections { o[s.name] = .object(s.json) }
        if let summary { o["summary"] = summary }
        return o
    }

    /// Python writes NaN tokens in results; keep that convention so desktop's Python re-reads them as floats.
    public func write(_ url: URL) throws {
        try JSONWriter(indent: 4, nonFinite: .tokens).write(.object(json), to: url)
    }

    public subscript(name: String) -> CrossSection? { sections.first { $0.name == name } }

    /// Section names become folder names on desktop (`stis/<name>/`) and pass through Latin-1 on Windows: keep them
    /// to ASCII letters, digits, `-` and `_`, and never `summary`.
    public static func safeName(_ proposed: String) -> String {
        let folded = proposed.folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
        var out = ""
        for ch in folded.unicodeScalars {
            if ch.isASCII, CharacterSet.alphanumerics.contains(ch) || ch == "-" || ch == "_" {
                out.unicodeScalars.append(ch)
            } else if ch == " " {
                out.append("_")
            }
        }
        if out.isEmpty || out.lowercased() == "summary" { out = "CS_" + out }
        return out
    }
}
