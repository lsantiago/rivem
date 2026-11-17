import Foundation

/// Cross-section bed profile: station (m along the section, increasing left → right) and bed elevation (m, same
/// datum as the water level). Desktop's Python always drops the first row as a header
/// (`compute_section.py:163-164`), so files are always written with the header `station,stage`.
public struct Bathymetry: Equatable, Sendable {
    public var stations: [Double]
    public var elevations: [Double]

    public init(stations: [Double], elevations: [Double]) {
        self.stations = stations
        self.elevations = elevations
    }

    /// Parses CSV text with `,`, `;` or tab separators and an optional header row. A comma is taken as decimal
    /// separator when the line uses `;` or tabs. Values are metres; pass `feet: true` to convert (× 0.3048),
    /// as desktop does for imperial projects. Rows with decreasing station are reversed to increasing order.
    public static func parse(csv text: String, feet: Bool = false) throws -> Bathymetry {
        var st: [Double] = [], el: [Double] = []
        let lines = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        for (n, line) in lines.enumerated() {
            let usesComma = !line.contains(";") && !line.contains("\t")
            let fields = line.split(whereSeparator: { usesComma ? $0 == "," : ($0 == ";" || $0 == "\t") })
                .map { $0.trimmingCharacters(in: .whitespaces) }
            guard fields.count >= 2 else { throw ProjectError.invalidBathymetry("línea \(n + 1): se esperan dos columnas") }
            let normalise: (String) -> String = { usesComma ? $0 : $0.replacingOccurrences(of: ",", with: ".") }
            guard let s = Double(normalise(fields[0])), let e = Double(normalise(fields[1])) else {
                if n == 0 { continue } // header
                throw ProjectError.invalidBathymetry("línea \(n + 1): valores no numéricos")
            }
            st.append(s)
            el.append(e)
        }
        guard st.count >= 2 else { throw ProjectError.invalidBathymetry("se necesitan al menos dos puntos") }
        if let first = st.first, let last = st.last, last < first {
            st.reverse()
            el.reverse()
        }
        if feet {
            st = st.map { $0 * 0.3048 }
            el = el.map { $0 * 0.3048 }
        }
        return Bathymetry(stations: st, elevations: el)
    }

    public static func read(_ url: URL, feet: Bool = false) throws -> Bathymetry {
        let data = try Data(contentsOf: url)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        return try parse(csv: text, feet: feet)
    }

    /// `station,stage` header, comma separator, dot decimals, SI.
    public var csv: String {
        var s = "station,stage\n"
        for (a, b) in zip(stations, elevations) { s += "\(a),\(b)\n" }
        return s
    }

    public func write(_ url: URL) throws {
        try Data(csv.utf8).write(to: url, options: .atomic)
    }

    public var minElevation: Double? { elevations.min() }
}
