package org.rivem.app.pipeline

import org.rivem.app.diagnostics.AppLog
import org.rivem.app.media.ImageCoding
import org.rivem.core.geometry.CameraCalibration
import org.rivem.core.geometry.CameraMatrix
import org.rivem.core.geometry.CameraSolution
import org.rivem.core.geometry.GroundPoint3D
import org.rivem.core.geometry.Homography
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.ObliqueDistances
import org.rivem.core.geometry.ObliqueSolution
import org.rivem.core.geometry.ObliqueTransform
import org.rivem.core.geometry.Orthorectifier
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.UAVTransform
import org.rivem.core.imaging.RGBAImage
import org.rivem.core.project.ControlPointsSetup
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.JSONWriter
import org.rivem.core.project.PixelSizeSetup
import org.rivem.core.project.RiverProject
import java.util.Locale
import kotlin.math.abs

/** Rectified preview written with the geometry (`transformed_image.png` / `ortho_image.png`). */
data class GeometryPreview(
    val imageFile: String,
    /** Real-world units per output pixel (`transformation.resolution`). */
    val resolution: Double,
    /** `[Emin, Emax, Nmin, Nmax]`. */
    val extent: List<Double>,
)

/** Ground reference point of the fixed-station (ipcam) mode, as imported from a GRP file (§4.2). */
data class GRPPoint(
    /** Position in the imported file (desktop's original index). */
    val id: Int,
    val label: String,
    val X: Double,
    val Y: Double,
    val Z: Double,
    /** Pixel position on the first frame; null = not marked yet. */
    val pixel: Point2?,
)

class UAVOutcome(val matrix: Matrix3, val pixelSize: Double, val preview: GeometryPreview)

class ObliqueOutcome(
    val solution: ObliqueSolution,
    /** Distance (m) between each point's solved world position and where the matrix sends its pixel. */
    val reprojection: List<Double>,
    val preview: GeometryPreview,
)

class CameraOutcome(
    val solution: CameraSolution,
    /** GRP ids of the points used (marked), in the order of `solution.reprojectionErrors`. */
    val usedIDs: List<Int>,
    val waterLevel: Double,
    val preview: GeometryPreview,
)

/**
 * Step 2 (desktop "Pixel Size" / "Control Points" / ipcam): matrix, settings keys and rectified preview, written as
 * desktop does (docs/desktop-format.md §2.1, §2.3, §2.8, §3). Writing a matrix discards sections, masks and PIV
 * results (`RiverProject.setMatrix`, like desktop's `createMatrix`). The functions modify the project passed in:
 * hand them a copy (`RiverProject(folder, settings.copy())`) when the caller must keep the original.
 */
object GeometryPipeline {
    fun loadFirstFrame(project: RiverProject): RGBAImage {
        val file = project.frameFiles.firstOrNull()
            ?: throw PipelineError("No hay frames en el proyecto: extrae el video primero.")
        return ImageCoding.rgba(file)
    }

    // MARK: UAV

    /** Two points + their real-world coordinates (default (0,0) and (L,0) for a measured length L). */
    fun applyUAV(project: RiverProject, pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2, frame: RGBAImage): UAVOutcome {
        val size = UAVTransform.pixelSize(pixel1, pixel2, world1, world2)
        val matrix = UAVTransform.matrix(pixel1 = pixel1, pixel2 = pixel2, world1 = world1, world2 = world2)
        val grid = Orthorectifier.uavGrid(matrix, imageWidth = frame.width, imageHeight = frame.height)
        val ortho = Orthorectifier.render(frame, grid, matrix)

        project.setMatrix(matrix)
        project.settings.json["control_points"] = null
        clearIpcamKeys(project)
        project.settings.pixelSize = PixelSizeSetup(
            size = size, realWorldLength = world1.distanceTo(world2),
            pixel1 = pixel1, pixel2 = pixel2, world1 = world1, world2 = world2,
        )
        project.settings.json["transformation"] = JSONValue.Object(
            JSONObject(
                "matrix" to JSONValue.String(RiverProject.matrixFile),
                "resolution" to JSONValue.Double(grid.resolution),
                "extent" to JSONValue.doubles(grid.extent),
            ),
        )
        project.save()
        ImageCoding.writePNG(ortho, ProjectFiles.url(project, ProjectFiles.transformedImage))
        AppLog.info(String.format(Locale.ROOT, "Geometría UAV: tamaño de píxel %.6f m/px, resolución orto %.4f m/px", size, grid.resolution))
        return UAVOutcome(matrix, size, GeometryPreview(ProjectFiles.transformedImage, grid.resolution, grid.extent))
    }

