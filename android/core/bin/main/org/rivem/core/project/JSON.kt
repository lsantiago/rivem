package org.rivem.core.project

import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.truncate

/**
 * JSON tree that round-trips RIVeR desktop files without losing information (Swift `JSONValue`):
 * - object key order is kept (in `xsections.json` the section index is the key position);
 * - integers stay integers (`num_stations` must remain an int for Python's `range()`);
 * - Python's bare `NaN`, `Infinity`, `-Infinity` tokens are accepted (`piv_results.json`, `xsections.json`).
 *
 * The cases keep the Swift names (`JSONValue.Int`, `JSONValue.Double`, …); the lower-case factories
 * (`JSONValue.int(1)`, `JSONValue.double(x)`, `JSONValue.string(s)`, `JSONValue.obj(o)`) mirror Swift's `.int(1)`.
 * Swift `Int` is 64-bit, so `JSONValue.Int` holds a `Long`; `intValue` returns a Kotlin `Int` (null when it does not
 * fit) and `longValue` the full value.
 */
sealed class JSONValue {
    object Null : JSONValue() {
        override fun toString(): kotlin.String = "null"
    }

    data class Bool(val value: Boolean) : JSONValue()

    data class Int(val value: Long) : JSONValue() {
        constructor(value: kotlin.Int) : this(value.toLong())
    }

    /** Structural equality; two NaN doubles are equal (so files with NaN compare equal after a round trip). */
    class Double(val value: kotlin.Double) : JSONValue() {
        override fun equals(other: Any?): Boolean =
            other is Double && (value == other.value || (value.isNaN() && other.value.isNaN()))

        override fun hashCode(): kotlin.Int = if (value == 0.0) 0 else value.hashCode()
        override fun toString(): kotlin.String = "Double($value)"
    }

    data class String(val value: kotlin.String) : JSONValue()

    data class Array(val value: List<JSONValue>) : JSONValue()

    /** Holds its own copy of the object, so mutating the `JSONObject` it was built from does not change it. */
    class Object(value: JSONObject) : JSONValue() {
        val value: JSONObject = value.copy()
        override fun equals(other: Any?): Boolean = other is Object && value == other.value
        override fun hashCode(): kotlin.Int = value.hashCode()
        override fun toString(): kotlin.String = "Object($value)"
    }

    // MARK: Convenience accessors

    /** Numbers as Double; numeric strings too (desktop may store `video.total_length` as a string). */
    val doubleValue: kotlin.Double?
        get() = when (this) {
            is Int -> value.toDouble()
            is Double -> value
            is String -> parseSwiftDouble(value.trim { it == ' ' || it == '\t' })
            else -> null
        }

    /** Integers (64-bit), and doubles with an integral value (e.g. `30.0`). Swift's `intValue`. */
    val longValue: Long?
        get() = when (this) {
            is Int -> value
            is Double -> if (value.isFinite() && swiftRounded(value) == value && abs(value) < 9.0e15) value.toLong() else null
            is String -> parseSwiftInt(value.trim { it == ' ' || it == '\t' })
            else -> null
        }

    /** Like Swift `intValue`, as a Kotlin `Int`; null when the value does not fit in 32 bits. */
    val intValue: kotlin.Int?
        get() = longValue?.let { if (it in kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE) it.toInt() else null }

    val stringValue: kotlin.String? get() = (this as? String)?.value
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<JSONValue>? get() = (this as? Array)?.value

    /** A copy (Swift value semantics): mutating it does not change this value. */
    val objectValue: JSONObject? get() = (this as? Object)?.value?.copy()
    val isNull: Boolean get() = this === Null

    /** Array of numbers; `null` (NaN sanitised by desktop's Node code) becomes NaN. */
    val doubleArray: List<kotlin.Double>?
        get() {
            val a = arrayValue ?: return null
            val out = ArrayList<kotlin.Double>(a.size)
            for (v in a) {
                if (v.isNull) out.add(kotlin.Double.NaN) else out.add(v.doubleValue ?: return null)
            }
            return out
        }

    companion object {
        fun bool(value: Boolean): JSONValue = Bool(value)
        fun int(value: kotlin.Int): JSONValue = Int(value.toLong())
        fun int(value: Long): JSONValue = Int(value)
        fun double(value: kotlin.Double): JSONValue = Double(value)
        fun string(value: kotlin.String): JSONValue = String(value)
        fun array(value: List<JSONValue>): JSONValue = Array(value)
        /** Swift `.object(o)` (`object` is a Kotlin keyword). */
        fun obj(value: JSONObject): JSONValue = Object(value)

        fun doubles(values: List<kotlin.Double>): JSONValue = Array(values.map { Double(it) })
        fun doubles(values: DoubleArray): JSONValue = Array(values.map { Double(it) })
    }
}

