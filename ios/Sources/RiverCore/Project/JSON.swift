import Foundation

/// JSON tree that round-trips RIVeR desktop files without losing information:
/// - object key order is kept (in `xsections.json` the section index is the key position);
/// - integers stay integers (`num_stations` must remain an int for Python's `range()`);
/// - Python's bare `NaN`, `Infinity`, `-Infinity` tokens are accepted (`piv_results.json`, `xsections.json`).
///
/// Foundation's `JSONSerialization`/`Codable` lose key order and reject `NaN`, hence this small implementation.
public indirect enum JSONValue: Sendable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object(JSONObject)
}

extension JSONValue: Equatable {
    /// Structural equality; two NaN doubles are equal (so files with NaN compare equal after a round trip).
    public static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
        switch (lhs, rhs) {
        case (.null, .null): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.int(a), .int(b)): return a == b
        case let (.double(a), .double(b)): return a == b || (a.isNaN && b.isNaN)
        case let (.string(a), .string(b)): return a == b
        case let (.array(a), .array(b)): return a == b
        case let (.object(a), .object(b)): return a == b
        default: return false
        }
    }
}

/// JSON object with insertion-ordered keys.
public struct JSONObject: Equatable, Sendable, Sequence {
    public struct Entry: Equatable, Sendable {
        public var key: String
        public var value: JSONValue
    }

    public private(set) var entries: [Entry] = []

    public init() {}

    public init(_ pairs: [(String, JSONValue)]) {
        for (k, v) in pairs { self[k] = v }
    }

    public var keys: [String] { entries.map(\.key) }
    public var count: Int { entries.count }
    public var isEmpty: Bool { entries.isEmpty }

    /// Setting `nil` removes the key; setting an existing key keeps its position.
    public subscript(key: String) -> JSONValue? {
        get { entries.first { $0.key == key }?.value }
        set {
            if let i = entries.firstIndex(where: { $0.key == key }) {
                if let newValue { entries[i].value = newValue } else { entries.remove(at: i) }
            } else if let newValue {
                entries.append(Entry(key: key, value: newValue))
            }
        }
    }

    public func makeIterator() -> IndexingIterator<[Entry]> { entries.makeIterator() }
}

// MARK: - Convenience accessors

