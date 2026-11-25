package org.rivem.core.project

import org.rivem.core.geometry.Matrix3
import java.io.File
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

sealed class ProjectError(message: String) : Exception(message) {
    class NoSettingsFile : ProjectError("La carpeta no contiene settings.json: no es un proyecto de RIVeR.")
    class InvalidFile(val file: String, val why: String) : ProjectError("$file no es válido: $why.")
    class InvalidBathymetry(val why: String) : ProjectError("Batimetría no válida: $why.")

    /** Equatable like the Swift enum: same case and same associated values. */
    override fun equals(other: Any?): Boolean =
        other != null && other.javaClass == javaClass && (other as ProjectError).message == message

    override fun hashCode(): Int = javaClass.hashCode() * 31 + (message?.hashCode() ?: 0)
}

/** Something that would stop RIVeR desktop from opening the project (or that the app could not find). */
data class CompatibilityIssue(val message: String) {
    override fun toString(): String = message
}

/**
 * A RIVeR project folder (the dated folder that holds `settings.json`), read and written in the RIVeR desktop
 * 3.8.3 format (docs/desktop-format.md).
 *
 * Paths stored in the files are never trusted: like desktop's `relocateProject`, every project-owned file is looked
 * up by its file name inside the project folder, so projects can move between the phone and a PC.
 *
 * Swift `mutating` methods (`setMatrix`, `writeCrossSections`) update [settings] in place.
 */
class RiverProject(val folder: File, var settings: ProjectSettings) {

    companion object {
        const val settingsFile = "settings.json"
        const val framesFolder = "frames"
        const val matrixFile = "transformation_matrix.json"
        const val xsectionsFile = "xsections.json"
        const val pivResultsFile = "piv_results.json"

        // MARK: Opening and creating

        /**
         * Opens a project folder. Throws only when `settings.json` is missing or unreadable; desktop-compatibility
         * problems are reported by `compatibilityIssues()`.
         */
        fun open(folder: File): RiverProject {
            val file = File(folder, settingsFile)
            if (!file.exists()) throw ProjectError.NoSettingsFile()
            return RiverProject(folder, ProjectSettings.read(file))
        }

        /**
         * Creates `<parent>/<name>/<YYYYMMDDTHHMM>/` with `settings.json` and an empty `frames/`, like desktop's
         * `createFolderStructure`. If that folder exists, a `-2`, `-3`… suffix is added instead of overwriting it.
         * (Swift `create(in:name:footage:video:date:timeZone:)`.)
         */
        fun create(
            parent: File,
            name: String,
            footage: Footage,
            video: VideoInfo,
            date: Date = Date(),
            timeZone: TimeZone = TimeZone.getDefault(),
        ): RiverProject {
            val stamp = folderName(date, timeZone)
            val base = File(parent, name)
            var folder = File(base, stamp)
            var n = 2
            while (folder.exists()) {
                folder = File(base, "$stamp-$n")
                n += 1
            }
            val frames = File(folder, framesFolder)
            if (!frames.mkdirs() && !frames.isDirectory) throw java.io.IOException("No se pudo crear ${frames.path}")
            val project = RiverProject(folder, ProjectSettings(creationDate = stamp, footage = footage, video = video))
            project.save()
            return project
        }

        /** `YYYYMMDDTHHMM` in local time (desktop's project folder name and `creation_date`). */
        fun folderName(date: Date, timeZone: TimeZone = TimeZone.getDefault()): String {
            val cal = GregorianCalendar(timeZone)
            cal.time = date
            return String.format(
                Locale.ROOT, "%04d%02d%02dT%02d%02d",
                cal.get(GregorianCalendar.YEAR), cal.get(GregorianCalendar.MONTH) + 1,
                cal.get(GregorianCalendar.DAY_OF_MONTH), cal.get(GregorianCalendar.HOUR_OF_DAY),
                cal.get(GregorianCalendar.MINUTE),
            )
        }

        // MARK: Paths

        /** File name of a stored path, accepting both `/` and Windows `\` separators. */
        fun basename(storedPath: String): String =
            storedPath.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: storedPath

        // MARK: Frames

        /** Desktop frame file name: source frame index, zero-padded to 10 digits (`video_to_frames.py:157`). */
        fun frameFileName(index: Int): String = String.format(Locale.ROOT, "%010d.jpg", index)
    }

    fun save() {
        settings.write(File(folder, settingsFile))
    }

    /** Where a stored path lives inside this project folder. */
    fun resolve(storedPath: String): File = File(folder, basename(storedPath))

    private fun exists(file: File): Boolean = file.exists()

    /** The `.jpg` files of `frames/` in name order (= time order thanks to the zero padding), without `background.jpg`. */
    val frameFiles: List<File>
        get() {
            val dir = File(folder, framesFolder)
            val names = dir.list()?.toList() ?: emptyList()
            return names.filter { it.lowercase().endsWith(".jpg") && it != "background.jpg" }
                .sorted()
                .map { File(dir, it) }
        }

    // MARK: Geometry

