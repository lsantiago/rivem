package org.rivem.core.project

import org.rivem.core.geometry.Point2
import java.io.File

/** Constant names keep the Swift case names (and are the raw values written to `settings.json`). */
@Suppress("EnumEntryName")
enum class Footage {
    uav, oblique, ipcam;

    val rawValue: String get() = name

    companion object {
        fun fromRawValue(raw: String): Footage? = entries.firstOrNull { it.name == raw }
    }
}

@Suppress("EnumEntryName")
enum class UnitSystem {
    si, imperial;

    val rawValue: String get() = name

    companion object {
        fun fromRawValue(raw: String): UnitSystem? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Typed view over `settings.json` (RIVeR desktop 3.8.3, docs/desktop-format.md §2.1).
 *
 * The underlying `JSONObject` is kept as is: keys this app does not know (processing options, metadata, keys added
 * by newer desktop versions) survive a read/modify/write cycle. Everything on disk is SI; `unit_system` only
 * changes how values are displayed.
 *
 * Swift's struct has value semantics; this class is a reference: use [copy] to get an independent value.
 */
class ProjectSettings(var json: JSONObject) {

    /** Settings of a new project (what desktop's `createFolderStructure` writes). */
    constructor(
        creationDate: String,
        footage: Footage,
        video: VideoInfo,
        language: String = "es",
        unitSystem: UnitSystem = UnitSystem.si,
    ) : this(JSONObject()) {
        json["creation_date"] = JSONValue.String(creationDate)
        json["footage"] = JSONValue.String(footage.rawValue)
        json["language"] = JSONValue.String(language)
        json["unit_system"] = JSONValue.String(unitSystem.rawValue)
        json["video"] = JSONValue.Object(video.json)
    }

    fun copy(): ProjectSettings = ProjectSettings(json.copy())

    override fun equals(other: Any?): Boolean = other is ProjectSettings && json == other.json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "ProjectSettings($json)"

    companion object {
        fun read(file: File): ProjectSettings {
            val obj = JSON.read(file).objectValue ?: throw ProjectError.InvalidFile(file.name, "no es un objeto JSON")
            return ProjectSettings(obj)
        }
    }

    /** Desktop handlers write settings with 4-space indentation; strict JSON (no NaN). */
    fun write(file: File) {
        JSONWriter(indent = 4, nonFinite = JSONWriter.NonFinite.fail).write(JSONValue.Object(json), file)
    }

    // MARK: Fields

    val creationDate: String? get() = json["creation_date"]?.stringValue

    var footage: Footage?
        get() = json["footage"]?.stringValue?.let { Footage.fromRawValue(it) }
        set(newValue) { json["footage"] = newValue?.let { JSONValue.String(it.rawValue) } }

    /** Any value other than "imperial" is treated as SI, like desktop. */
    var unitSystem: UnitSystem
        get() = if (json["unit_system"]?.stringValue == "imperial") UnitSystem.imperial else UnitSystem.si
        set(newValue) { json["unit_system"] = JSONValue.String(newValue.rawValue) }

    var video: VideoInfo?
        get() = json["video"]?.objectValue?.let { VideoInfo(it) }
        set(newValue) { json["video"] = newValue?.let { JSONValue.Object(it.json) } }

    var videoRange: VideoRange?
        get() = json["video_range"]?.objectValue?.let { VideoRange(json = it) }
        set(newValue) { json["video_range"] = newValue?.let { JSONValue.Object(it.json) } }

    var pixelSize: PixelSizeSetup?
        get() = json["pixel_size"]?.objectValue?.let { PixelSizeSetup(json = it) }
        set(newValue) { json["pixel_size"] = newValue?.let { JSONValue.Object(it.json) } }

    var controlPoints: ControlPointsSetup?
        get() = json["control_points"]?.objectValue?.let { ControlPointsSetup(json = it) }
        set(newValue) { json["control_points"] = newValue?.let { JSONValue.Object(it.json) } }

    /** `transformation.matrix` (path to `transformation_matrix.json`). */
    val transformationMatrixPath: String?
        get() = json["transformation"]?.objectValue?.get("matrix")?.stringValue

    var xsectionsPath: String?
        get() = json["xsections"]?.stringValue
        set(newValue) { json["xsections"] = newValue?.let { JSONValue.String(it) } }

    var pivResultsPath: String?
        get() = json["piv_results"]?.stringValue
        set(newValue) { json["piv_results"] = newValue?.let { JSONValue.String(it) } }

    val riverName: String? get() = json["river_name"]?.stringValue
    val site: String? get() = json["site"]?.stringValue

    /** Time between two consecutive files of `frames/`: `video_range.step / video.fps` (desktop §5.3). */
    val frameInterval: Double?
        get() {
            val step = videoRange?.step ?: return null
            val fps = video?.fps ?: return null
            if (!(fps > 0)) return null
            return step.toDouble() / fps
        }

    /** Which step of the desktop workflow the project has reached. */
    val stage: ProjectStage
        get() = when {
            pivResultsPath != null -> ProjectStage.results
            xsectionsPath != null -> ProjectStage.crossSections
            transformationMatrixPath != null -> ProjectStage.geometry
            videoRange != null -> ProjectStage.frames
            else -> ProjectStage.created
        }
}

/** Ordered like Swift's `Comparable` conformance (enum order). */
@Suppress("EnumEntryName")
enum class ProjectStage {
    created, frames, geometry, crossSections, results;

    val rawValue: Int get() = ordinal
}

/** `settings.video`. Reference type (see [ProjectSettings]); [copy] for an independent value. */
class VideoInfo(var json: JSONObject) {

    /** Block for a video file. `fps` is rounded to an integer like desktop does (29.97 → 30); `dt` derives from it. */
    constructor(filepath: String, name: String, duration: Double, fps: Double, width: Int, height: Int) : this(JSONObject()) {
        val fpsInt = swiftRounded(fps).toLong()
        json["filepath"] = JSONValue.String(filepath)
        json["name"] = JSONValue.String(name)
        json["total_length"] = JSONValue.Double(duration)
        json["time_between_frames"] = JSONValue.Double(swiftRounded(1.0 / maxOf(fpsInt, 1L).toDouble() * 100) / 100)
        json["fps"] = JSONValue.Int(fpsInt)
        json["resolution"] = JSONValue.Object(
            JSONObject("width" to JSONValue.Int(width), "height" to JSONValue.Int(height)),
        )
    }

    fun copy(): VideoInfo = VideoInfo(json.copy())

    override fun equals(other: Any?): Boolean = other is VideoInfo && json == other.json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "VideoInfo($json)"

    val filepath: String? get() = json["filepath"]?.stringValue
    val name: String? get() = json["name"]?.stringValue
    val fps: Double? get() = json["fps"]?.doubleValue
    val duration: Double? get() = json["total_length"]?.doubleValue
    val width: Int? get() = json["resolution"]?.objectValue?.get("width")?.intValue
    val height: Int? get() = json["resolution"]?.objectValue?.get("height")?.intValue

    /** `"frames"` for projects made from a folder of images; absent (= video) otherwise. */
    val isFramesFolder: Boolean get() = json["source"]?.stringValue == "frames"
}

/** `settings.video_range`: source frame indices (`end` exclusive), extraction step and resize factor. */
data class VideoRange(var start: Int, var end: Int, var step: Int, var factor: Double = 1.0) {

    companion object {
        /** Swift's failable `init?(json:)`: `VideoRange(json = o)`. */
        operator fun invoke(json: JSONObject): VideoRange? {
            val start = json["start"]?.intValue ?: return null
            val end = json["end"]?.intValue ?: return null
            val step = json["step"]?.intValue ?: return null
            if (step <= 0) return null
            return VideoRange(start, end, step, json["factor"]?.doubleValue ?: 1.0)
        }
    }

    val json: JSONObject
        get() {
            val factorValue: JSONValue =
                if (factor == swiftRounded(factor)) JSONValue.Int(factor.toLong()) else JSONValue.Double(factor)
            return JSONObject(
                "start" to JSONValue.Int(start), "end" to JSONValue.Int(end), "step" to JSONValue.Int(step),
                "factor" to factorValue,
            )
        }

    /** Source indices that are extracted: multiples of `step` in [start, end) (desktop uses `frame % step`). */
    val frameIndices: List<Int>
        get() {
            if (end <= start) return emptyList()
            val first = if (start % step == 0) start else start + (step - start % step)
            return (first until end step step).toList()
        }
}

/** `settings.pixel_size` (UAV): two pixel points, their real-world coordinates and the resulting size (m/px). */
data class PixelSizeSetup(
    var size: Double,
    var realWorldLength: Double,
    var pixel1: Point2,
    var pixel2: Point2,
    var world1: Point2,
    var world2: Point2,
) {
    companion object {
        /** Swift's failable `init?(json:)`: `PixelSizeSetup(json = o)`. */
        operator fun invoke(json: JSONObject): PixelSizeSetup? {
            fun d(k: String): Double? = json[k]?.doubleValue
            val size = d("size") ?: return null
            val x1 = d("x1") ?: return null
            val y1 = d("y1") ?: return null
            val x2 = d("x2") ?: return null
            val y2 = d("y2") ?: return null
            val length = d("rw_length") ?: 0.0
            return PixelSizeSetup(
                size = size, realWorldLength = length,
                pixel1 = Point2(x1, y1), pixel2 = Point2(x2, y2),
                world1 = Point2(d("east1") ?: 0.0, d("north1") ?: 0.0),
                world2 = Point2(d("east2") ?: length, d("north2") ?: 0.0),
            )
        }
    }

    val json: JSONObject
        get() = JSONObject(
            "size" to JSONValue.Double(size), "rw_length" to JSONValue.Double(realWorldLength),
            "x1" to JSONValue.Double(pixel1.x), "y1" to JSONValue.Double(pixel1.y),
            "x2" to JSONValue.Double(pixel2.x), "y2" to JSONValue.Double(pixel2.y),
            "east1" to JSONValue.Double(world1.x), "north1" to JSONValue.Double(world1.y),
            "east2" to JSONValue.Double(world2.x), "north2" to JSONValue.Double(world2.y),
        )
}

/** `settings.control_points` (oblique): four pixel points and the six distances between them (m). */
data class ControlPointsSetup(
    var pixels: List<Point2>,
    /** d12, d23, d34, d41, d13, d24 */
    var distances: List<Double>,
) {
    companion object {
        val distanceKeys = listOf("d12", "d23", "d34", "d41", "d13", "d24")

        /** Swift's failable `init?(json:)`: `ControlPointsSetup(json = o)`. */
        operator fun invoke(json: JSONObject): ControlPointsSetup? {
            val c = json["coordinates"]?.objectValue ?: return null
            val d = json["distances"]?.objectValue ?: return null
            val px = ArrayList<Point2>()
            for (i in 1..4) {
                val x = c["x$i"]?.doubleValue ?: return null
                val y = c["y$i"]?.doubleValue ?: return null
                px.add(Point2(x, y))
            }
            val ds = distanceKeys.mapNotNull { d[it]?.doubleValue }
            if (ds.size != 6) return null
            return ControlPointsSetup(px, ds)
        }
    }

    val json: JSONObject
        get() {
            val c = JSONObject()
            for ((i, p) in pixels.withIndex()) {
                c["x${i + 1}"] = JSONValue.Double(p.x)
                c["y${i + 1}"] = JSONValue.Double(p.y)
            }
            val d = JSONObject()
            for ((k, v) in distanceKeys.zip(distances)) d[k] = JSONValue.Double(v)
            return JSONObject("coordinates" to JSONValue.Object(c), "distances" to JSONValue.Object(d))
        }
}
