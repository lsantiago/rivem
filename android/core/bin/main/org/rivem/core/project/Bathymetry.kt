package org.rivem.core.project

import java.io.File

/**
 * Cross-section bed profile: station (m along the section, increasing left → right) and bed elevation (m, same
 * datum as the water level). Desktop's Python always drops the first row as a header
 * (`compute_section.py:163-164`), so files are always written with the header `station,stage`.
 */
data class Bathymetry(var stations: List<Double>, var elevations: List<Double>) {

    companion object {
        /**
         * Parses CSV text with `,`, `;` or tab separators and an optional header row. A comma is taken as decimal
         * separator when the line uses `;` or tabs. Values are metres; pass `feet = true` to convert (× 0.3048),
         * as desktop does for imperial projects. Rows with decreasing station are reversed to increasing order.
         */
        fun parse(csv: String, feet: Boolean = false): Bathymetry {
            var st = ArrayList<Double>()
            var el = ArrayList<Double>()
            val lines = csv.split('\n', '\r', '\u000B', '\u000C', '\u0085', ' ', ' ')
                .map { trimSwiftWhitespace(it) }
                .filter { it.isNotEmpty() }
            for ((n, line) in lines.withIndex()) {
                val usesComma = !line.contains(";") && !line.contains("\t")
                val fields = line.split(*(if (usesComma) charArrayOf(',') else charArrayOf(';', '\t')))
                    .filter { it.isNotEmpty() } // Swift `split` omits empty subsequences
                    .map { trimSwiftWhitespace(it) }
                if (fields.size < 2) throw ProjectError.InvalidBathymetry("línea ${n + 1}: se esperan dos columnas")
                val normalise: (String) -> String = { if (usesComma) it else it.replace(",", ".") }
                val s = parseSwiftDouble(normalise(fields[0]))
                val e = parseSwiftDouble(normalise(fields[1]))
                if (s == null || e == null) {
                    if (n == 0) continue // header
                    throw ProjectError.InvalidBathymetry("línea ${n + 1}: valores no numéricos")
                }
                st.add(s)
                el.add(e)
            }
            if (st.size < 2) throw ProjectError.InvalidBathymetry("se necesitan al menos dos puntos")
            if (st.last() < st.first()) {
                st.reverse()
                el.reverse()
            }
            if (feet) {
                st = ArrayList(st.map { it * 0.3048 })
                el = ArrayList(el.map { it * 0.3048 })
            }
            return Bathymetry(st, el)
        }

        fun read(file: File, feet: Boolean = false): Bathymetry =
            parse(String(file.readBytes(), Charsets.UTF_8), feet)

        /** Swift `.whitespaces`: horizontal white space (space, tab, Unicode space separators). */
        private fun trimSwiftWhitespace(s: String): String =
            s.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
    }

    /** `station,stage` header, comma separator, dot decimals, SI (numbers as Swift's `"\(d)"`). */
    val csv: String
        get() {
            val s = StringBuilder("station,stage\n")
            for ((a, b) in stations.zip(elevations)) {
                s.append(swiftDoubleDescription(a)).append(',').append(swiftDoubleDescription(b)).append('\n')
            }
            return s.toString()
        }

    fun write(file: File) {
        writeAtomically(file, csv.toByteArray(Charsets.UTF_8))
    }

    val minElevation: Double? get() = elevations.minOrNull()
}
