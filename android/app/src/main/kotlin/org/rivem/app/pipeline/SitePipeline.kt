package org.rivem.app.pipeline

import org.rivem.app.diagnostics.AppLog
import org.rivem.app.media.VideoMetadata
import org.rivem.core.project.JSONValue
import org.rivem.core.project.RiverProject
import org.rivem.core.project.RiverWorkspace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One gauging of a site, for the rating curve. */
data class SiteGauging(
    val folder: File,
    /** `YYYYMMDDTHHMM` folder name. */
    val stamp: String,
    /** Water level of the first section (m). */
    val level: Double,
    /** Mean discharge over the sections (m³/s), like desktop's summary. */
    val discharge: Double,
) {
    val id: File get() = folder

    val date: Date?
        get() = try {
            SimpleDateFormat("yyyyMMdd'T'HHmm", Locale.ROOT).apply { isLenient = false }.parse(stamp.take(13))
        } catch (_: Exception) {
            null
        }
}

/**
 * RIVEM addition: a **site** is a project-name folder of the workspace (`RIVeR/<name>/`), the same grouping desktop
 * uses; every dated folder inside is a visit. A new visit reuses the georeferencing, sections, bathymetry, mask and
 * PIV settings of an earlier one, so on site only the video and the water level are needed.
 */
object SitePipeline {
    /** Folder of the visit the configuration came from (RIVEM key, desktop ignores it). */
    const val templateKey = "rivem_site_template"

    /** Settings keys that belong to one visit and are not carried over. */
    val visitKeys: Set<String> = setOf(
        "creation_date", "video", "video_range", "piv_results", "lens_correction", "stabilization",
        "stabilization_regions_path", "footage", templateKey,
    )

    /** Files of the template that are not copied (results and things of that visit). */
    val skippedFiles: Set<String> = setOf(RiverProject.settingsFile, RiverProject.pivResultsFile, "_stats_cache.json")
    val videoExtensions: Set<String> = setOf("mov", "mp4", "m4v", "avi", "mkv", "mts", "3gp", "webm")

    /** `xsections.json` keys written by the discharge computation (`DischargeCalculator.apply`). */
    val resultKeys: Set<String> = setOf(
        "id", "east", "north", "distance", "wet_segments", "x", "y", "displacement_x", "displacement_y", "displacement_east",
        "displacement_north", "streamwise_east", "streamwise_north", "crosswise_east", "crosswise_north",
        "streamwise_velocity_magnitude", "streamwise_x", "streamwise_y", "depth", "check", "minus_std", "plus_std",
        "5th_percentile", "95th_percentile", "seeded_vel_profile", "W", "A", "Q", "Q_minus_std", "Q_plus_std", "Q_portion",
        "total_Q", "total_q_std", "measured_Q", "interpolated_Q", "total_A", "total_W", "max_depth", "average_depth", "mean_V",
        "mean_Vs", "interpolated", "artificial_seeding", "_stats_cache",
    )

    fun siteName(project: RiverProject): String = project.folder.absoluteFile.parentFile?.name ?: ""
    fun siteFolder(project: RiverProject): File = project.folder.absoluteFile.parentFile!!

    /**
     * New visit of the template's site with this video (moved or copied into it) and, optionally, the new water
     * level (oblique / UAV; a fixed camera changes its level in the Sections step, which recomputes its matrix).
     */
    fun newVisit(template: RiverProject, source: File, metadata: VideoMetadata, moveSource: Boolean, level: Double?): RiverProject {
        val footage = template.settings.footage ?: throw PipelineError("El aforo de referencia no tiene tipo de toma.")
        val v = template.settings.video
        val w = v?.width
        val h = v?.height
        if (w != null && h != null && w > 0 && h > 0 && (w != metadata.width || h != metadata.height)) {
            throw PipelineError(
                "El video nuevo es de ${metadata.width}×${metadata.height} y el de referencia de ${w}×$h: " +
                    "los puntos de la imagen no coincidirían. Graba con la misma resolución y orientación.",
            )
        }
        val root = siteFolder(template).parentFile!!
        val project = ProjectCreation.create(root, siteName(template), footage, source, metadata, moveSource)
        try {
            carry(template, project, level)
        } catch (e: Exception) {
            project.folder.deleteRecursively()
            throw e
        }
        AppLog.info(
            "Visita nueva ${project.folder.name} del sitio ${siteName(template)} con la configuración de " +
                template.folder.name + (level?.let { String.format(Locale.ROOT, ", nivel %.3f m", it) } ?: ""),
        )
        return project
    }

    /**
     * Copies the template's configuration into a project that has its own video: settings (except the visit's own
     * keys), files (except video and results), sections without results, the same frame step and resize factor.
     * [project] is updated in place and saved.
     */
    fun carry(template: RiverProject, project: RiverProject, level: Double?) {
        val ownVideo = project.settings.video?.filepath?.let { RiverProject.basename(it) }
        for (file in template.folder.listFiles() ?: emptyArray()) {
            if (!file.isFile) continue
            val name = file.name
            if (name in skippedFiles || file.extension.lowercase(Locale.ROOT) in videoExtensions || name == ownVideo) continue
            val dest = File(project.folder, name)
            if (dest.exists()) continue
            file.copyTo(dest)
        }

        val json = project.settings.json
        for (entry in template.settings.json) {
            if (entry.key !in visitKeys) json[entry.key] = entry.value
        }
        json[templateKey] = JSONValue.String(template.folder.name)
        val previous = template.settings.videoRange
        val video = project.settings.video
        if (previous != null && video != null) {
            val range = FramesPipeline.defaultRange(video)
            range.step = previous.step
            range.factor = previous.factor
            project.settings.videoRange = range
        }
        project.save()

        val sections = project.readCrossSections()
        if (sections != null) {
            for (s in sections.sections) {
                val o = s.json
                for (key in o.keys) {
                    if (key in resultKeys || key.startsWith("filled_")) o[key] = null
                }
                if (level != null && project.settings.footage != org.rivem.core.project.Footage.ipcam) {
                    o["level"] = JSONValue.Double(level)
                }
            }
            sections.summary = null
            project.writeCrossSections(sections)
        }
    }

    /** Visits of the site with a discharge (results computed), oldest first. */
    fun gaugings(siteFolder: File): List<SiteGauging> {
        val site = siteFolder.canonicalFile
        val folders = RiverWorkspace.projectFolders(site.parentFile ?: return emptyList())
            .filter { it.canonicalFile.parentFile == site }
        val out = ArrayList<SiteGauging>()
        for (f in folders) {
            val p = try { RiverProject.open(f) } catch (_: Exception) { continue }
            if (p.settings.pivResultsPath == null) continue
            val sections = try { p.readCrossSections() } catch (_: Exception) { null } ?: continue
            val qs = sections.sections.mapNotNull { it.totalDischarge }.filter { it.isFinite() && it > 0 }
            val level = sections.sections.mapNotNull { it.level }.firstOrNull { it.isFinite() } ?: continue
            if (qs.isEmpty()) continue
            out.add(SiteGauging(f, f.name, level, qs.sum() / qs.size))
        }
        return out.sortedBy { it.stamp }
    }
}