    fun readMatrix(): Matrix3? {
        val file = File(folder, matrixFile)
        if (!exists(file)) return null
        val rows = JSON.read(file).arrayValue?.mapNotNull { it.doubleArray }
        if (rows == null || rows.size != 3 || !rows.all { it.size == 3 }) {
            throw ProjectError.InvalidFile(matrixFile, "se esperaba una matriz 3×3")
        }
        return Matrix3.fromRows(rows)
    }

    /**
     * Writes the pixel → real-world matrix and, like desktop's `createMatrix`, discards everything computed with
     * the previous geometry (sections, masks, PIV results, statistics cache). The settings are saved.
     */
    fun setMatrix(matrix: Matrix3) {
        for (name in listOf(
            xsectionsFile, "bbox.json", "mask.npy", "mask.png", "final_mask.npy", "final_mask.png",
            pivResultsFile, "_stats_cache.json",
        )) {
            val file = File(folder, name)
            if (exists(file) && !file.delete()) throw java.io.IOException("No se pudo borrar ${file.path}")
        }
        settings.xsectionsPath = null
        settings.pivResultsPath = null
        val rows = JSONValue.Array(matrix.rows.map { JSONValue.doubles(it) })
        JSONWriter(indent = null, nonFinite = JSONWriter.NonFinite.fail).write(rows, File(folder, matrixFile))
        val transformation = settings.json["transformation"]?.objectValue ?: JSONObject()
        transformation["matrix"] = JSONValue.String(matrixFile)
        settings.json["transformation"] = JSONValue.Object(transformation)
        save()
    }

    // MARK: Sections and results

    fun readCrossSections(): CrossSections? {
        val stored = settings.xsectionsPath ?: return null
        val file = resolve(stored)
        return if (exists(file)) CrossSections.read(file) else null
    }

    fun writeCrossSections(sections: CrossSections) {
        sections.write(File(folder, xsectionsFile))
        settings.xsectionsPath = xsectionsFile
        save()
    }

    fun readPIVResults(): PIVResults? {
        if (settings.pivResultsPath == null) return null
        // Desktop always reads <project>/piv_results.json; the stored path is only a "results exist" flag.
        val file = File(folder, pivResultsFile)
        return if (exists(file)) PIVResults.read(file) else null
    }

    /** Bathymetry CSV of a section: looked up by file name inside the project (desktop copies it there). */
    fun bathymetryURL(section: CrossSection): File? {
        val stored = section.bathymetryPath ?: return null
        val file = resolve(stored)
        return if (exists(file)) file else null
    }

    // MARK: Desktop compatibility

    /** Problems that would make RIVeR desktop 3.8.3 refuse the project (docs/desktop-format.md §2.1 and §7). */
    fun compatibilityIssues(): List<CompatibilityIssue> {
        val issues = ArrayList<CompatibilityIssue>()
        val s = settings
        if (s.footage == null) issues.add(CompatibilityIssue("settings.json: 'footage' falta o no es uav/oblique/ipcam"))
        if (s.video?.filepath == null) issues.add(CompatibilityIssue("settings.json: falta 'video.filepath'"))
        if (!File(folder, framesFolder).isDirectory) issues.add(CompatibilityIssue("falta la carpeta frames/"))
        val needsRange = s.pixelSize != null || s.controlPoints != null || s.xsectionsPath != null || s.pivResultsPath != null
        if (needsRange && s.videoRange == null) issues.add(CompatibilityIssue("settings.json: falta 'video_range'"))
        if ((s.pixelSize != null || s.controlPoints != null) && !exists(File(folder, matrixFile))) {
            issues.add(CompatibilityIssue("falta $matrixFile"))
        }
        for (key in listOf("xsections", "grp_3d", "camera_solution_3d")) {
            val p = s.json[key]?.stringValue
            if (p != null && !exists(resolve(p))) {
                issues.add(CompatibilityIssue("settings.json apunta a ${basename(p)}, que no está en la carpeta"))
            }
        }
        val sections = try { readCrossSections() } catch (e: Exception) { null }
        if (sections != null) {
            for (sec in sections.sections) {
                val missing = sec.missingKeys
                if (missing.isNotEmpty()) issues.add(CompatibilityIssue("sección ${sec.name}: faltan ${missing.joinToString(", ")}"))
                if (sec.bathymetryPath != null && bathymetryURL(sec) == null) {
                    issues.add(CompatibilityIssue("sección ${sec.name}: la batimetría no está en la carpeta"))
                }
            }
        }
        return issues
    }
}

/**
 * Finds projects under a workspace folder: `<root>/<name>/<date>/settings.json` (desktop layout) or
 * `<root>/<name>/settings.json`.
 */
object RiverWorkspace {
    fun projectFolders(root: File): List<File> {
        fun dirs(dir: File): List<File> = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        fun isProject(dir: File): Boolean = File(dir, RiverProject.settingsFile).exists()
        val found = ArrayList<File>()
        for (named in dirs(root)) {
            if (isProject(named)) { found.add(named); continue }
            found.addAll(dirs(named).filter { isProject(it) })
        }
        // Newest first: desktop folder names are timestamps.
        return found.sortedWith { a, b -> b.name.compareTo(a.name) }
    }
}
