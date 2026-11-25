package org.rivem.app.pipeline

import android.content.Context
import org.rivem.app.Autotest
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.steps.results.ReportPDF
import org.rivem.core.piv.PIVParameters
import org.rivem.core.project.Footage
import org.rivem.core.project.JSONValue
import org.rivem.core.project.ProjectStage
import org.rivem.core.project.RiverProject
import org.rivem.core.section.RatingCurve
import org.rivem.core.section.RatingPoint
import org.rivem.core.section.UncertaintySource
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * `autotest_example`: installs the pisco example, runs PIV and discharge with the app's defaults (interpolation on)
 * and compares Q with RIVeR's notebook (different PIV field and ROI, so a loose tolerance), then the quality
 * assessment, a station toggle, the PDF report, the site checks and desktop compatibility. Same checks and line
 * format as iOS `ExampleAutotest`.
 */
object ExampleAutotest {
    const val tolerance: Double = 0.15
    const val testSite = "Sitio_Prueba"

    private fun f(format: String, vararg args: Any?): String = String.format(Locale.ROOT, format, *args)

    /** Blocking: call it off the main thread. */
    fun run(context: Context, root: File): List<String> {
        val lines = ArrayList<String>()
        val report: (String, Boolean, String) -> Unit = { name, ok, detail ->
            val line = "${if (ok) "PASS" else "FAIL"} ejemplo $name: $detail"
            lines.add(line)
            if (ok) AppLog.info(line) else AppLog.error(line)
        }
        val frames = ExampleProject.bundledFrames(context)
        if (frames.size < 2) {
            report("disponible", false, "la app no incluye los frames de assets/pisco/frames")
            return lines
        }
        try {
            File(root, ExampleProject.projectName).deleteRecursively()
            val project: RiverProject = ExampleProject.install(context, root)
            val reopened = RiverProject.open(project.folder)
            report(
                "instalado",
                reopened.settings.stage == ProjectStage.crossSections && reopened.frameFiles.size == frames.size,
                "${reopened.frameFiles.size} frames, etapa ${reopened.settings.stage}",
            )

            val piv = ProcessingPipeline.run(project, PIVParameters(), progress = { _, _ -> }, cancel = CancellationFlag())
            report(
                "PIV", piv.failed.isEmpty(),
                "${piv.pairs} pares, ${piv.failed.size} fallidos, rejilla ${piv.rows}x${piv.columns}, paralelo ${piv.parallel}, " +
                    f("%.1f s", piv.seconds),
            )

            val outcome = ResultsPipeline.compute(project, interpolate = true, artificialSeeding = false)
            val r = outcome.results.firstOrNull()
            val q = r?.totalQ ?: throw PipelineError("sin caudal: ${outcome.errors.joinToString("; ")}")
            val diff = abs(q - ExampleProject.referenceQ) / ExampleProject.referenceQ
            report(
                "caudal", diff <= tolerance,
                f(
                    "Q = %.2f m3/s, RIVeR (notebook 05) %.2f m3/s, diferencia %.1f %% (tolerancia %.0f %%)",
                    q, ExampleProject.referenceQ, diff * 100, tolerance * 100,
                ),
            )
            // Station velocities, for the comparison with iOS and desktop's own run of this example.
            val vs = (0 until r.stationCount).joinToString(" ") { Fmt.num(r.value(r.velocity, it), 2) }
            report("estaciones", r.stationCount > 0, "Vs (m/s): $vs")

            // Traffic light and Q ± U (RIVEM addition): the Monte Carlo total must be the section's total_Q.
            val a = QualityPipeline.assess(project, outcome.results).firstOrNull()
            if (a != null) {
                val u = a.uncertainty
                val checks = a.quality.checks.joinToString(", ") {
                    "${it.criterion.rawValue} ${Fmt.num(it.value, 3)} ${QualityPipeline.levelText(it.level)}"
                }
                val shares = UncertaintySource.allCases.joinToString(", ") { "${it.rawValue} ${Fmt.percent(u.shares[it], 0)}" }
                val doubtful = a.quality.doubtful.joinToString(", ") { d ->
                    "${d.index + 1} (${d.reasons.joinToString("+") { it.rawValue }})"
                }
                val sameQ = abs(u.discharge - q) <= 0.01 * abs(q)
                report(
                    "calidad", sameQ && u.expanded.isFinite() && u.relative > 0 && u.relative < 0.5,
                    f(
                        "Q = %.2f ± %.2f m3/s (95 %%, %.1f %%), suma de estaciones %.4f vs total_Q %.4f; ",
                        q, u.expanded, u.relative * 100, u.discharge, q,
                    ) + "${QualityPipeline.levelText(a.quality.overall)}; $checks; fuentes: $shares; dudosas: [$doubtful]",
                )
            } else {
                report("calidad", false, "sin evaluación")
            }

            // Station toggle (desktop's check) and report.
            ResultsPipeline.setCheck(project, r.name, 13, included = false)
            val qWithout = ResultsPipeline.compute(project, true, false).results.firstOrNull()?.totalQ ?: Double.NaN
            ResultsPipeline.setCheck(project, r.name, 13, included = true)
            val qBack = ResultsPipeline.compute(project, true, false).results.firstOrNull()?.totalQ ?: Double.NaN
            report(
                "estacion desmarcada", qWithout.isFinite() && abs(qBack - q) < 1e-9 && abs(qWithout - q) > 1e-6,
                f("sin la estación 14 (interpolada): Q = %.2f m3/s; marcada de nuevo: %.2f m3/s", qWithout, qBack),
            )

            try {
                val pdf = ReportPDF.make(context, RiverProject.open(project.folder), showQuality = true)
                val size = pdf.length()
                pdf.copyTo(File(Autotest.outputFolder(context), "Ejemplo_Pisco_informe.pdf"), overwrite = true)
                report("informe PDF", size > 20_000, "${pdf.name}, ${size / 1024} KB")
            } catch (e: Exception) {
                report("informe PDF", false, "$e")
            }

            siteChecks(root, RiverProject.open(project.folder), report)

            val issues = RiverProject.open(project.folder).compatibilityIssues()
            report(
                "compatibilidad escritorio", issues.isEmpty(),
                if (issues.isEmpty()) "sin problemas" else issues.joinToString("; ") { it.message },
            )
        } catch (t: Throwable) {
            AppLog.error("autotest ejemplo: ${t.stackTraceToString()}")
            report("ejecucion", false, "$t")
        }
        return lines
    }