/**
 * JSON object with insertion-ordered keys (Swift `JSONObject`). Unlike the Swift struct this is a mutable reference
 * type: use [copy] where Swift relied on value semantics. `JSONValue.Object` and `objectValue` copy for you.
 */
class JSONObject() : Iterable<JSONObject.Entry> {
    data class Entry(val key: String, val value: JSONValue)

    private val list = ArrayList<Entry>()

    val entries: List<Entry> get() = list

    constructor(pairs: List<Pair<String, JSONValue>>) : this() {
        for ((k, v) in pairs) this[k] = v
    }

    constructor(vararg pairs: Pair<String, JSONValue>) : this(pairs.toList())

    val keys: List<String> get() = list.map { it.key }
    val count: Int get() = list.size
    val isEmpty: Boolean get() = list.isEmpty()

    operator fun get(key: String): JSONValue? = list.firstOrNull { it.key == key }?.value

    /** Setting `null` removes the key; setting an existing key keeps its position. */
    operator fun set(key: String, newValue: JSONValue?) {
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0) {
            if (newValue != null) list[i] = Entry(key, newValue) else list.removeAt(i)
        } else if (newValue != null) {
            list.add(Entry(key, newValue))
        }
    }

    fun copy(): JSONObject = JSONObject().also { it.list.addAll(list) }

    override fun iterator(): Iterator<Entry> = list.toList().iterator()

    override fun equals(other: Any?): Boolean = other is JSONObject && list == other.list
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = "JSONObject(${list.joinToString { "${it.key}=${it.value}" }})"
}

// MARK: - Parsing

sealed class JSONError(message: String) : Exception(message) {
    class UnexpectedEnd : JSONError("JSON incompleto.")

    /** [byte] is the unsigned byte value (0…255). */
    class UnexpectedByte(val byte: Int, val offset: Int) :
        JSONError("Carácter inesperado '${byte.toChar()}' en la posición $offset.")

    class InvalidNumber(val offset: Int) : JSONError("Número no válido en la posición $offset.")
    class InvalidEscape(val offset: Int) : JSONError("Secuencia de escape no válida en la posición $offset.")
    class TrailingData(val offset: Int) : JSONError("Datos sobrantes después del JSON en la posición $offset.")
    class NonFiniteNotAllowed : JSONError("Este archivo no admite NaN ni infinitos.")

    override fun equals(other: Any?): Boolean =
        other != null && other.javaClass == javaClass && other is JSONError && other.message == message

    override fun hashCode(): Int = javaClass.hashCode() * 31 + (message?.hashCode() ?: 0)
}

object JSON {
    /**
     * Parses UTF-8 JSON (a UTF-8 BOM is skipped). Bytes that are not valid UTF-8 are read as Latin-1, which is how
     * desktop RIVeR on Windows may have written `xsections.json`.
     */
    fun parse(data: ByteArray): JSONValue {
        var bytes = data
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        if (!isValidUTF8(bytes)) {
            bytes = String(bytes, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8)
        }
        val parser = Parser(bytes)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (parser.index != bytes.size) throw JSONError.TrailingData(parser.index)
        return value
    }

    fun parse(text: String): JSONValue = parse(text.toByteArray(Charsets.UTF_8))

    fun read(file: File): JSONValue = parse(file.readBytes())

    private fun isValidUTF8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}

private class Parser(val bytes: ByteArray) {
    var index = 0

    private fun at(i: Int): Int = bytes[i].toInt() and 0xFF

