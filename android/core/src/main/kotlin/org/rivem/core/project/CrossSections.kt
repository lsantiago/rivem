package org.rivem.core.project

import org.rivem.core.geometry.Point2
import java.io.File
import java.text.Normalizer

/**
 * One cross-section of `xsections.json` (docs/desktop-format.md §2.4). Typed access to the 22 "basic" keys the
 * desktop GUI writes in the Cross Sections step; the rest (per-station results added by Python, STIV/iWave
 * profiles) stays in `json` untouched.
 *
 * Swift's struct has value semantics; `json` is a mutable reference here: use [copy] (deep for `json`) for an
 * independent value.
 */
class CrossSection(var name: String, var json: JSONObject) {

    fun copy(name: String = this.name, json: JSONObject = this.json.copy()): CrossSection = CrossSection(name, json)

    override fun equals(other: Any?): Boolean = other is CrossSection && name == other.name && json == other.json
    override fun hashCode(): Int = name.hashCode() * 31 + json.hashCode()
    override fun toString(): String = "CrossSection($name, $json)"

    private fun d(k: String): Double? = json[k]?.doubleValue

    /** Path of the bathymetry CSV as stored (desktop relocates it by file name into the project). */
    val bathymetryPath: String? get() = json["bath"]?.stringValue

    /** Water level (m, same datum as the bathymetry). */
    val level: Double? get() = d("level")
    val leftStation: Double? get() = d("left_station")
    val numStations: Int? get() = json["num_stations"]?.intValue

    /** Surface-to-depth-averaged velocity coefficient. */
    val alpha: Double? get() = d("alpha")
    val realWorldLength: Double? get() = d("rw_length")

    /** Bathymetry end points on the drawn line, pixels. */
    val pixelLeft: Point2? get() = point("xl", "yl")
    val pixelRight: Point2? get() = point("xr", "yr")

    /** Same points in real-world metres. */
    val worldLeft: Point2? get() = point("east_l", "north_l")
    val worldRight: Point2? get() = point("east_r", "north_r")

    /** Results written by desktop's Python step, if present. */
    val totalDischarge: Double? get() = d("total_Q")
    val hasResults: Boolean get() = json["distance"] != null

    private fun point(kx: String, ky: String): Point2? {
        val x = d(kx) ?: return null
        val y = d(ky) ?: return null
        return Point2(x, y)
    }

    val missingKeys: List<String> get() = requiredKeys.filter { json[it] == null }

    companion object {
        /** Keys every section must have for desktop's Python (`compute_section.py:1399-1458`). */
        val requiredKeys = listOf("bath", "left_station", "east_l", "north_l", "east_r", "north_r", "level", "alpha", "num_stations")
    }
}

/** `xsections.json`: ordered sections (Python uses the key position as section index) plus an optional `summary`. */
class CrossSections(
    sections: List<CrossSection> = emptyList(),
    /** Desktop's statistics over all sections (`summary` key), kept verbatim. */
    var summary: JSONValue? = null,
) {
    var sections: MutableList<CrossSection> = sections.toMutableList()

    constructor(json: JSONObject) : this(parseSections(json), json["summary"])

    /** Copy with each section's `json` copied too (Swift value semantics). */
    fun copy(): CrossSections = CrossSections(sections.map { it.copy() }, summary)

    override fun equals(other: Any?): Boolean = other is CrossSections && sections == other.sections && summary == other.summary
    override fun hashCode(): Int = sections.hashCode() * 31 + (summary?.hashCode() ?: 0)
    override fun toString(): String = "CrossSections($sections, summary=$summary)"

    val json: JSONObject
        get() {
            val o = JSONObject()
            for (s in sections) o[s.name] = JSONValue.Object(s.json)
            summary?.let { o["summary"] = it }
            return o
        }

    /** Python writes NaN tokens in results; keep that convention so desktop's Python re-reads them as floats. */
    fun write(file: File) {
        JSONWriter(indent = 4, nonFinite = JSONWriter.NonFinite.tokens).write(JSONValue.Object(json), file)
    }

    operator fun get(name: String): CrossSection? = sections.firstOrNull { it.name == name }

    companion object {
        private fun parseSections(json: JSONObject): List<CrossSection> {
            val list = ArrayList<CrossSection>()
            for (entry in json) {
                if (entry.key == "summary") continue
                val obj = entry.value.objectValue
                    ?: throw ProjectError.InvalidFile("xsections.json", "la sección ${entry.key} no es un objeto")
                list.add(CrossSection(entry.key, obj))
            }
            return list
        }

        fun read(file: File): CrossSections {
            val obj = JSON.read(file).objectValue ?: throw ProjectError.InvalidFile("xsections.json", "no es un objeto JSON")
            return CrossSections(obj)
        }

        /**
         * Section names become folder names on desktop (`stis/<name>/`) and pass through Latin-1 on Windows: keep
         * them to ASCII letters, digits, `-` and `_`, and never `summary`.
         */
        fun safeName(proposed: String): String {
            // Swift `folding(options: .diacriticInsensitive)`: decompose and drop the combining marks.
            val folded = Normalizer.normalize(proposed, Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            val out = StringBuilder()
            for (ch in folded) {
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_') {
                    out.append(ch)
                } else if (ch == ' ') {
                    out.append('_')
                }
            }
            var s = out.toString()
            if (s.isEmpty() || s.lowercase() == "summary") s = "CS_$s"
            return s
        }
    }
}
