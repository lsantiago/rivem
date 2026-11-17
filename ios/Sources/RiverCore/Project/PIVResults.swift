import Foundation

/// `piv_results.json` (docs/desktop-format.md §2.5): displacement field in pixels per pair of consecutive frames.
/// Grid arrays are the `shape` grid flattened row-major (x varies fastest). Missing vectors are NaN.
public struct PIVResults: Equatable, Sendable {
    /// [rows, columns]
    public var rows: Int
    public var columns: Int
    /// Window centres (pixels of the extracted frames, y down).
    public var x: [Double]
    public var y: [Double]
    /// One array per frame pair.
    public var u: [[Double]]
    public var v: [[Double]]
    public var uMedian: [Double]
    public var vMedian: [Double]
    public var gradient: [[Double]]
    /// Other keys (e.g. `_piv_params_hash`), kept verbatim.
    public var extra: JSONObject

    public init(rows: Int, columns: Int, x: [Double], y: [Double], u: [[Double]], v: [[Double]],
                uMedian: [Double], vMedian: [Double], gradient: [[Double]], extra: JSONObject = JSONObject()) {
        self.rows = rows
        self.columns = columns
        self.x = x
        self.y = y
        self.u = u
        self.v = v
        self.uMedian = uMedian
        self.vMedian = vMedian
        self.gradient = gradient
        self.extra = extra
    }

    public var pairCount: Int { u.count }

    private static let known: Set<String> = ["shape", "x", "y", "u", "v", "u_median", "v_median", "gradient"]

    public init(json o: JSONObject) throws {
        func fail(_ why: String) -> ProjectError { .invalidFile("piv_results.json", why) }
        guard let shape = o["shape"]?.arrayValue?.compactMap(\.intValue), shape.count == 2 else { throw fail("falta 'shape'") }
        guard let x = o["x"]?.doubleArray, let y = o["y"]?.doubleArray else { throw fail("faltan 'x'/'y'") }
        guard let um = o["u_median"]?.doubleArray, let vm = o["v_median"]?.doubleArray else { throw fail("faltan las medianas") }
        func matrix(_ k: String) throws -> [[Double]] {
            guard let rows = o[k]?.arrayValue else { throw fail("falta '\(k)'") }
            return try rows.map { row in
                guard let r = row.doubleArray else { throw fail("'\(k)' contiene valores no numéricos") }
                return r
            }
        }
        let u = try matrix("u"), v = try matrix("v")
        // `gradient` is required by desktop's Python but not by its GUI; tolerate its absence.
        let gradient: [[Double]] = try (o["gradient"] == nil ? [] : matrix("gradient"))
        let n = shape[0] * shape[1]
        guard x.count == n, y.count == n, um.count == n, vm.count == n,
              u.allSatisfy({ $0.count == n }), v.allSatisfy({ $0.count == n }), u.count == v.count else {
            throw fail("las dimensiones no coinciden con 'shape' \(shape)")
        }
        var extra = JSONObject()
        for e in o where !Self.known.contains(e.key) { extra[e.key] = e.value }
        self.init(rows: shape[0], columns: shape[1], x: x, y: y, u: u, v: v,
                  uMedian: um, vMedian: vm, gradient: gradient, extra: extra)
    }

    public static func read(_ url: URL) throws -> PIVResults {
        guard let obj = try JSON.read(url).objectValue else { throw ProjectError.invalidFile("piv_results.json", "no es un objeto JSON") }
        return try PIVResults(json: obj)
    }

    public var json: JSONObject {
        var o = JSONObject([
            ("shape", .array([.int(rows), .int(columns)])),
            ("x", .doubles(x)), ("y", .doubles(y)),
            ("u_median", .doubles(uMedian)), ("v_median", .doubles(vMedian)),
            ("u", .array(u.map(JSONValue.doubles))), ("v", .array(v.map(JSONValue.doubles))),
            ("gradient", .array(gradient.map(JSONValue.doubles))),
        ])
        for e in extra { o[e.key] = e.value }
        return o
    }

    /// Compact with bare NaN tokens, like desktop's Python (`json.dumps`).
    public func write(_ url: URL) throws {
        try JSONWriter(indent: nil, nonFinite: .tokens).write(.object(json), to: url)
    }
}