public extension JSONValue {
    /// Numbers as Double; numeric strings too (desktop may store `video.total_length` as a string).
    var doubleValue: Double? {
        switch self {
        case let .int(i): return Double(i)
        case let .double(d): return d
        case let .string(s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    /// Integers, and doubles with an integral value (e.g. `30.0`).
    var intValue: Int? {
        switch self {
        case let .int(i): return i
        case let .double(d) where d.isFinite && d.rounded() == d && abs(d) < 9.0e15: return Int(d)
        case let .string(s): return Int(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    var stringValue: String? { if case let .string(s) = self { return s } else { return nil } }
    var boolValue: Bool? { if case let .bool(b) = self { return b } else { return nil } }
    var arrayValue: [JSONValue]? { if case let .array(a) = self { return a } else { return nil } }
    var objectValue: JSONObject? { if case let .object(o) = self { return o } else { return nil } }
    var isNull: Bool { if case .null = self { return true } else { return false } }

    /// Array of numbers; `null` (NaN sanitised by desktop's Node code) becomes NaN.
    var doubleArray: [Double]? {
        guard let a = arrayValue else { return nil }
        var out: [Double] = []
        out.reserveCapacity(a.count)
        for v in a {
            if v.isNull { out.append(.nan) } else if let d = v.doubleValue { out.append(d) } else { return nil }
        }
        return out
    }

    static func doubles(_ values: [Double]) -> JSONValue { .array(values.map { .double($0) }) }
}

// MARK: - Parsing

public enum JSONError: Error, Equatable, CustomStringConvertible {
    case unexpectedEnd
    case unexpectedByte(UInt8, offset: Int)
    case invalidNumber(offset: Int)
    case invalidEscape(offset: Int)
    case trailingData(offset: Int)
    case nonFiniteNotAllowed

    public var description: String {
        switch self {
        case .unexpectedEnd: return "JSON incompleto."
        case let .unexpectedByte(b, o): return "Carácter inesperado '\(Character(UnicodeScalar(b)))' en la posición \(o)."
        case let .invalidNumber(o): return "Número no válido en la posición \(o)."
        case let .invalidEscape(o): return "Secuencia de escape no válida en la posición \(o)."
        case let .trailingData(o): return "Datos sobrantes después del JSON en la posición \(o)."
        case .nonFiniteNotAllowed: return "Este archivo no admite NaN ni infinitos."
        }
    }
}

public enum JSON {
    /// Parses UTF-8 JSON (a UTF-8 BOM is skipped). Bytes that are not valid UTF-8 are read as Latin-1, which is how
    /// desktop RIVeR on Windows may have written `xsections.json`.
    public static func parse(_ data: Data) throws -> JSONValue {
        var bytes = [UInt8](data)
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes.removeFirst(3) }
        if String(bytes: bytes, encoding: .utf8) == nil, let latin = String(bytes: bytes, encoding: .isoLatin1) {
            bytes = Array(latin.utf8)
        }
        var parser = Parser(bytes: bytes)
        let value = try parser.parseValue()
        parser.skipWhitespace()
        guard parser.index == bytes.count else { throw JSONError.trailingData(offset: parser.index) }
        return value
    }

    public static func parse(_ text: String) throws -> JSONValue { try parse(Data(text.utf8)) }

    public static func read(_ url: URL) throws -> JSONValue { try parse(Data(contentsOf: url)) }
}

private struct Parser {
    let bytes: [UInt8]
    var index = 0

    init(bytes: [UInt8]) { self.bytes = bytes }

    private static let quote = UInt8(ascii: "\""), backslash = UInt8(ascii: "\\")

    mutating func skipWhitespace() {
        while index < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[index]) { index += 1 }
    }

    mutating func parseValue() throws -> JSONValue {
        skipWhitespace()
        guard index < bytes.count else { throw JSONError.unexpectedEnd }
        switch bytes[index] {
        case UInt8(ascii: "{"): return try parseObject()
        case UInt8(ascii: "["): return try parseArray()
        case Self.quote: return .string(try parseString())
        case UInt8(ascii: "t"): try expect("true"); return .bool(true)
        case UInt8(ascii: "f"): try expect("false"); return .bool(false)
        case UInt8(ascii: "n"): try expect("null"); return .null
        case UInt8(ascii: "N"): try expect("NaN"); return .double(.nan)
        case UInt8(ascii: "I"): try expect("Infinity"); return .double(.infinity)
        case UInt8(ascii: "-") where index + 1 < bytes.count && bytes[index + 1] == UInt8(ascii: "I"):
            index += 1
            try expect("Infinity")
            return .double(-.infinity)
        case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): return try parseNumber()
        default: throw JSONError.unexpectedByte(bytes[index], offset: index)
        }
    }

    private mutating func expect(_ word: String) throws {
        for b in word.utf8 {
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            guard bytes[index] == b else { throw JSONError.unexpectedByte(bytes[index], offset: index) }
            index += 1
        }
    }

    private mutating func expectByte(_ b: UInt8) throws {
        skipWhitespace()
        guard index < bytes.count else { throw JSONError.unexpectedEnd }
        guard bytes[index] == b else { throw JSONError.unexpectedByte(bytes[index], offset: index) }
        index += 1
    }

    private mutating func parseObject() throws -> JSONValue {
        index += 1 // {
        var obj = JSONObject()
        skipWhitespace()
        if index < bytes.count, bytes[index] == UInt8(ascii: "}") { index += 1; return .object(obj) }
        while true {
            skipWhitespace()
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            guard bytes[index] == Self.quote else { throw JSONError.unexpectedByte(bytes[index], offset: index) }
            let key = try parseString()
            try expectByte(UInt8(ascii: ":"))
            obj[key] = try parseValue()
            skipWhitespace()
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
            if bytes[index] == UInt8(ascii: "}") { index += 1; return .object(obj) }
            throw JSONError.unexpectedByte(bytes[index], offset: index)
        }
    }

    private mutating func parseArray() throws -> JSONValue {
        index += 1 // [
        var arr: [JSONValue] = []
        skipWhitespace()
        if index < bytes.count, bytes[index] == UInt8(ascii: "]") { index += 1; return .array(arr) }
        while true {
            arr.append(try parseValue())
            skipWhitespace()
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
            if bytes[index] == UInt8(ascii: "]") { index += 1; return .array(arr) }
            throw JSONError.unexpectedByte(bytes[index], offset: index)
        }
    }

    private mutating func parseString() throws -> String {
        index += 1 // opening quote
        var buffer: [UInt8] = []
        while true {
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            let b = bytes[index]
            if b == Self.quote { index += 1; break }
            if b != Self.backslash { buffer.append(b); index += 1; continue }
            index += 1
            guard index < bytes.count else { throw JSONError.unexpectedEnd }
            let e = bytes[index]
            index += 1
            switch e {
            case UInt8(ascii: "\""): buffer.append(Self.quote)
            case UInt8(ascii: "\\"): buffer.append(Self.backslash)
            case UInt8(ascii: "/"): buffer.append(UInt8(ascii: "/"))
            case UInt8(ascii: "b"): buffer.append(0x08)
            case UInt8(ascii: "f"): buffer.append(0x0C)
            case UInt8(ascii: "n"): buffer.append(0x0A)
            case UInt8(ascii: "r"): buffer.append(0x0D)
            case UInt8(ascii: "t"): buffer.append(0x09)
            case UInt8(ascii: "u"):
                var code = try readHex4()
                if (0xD800...0xDBFF).contains(code), index + 1 < bytes.count,
                   bytes[index] == Self.backslash, bytes[index + 1] == UInt8(ascii: "u") {
                    index += 2
                    let low = try readHex4()
                    guard (0xDC00...0xDFFF).contains(low) else { throw JSONError.invalidEscape(offset: index) }
                    code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                }
                let scalar = UnicodeScalar(code) ?? "\u{FFFD}"
                buffer.append(contentsOf: Array(String(Character(scalar)).utf8))
            default: throw JSONError.invalidEscape(offset: index - 1)
            }
        }
        return String(decoding: buffer, as: UTF8.self)
    }

    private mutating func readHex4() throws -> UInt32 {
        guard index + 4 <= bytes.count else { throw JSONError.unexpectedEnd }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let c = bytes[index]
            let d: UInt32
            switch c {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): d = UInt32(c - UInt8(ascii: "0"))
            case UInt8(ascii: "a")...UInt8(ascii: "f"): d = UInt32(c - UInt8(ascii: "a") + 10)
            case UInt8(ascii: "A")...UInt8(ascii: "F"): d = UInt32(c - UInt8(ascii: "A") + 10)
            default: throw JSONError.invalidEscape(offset: index)
            }
            v = v * 16 + d
            index += 1
        }
        return v
    }

    private mutating func parseNumber() throws -> JSONValue {
        let start = index
        var isInteger = true
        while index < bytes.count {
            let c = bytes[index]
            if c == UInt8(ascii: ".") || c == UInt8(ascii: "e") || c == UInt8(ascii: "E") {
                isInteger = false
            } else if !(c == UInt8(ascii: "-") || c == UInt8(ascii: "+") || (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(c)) {
                break
            }
            index += 1
        }
        let text = String(decoding: bytes[start..<index], as: UTF8.self)
        if isInteger, let i = Int(text) { return .int(i) }
        guard let d = Double(text) else { throw JSONError.invalidNumber(offset: start) }
        return .double(d)
    }
}

// MARK: - Writing

public struct JSONWriter: Sendable {
    public enum NonFinite: Sendable {
        /// Bare `NaN` / `Infinity` tokens, as Python writes them (`piv_results.json`, `xsections.json`).
        case tokens
        /// Strict JSON: throw (`settings.json`, `transformation_matrix.json`).
        case fail
    }

    /// Spaces per level; nil writes compact JSON on one line.
    public var indent: Int?
    public var nonFinite: NonFinite

    public init(indent: Int? = 4, nonFinite: NonFinite = .fail) {
        self.indent = indent
        self.nonFinite = nonFinite
    }

    /// UTF-8 without BOM. Non-ASCII characters are written as `\uXXXX` escapes (like Python's `ensure_ascii`),
    /// so the bytes are identical whether desktop reads the file as UTF-8 or as Latin-1 on Windows.
    public func data(_ value: JSONValue) throws -> Data {
        var out = ""
        try write(value, into: &out, level: 0)
        if indent != nil { out.append("\n") }
        return Data(out.utf8)
    }

    public func write(_ value: JSONValue, to url: URL) throws {
        try data(value).write(to: url, options: .atomic)
    }

    private func write(_ value: JSONValue, into out: inout String, level: Int) throws {
        switch value {
        case .null: out.append("null")
        case let .bool(b): out.append(b ? "true" : "false")
        case let .int(i): out.append(String(i))
        case let .double(d): out.append(try format(d))
        case let .string(s): Self.appendEscaped(s, into: &out)
        case let .array(a):
            if a.isEmpty { out.append("[]"); return }
            out.append("[")
            for (n, item) in a.enumerated() {
                if n > 0 { out.append(",") }
                newline(&out, level + 1)
                try write(item, into: &out, level: level + 1)
            }
            newline(&out, level)
            out.append("]")
        case let .object(o):
            if o.isEmpty { out.append("{}"); return }
            out.append("{")
            for (n, entry) in o.entries.enumerated() {
                if n > 0 { out.append(",") }
                newline(&out, level + 1)
                Self.appendEscaped(entry.key, into: &out)
                out.append(indent == nil ? ":" : ": ")
                try write(entry.value, into: &out, level: level + 1)
            }
            newline(&out, level)
            out.append("}")
        }
    }

    private func newline(_ out: inout String, _ level: Int) {
        guard let indent else { return }
        out.append("\n")
        out.append(String(repeating: " ", count: indent * level))
    }

    private func format(_ d: Double) throws -> String {
        if d.isFinite { return "\(d)" }
        guard nonFinite == .tokens else { throw JSONError.nonFiniteNotAllowed }
        if d.isNaN { return "NaN" }
        return d > 0 ? "Infinity" : "-Infinity"
    }

    static func appendEscaped(_ s: String, into out: inout String) {
        out.append("\"")
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out.append("\\\"")
            case "\\": out.append("\\\\")
            case "\n": out.append("\\n")
            case "\r": out.append("\\r")
            case "\t": out.append("\\t")
            case "\u{08}": out.append("\\b")
            case "\u{0C}": out.append("\\f")
            default:
                if scalar.value < 0x20 || scalar.value >= 0x7F {
                    if scalar.value > 0xFFFF {
                        let v = scalar.value - 0x10000
                        out.append(hex4(0xD800 + (v >> 10)))
                        out.append(hex4(0xDC00 + (v & 0x3FF)))
                    } else {
                        out.append(hex4(scalar.value))
                    }
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out.append("\"")
    }

    private static func hex4(_ v: UInt32) -> String {
        let h = String(v, radix: 16)
        return "\\u" + String(repeating: "0", count: max(0, 4 - h.count)) + h
    }
}
