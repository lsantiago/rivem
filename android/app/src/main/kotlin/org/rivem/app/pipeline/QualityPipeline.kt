package org.rivem.app.pipeline

import org.rivem.core.geometry.ObliqueDistances
import org.rivem.core.geometry.ObliqueTransform
import org.rivem.core.project.Footage
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.ProjectSettings
import org.rivem.core.project.RiverProject
import org.rivem.core.section.DischargeUncertainty
import org.rivem.core.section.DoubtReason
import org.rivem.core.section.GaugingQuality
import org.rivem.core.section.GaugingStation
import org.rivem.core.section.GeoreferenceFit
import org.rivem.core.section.QualityCheck
import org.rivem.core.section.QualityCriterion
import org.rivem.core.section.QualityLevel
import org.rivem.core.section.UncertaintySource
import org.rivem.core.section.UncertaintySources
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-project assumptions of the uncertainty estimate, in `settings.json` under `rivem_uncertainty` (a RIVEM key that
 * desktop ignores). The georeferencing scale error is not an assumption: it comes from the control-point residuals.
 */
data class UncertaintyAssumptions(
    /** Water level read on site (m). */
    val levelStd: Double = 0.01,
    /** Bathymetry depth (m). */
    val depthStd: Double = 0.02,
    /** Velocity coefficient α. */
    val alphaStd: Double = 0.05,
) {
    fun write(settings: ProjectSettings) {
        settings.json[KEY] = JSONValue.obj(
            JSONObject(
                "level_std" to JSONValue.double(levelStd),
                "depth_std" to JSONValue.double(depthStd),
                "alpha_std" to JSONValue.double(alphaStd),
            ),
        )
    }

    companion object {
        const val KEY = "rivem_uncertainty"

        fun from(settings: ProjectSettings): UncertaintyAssumptions {
            val o = settings.json[KEY]?.objectValue ?: return UncertaintyAssumptions()
            fun v(k: String, default: Double): Double {
                val x = o[k]?.doubleValue ?: return default
                return if (x.isFinite() && x >= 0) x else default
            }
            return UncertaintyAssumptions(v("level_std", 0.01), v("depth_std", 0.02), v("alpha_std", 0.05))
        }
    }
}

/** Quality and uncertainty of one section's discharge. */
class SectionAssessment(
    val name: String,
    val uncertainty: DischargeUncertainty,
    val quality: GaugingQuality,
    val fit: GeoreferenceFit,
    /** Relative scale error used for the georeferencing (from the residuals, at least 0.5 %). */
    val scaleStd: Double,
)

/** RIVEM addition: traffic light and Q ± U (95 %) of every section, computed from what `xsections.json` holds. */
object QualityPipeline {
    const val minimumScaleStd: Double = 0.005

    /** Blocking (Monte Carlo): call it off the main thread. */
    fun assess(project: RiverProject, results: List<SectionResult>, pairCount: Int? = null): List<SectionAssessment> {
        val assumptions = UncertaintyAssumptions.from(project.settings)
        val fit = georeferenceFit(project)
        var scale = minimumScaleStd
        if (fit is GeoreferenceFit.RelativeDistanceResidual && fit.value.isFinite()) scale = max(scale, fit.value)
        val pairs: Int = pairCount
            ?: (try { project.readPIVResults()?.pairCount } catch (_: Exception) { null })
            ?: max(project.frameFiles.size - 1, 1)
        return results.map { r ->
            val st = stations(r)
            val sources = UncertaintySources(
                levelStd = assumptions.levelStd, depthStd = assumptions.depthStd,
                alphaStd = assumptions.alphaStd, scaleRelativeStd = scale, pairCount = pairs,
            )
            val u = DischargeUncertainty.estimate(st, r.alpha ?: 0.85, sources)
            val q = GaugingQuality.assess(st, r.measuredQ, u, fit)
            SectionAssessment(r.name, u, q, fit, scale)
        }
    }

    /**
     * Stations as the core estimates need them. With "interpolate" on, desktop fills and counts unticked stations
     * and stations without a velocity; those are the interpolated ones.
     */
    fun stations(r: SectionResult): List<GaugingStation> = (0 until r.stationCount).map { i ->
        val checked = r.isChecked(i)
        val raw = r.value(r.rawVelocity, i)
        var std = Double.NaN
        val lo = r.value(r.minusStd, i)
        val hi = r.value(r.plusStd, i)
        if (lo != null && hi != null) std = abs(hi - lo) / 2
        GaugingStation(
            distance = r.value(r.distance, i) ?: Double.NaN,
            depth = r.value(r.depth, i) ?: 0.0,
            area = r.value(r.area, i) ?: 0.0,
            velocity = r.value(r.velocity, i) ?: Double.NaN,
            velocityStd = std,
            discharge = r.value(r.q, i) ?: Double.NaN,
            included = checked || r.interpolated,
            interpolated = r.interpolated && (!checked || raw == null),
        )
    }