    fun skipWhitespace() {
        while (index < bytes.size) {
            val b = at(index)
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) index += 1 else break
        }
    }

    fun parseValue(): JSONValue {
        skipWhitespace()
        if (index >= bytes.size) throw JSONError.UnexpectedEnd()
        val c = at(index)
        return when {
            c == '{'.code -> parseObject()
            c == '['.code -> parseArray()
            c == QUOTE -> JSONValue.String(parseString())
            c == 't'.code -> { expect("true"); JSONValue.Bool(true) }
            c == 'f'.code -> { expect("false"); JSONValue.Bool(false) }
            c == 'n'.code -> { expect("null"); JSONValue.Null }
            c == 'N'.code -> { expect("NaN"); JSONValue.Double(Double.NaN) }
            c == 'I'.code -> { expect("Infinity"); JSONValue.Double(Double.POSITIVE_INFINITY) }
            c == '-'.code && index + 1 < bytes.size && at(index + 1) == 'I'.code -> {
                index += 1
                expect("Infinity")
                JSONValue.Double(Double.NEGATIVE_INFINITY)
            }
            c == '-'.code || c in '0'.code..'9'.code -> parseNumber()
            else -> throw JSONError.UnexpectedByte(c, index)
        }
    }

    private fun expect(word: String) {
        for (ch in word) {
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            if (at(index) != ch.code) throw JSONError.UnexpectedByte(at(index), index)
            index += 1
        }
    }

    private fun expectByte(b: Int) {
        skipWhitespace()
        if (index >= bytes.size) throw JSONError.UnexpectedEnd()
        if (at(index) != b) throw JSONError.UnexpectedByte(at(index), index)
        index += 1
    }

    private fun parseObject(): JSONValue {
        index += 1 // {
        val obj = JSONObject()
        skipWhitespace()
        if (index < bytes.size && at(index) == '}'.code) { index += 1; return JSONValue.Object(obj) }
        while (true) {
            skipWhitespace()
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            if (at(index) != QUOTE) throw JSONError.UnexpectedByte(at(index), index)
            val key = parseString()
            expectByte(':'.code)
            obj[key] = parseValue()
            skipWhitespace()
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            if (at(index) == ','.code) { index += 1; continue }
            if (at(index) == '}'.code) { index += 1; return JSONValue.Object(obj) }
            throw JSONError.UnexpectedByte(at(index), index)
        }
    }

    private fun parseArray(): JSONValue {
        index += 1 // [
        val arr = ArrayList<JSONValue>()
        skipWhitespace()
        if (index < bytes.size && at(index) == ']'.code) { index += 1; return JSONValue.Array(arr) }
        while (true) {
            arr.add(parseValue())
            skipWhitespace()
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            if (at(index) == ','.code) { index += 1; continue }
            if (at(index) == ']'.code) { index += 1; return JSONValue.Array(arr) }
            throw JSONError.UnexpectedByte(at(index), index)
        }
    }

    private fun parseString(): String {
        index += 1 // opening quote
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            val b = at(index)
            if (b == QUOTE) { index += 1; break }
            if (b != BACKSLASH) { buffer.write(b); index += 1; continue }
            index += 1
            if (index >= bytes.size) throw JSONError.UnexpectedEnd()
            val e = at(index)
            index += 1
            when (e) {
                QUOTE -> buffer.write(QUOTE)
                BACKSLASH -> buffer.write(BACKSLASH)
                '/'.code -> buffer.write('/'.code)
                'b'.code -> buffer.write(0x08)
                'f'.code -> buffer.write(0x0C)
                'n'.code -> buffer.write(0x0A)
                'r'.code -> buffer.write(0x0D)
                't'.code -> buffer.write(0x09)
                'u'.code -> {
                    var code = readHex4()
                    if (code in 0xD800..0xDBFF && index + 1 < bytes.size && at(index) == BACKSLASH && at(index + 1) == 'u'.code) {
                        index += 2
                        val low = readHex4()
                        if (low !in 0xDC00..0xDFFF) throw JSONError.InvalidEscape(index)
                        code = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                    }
                    // Lone surrogates are not Unicode scalars: U+FFFD, like Swift's `UnicodeScalar(code) ?? "\u{FFFD}"`.
                    if (code in 0xD800..0xDFFF) code = 0xFFFD
                    buffer.write(String(Character.toChars(code)).toByteArray(Charsets.UTF_8))
                }
                else -> throw JSONError.InvalidEscape(index - 1)
            }
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    private fun readHex4(): Int {
        if (index + 4 > bytes.size) throw JSONError.UnexpectedEnd()
        var v = 0
        repeat(4) {
            val c = at(index)
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> throw JSONError.InvalidEscape(index)
            }
            v = v * 16 + d
            index += 1
        }
        return v
    }

    private fun parseNumber(): JSONValue {
        val start = index
        var isInteger = true
        while (index < bytes.size) {
            val c = at(index)
            if (c == '.'.code || c == 'e'.code || c == 'E'.code) {
                isInteger = false
            } else if (!(c == '-'.code || c == '+'.code || c in '0'.code..'9'.code)) {
                break
            }
            index += 1
        }
        val text = String(bytes, start, index - start, Charsets.UTF_8)
        if (isInteger) parseSwiftInt(text)?.let { return JSONValue.Int(it) }
        val d = parseSwiftDouble(text) ?: throw JSONError.InvalidNumber(start)
        return JSONValue.Double(d)
    }

    companion object {
        const val QUOTE = '"'.code
        const val BACKSLASH = '\\'.code
    }
}

