package org.rivem.core.imaging

import org.rivem.core.geometry.ROIMask
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Errors reading `.npy` files (Swift `NPYError`). */
sealed class NPYError(message: String) : Exception(message) {
    class NotNPY : NPYError("El archivo no es un .npy de NumPy.") {
        override fun equals(other: Any?): Boolean = other is NotNPY
        override fun hashCode(): Int = 1
    }

    data class UnsupportedVersion(val version: Int) : NPYError("Versión de .npy no soportada ($version).")

    data class BadHeader(val why: String) : NPYError("Cabecera .npy no válida: $why")

    data class UnsupportedDType(val dtype: String) : NPYError("Tipo de datos .npy no soportado: $dtype")

    class Truncated : NPYError("El archivo .npy está incompleto.") {
        override fun equals(other: Any?): Boolean = other is Truncated
        override fun hashCode(): Int = 2
    }
}

/**
 * NumPy `.npy` files for 2-D `uint8` arrays: desktop's `mask.npy`, `usr_mask_<N>.npy`, `final_mask.npy`
 * (shape `(height, width)`, C order).
 *
 * Writing produces exactly what `np.save` writes for such an array: magic `\x93NUMPY`, version 1.0, a
 * little-endian `uint16` header length, the header dict `{'descr': '|u1', 'fortran_order': False, 'shape': (H, W), }`
 * padded with spaces and ending in a newline so the data starts at a multiple of 64 bytes, then the raw bytes.
 */
object NPY {
    /** A decoded 2-D `uint8` array (Swift's `(width:, height:, values:)` tuple). Bytes are unsigned. */
    class UInt8Matrix(val width: Int, val height: Int, val values: ByteArray) {
        operator fun component1(): Int = width
        operator fun component2(): Int = height
        operator fun component3(): ByteArray = values

        override fun equals(other: Any?): Boolean =
            other is UInt8Matrix && width == other.width && height == other.height && values.contentEquals(other.values)

        override fun hashCode(): Int = 31 * (31 * width + height) + values.contentHashCode()
    }

    internal val magic: ByteArray = byteArrayOf(0x93.toByte(), 0x4E, 0x55, 0x4D, 0x50, 0x59) // 0x93 "NUMPY"

    /** Bytes of a version 1.0 `.npy` with dtype `|u1` and shape `(height, width)`. */
    fun encodeUInt8(width: Int, height: Int, values: ByteArray): ByteArray {
        require(values.size == width * height) { "NPY size mismatch" }
        var header = "{'descr': '|u1', 'fortran_order': False, 'shape': (" + "$height, $width" + "), }"
        // NumPy ≥ 1.17 reserves room so the first axis can grow in place: 21 − digits(shape[0]) spaces.
        val digits = height.toString().toByteArray(Charsets.UTF_8).size
        header += " ".repeat(maxOf(0, 21 - digits))
        // magic (6) + version (2) + length (2) + header + "\n", padded to a multiple of 64 exactly like
        // `numpy.lib.format._wrap_header` (which adds a full 64 when already aligned).
        val unpadded = 10 + header.toByteArray(Charsets.UTF_8).size + 1
        val padding = 64 - unpadded % 64
        header += " ".repeat(padding) + "\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val out = ByteArray(10 + headerBytes.size + values.size)
        magic.copyInto(out, 0)
        out[6] = 1
        out[7] = 0
        val len = headerBytes.size and 0xFFFF
        out[8] = (len and 0xFF).toByte()
        out[9] = (len shr 8).toByte()
        headerBytes.copyInto(out, 10)
        values.copyInto(out, 10 + headerBytes.size)
        return out
    }

    fun encode(mask: ROIMask): ByteArray = encodeUInt8(mask.width, mask.height, mask.values)

    /** Reads a 2-D `uint8` (also `bool` `|b1`) array, C order, versions 1.x–3.x. */
    fun decodeUInt8(data: ByteArray): UInt8Matrix {
        val bytes = data
        if (!(bytes.size >= 10 && bytes.copyOfRange(0, 6).contentEquals(magic))) throw NPYError.NotNPY()
        val major = u(bytes[6])
        val headerStart: Int
        val headerLength: Int
        when (major) {
            1 -> {
                headerLength = u(bytes[8]) or (u(bytes[9]) shl 8)
                headerStart = 10
            }
            2, 3 -> {
                if (bytes.size < 12) throw NPYError.Truncated()
                headerLength = u(bytes[8]) or (u(bytes[9]) shl 8) or (u(bytes[10]) shl 16) or (u(bytes[11]) shl 24)
                headerStart = 12
            }
            else -> throw NPYError.UnsupportedVersion(major)
        }
        if (headerLength < 0 || bytes.size < headerStart + headerLength) throw NPYError.Truncated()
        val header: String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, headerStart, headerLength))
                .toString()
        } catch (e: CharacterCodingException) {
            throw NPYError.BadHeader("no es texto")
        }
        val descr = value("descr", header)
        val cleanDescr = descr.trim { it == '\'' || it == '"' || it == ' ' }
        if (cleanDescr !in listOf("|u1", "u1", "<u1", ">u1", "|b1", "b1")) {
            throw NPYError.UnsupportedDType(cleanDescr)
        }
        val fortran = value("fortran_order", header)
        if (trimWhitespaces(fortran) != "False") {
            throw NPYError.BadHeader("fortran_order True no soportado")
        }
        val shapeText = value("shape", header)
        val dims: List<Int> = shapeText
            .trim { it == '(' || it == ')' || it == ' ' }
            .split(",")
            .map { trimWhitespaces(it) }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        if (dims.size != 2) throw NPYError.BadHeader("se esperaba una matriz 2-D, forma $shapeText")
        val height = dims[0]
        val width = dims[1]
        val start = headerStart + headerLength
        val count = width * height
        if (bytes.size < start + count) throw NPYError.Truncated()
        return UInt8Matrix(width, height, bytes.copyOfRange(start, start + count))
    }

    fun decodeMask(data: ByteArray): ROIMask {
        val d = decodeUInt8(data)
        return ROIMask(d.width, d.height, d.values)
    }

    /** Text of `'key': <value>` in the header dict, up to the next top-level comma. */
    private fun value(key: String, header: String): String {
        var keyIndex = header.indexOf("'$key'")
        var keyLength = key.length + 2
        if (keyIndex < 0) {
            keyIndex = header.indexOf("\"$key\"")
            keyLength = key.length + 2
        }
        if (keyIndex < 0) throw NPYError.BadHeader("falta $key")
        var rest = header.substring(keyIndex + keyLength)
        val colon = rest.indexOf(':')
        if (colon < 0) throw NPYError.BadHeader("falta ':' tras $key")
        rest = rest.substring(colon + 1)
        var depth = 0
        val out = StringBuilder()
        for (ch in rest) {
            if (ch == '(') depth += 1
            if (ch == ')') depth -= 1
            if ((ch == ',' && depth == 0) || ch == '}') break
            out.append(ch)
        }
        return trimWhitespaces(out.toString())
    }

    /** Swift's `trimmingCharacters(in: .whitespaces)`: tab and Unicode space separators, not newlines. */
    private fun trimWhitespaces(s: String): String =
        s.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
