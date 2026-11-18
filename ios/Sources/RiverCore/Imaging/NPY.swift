import Foundation

public enum NPYError: Error, Equatable, CustomStringConvertible {
    case notNPY
    case unsupportedVersion(Int)
    case badHeader(String)
    case unsupportedDType(String)
    case truncated

    public var description: String {
        switch self {
        case .notNPY: return "El archivo no es un .npy de NumPy."
        case let .unsupportedVersion(v): return "Versión de .npy no soportada (\(v))."
        case let .badHeader(why): return "Cabecera .npy no válida: \(why)"
        case let .unsupportedDType(d): return "Tipo de datos .npy no soportado: \(d)"
        case .truncated: return "El archivo .npy está incompleto."
        }
    }
}

/// NumPy `.npy` files for 2-D `uint8` arrays: desktop's `mask.npy`, `usr_mask_<N>.npy`, `final_mask.npy`
/// (shape `(height, width)`, C order).
///
/// Writing produces exactly what `np.save` writes for such an array: magic `\x93NUMPY`, version 1.0, a
/// little-endian `uint16` header length, the header dict `{'descr': '|u1', 'fortran_order': False, 'shape': (H, W), }`
/// padded with spaces and ending in a newline so the data starts at a multiple of 64 bytes, then the raw bytes.
public enum NPY {
    static let magic: [UInt8] = [0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59] // 0x93 "NUMPY"

    /// Bytes of a version 1.0 `.npy` with dtype `|u1` and shape `(height, width)`.
    public static func encodeUInt8(width: Int, height: Int, values: [UInt8]) -> Data {
        precondition(values.count == width * height, "NPY size mismatch")
        var header = "{'descr': '|u1', 'fortran_order': False, 'shape': ("
            + String(format: "%ld, %ld", height, width) + "), }"
        // NumPy ≥ 1.17 reserves room so the first axis can grow in place: 21 − digits(shape[0]) spaces.
        let digits = String(format: "%ld", height).utf8.count
        header += String(repeating: " ", count: max(0, 21 - digits))
        // magic (6) + version (2) + length (2) + header + "\n", padded to a multiple of 64 exactly like
        // `numpy.lib.format._wrap_header` (which adds a full 64 when already aligned).
        let unpadded = 10 + header.utf8.count + 1
        let padding = 64 - unpadded % 64
        header += String(repeating: " ", count: padding) + "\n"
        let headerBytes: [UInt8] = Array(header.utf8)
        var out = Data()
        out.reserveCapacity(10 + headerBytes.count + values.count)
        out.append(contentsOf: magic)
        out.append(contentsOf: [1, 0])
        let len = UInt16(headerBytes.count)
        out.append(contentsOf: [UInt8(len & 0xFF), UInt8(len >> 8)])
        out.append(contentsOf: headerBytes)
        out.append(contentsOf: values)
        return out
    }

    public static func encode(_ mask: ROIMask) -> Data {
        encodeUInt8(width: mask.width, height: mask.height, values: mask.values)
    }

    /// Reads a 2-D `uint8` (also `bool` `|b1`) array, C order, versions 1.x–3.x.
    public static func decodeUInt8(_ data: Data) throws -> (width: Int, height: Int, values: [UInt8]) {
        let bytes = [UInt8](data)
        guard bytes.count >= 10, Array(bytes[0..<6]) == magic else { throw NPYError.notNPY }
        let major = Int(bytes[6])
        var headerStart: Int
        var headerLength: Int
        switch major {
        case 1:
            headerLength = Int(bytes[8]) | (Int(bytes[9]) << 8)
            headerStart = 10
        case 2, 3:
            guard bytes.count >= 12 else { throw NPYError.truncated }
            headerLength = Int(bytes[8]) | (Int(bytes[9]) << 8) | (Int(bytes[10]) << 16) | (Int(bytes[11]) << 24)
            headerStart = 12
        default:
            throw NPYError.unsupportedVersion(major)
        }
        guard bytes.count >= headerStart + headerLength else { throw NPYError.truncated }
        guard let header = String(bytes: bytes[headerStart..<(headerStart + headerLength)], encoding: .utf8) else {
            throw NPYError.badHeader("no es texto")
        }
        let descr: String = try value(of: "descr", in: header)
        let cleanDescr = descr.trimmingCharacters(in: CharacterSet(charactersIn: "'\" "))
        guard ["|u1", "u1", "<u1", ">u1", "|b1", "b1"].contains(cleanDescr) else {
            throw NPYError.unsupportedDType(cleanDescr)
        }
        let fortran: String = try value(of: "fortran_order", in: header)
        guard fortran.trimmingCharacters(in: .whitespaces) == "False" else {
            throw NPYError.badHeader("fortran_order True no soportado")
        }
        let shapeText: String = try value(of: "shape", in: header)
        let dims: [Int] = shapeText
            .trimmingCharacters(in: CharacterSet(charactersIn: "() "))
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .compactMap { Int($0) }
        guard dims.count == 2 else { throw NPYError.badHeader("se esperaba una matriz 2-D, forma \(shapeText)") }
        let height = dims[0], width = dims[1]
        let start = headerStart + headerLength
        let count = width * height
        guard bytes.count >= start + count else { throw NPYError.truncated }
        return (width, height, Array(bytes[start..<(start + count)]))
    }

    public static func decodeMask(_ data: Data) throws -> ROIMask {
        let d = try decodeUInt8(data)
        return ROIMask(width: d.width, height: d.height, values: d.values)
    }

    /// Text of `'key': <value>` in the header dict, up to the next top-level comma.
    private static func value(of key: String, in header: String) throws -> String {
        guard let keyRange = header.range(of: "'" + key + "'") ?? header.range(of: "\"" + key + "\"") else {
            throw NPYError.badHeader("falta \(key)")
        }
        var rest = header[keyRange.upperBound...]
        guard let colon = rest.firstIndex(of: ":") else { throw NPYError.badHeader("falta ':' tras \(key)") }
        rest = rest[rest.index(after: colon)...]
        var depth = 0
        var out = ""
        for ch in rest {
            if ch == "(" { depth += 1 }
            if ch == ")" { depth -= 1 }
            if (ch == "," && depth == 0) || ch == "}" { break }
            out.append(ch)
        }
        return out.trimmingCharacters(in: .whitespaces)
    }
}