    /**
     * Oblique: RMS of the distance residuals over the mean distance. 3D camera: mean reprojection error (px). UAV:
     * nothing to check (two points fit exactly).
     */
    fun georeferenceFit(project: RiverProject): GeoreferenceFit {
        when (project.settings.footage) {
            Footage.oblique -> {
                val cp = project.settings.controlPoints ?: return GeoreferenceFit.none
                val d = ObliqueDistances(cp.distances) ?: return GeoreferenceFit.none
                if (cp.pixels.size != 4) return GeoreferenceFit.none
                val solution = try { ObliqueTransform.solve(cp.pixels, d) } catch (_: Exception) { null } ?: return GeoreferenceFit.none
                val res = solution.distanceResiduals
                if (res.isEmpty()) return GeoreferenceFit.none
                val rms = sqrt(res.fold(0.0) { acc, x -> acc + x * x } / res.size)
                val mean = d.values.sum() / d.values.size
                return if (mean > 0) GeoreferenceFit.relativeDistanceResidual(rms / mean) else GeoreferenceFit.none
            }
            Footage.ipcam -> {
                val url = ProjectFiles.url(project, ProjectFiles.cameraSolution)
                val error = try { JSON.read(url).objectValue?.get("error")?.doubleValue } catch (_: Exception) { null }
                if (error == null || !error.isFinite()) return GeoreferenceFit.none
                return GeoreferenceFit.reprojectionPixels(error)
            }
            else -> return GeoreferenceFit.none
        }
    }

    // MARK: Texts

    fun levelText(l: QualityLevel): String = when (l) {
        QualityLevel.good -> "Buena"
        QualityLevel.fair -> "Aceptable"
        QualityLevel.poor -> "Revisar"
    }

    fun title(c: QualityCriterion): String = when (c) {
        QualityCriterion.measured -> "Caudal medido"
        QualityCriterion.maxStationShare -> "Mayor aporte de una estación"
        QualityCriterion.wetStations -> "Estaciones con agua y velocidad"
        QualityCriterion.variability -> "Variabilidad entre pares"
        QualityCriterion.doubtful -> "Estaciones dudosas"
        QualityCriterion.uncertainty -> "Incertidumbre (95 %)"
        QualityCriterion.georeference -> "Ajuste de la georreferenciación"
    }

    fun valueText(c: QualityCheck): String = when (c.criterion) {
        QualityCriterion.measured, QualityCriterion.maxStationShare, QualityCriterion.variability, QualityCriterion.uncertainty ->
            Fmt.percent(c.value, 1)
        QualityCriterion.wetStations, QualityCriterion.doubtful -> Fmt.num(c.value, 0)
        QualityCriterion.georeference -> if (c.value < 0.5) Fmt.percent(c.value, 2) else "${Fmt.num(c.value, 2)} px"
    }

    /** What the criterion means and its thresholds (good / fair). */
    fun hint(c: QualityCriterion, georeference: GeoreferenceFit): String = when (c) {
        QualityCriterion.measured -> "Parte del caudal con velocidad medida, no interpolada. Buena ≥ 90 %, aceptable ≥ 75 %."
        QualityCriterion.maxStationShare -> "ISO 748 recomienda que ninguna estación lleve más del 10 % del caudal. Aceptable ≤ 20 %; si no, usa más estaciones."
        QualityCriterion.wetStations -> "Buena ≥ 12, aceptable ≥ 8. Con pocas estaciones el perfil queda mal descrito."
        QualityCriterion.variability -> "Mediana de σ/v entre pares. Buena ≤ 25 %, aceptable ≤ 50 %. Alta: pocos trazadores, reflejos u oleaje."
        QualityCriterion.doubtful -> "Saltos respecto a las vecinas, contraflujo, mucha variabilidad o sin velocidad. Revísalas en la tabla."
        QualityCriterion.uncertainty -> "Mitad del intervalo del 95 % (Monte Carlo) sobre Q. Buena ≤ 10 %, aceptable ≤ 20 %."
        QualityCriterion.georeference ->
            if (georeference is GeoreferenceFit.ReprojectionPixels) {
                "Error medio de reproyección de los puntos. Bueno ≤ 1 px, aceptable ≤ 3 px."
            } else {
                "Residuo RMS de las 6 distancias sobre su media. Bueno ≤ 0,5 %, aceptable ≤ 2 %. Alto: distancia mal medida o punto mal marcado."
            }
    }

    fun sourceText(s: UncertaintySource): String = when (s) {
        UncertaintySource.velocity -> "Velocidad (promedio de pares)"
        UncertaintySource.alpha -> "Coeficiente α"
        UncertaintySource.level -> "Nivel del agua"
        UncertaintySource.bathymetry -> "Batimetría"
        UncertaintySource.georeference -> "Georreferenciación (escala)"
        UncertaintySource.interpolation -> "Estaciones interpoladas"
    }

    fun reasonText(r: DoubtReason): String = when (r) {
        DoubtReason.noVelocity -> "sin velocidad"
        DoubtReason.highVariability -> "muy variable entre pares"
        DoubtReason.jump -> "salto respecto a sus vecinas"
        DoubtReason.reverse -> "contraflujo"
    }
}
