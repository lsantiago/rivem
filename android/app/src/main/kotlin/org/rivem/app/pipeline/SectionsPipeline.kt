package org.rivem.app.pipeline

import org.rivem.app.diagnostics.AppLog
import org.rivem.app.media.ImageCoding
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.ROIMask
import org.rivem.core.geometry.ROIMasks
import org.rivem.core.geometry.ROISection
import org.rivem.core.imaging.NPY
import org.rivem.core.piv.PIVParameters
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CrossSection
import org.rivem.core.project.CrossSections
import org.rivem.core.project.Footage
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.JSONWriter
import org.rivem.core.project.RiverProject
import org.rivem.core.section.SectionBuilder
import org.rivem.core.section.SectionProfile
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** A cross-section being edited (what the desktop "Cross Sections" form holds before `set-sections`). */
data class SectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Drawn line on the first frame (pixels), left bank first. */
    val left: Point2? = null,
    val right: Point2? = null,
    /** Bathymetry CSV inside the project folder (`station,stage`, SI). */
    val bathymetryFile: String? = null,
    /** Water level (m); null = desktop's default (lower of the two end elevations). */
    val level: Double? = null,
    val leftStation: Double = 0.0,
    val numStations: Int = SectionBuilder.defaultNumStations,
    val alpha: Double = SectionBuilder.defaultAlpha,
) {
    val isComplete: Boolean get() = left != null && right != null && bathymetryFile != null
}

class SectionsOutcome(val sections: CrossSections, val heightROI: Double, val bbox: List<Double>, val analysedPixels: Int)

class ImportedBathymetry(val file: String, val bathymetry: Bathymetry)

class MaskOutcome(val heightROI: Double, val bbox: List<Double>, val mask: ROIMask)

/**
 * Step 3 (desktop "Cross Sections"): bathymetry copied into the project, `xsections.json` with the 22 GUI keys
 * (`SectionBuilder`), then the ROI mask (`mask.npy`, `mask.png`, `bbox.json`) and `processing.roi_height`
 * (docs/desktop-format.md §2.4, §2.6, §2.7). The functions modify the project passed in (hand them a copy).
 */