    // MARK: Oblique

    fun applyOblique(project: RiverProject, pixels: List<Point2>, distances: ObliqueDistances, frame: RGBAImage): ObliqueOutcome {
        val solution = ObliqueTransform.solve(pixels = pixels, distances = distances)
        val grid = Orthorectifier.obliqueGrid(solution.matrix, solution.roi)
        val ortho = Orthorectifier.render(frame, grid, solution.matrix)
        val reprojection = Homography.reprojectionErrors(solution.matrix, pixels, solution.world)

        project.setMatrix(solution.matrix)
        project.settings.json["pixel_size"] = null
        clearIpcamKeys(project)
        project.settings.controlPoints = ControlPointsSetup(pixels, distances.values)
        project.settings.json["transformation"] = JSONValue.Object(
            JSONObject(
                "matrix" to JSONValue.String(RiverProject.matrixFile),
                "resolution" to JSONValue.Double(grid.resolution),
                "extent" to JSONValue.doubles(grid.extent),
                "roi" to JSONValue.doubles(solution.roi),
            ),
        )
        project.save()
        ImageCoding.writePNG(ortho, ProjectFiles.url(project, ProjectFiles.transformedImage))
        val worst = solution.distanceResiduals.maxOfOrNull { abs(it) } ?: 0.0
        AppLog.info(String.format(Locale.ROOT, "Geometría oblicua: residuo máximo de distancias %.4f m", worst))
        return ObliqueOutcome(solution, reprojection, GeometryPreview(ProjectFiles.transformedImage, grid.resolution, grid.extent))
    }

