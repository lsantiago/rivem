package org.rivem.core.project

import java.io.File

/**
 * `piv_results.json` (docs/desktop-format.md §2.5): displacement field in pixels per pair of consecutive frames.
 * Grid arrays are the `shape` grid flattened row-major (x varies fastest). Missing vectors are NaN.
 *
 * Note: Kotlin list equality treats NaN == NaN (boxed `Double.equals`), Swift's `[Double] ==` does not.
 */
data class PIVResults(
    /** [rows, columns] */
    var rows: Int,
    var columns: Int,
    /** Window centres (pixels of the extracted frames, y down). */
    var x: List<Double>,
    var y: List<Double>,
    /** One array per frame pair. */
    var u: List<List<Double>>,
    var v: List<List<Double>>,
    var uMedian: List<Double>,
    var vMedian: List<Double>,
    var gradient: List<List<Double>>,
    /** Other keys (e.g. `_piv_params_hash`), kept verbatim. */
    var extra: JSONObject = JSONObject(),
) {
    val pairCount: Int get() = u.size

    companion object {
        private val known = setOf("shape", "x", "y", "u", "v", "u_median", "v_median", "gradient")

        /** Swift's throwing `init(json:)`: `PIVResults(json = o)`. */
        operator fun invoke(json: JSONObject): PIVResults {
            val o = json
            fun fail(why: String) = ProjectError.InvalidFile("piv_results.json", why)
            val shape = o["shape"]?.arrayValue?.mapNotNull { it.intValue }
            if (shape == null || shape.size != 2) throw fail("falta 'shape'")
            val x = o["x"]?.doubleArray
            val y = o["y"]?.doubleArray
            if (x == null || y == null) throw fail("faltan 'x'/'y'")
            val um = o["u_median"]?.doubleArray
            val vm = o["v_median"]?.doubleArray
            if (um == null || vm == null) throw fail("faltan las medianas")
            fun matrix(k: String): List<List<Double>> {
                val rows = o[k]?.arrayValue ?: throw fail("falta '$k'")
                return rows.map { row -> row.doubleArray ?: throw fail("'$k' contiene valores no numéricos") }
            }
            val u = matrix("u")
            val v = matrix("v")
            // `gradient` is required by desktop's Python but not by its GUI; tolerate its absence.
            val gradient = if (o["gradient"] == null) emptyList() else matrix("gradient")
            val n = shape[0] * shape[1]
            if (x.size != n || y.size != n || um.size != n || vm.size != n ||
                !u.all { it.size == n } || !v.all { it.size == n } || u.size != v.size
            ) {
                // Swift interpolates the array as "[11, 58]".
                throw fail("las dimensiones no coinciden con 'shape' [${shape.joinToString(", ")}]")
            }
            val extra = JSONObject()
            for (e in o) if (e.key !in known) extra[e.key] = e.value
            return PIVResults(
                rows = shape[0], columns = shape[1], x = x, y = y, u = u, v = v,
                uMedian = um, vMedian = vm, gradient = gradient, extra = extra,
            )
        }

        fun read(file: File): PIVResults {
            val obj = JSON.read(file).objectValue ?: throw ProjectError.InvalidFile("piv_results.json", "no es un objeto JSON")
            return PIVResults(json = obj)
        }
    }

    val json: JSONObject
        get() {
            val o = JSONObject(
                "shape" to JSONValue.Array(listOf(JSONValue.Int(rows), JSONValue.Int(columns))),
                "x" to JSONValue.doubles(x), "y" to JSONValue.doubles(y),
                "u_median" to JSONValue.doubles(uMedian), "v_median" to JSONValue.doubles(vMedian),
                "u" to JSONValue.Array(u.map { JSONValue.doubles(it) }),
                "v" to JSONValue.Array(v.map { JSONValue.doubles(it) }),
                "gradient" to JSONValue.Array(gradient.map { JSONValue.doubles(it) }),
            )
            for (e in extra) o[e.key] = e.value
            return o
        }

    /** Compact with bare NaN tokens, like desktop's Python (`json.dumps`). */
    fun write(file: File) {
        JSONWriter(indent = null, nonFinite = JSONWriter.NonFinite.tokens).write(JSONValue.Object(json), file)
    }
}