object SectionsPipeline {
    /**
     * Parses a bathymetry file's text (CSV/TSV, `Bathymetry.parse`) and writes it normalised (`station,stage`, comma,
     * SI) into the project folder. A different file with the same name is never overwritten (`_2`, `_3`…), like
     * desktop's `copyIntoProject`. Returns the file name stored in `bath`.
     * @param sourceName file name of the picked document (its base name names the copy).
     */
    fun importBathymetry(text: String, sourceName: String, folder: File, feet: Boolean): ImportedBathymetry {
        val bathymetry = Bathymetry.parse(text, feet)
        val base = ProjectCreation.safeName(sourceName.substringBeforeLast('.'), fallback = "batimetria")
        val csv = bathymetry.csv
        var name = "$base.csv"
        var n = 2
        while (true) {
            val file = File(folder, name)
            if (!file.exists()) break
            val existing = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }
            if (existing == csv) break
            name = "${base}_$n.csv"
            n += 1
        }
        bathymetry.write(File(folder, name))
        AppLog.info("Batimetría importada como $name: ${bathymetry.stations.size} puntos")
        return ImportedBathymetry(name, bathymetry)
    }

    fun readBathymetry(project: RiverProject, file: String): Bathymetry = Bathymetry.read(project.resolve(file))

    /** File name of a bathymetry made in the app's editor for a section. */
    fun editorFileName(section: String): String = ProjectCreation.safeName(section, fallback = "seccion") + "_batimetria.csv"

    /**
     * Writes a bathymetry made in the app (editor, template, pasted CSV) as the same normalised `station,stage` CSV
     * the import produces. The editor owns [fileName], so an existing file with that name is replaced.
     */
    fun writeBathymetry(bathymetry: Bathymetry, fileName: String, folder: File): String {
        // Round trip through the parser: same normalisation (increasing stations, ≥ 2 points) as an imported file.
        val normalised = Bathymetry.parse(bathymetry.csv)
        normalised.write(File(folder, fileName))
        AppLog.info("Batimetría escrita como $fileName: ${normalised.stations.size} puntos")
        return fileName
    }

    /** Desktop's default water level for a bathymetry (`getBathimetryLimitsY`), null if the level does not cross it. */
    fun defaultLevel(bathymetry: Bathymetry): Double? = try {
        SectionBuilder.limits(bathymetry, null).level
    } catch (_: Exception) {
        null
    }

    /** Drafts from an existing `xsections.json` (desktop or app), to edit them again. */
    fun drafts(sections: CrossSections): List<SectionDraft> = sections.sections.map { s ->
        val j = s.json
        val dxl = j["dir_xl"]?.doubleValue
        val dyl = j["dir_yl"]?.doubleValue
        val dxr = j["dir_xr"]?.doubleValue
        val dyr = j["dir_yr"]?.doubleValue
        SectionDraft(
            name = s.name,
            left = if (dxl != null && dyl != null) Point2(dxl, dyl) else s.pixelLeft,
            right = if (dxr != null && dyr != null) Point2(dxr, dyr) else s.pixelRight,
            bathymetryFile = s.bathymetryPath?.let { RiverProject.basename(it) },
            level = s.level,
            leftStation = s.leftStation ?: 0.0,
            numStations = s.numStations ?: SectionBuilder.defaultNumStations,
            alpha = s.alpha ?: SectionBuilder.defaultAlpha,
        )
    }

    /** PIV window used to recommend the ROI height: `settings.processing.interrogation_area_1` or desktop's default. */
    fun windowSize(project: RiverProject): Int = pivParameters(project).interrogationArea1

    /**
     * Builds and writes every section, then the masks. In ipcam projects the matrix depends on the water level: it
     * is recomputed at the level of the first section and every section uses that level (desktop shares one level
     * per ipcam project). PIV results are discarded because the mask changes.
     */
    fun save(project: RiverProject, drafts: List<SectionDraft>, windowSize: Int = 128): SectionsOutcome {
        if (drafts.isEmpty()) throw PipelineError("Agrega al menos una sección.")
        val names = HashSet<String>()
        var list = ArrayList<SectionDraft>()
        for (d in drafts) {
            val copy = d.copy(name = CrossSections.safeName(d.name))
            if (copy.name in names) throw PipelineError("Hay dos secciones llamadas ${copy.name}.")
            names.add(copy.name)
            if (copy.left == null || copy.right == null) throw PipelineError("${copy.name}: dibuja la línea con dos toques.")
            if (copy.bathymetryFile == null) throw PipelineError("${copy.name}: importa la batimetría.")
            if (copy.numStations < 2) throw PipelineError("${copy.name}: se necesitan al menos 2 estaciones.")
            list.add(copy)
        }

        val matrix: Matrix3
        if (project.settings.footage == Footage.ipcam) {
            val camera = ProjectFiles.readCamera(project)
                ?: throw PipelineError("Falta camera_solution_3d.json: resuelve la cámara en el paso de geometría.")
            val first = list[0]
            var level: Double? = first.level
            val file = first.bathymetryFile
            if (level == null && file != null) {
                val b = try { readBathymetry(project, file) } catch (_: Exception) { null }
                if (b != null) level = defaultLevel(b)
            }
            val z = level ?: throw PipelineError("Indica el nivel del agua de la primera sección.")
            matrix = GeometryPipeline.setIpcamLevel(project, camera, z)
            list = ArrayList(list.map { it.copy(level = z) })
        } else {
            matrix = project.readMatrix() ?: throw PipelineError("Falta la matriz: completa el paso de geometría.")
        }

        val built = ArrayList<CrossSection>()
        for (d in list) {
            val file = d.bathymetryFile ?: continue
            val l = d.left ?: continue
            val r = d.right ?: continue
            val bathymetry = try {
                readBathymetry(project, file)
            } catch (e: Exception) {
                throw PipelineError("${d.name}: no se pudo leer la batimetría $file: ${e.userMessage()}")
            }
            try {
                built.add(
                    SectionBuilder.build(
                        name = d.name, drawnLeft = l, drawnRight = r, matrix = matrix, bathymetry = bathymetry,
                        bathymetryPath = file, leftStation = d.leftStation, level = d.level,
                        numStations = d.numStations, alpha = d.alpha,
                    ),
                )
            } catch (e: Exception) {
                throw PipelineError("${d.name}: ${e.userMessage()}")
            }
        }
        val sections = CrossSections(built)

        // The mask (and so the PIV field) changes: drop old results.
        ProjectFiles.remove(project, listOf(RiverProject.pivResultsFile, "_stats_cache.json"))
        project.settings.pivResultsPath = null
        project.writeCrossSections(sections)
        val masks = writeMasks(project, sections, matrix, windowSize)
        AppLog.info(
            "Secciones guardadas: ${built.size}; altura ROI ${Fmt.num(masks.heightROI, 1)} m; " +
                "bbox ${masks.bbox.map { Fmt.num(it, 1) }}",
        )
        return SectionsOutcome(sections, masks.heightROI, masks.bbox, masks.mask.count)
    }

    /**
     * `recommend-height-roi` + `create-mask-and-bbox` (dry islands from the wet segments excluded), written as
     * `mask.npy`, `mask.png`, `bbox.json`, and `processing.roi_height`. User masks are not supported in the app: any
     * `final_mask.*` left by desktop is removed so the new ROI is the one used.
     */
    fun writeMasks(project: RiverProject, sections: CrossSections, matrix: Matrix3, windowSize: Int): MaskOutcome {
        val size = ProjectFiles.frameSize(project) ?: throw PipelineError("No hay frames para dimensionar la máscara.")
        val roiSections = ArrayList<ROISection>()
        for (s in sections.sections) {
            val wet = ArrayList<ClosedFloatingPointRange<Double>>()
            val url = project.bathymetryURL(s)
            val level = s.level
            if (url != null && level != null) {
                val b = try { Bathymetry.read(url) } catch (_: Exception) { null }
                if (b != null) {
                    for (seg in SectionProfile.wetSegments(b.stations, b.elevations, level)) {
                        if (seg.second >= seg.first) wet.add(seg.first..seg.second)
                    }
                }
            }
            val roi = ROISection(s, wetSegments = wet) ?: throw PipelineError("${s.name}: faltan los extremos de la sección.")
            roiSections.add(roi)
        }
        val height = ROIMasks.recommendHeightROI(roiSections, matrix, windowSize)
        val result = ROIMasks.sectionMask(size.first, size.second, roiSections, matrix, height)
        if (result.mask.count <= 0) throw PipelineError("La máscara quedó vacía: la sección cae fuera de la imagen.")
        val npy = ProjectFiles.url(project, ProjectFiles.maskNPY)
        val tmp = File(npy.parentFile, npy.name + ".tmp")
        tmp.writeBytes(NPY.encode(result.mask))
        if (!tmp.renameTo(npy)) {
            npy.delete()
            moveFile(tmp, npy)
        }
        ImageCoding.writePNG(result.mask.overlayImage(), ProjectFiles.url(project, ProjectFiles.maskPNG))
        JSONWriter(indent = null, nonFinite = JSONWriter.NonFinite.fail)
            .write(JSONValue.doubles(result.bbox), ProjectFiles.url(project, ProjectFiles.bbox))
        ProjectFiles.remove(project, listOf(ProjectFiles.finalMaskNPY, ProjectFiles.finalMaskPNG))
        val existing = project.settings.json["processing"]?.objectValue
        project.settings.json["processing"] = JSONValue.Object(processingJSON(pivParameters(project), height, existing))
        project.save()
        return MaskOutcome(height, result.bbox, result.mask)
    }

    // The two helpers below mirror iOS `ProcessingPipeline.parameters` / `processingJSON` (step 4 owns them); kept
    // private here so step 3 does not depend on the processing screen.

    private fun pivParameters(project: RiverProject): PIVParameters {
        val o = project.settings.json["processing"]?.objectValue
        return if (o != null) PIVParameters(o) else PIVParameters()
    }

    /** `settings.processing` with desktop's keys and order (`getQuiver.ts`); STIV/iWave flags already present kept. */
    private fun processingJSON(p: PIVParameters, roiHeight: Double?, existing: JSONObject?): JSONObject {
        val roi: Double = roiHeight ?: existing?.get("roi_height")?.doubleValue ?: p.roiHeight ?: 0.0
        val roiValue: JSONValue =
            if (roi == Math.rint(roi) && abs(roi) < 1e9) JSONValue.Int(roi.toLong()) else JSONValue.Double(roi)
        return JSONObject(
            "interrogation_area_1" to JSONValue.Int(p.interrogationArea1),
            "interrogation_area_2" to JSONValue.Int(p.interrogationArea1 / 2),
            "roi_height" to roiValue,
            "remove_background" to JSONValue.Bool(p.removeBackground),
            "clahe" to JSONValue.Bool(p.clahe),
            "clip_limit" to JSONValue.Double(p.clipLimit),
            "std_filtering" to JSONValue.Bool(p.stdFiltering),
            "std_threshold" to JSONValue.Double(p.stdThreshold),
            "median_test_filtering" to JSONValue.Bool(p.medianTestFiltering),
            "median_test_epsilon" to JSONValue.Double(p.medianTestEpsilon),
            "median_test_threshold" to JSONValue.Double(p.medianTestThreshold),
            "stiv" to (existing?.get("stiv") ?: JSONValue.Bool(false)),
            "iwave" to (existing?.get("iwave") ?: JSONValue.Bool(false)),
            "iwave_manual" to (existing?.get("iwave_manual") ?: JSONValue.Null),
        )
    }
}