    /**
     * Distances CSV like desktop (§4.3): six values in canonical order (one column) or `label,value` rows in any
     * order (`d12`, `12`, `1-2`, `1_2`, … ; `14` = `d41`). Header optional. Values in metres, or feet if [feet].
     */
    fun parseDistances(text: String, feet: Boolean = false): ObliqueDistances {
        val keys = listOf("12", "23", "34", "41", "13", "24")
        val byKey = HashMap<String, Double>()
        val plain = ArrayList<Double>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val usesComma = !line.contains(";") && !line.contains("\t")
            val fields = (if (usesComma) line.split(',') else line.split(';', '\t'))
                .filter { it.isNotEmpty() }
                .map { it.trim() }
            val firstField = fields.firstOrNull() ?: continue
            val normalise: (String) -> String = { if (usesComma) it else it.replace(",", ".") }
            val value = if (fields.size >= 2) normalise(fields[1]).toDoubleOrNull() else null
            if (value != null) {
                var label = firstField.lowercase(Locale.ROOT)
                if (label.startsWith("d")) label = label.substring(1)
                label = label.filter { it.isDigit() }
                label = when (label) {
                    "14" -> "41"
                    "31" -> "13"
                    "42" -> "24"
                    "21" -> "12"
                    "32" -> "23"
                    "43" -> "34"
                    else -> label
                }
                if (label in keys) {
                    byKey[label] = value
                    continue
                }
            }
            normalise(firstField).toDoubleOrNull()?.let { plain.add(it) }
        }
        var values: List<Double> = when {
            byKey.size == 6 -> keys.mapNotNull { byKey[it] }
            plain.size == 6 -> plain
            else -> throw PipelineError("Se esperaban 6 distancias (d12, d23, d34, d41, d13, d24).")
        }
        if (feet) values = values.map { it * 0.3048 }
        val d = if (values.all { it.isFinite() && it >= 0 }) ObliqueDistances(values) else null
        return d ?: throw PipelineError("Hay distancias no válidas.")
    }

    // MARK: Fixed station (ipcam, 3-D)

    /**
     * GRP file (§4.2): `Label, X, Y, Z [, x, y]`; header row only if the first cell is `Label`. Pixel (0, 0) means
     * "not marked". Separators `,`, `;` or tab (decimal comma with the last two).
     */
    fun parseGRP(text: String, feet: Boolean = false): List<GRPPoint> {
        val out = ArrayList<GRPPoint>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((n, line) in lines.withIndex()) {
            val usesComma = !line.contains(";") && !line.contains("\t")
            val fields = line.split(if (usesComma) ',' else ';')
                .flatMap { it.split('\t') }
                .map { it.trim() }
            if (n == 0 && fields.firstOrNull()?.uppercase(Locale.ROOT) == "LABEL") continue
            if (fields.size < 4) throw PipelineError("Línea ${n + 1}: se esperan al menos 4 columnas (Label, X, Y, Z).")
            val normalise: (String) -> String = { if (usesComma) it else it.replace(",", ".") }
            val x = normalise(fields[1]).toDoubleOrNull()
            val y = normalise(fields[2]).toDoubleOrNull()
            val z = normalise(fields[3]).toDoubleOrNull()
            if (x == null || y == null || z == null) throw PipelineError("Línea ${n + 1}: X, Y, Z no son números.")
            val scale = if (feet) 0.3048 else 1.0
            var pixel: Point2? = null
            if (fields.size >= 6) {
                val px = normalise(fields[4]).toDoubleOrNull()
                val py = normalise(fields[5]).toDoubleOrNull()
                if (px != null && py != null && !(px == 0.0 && py == 0.0)) pixel = Point2(px, py)
            }
            out.add(GRPPoint(id = out.size, label = fields[0], X = x * scale, Y = y * scale, Z = z * scale, pixel = pixel))
        }
        if (out.isEmpty()) throw PipelineError("El archivo no contiene puntos.")
        return out
    }

    /** Points stored in `grp_3d.json` (selected + not selected, rebuilt in their original order). */
    fun loadGRP(project: RiverProject): List<GRPPoint>? {
        val obj = try {
            JSON.read(ProjectFiles.url(project, ProjectFiles.grp3D)).objectValue
        } catch (_: Exception) {
            null
        } ?: return null
        val found = ArrayList<Pair<Int, GRPPoint>>()
        for (prefix in listOf("", "not_selected_")) {
            val xs = obj[prefix + "X"]?.doubleArray ?: continue
            val ys = obj[prefix + "Y"]?.doubleArray ?: continue
            val zs = obj[prefix + "Z"]?.doubleArray ?: continue
            val px = obj[prefix + "x"]?.doubleArray ?: continue
            val py = obj[prefix + "y"]?.doubleArray ?: continue
            val labels: List<JSONValue> = obj[prefix + "label"]?.arrayValue ?: emptyList()
            val n = minOf(xs.size, ys.size, zs.size, px.size, py.size)
            for (i in 0 until n) {
                var label = "P${found.size + 1}"
                var index = 1000 + found.size
                val pair = if (i < labels.size) labels[i].arrayValue else null
                if (pair != null && pair.size == 2) {
                    label = pair[0].stringValue ?: label
                    index = pair[1].intValue ?: index
                }
                val marked = !(px[i] == 0.0 && py[i] == 0.0) && px[i].isFinite() && py[i].isFinite()
                found.add(index to GRPPoint(index, label, xs[i], ys[i], zs[i], if (marked) Point2(px[i], py[i]) else null))
            }
        }
        if (found.isEmpty()) return null
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun labelArray(points: List<GRPPoint>): JSONValue =
        JSONValue.Array(points.map { JSONValue.Array(listOf(JSONValue.String(it.label), JSONValue.Int(it.id))) })

    /**
     * Solves the camera with every marked point (desktop "direct-solve"), writes `grp_3d.json`, `full_grp_3d.json`
     * (when some point is not marked), `camera_solution_3d.json`, `ortho_image.png` and the matrix of the water
     * plane at [waterLevel] (§2.8, §2.3).
     */
    fun applyCamera3D(project: RiverProject, points: List<GRPPoint>, waterLevel: Double, frame: RGBAImage): CameraOutcome {
        val marked = points.filter { it.pixel != null }
        if (marked.size < 6) throw PipelineError("Marca al menos 6 puntos en la imagen (hay ${marked.size}).")
        val ground = marked.map { GroundPoint3D(it.X, it.Y, it.Z, it.pixel ?: Point2(0.0, 0.0)) }
        val solution = CameraCalibration.solve(ground)
        val cam = Orthorectifier.cameraGrid(ground)
        val ortho = Orthorectifier.render(frame, cam.grid, solution.camera, cam.meanZ)
        val matrix = solution.camera.pixelToWorld(waterLevel)

        // grp_3d.json (desktop's calculate-3d-rectification, indent 2)
        val unmarked = points.filter { it.pixel == null }
        val grp = JSONObject()
        grp["X"] = JSONValue.doubles(marked.map { it.X })
        grp["Y"] = JSONValue.doubles(marked.map { it.Y })
        grp["Z"] = JSONValue.doubles(marked.map { it.Z })
        grp["x"] = JSONValue.doubles(marked.map { it.pixel?.x ?: 0.0 })
        grp["y"] = JSONValue.doubles(marked.map { it.pixel?.y ?: 0.0 })
        grp["label"] = labelArray(marked)
        grp["image"] = JSONValue.Array(marked.map { JSONValue.Null })
        grp["not_selected_X"] = JSONValue.doubles(unmarked.map { it.X })
        grp["not_selected_Y"] = JSONValue.doubles(unmarked.map { it.Y })
        grp["not_selected_Z"] = JSONValue.doubles(unmarked.map { it.Z })
        grp["not_selected_x"] = JSONValue.doubles(unmarked.map { 0.0 })
        grp["not_selected_y"] = JSONValue.doubles(unmarked.map { 0.0 })
        grp["not_selected_label"] = labelArray(unmarked)
        grp["not_selected_image"] = JSONValue.Array(unmarked.map { JSONValue.Null })
        grp["solution"] = JSONValue.String("direct-solve")
        val writer2 = JSONWriter(indent = 2, nonFinite = JSONWriter.NonFinite.fail)
        writer2.write(JSONValue.Object(grp), ProjectFiles.url(project, ProjectFiles.grp3D))
        if (unmarked.isEmpty()) {
            ProjectFiles.remove(project, listOf(ProjectFiles.fullGRP3D))
        } else {
            val full = JSONObject(
                "X" to JSONValue.doubles(marked.map { it.X }), "Y" to JSONValue.doubles(marked.map { it.Y }),
                "Z" to JSONValue.doubles(marked.map { it.Z }),
                "x" to JSONValue.doubles(marked.map { it.pixel?.x ?: 0.0 }), "y" to JSONValue.doubles(marked.map { it.pixel?.y ?: 0.0 }),
            )
            writer2.write(JSONValue.Object(full), ProjectFiles.url(project, ProjectFiles.fullGRP3D))
        }

        // camera_solution_3d.json (Python's get_camera_solution + CLI, indent 4; JSON.parse-safe: no NaN)
        val ellipses = solution.uncertaintyEllipses.map { e ->
            JSONValue.Object(
                JSONObject(
                    "center" to JSONValue.Array(listOf(finiteOrNull(e.center.x), finiteOrNull(e.center.y))),
                    "width" to finiteOrNull(e.width), "height" to finiteOrNull(e.height), "angle" to finiteOrNull(e.angle),
                ),
            )
        }
        val solutionJSON = JSONObject(
            "camera_matrix" to JSONValue.Array(solution.camera.rows.map { JSONValue.doubles(it) }),
            "camera_position" to JSONValue.Array(solution.cameraPosition.map { finiteOrNull(it) }),
            "error" to finiteOrNull(solution.meanError),
            "reprojection_errors" to JSONValue.Array(solution.reprojectionErrors.map { finiteOrNull(it) }),
            "projected_points" to JSONValue.Array(
                solution.projectedPoints.map { JSONValue.Array(listOf(finiteOrNull(it.x), finiteOrNull(it.y))) },
            ),
            "uncertainty_ellipses" to JSONValue.Array(ellipses),
            "ortho_extent" to JSONValue.doubles(cam.grid.extent),
            "ortho_image_path" to JSONValue.String(ProjectFiles.orthoImage),
        )
        JSONWriter(indent = 4, nonFinite = JSONWriter.NonFinite.fail)
            .write(JSONValue.Object(solutionJSON), ProjectFiles.url(project, ProjectFiles.cameraSolution))
        ImageCoding.writePNG(ortho, ProjectFiles.url(project, ProjectFiles.orthoImage))

        project.setMatrix(matrix)
        project.settings.json["pixel_size"] = null
        project.settings.json["control_points"] = null
        project.settings.json["transformation"] = JSONValue.Object(JSONObject("matrix" to JSONValue.String(RiverProject.matrixFile)))
        project.settings.json["grp_3d"] = JSONValue.String(ProjectFiles.grp3D)
        project.settings.json["camera_solution_3d"] = JSONValue.String(ProjectFiles.cameraSolution)
        project.save()
        AppLog.info(
            String.format(Locale.ROOT, "Geometría 3D: %d puntos, error medio %.3f px, nivel %.3f m", marked.size, solution.meanError, waterLevel),
        )
        return CameraOutcome(
            solution, marked.map { it.id }, waterLevel,
            GeometryPreview(ProjectFiles.orthoImage, cam.grid.resolution, cam.grid.extent),
        )
    }

    /**
     * ipcam: matrix of the water plane at [level] (desktop recomputes and re-saves it whenever the level changes;
     * like desktop's `createMatrix`, this discards sections, masks and PIV results).
     */
    fun setIpcamLevel(project: RiverProject, camera: CameraMatrix, level: Double): Matrix3 {
        val matrix = camera.pixelToWorld(level)
        project.setMatrix(matrix)
        project.settings.json["transformation"] = JSONValue.Object(JSONObject("matrix" to JSONValue.String(RiverProject.matrixFile)))
        project.save()
        return matrix
    }

    /**
     * Level whose plane matrix matches the stored one (desktop does not store it): solved from the image of one pixel,
     * Z such that inv(H(Z)) maps like [m].
     */
    fun ipcamLevel(m: Matrix3, camera: CameraMatrix): Double {
        val probe = Point2(100.0, 100.0)
        val world = m.apply(probe)
        // P · [X, Y, Z, 1] ∝ [x, y, 1]: solve the x-row (x · w = u) for Z given X, Y.
        val u = camera[0, 0] * world.x + camera[0, 1] * world.y + camera[0, 3]
        val w = camera[2, 0] * world.x + camera[2, 1] * world.y + camera[2, 3]
        val a = camera[0, 2] - probe.x * camera[2, 2]
        if (abs(a) <= 1e-15) return 0.0
        return (probe.x * w - u) / a
    }

    private fun clearIpcamKeys(project: RiverProject) {
        for (key in listOf("grp_3d", "camera_solution_3d", "rectification_3d_images")) project.settings.json[key] = null
    }
}
