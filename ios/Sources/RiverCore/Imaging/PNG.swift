import Foundation

/// Minimal PNG writer for `RGBAImage` (8-bit RGBA, colour type 6, no interlace), pure Swift with no zlib: the image
/// data goes into a zlib stream of *stored* (uncompressed) deflate blocks, so files are about 4·W·H bytes. Used for
/// desktop's `transformed_image.png`, `mask.png` and `final_mask.png`; any PNG reader (PIL, browsers, Electron)
/// opens them. The app may re-encode with ImageIO when size matters.
public enum PNG {
    public static let signature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]

    public static func encode(_ image: RGBAImage) -> Data {
        var out: [UInt8] = []
        let rowBytes = image.width * 4
        let raw = (rowBytes + 1) * image.height
        out.reserveCapacity(raw + raw / 65535 * 5 + 128)
        out.append(contentsOf: signature)

        var ihdr: [UInt8] = []
        appendBigEndian(&ihdr, UInt32(image.width))
        appendBigEndian(&ihdr, UInt32(image.height))
        ihdr.append(contentsOf: [8, 6, 0, 0, 0]) // bit depth, colour type RGBA, deflate, filter 0, no interlace
        appendChunk(&out, type: "IHDR", data: ihdr)

        // Scanlines with filter byte 0 (None).
        var scan: [UInt8] = []
        scan.reserveCapacity(raw)
        for y in 0..<image.height {
            scan.append(0)
            let start = y * rowBytes
            scan.append(contentsOf: image.bytes[start..<(start + rowBytes)])
        }
        appendChunk(&out, type: "IDAT", data: zlibStored(scan))
        appendChunk(&out, type: "IEND", data: [])
        return Data(out)
    }

    /// zlib stream (RFC 1950) with stored deflate blocks (RFC 1951, BTYPE 00) of at most 65 535 bytes.
    static func zlibStored(_ data: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [0x78, 0x01] // CMF: deflate, 32 KiB window; FLG: no dict, level 0, (CMF·256+FLG) % 31 == 0
        out.reserveCapacity(data.count + data.count / 65535 * 5 + 16)
        var offset = 0
        repeat {
            let n = min(65535, data.count - offset)
            let isLast: UInt8 = offset + n >= data.count ? 1 : 0
            out.append(isLast)
            let len = UInt16(n)
            let nlen = ~len
            out.append(contentsOf: [UInt8(len & 0xFF), UInt8(len >> 8), UInt8(nlen & 0xFF), UInt8(nlen >> 8)])
            out.append(contentsOf: data[offset..<(offset + n)])
            offset += n
        } while offset < data.count
        appendBigEndian(&out, adler32(data))
        return out
    }

    static func adler32(_ data: [UInt8]) -> UInt32 {
        var a: UInt32 = 1, b: UInt32 = 0
        var i = 0
        while i < data.count {
            let end = min(i + 5552, data.count) // largest block before the sums can overflow 32 bits
            while i < end {
                a += UInt32(data[i])
                b += a
                i += 1
            }
            a %= 65521
            b %= 65521
        }
        return (b << 16) | a
    }

    static let crcTable: [UInt32] = {
        var table = [UInt32](repeating: 0, count: 256)
        for n in 0..<256 {
            var c = UInt32(n)
            for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
            table[n] = c
        }
        return table
    }()

    /// CRC-32 (ISO 3309 / PNG).
    static func crc32<S: Sequence>(_ bytes: S) -> UInt32 where S.Element == UInt8 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in bytes { c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    static func appendBigEndian(_ out: inout [UInt8], _ v: UInt32) {
        out.append(UInt8((v >> 24) & 0xFF))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8(v & 0xFF))
    }

    static func appendChunk(_ out: inout [UInt8], type: String, data: [UInt8]) {
        appendBigEndian(&out, UInt32(data.count))
        let typeBytes: [UInt8] = Array(type.utf8)
        out.append(contentsOf: typeBytes)
        out.append(contentsOf: data)
        appendBigEndian(&out, crc32(typeBytes + data))
    }
}
