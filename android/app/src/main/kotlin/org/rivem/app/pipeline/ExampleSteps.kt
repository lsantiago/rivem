package org.rivem.app.pipeline

import android.content.Context
import org.rivem.core.project.RiverProject

/**
 * Steps 2–3 of the pisco example ([ExampleProject.completeSteps]), with the same pipeline functions as the screens,
 * like iOS `ExampleProject.install`: oblique georeferencing with the known pixels and tape distances, then the cross
 * section with RIVeR's bathymetry, level 1.76 m, 15 stations and alpha 1 (`xsections.json` + ROI mask). The
 * example opens ready for PIV.
 */
object ExampleSteps {
    fun complete(context: Context, project: RiverProject) {
        // 2. Georeferencing: oblique, 4 points + 6 distances.
        val frame = GeometryPipeline.loadFirstFrame(project)
        GeometryPipeline.applyOblique(project, ExampleProject.pixels, ExampleProject.distances, frame)

        // 3. Cross section: bathymetry, level, stations, alpha → xsections.json + ROI mask.
        val text = context.assets.open(ExampleProject.bathymetryAsset).use { String(it.readBytes(), Charsets.UTF_8) }
        val imported = SectionsPipeline.importBathymetry(text, ExampleProject.bathymetryFile, project.folder, feet = false)
        val draft = SectionDraft(
            name = ExampleProject.sectionName,
            left = ExampleProject.sectionLeft,
            right = ExampleProject.sectionRight,
            bathymetryFile = imported.file,
            level = ExampleProject.level,
            numStations = ExampleProject.numStations,
            alpha = ExampleProject.alpha,
        )
        SectionsPipeline.save(project, listOf(draft))
    }
}