/**
 * Bathymetries built in the app when the user has no surveyed profile: simple channel shapes from the water width,
 * depth and banks, with the water level they were built for.
 */
object BathymetryTemplates {
    enum class Shape(val label: String) { rectangular("Rectangular"), trapezoidal("Trapezoidal") }

    /**
     * Walls of the "rectangular" shape: almost vertical (1 mm horizontal per metre), so stations stay strictly
     * increasing (desktop's Python interpolates on them).
     */
    const val rectangularSlope: Double = 0.001

    /**
     * Channel symmetric about its centre line, stations from 0 at the left bank top.
     * @param waterWidth width of the water surface at [level] (m).
     * @param depth maximum depth below [level] (m).
     * @param bankSlope horizontal / vertical of the banks (trapezoidal only; 2 means 2 m out per 1 m up).
     * @param freeboard bank height above the water (m); 0 = the profile ends at the water edges.
     * @param level water level elevation (m), in the datum of the stages.
     */
    fun make(shape: Shape, waterWidth: Double, depth: Double, bankSlope: Double, freeboard: Double, level: Double): Bathymetry {
        if (!(waterWidth > 0 && depth > 0 && waterWidth.isFinite() && depth.isFinite())) {
            throw PipelineError("El ancho y la profundidad deben ser mayores que cero.")
        }
        if (!(freeboard >= 0 && freeboard.isFinite() && level.isFinite())) throw PipelineError("Altura de orilla o nivel no válidos.")
        val z = if (shape == Shape.rectangular) rectangularSlope else bankSlope
        if (!(z > 0 && z.isFinite())) throw PipelineError("El talud debe ser mayor que cero.")
        val bottom = waterWidth - 2 * z * depth
        if (!(bottom > 0)) {
            throw PipelineError(
                "Con ese talud el fondo quedaría con ancho ${Fmt.num(bottom, 2)} m: reduce el talud o la profundidad.",
            )
        }
        val x0 = z * freeboard // left water edge
        val stations = ArrayList<Double>()
        val stages = ArrayList<Double>()
        if (freeboard > 0) {
            stations.add(0.0)
            stages.add(level + freeboard)
        }
        stations.addAll(listOf(x0, x0 + z * depth, x0 + waterWidth - z * depth, x0 + waterWidth))
        stages.addAll(listOf(level, level - depth, level - depth, level))
        if (freeboard > 0) {
            stations.add(x0 + waterWidth + z * freeboard)
            stages.add(level + freeboard)
        }
        return Bathymetry(stations, stages)
    }

    /** Wetted area of a template at its level (for checks): trapezoid (W + bottom) / 2 · depth. */
    fun area(shape: Shape, waterWidth: Double, depth: Double, bankSlope: Double): Double {
        val z = if (shape == Shape.rectangular) rectangularSlope else bankSlope
        return (waterWidth + (waterWidth - 2 * z * depth)) / 2 * depth
    }

    /** Rows typed or pasted by the user (station, stage), sorted by station; at least two distinct stations. */
    fun fromRows(rows: List<Pair<Double, Double>>): Bathymetry {
        val valid = rows.filter { it.first.isFinite() && it.second.isFinite() }
        val sorted = valid.sortedBy { it.first } // stable: equal stations keep their order
        val first = sorted.firstOrNull()
        val last = sorted.lastOrNull()
        if (sorted.size < 2 || first == null || last == null || !(last.first > first.first)) {
            throw PipelineError("Se necesitan al menos dos puntos con estaciones distintas.")
        }
        return Bathymetry(sorted.map { it.first }, sorted.map { it.second })
    }
}
