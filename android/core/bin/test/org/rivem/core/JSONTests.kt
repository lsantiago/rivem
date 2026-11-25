package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONError
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.JSONWriter
import org.rivem.core.project.swiftDoubleDescription

/** Swift `JSONTests.swift`. */
class JSONTests {
    @Test
    fun pythonNonFiniteTokens() {
        val v = JSON.parse("""{"a": [NaN, Infinity, -Infinity, -1.5]}""")
        val a = v.objectValue?.get("a")?.doubleArray
        assertNotNull(a)
        assertTrue(a!![0].isNaN())
        assertEquals(Double.POSITIVE_INFINITY, a[1], 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, a[2], 0.0)
        assertEquals(-1.5, a[3], 0.0)
    }

    @Test
    fun keyOrderAndIntegersArePreserved() {
        val v = JSON.parse("""{"z": 1, "a": 2.0, "m": {"y": 15, "b": 1e-05}}""")
        val o = v.objectValue!!
        assertEquals(listOf("z", "a", "m"), o.keys)
        assertEquals(JSONValue.int(1), o["z"])
        assertEquals(JSONValue.double(2.0), o["a"])
        assertEquals(listOf("y", "b"), o["m"]?.objectValue?.keys)
        assertEquals(JSONValue.int(15), o["m"]?.objectValue?.get("y"))
    }

    /** The escapes are assembled at runtime (`esc` + hex) so no tool can turn them into the characters. */
    private val esc = 92.toChar().toString() + "u"

    @Test
    fun unicodeEscapesAndSurrogatePairs() {
        val json = "[\"R" + esc + "00edo\", \"" + esc + "d83c" + esc + "df0a\"]"
        assertFalse(json.contains("í"))
        val v = JSON.parse(json)
        assertEquals(JSONValue.array(listOf(JSONValue.string("Río"), JSONValue.string("🌊"))), v)
        assertEquals(JSONValue.array(listOf(JSONValue.string("a\"b\\c\n"))), JSON.parse("""["a\"b\\c\n"]"""))
    }

    @Test
    fun bomIsSkippedAndLatin1IsAccepted() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[1]".toByteArray()
        assertEquals(JSONValue.array(listOf(JSONValue.int(1))), JSON.parse(bom))
        // {"é":1} written as Latin-1 bytes (invalid UTF-8)
        val latin1 = byteArrayOf(0x7B, 0x22, 0xE9.toByte(), 0x22, 0x3A, 0x31, 0x7D)
        assertEquals(listOf("é"), JSON.parse(latin1).objectValue?.keys)
    }

    @Test
    fun rejectsMalformedInput() {
        assertThrows(JSONError::class.java) { JSON.parse("{\"a\": }") }
        assertThrows(JSONError::class.java) { JSON.parse("[1, 2") }
        assertThrows(JSONError::class.java) { JSON.parse("[1] x") }
    }

    @Test
    fun writerEscapesNonASCIILikePython() {
        val key = "Río 🌊"
        val text = String(JSONWriter(indent = null).data(JSONValue.obj(JSONObject(key to JSONValue.int(1)))), Charsets.UTF_8)
        assertEquals("{\"R" + esc + "00edo " + esc + "d83c" + esc + "df0a\":1}", text)
        assertTrue(text.all { it.code < 0x80 })
    }

    @Test
    fun nonFinitePolicy() {
        val v = JSONValue.array(listOf(JSONValue.double(Double.NaN), JSONValue.double(Double.NEGATIVE_INFINITY)))
        assertEquals("[NaN,-Infinity]", String(JSONWriter(indent = null, nonFinite = JSONWriter.NonFinite.tokens).data(v), Charsets.UTF_8))
        assertThrows(JSONError.NonFiniteNotAllowed::class.java) {
            JSONWriter(indent = null, nonFinite = JSONWriter.NonFinite.fail).data(v)
        }
    }

    @Test
    fun doublesRoundTripExactly() {
        val values = listOf(0.01033821008905579, 5.830041409110808e-05, -0.3484186538996769, 1e16, 123456789.125, 0.1)
        val back = JSON.parse(JSONWriter(indent = 2).data(JSONValue.doubles(values))).doubleArray
        assertEquals(values, back)
    }

    @Test
    fun indentedOutputShape() {
        val o = JSONObject("a" to JSONValue.int(1), "b" to JSONValue.array(emptyList()), "c" to JSONValue.obj(JSONObject()))
        val text = String(JSONWriter(indent = 4).data(JSONValue.obj(o)), Charsets.UTF_8)
        assertEquals("{\n    \"a\": 1,\n    \"b\": [],\n    \"c\": {}\n}\n", text)
    }

    // Android-only: the writer must print doubles exactly like Swift's `"\(d)"` (SwiftDtoa) so both apps write
    // byte-identical files.

    @Test
    fun doublesAreFormattedLikeSwift() {
        val expected = listOf(
            2.0 to "2.0", 0.1 to "0.1", 0.03 to "0.03", -1.5 to "-1.5", 100.0 to "100.0", 0.0 to "0.0", -0.0 to "-0.0",
            0.0001 to "0.0001", 0.00012 to "0.00012", 1e-05 to "1e-05", 5.830041409110808e-05 to "5.830041409110808e-05",
            123456789.125 to "123456789.125", 0.01033821008905579 to "0.01033821008905579",
            9007199254740992.0 to "9007199254740992.0", 9007199254740994.0 to "9.007199254740994e+15",
            1e16 to "1e+16", 1.5e300 to "1.5e+300", 1e100 to "1e+100", 5e-324 to "5e-324", 1e-100 to "1e-100",
            1.7976931348623157e308 to "1.7976931348623157e+308", 2.2250738585072014e-308 to "2.2250738585072014e-308",
            1e15 to "1000000000000000.0", 22.656012 to "22.656012", 1.0 / 3.0 to "0.3333333333333333",
            2.0 / 3.0 to "0.6666666666666666", 5e-5 to "5e-05", 9.999999999999999e-5 to "9.999999999999999e-05",
            // JDK < 19 `Double.toString` gives "2.0E-3" style and occasionally non-shortest digits (JDK-4511638).
            2.0e-3 to "0.002", 1.0e23 to "1e+23", 2.82879384806159E17 to "2.82879384806159e+17",
        )
        for ((d, s) in expected) assertEquals("$d", s, swiftDoubleDescription(d))
        assertEquals("nan", swiftDoubleDescription(Double.NaN))
        assertEquals("-inf", swiftDoubleDescription(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun formattedDoublesRoundTripAndAreShortest() {
        val rnd = java.util.Random(42)
        repeat(20000) {
            val d = java.lang.Double.longBitsToDouble(rnd.nextLong())
            if (!d.isFinite()) return@repeat
            val s = swiftDoubleDescription(d)
            assertEquals(s, d, s.toDouble(), 0.0)
        }
        repeat(20000) {
            val d = (rnd.nextDouble() - 0.5) * Math.pow(10.0, (rnd.nextInt(30) - 15).toDouble())
            val s = swiftDoubleDescription(d)
            assertEquals(s, d, s.toDouble(), 0.0)
        }
    }
}