/** Swift `Int(String)`: optional sign and decimal digits only, 64-bit. */
internal fun parseSwiftInt(s: String): Long? {
    if (s.isEmpty()) return null
    val body = if (s[0] == '+' || s[0] == '-') s.substring(1) else s
    if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
    return s.removePrefix("+").toLongOrNull()
}

private val decimalNumber = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

/**
 * Swift `Double(String)` for decimal text (also `nan`, `inf`, `infinity`, any case). Java's parser would also take
 * `1d`, `0x1p3` or leading/trailing spaces, so the text is validated first.
 */
internal fun parseSwiftDouble(s: String): Double? {
    if (decimalNumber.matches(s)) return s.toDoubleOrNull()
    val body = s.removePrefix("+").removePrefix("-")
    if (body.length == s.length - 1 || body.length == s.length) {
        val neg = s.startsWith("-")
        when (body.lowercase()) {
            "nan" -> return Double.NaN
            "inf", "infinity" -> return if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
    }
    return null
}

/** Swift's `Double.rounded()` (to nearest, halves away from zero). */
internal fun swiftRounded(x: Double): Double {
    if (!x.isFinite()) return x
    val t = truncate(x)
    return if (abs(x - t) >= 0.5) t + (if (x < 0) -1.0 else 1.0) else t
}

// MARK: - Swift `Double.description`

/**
 * Exactly what Swift prints for `"\(d)"` (`Double.description`, SwiftDtoa `swift_dtoa_optimal_double`): the
 * shortest digits that round-trip; exponential form (`1e-05`, `5.830041409110808e-05`, `1e+16`) when the decimal
 * exponent is below -4 or |d| > 2^53, otherwise decimal with at least one fractional digit (`2.0`, `0.0001`,
 * `123456789.125`). `nan`, `inf`, `-inf`, `-0.0` as Swift.
 */
fun swiftDoubleDescription(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
    val a = abs(d)
    val (digits, exp10) = shortestDigits(a)
    val sb = StringBuilder()
    if (d < 0) sb.append('-')
    val n = digits.length
    // SwiftDtoa: binaryExponent > 54 || (== 54 && !isBoundary)  <=>  |d| > 2^53.
    val forceExponential = a > 9007199254740992.0
    if (exp10 < -4 || forceExponential) {
        sb.append(digits[0])
        if (n > 1) sb.append('.').append(digits, 1, n)
        sb.append('e').append(if (exp10 < 0) '-' else '+')
        val e = abs(exp10)
        if (e < 10) sb.append('0')
        sb.append(e)
    } else if (exp10 < 0) {
        sb.append("0.")
        repeat(-exp10 - 1) { sb.append('0') }
        sb.append(digits)
    } else if (exp10 + 1 < n) {
        sb.append(digits, 0, exp10 + 1).append('.').append(digits, exp10 + 1, n)
    } else {
        sb.append(digits)
        repeat(exp10 - n + 1) { sb.append('0') }
        sb.append(".0")
    }
    return sb.toString()
}

/**
 * Shortest decimal digits (no leading/trailing zeros) that parse back to [a] (> 0, finite), and the scientific
 * exponent of the first digit. Among the shortest candidates the one closest to the exact value wins, as in
 * SwiftDtoa. Java's `Double.toString` is shortest only from JDK 19, so it is used just as a starting length.
 */
private fun shortestDigits(a: Double): Pair<String, Int> {
    val exact = BigDecimal(a)
    fun candidate(p: Int): BigDecimal? {
        val down = exact.round(MathContext(p, RoundingMode.FLOOR))
        val up = exact.round(MathContext(p, RoundingMode.CEILING))
        val okDown = java.lang.Double.parseDouble(down.toString()) == a
        val okUp = java.lang.Double.parseDouble(up.toString()) == a
        return when {
            okDown && okUp -> if (exact.subtract(down) <= up.subtract(exact)) down else up
            okDown -> down
            okUp -> up
            else -> null
        }
    }
    val javaDigits = a.toString().substringBefore('E').replace(".", "").trimStart('0').trimEnd('0')
    var p = javaDigits.length.coerceIn(1, 17)
    var best = candidate(p)
    while (best == null && p < 17) { p += 1; best = candidate(p) }
    while (p > 1) {
        val shorter = candidate(p - 1) ?: break
        best = shorter
        p -= 1
    }
    val r = best!!.stripTrailingZeros()
    val digits = r.unscaledValue().toString()
    return digits to (digits.length - 1 - r.scale())
}

// MARK: - Writing

class JSONWriter(
    /** Spaces per level; null writes compact JSON on one line. */
    var indent: Int? = 4,
    var nonFinite: NonFinite = NonFinite.fail,
) {
    enum class NonFinite {
        /** Bare `NaN` / `Infinity` tokens, as Python writes them (`piv_results.json`, `xsections.json`). */
        tokens,

        /** Strict JSON: throw (`settings.json`, `transformation_matrix.json`). */
        fail,
    }

    /**
     * UTF-8 without BOM. Non-ASCII characters are written as `\uXXXX` escapes (like Python's `ensure_ascii`), so the
     * bytes are identical whether desktop reads the file as UTF-8 or as Latin-1 on Windows. Doubles are formatted as
     * Swift's `"\(d)"`, so the bytes are identical to the iOS writer's.
     */
    fun data(value: JSONValue): ByteArray {
        val out = StringBuilder()
        write(value, out, 0)
        if (indent != null) out.append("\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Writes atomically (temporary file in the same folder, then rename), like Swift's `.atomic`. */
    fun write(value: JSONValue, file: File) {
        writeAtomically(file, data(value))
    }

    private fun write(value: JSONValue, out: StringBuilder, level: Int) {
        when (value) {
            is JSONValue.Null -> out.append("null")
            is JSONValue.Bool -> out.append(if (value.value) "true" else "false")
            is JSONValue.Int -> out.append(value.value.toString())
            is JSONValue.Double -> out.append(format(value.value))
            is JSONValue.String -> appendEscaped(value.value, out)
            is JSONValue.Array -> {
                val a = value.value
                if (a.isEmpty()) { out.append("[]"); return }
                out.append("[")
                for ((n, item) in a.withIndex()) {
                    if (n > 0) out.append(",")
                    newline(out, level + 1)
                    write(item, out, level + 1)
                }
                newline(out, level)
                out.append("]")
            }
            is JSONValue.Object -> {
                val o = value.value
                if (o.isEmpty) { out.append("{}"); return }
                out.append("{")
                for ((n, entry) in o.entries.withIndex()) {
                    if (n > 0) out.append(",")
                    newline(out, level + 1)
                    appendEscaped(entry.key, out)
                    out.append(if (indent == null) ":" else ": ")
                    write(entry.value, out, level + 1)
                }
                newline(out, level)
                out.append("}")
            }
        }
    }

    private fun newline(out: StringBuilder, level: Int) {
        val indent = indent ?: return
        out.append("\n")
        repeat(indent * level) { out.append(' ') }
    }

    private fun format(d: Double): String {
        if (d.isFinite()) return swiftDoubleDescription(d)
        if (nonFinite != NonFinite.tokens) throw JSONError.NonFiniteNotAllowed()
        if (d.isNaN()) return "NaN"
        return if (d > 0) "Infinity" else "-Infinity"
    }

    companion object {
        fun appendEscaped(s: String, out: StringBuilder) {
            out.append("\"")
            var i = 0
            while (i < s.length) {
                var scalar = s.codePointAt(i)
                i += Character.charCount(scalar)
                // Swift strings cannot hold lone surrogates; Kotlin ones can: write them as U+FFFD.
                if (scalar in 0xD800..0xDFFF) scalar = 0xFFFD
                when (scalar) {
                    '"'.code -> out.append("\\\"")
                    '\\'.code -> out.append("\\\\")
                    '\n'.code -> out.append("\\n")
                    '\r'.code -> out.append("\\r")
                    '\t'.code -> out.append("\\t")
                    0x08 -> out.append("\\b")
                    0x0C -> out.append("\\f")
                    else -> if (scalar < 0x20 || scalar >= 0x7F) {
                        if (scalar > 0xFFFF) {
                            val v = scalar - 0x10000
                            out.append(hex4(0xD800 + (v shr 10)))
                            out.append(hex4(0xDC00 + (v and 0x3FF)))
                        } else {
                            out.append(hex4(scalar))
                        }
                    } else {
                        out.append(scalar.toChar())
                    }
                }
            }
            out.append("\"")
        }

        private fun hex4(v: Int): String = "\\u" + Integer.toHexString(v).padStart(4, '0')
    }
}

/** Swift `Data.write(to:options: .atomic)`. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile
    val tmp = File.createTempFile(".${file.name}-", ".tmp", dir)
    try {
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