    /**
     * Site: a new visit carries the configuration without results; five copies of the example at known levels and
     * discharges (Q = 20·(h − 1)^1.6) give back that rating curve. The copies stay as [testSite] for the screenshot
     * of the site screen.
     */
    private fun siteChecks(root: File, template: RiverProject, report: (String, Boolean, String) -> Unit) {
        val video = template.settings.video
        if (video == null) {
            report("sitio visita nueva", false, "el ejemplo no tiene bloque video")
            return
        }
        val visit = RiverProject.create(root, ExampleProject.projectName, Footage.oblique, video)
        try {
            SitePipeline.carry(template, visit, 1.70)
            val reopened = RiverProject.open(visit.folder)
            val sections = reopened.readCrossSections()?.sections ?: emptyList()
            val ok = reopened.readMatrix() != null &&
                sections.isNotEmpty() &&
                sections.all { it.level == 1.70 && !it.hasResults && it.missingKeys.isEmpty() } &&
                reopened.settings.pivResultsPath == null &&
                reopened.settings.controlPoints == template.settings.controlPoints &&
                reopened.settings.videoRange?.step == template.settings.videoRange?.step &&
                reopened.settings.json[SitePipeline.templateKey]?.stringValue == template.folder.name &&
                reopened.frameFiles.isEmpty()
            report(
                "sitio visita nueva", ok,
                "${sections.size} secciones a nivel 1.70 sin resultados, matriz y puntos copiados, " +
                    "${reopened.frameFiles.size} frames, paso ${reopened.settings.videoRange?.step ?: 0}",
            )
        } finally {
            visit.folder.deleteRecursively()
        }

        val site = File(root, testSite)
        site.deleteRecursively()
        site.mkdirs()
        val levels = listOf(1.45, 1.60, 1.76, 1.95, 2.20)
        for ((k, h) in levels.withIndex()) {
            val dest = File(site, f("202601%02dT1000", k + 1))
            template.folder.copyRecursively(dest, overwrite = true)
            val p = RiverProject.open(dest)
            p.settings.json["river_name"] = JSONValue.String("Sitio de prueba")
            p.save()
            val s = p.readCrossSections() ?: continue
            for (sec in s.sections) {
                sec.json["level"] = JSONValue.Double(h)
                sec.json["total_Q"] = JSONValue.Double(20 * (h - 1.0).pow(1.6))
            }
            p.writeCrossSections(s)
        }
        val gaugings = SitePipeline.gaugings(site)
        val curve = RatingCurve.fit(gaugings.map { RatingPoint(it.level, it.discharge) })
        val fits = curve?.let { abs(it.b - 1.6) < 0.02 && abs(it.h0 - 1.0) < 0.02 && abs(it.a - 20) < 0.5 } ?: false
        report(
            "sitio curva", gaugings.size == levels.size && fits,
            "${gaugings.size} aforos; " + (curve?.let { f("Q = %.3f·(h − %.3f)^%.3f, R² %.5f", it.a, it.h0, it.b, it.r2) } ?: "sin ajuste"),
        )
    }
}
