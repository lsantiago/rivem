package org.rivem.app.steps.results

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.rivem.app.common.CanvasLayout
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.label
import org.rivem.app.media.ImageCoding
import org.rivem.app.pipeline.ExportPipeline
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.PipelineError
import org.rivem.app.pipeline.QualityPipeline
import org.rivem.app.pipeline.ResultsPipeline
import org.rivem.app.pipeline.SectionAssessment
import org.rivem.app.pipeline.SectionResult
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.core.RiverCore
import org.rivem.core.geometry.Matrix3
import org.rivem.core.project.RiverProject
import org.rivem.core.section.QualityLevel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * One-page PDF report (desktop's "Summary"): header, total discharge, per section the frame with the section and still
 * chevrons, the discharge / velocity / depth charts and the station table, the quality block when "Calidad e
 * incertidumbre" is on, and how it was measured. A4 width (595 pt), as tall as the content (at least A4). Always
 * light (printed).
 */
object ReportPDF {
    private const val PAGE_WIDTH = 595
    private const val MARGIN = 28f
    private const val CONTENT = PAGE_WIDTH - 2 * MARGIN

    private val black: Int = 0xFF000000.toInt()
    private val secondary: Int = 0xFF6E6E73.toInt()
    private val blue: Int = 0xFF007AFF.toInt()
    private val line: Int = 0xFFC7C7CC.toInt()

    /** Blocking: call it off the main thread. */
    fun make(context: Context, project: RiverProject, showQuality: Boolean): File {
        val results = ResultsPipeline.read(project)
        if (results.isEmpty()) throw PipelineError("No hay resultados de caudal para el informe.")
        val units = UnitDisplay(project.settings.unitSystem)
        val first = project.frameFiles.firstOrNull()
        val frame: Bitmap? = first?.let { ImageCoding.thumbnailBitmap(it, 1400) }
        val frameSize: Pair<Int, Int>? = first?.let { ImageCoding.pixelSize(it) }
        val assessments: List<SectionAssessment> = if (showQuality) QualityPipeline.assess(project, results) else emptyList()
        val inverse: Matrix3? = try { project.readMatrix()?.inverse() } catch (_: Exception) { null }
        val page = Page(project, results, assessments, units, frame, frameSize, inverse, AppLog.appVersion(context))

        val height = max(842f, page.draw(null))
        val folder = ExportPipeline.exportFolder(context)
        folder.mkdirs()
        val file = File(folder, "${ExportPipeline.baseName(project)}_informe.pdf")
        if (file.exists()) file.delete()
        val document = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, kotlin.math.ceil(height).toInt(), 1).create()
            val pdfPage = document.startPage(info)
            pdfPage.canvas.drawColor(0xFFFFFFFF.toInt())
            page.draw(pdfPage.canvas)
            document.finishPage(pdfPage)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
            frame?.recycle()
        }
        if (!file.exists()) throw PipelineError("no se pudo crear el PDF")
        AppLog.info("Informe PDF: ${file.name}")
        return file
    }

    /**
     * The page content. Every block is drawn by a function `(canvas, top) → height`; with a null canvas it only
     * measures, so the page height is known before the PDF page is created.
     */
    private class Page(
        val project: RiverProject,
        val results: List<SectionResult>,
        val assessments: List<SectionAssessment>,
        val units: UnitDisplay,
        val frame: Bitmap?,
        val frameSize: Pair<Int, Int>?,
        val inverse: Matrix3?,
        val appVersion: String,
    ) {
        val settings = project.settings

        fun draw(canvas: Canvas?): Float {
            var y = MARGIN
            y += header(canvas, y) + 14
            canvas?.drawLine(MARGIN, y, MARGIN + CONTENT, y, stroke(line, 0.5f))
            y += 14
            for (r in results) y += section(canvas, r, y) + 14
            y += method(canvas, y) + 14
            y += text(
                canvas, "Generado con RIVEM $appVersion · formato compatible con RIVeR escritorio ${RiverCore.DESKTOP_COMPATIBILITY}",
                MARGIN, y, 8f, color = secondary, width = CONTENT,
            )
            return y + MARGIN
        }

        // MARK: Drawing helpers

        fun paint(size: Float, bold: Boolean = false, color: Int = black, align: Paint.Align = Paint.Align.LEFT): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textAlign = align
            }

        fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        }

        /** Text wrapped to [width] (or one line at x when [align] is RIGHT / CENTER), top at [top]. Returns its height. */
        fun text(
            canvas: Canvas?,
            s: String,
            x: Float,
            top: Float,
            size: Float,
            bold: Boolean = false,
            color: Int = black,
            width: Float = CONTENT,
            align: Paint.Align = Paint.Align.LEFT,
        ): Float {
            if (align != Paint.Align.LEFT) {
                val p = paint(size, bold, color, align)
                canvas?.drawText(s, x, top - p.fontMetrics.ascent, p)
                return p.fontMetrics.descent - p.fontMetrics.ascent
            }
            val p = paint(size, bold, color)
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, max(1, width.toInt()))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (canvas != null) {
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            }
            return layout.height.toFloat()
        }

        fun lineHeight(size: Float, bold: Boolean = false): Float {
            val fm = paint(size, bold).fontMetrics
            return fm.descent - fm.ascent
        }

        // MARK: Blocks

        fun header(canvas: Canvas?, top: Float): Float {
            var y = top
            val title = settings.riverName ?: (project.folder.absoluteFile.parentFile?.name ?: "")
            val h20 = lineHeight(20f, true)
            text(canvas, settings.footage?.label ?: "", MARGIN + CONTENT, y + h20 - lineHeight(11f) - 2, 11f, color = secondary, align = Paint.Align.RIGHT)
            y += text(canvas, title, MARGIN, y, 20f, bold = true, width = CONTENT - 120) + 6
            val site = settings.site
            if (!site.isNullOrEmpty()) y += text(canvas, site, MARGIN, y, 11f) + 6
            y += text(canvas, "Aforo ${project.folder.name}", MARGIN, y, 10f, color = secondary) + 6
            // Total discharge (mean over sections, like desktop's summary) and facts.
            val qs = results.mapNotNull { it.totalQ }.filter { it.isFinite() }
            val total: Double? = if (qs.isEmpty()) null else qs.sum() / qs.size
            val big = paint(34f, true)
            val qText = Fmt.num(total?.let { units.discharge(it) }, 2)
            val hBig = lineHeight(34f, true)
            val baseline = y - big.fontMetrics.ascent
            canvas?.drawText(qText, MARGIN, baseline, big)
            var x = MARGIN + big.measureText(qText) + 4
            val unitPaint = paint(13f, color = secondary)
            canvas?.drawText(units.dischargeUnit, x, baseline, unitPaint)
            x += unitPaint.measureText(units.dischargeUnit) + 18
            val facts = listOf(
                "Secciones" to "${results.size}",
                "Estaciones" to "${results.firstOrNull()?.stationCount ?: 0}",
                "Medido" to Fmt.percent(results.firstOrNull()?.measuredQ, 1),
            )
            val factTop = baseline - lineHeight(8f) - lineHeight(11f, true)
            for ((title2, value) in facts) {
                text(canvas, title2, x, factTop, 8f, color = secondary, width = 90f)
                text(canvas, value, x, factTop + lineHeight(8f) + 1, 11f, bold = true, width = 90f)
                x += max(paint(8f).measureText(title2), paint(11f, true).measureText(value)) + 18
            }
            y += hBig
            return y - top
        }

        fun section(canvas: Canvas?, r: SectionResult, top: Float): Float {
            var y = top
            // Title line: name, Q ± σ (blue), measured share and α on the right.
            val namePaint = paint(13f, true)
            val h = lineHeight(13f, true)
            val baseline = y - namePaint.fontMetrics.ascent
            canvas?.drawText(r.name, MARGIN, baseline, namePaint)
            val qText = units.dischargeText(r.totalQ, 2) + (r.totalQStd?.let { " ± ${units.dischargeText(it, 2)}" } ?: "")
            val qPaint = paint(12f, true, blue)
            canvas?.drawText(qText, MARGIN + namePaint.measureText(r.name) + 8, baseline, qPaint)
            val right = paint(9f, color = secondary, align = Paint.Align.RIGHT)
            canvas?.drawText("${Fmt.percent(r.measuredQ, 1)} medido · α ${Fmt.num(r.alpha, 2)}", MARGIN + CONTENT, baseline, right)
            y += h + 8

            assessments.firstOrNull { it.name == r.name }?.let { a -> y += qualityBlock(canvas, a, y) + 8 }

            if (frame != null && frameSize != null && frameSize.first > 0 && frameSize.second > 0) {
                val w = CONTENT
                val hFrame = w * frameSize.second / frameSize.first
                if (canvas != null) {
                    val rect = RectF(MARGIN, y, MARGIN + w, y + hFrame)
                    canvas.save()
                    canvas.clipPath(Path().apply { addRoundRect(rect, 4f, 4f, Path.Direction.CW) })
                    canvas.drawBitmap(frame, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                    val layout = CanvasLayout(
                        Offset(rect.left, rect.top), w / frameSize.first,
                        Size(frameSize.first.toFloat(), frameSize.second.toFloat()),
                    )
                    ChevronLayer.draw(canvas, layout, listOf(r), inverse, ChevronLayer.stationRange(listOf(r)), 0.0, null, 1f, blue)
                    canvas.restore()
                }
                y += hFrame + 8
            }

            val style = ChartStyle(1f, secondary, line, 8f)
            val half = (CONTENT - 12) / 2
            if (canvas != null) {
                Charts.dischargeBars(canvas, RectF(MARGIN, y, MARGIN + half, y + 130), r, units, style)
                Charts.velocityProfile(canvas, RectF(MARGIN + half + 12, y, MARGIN + CONTENT, y + 130), r, units, style)
            }
            y += 130 + 8
            if (canvas != null) Charts.depthProfile(canvas, RectF(MARGIN, y, MARGIN + CONTENT, y + 110), r, units, style)
            y += 110 + 8
            y += stationTable(canvas, r, y)
            return y - top
        }

        /** Traffic light, Q ± U (95 %) and every criterion, like the Results screen. */
        fun qualityBlock(canvas: Canvas?, a: SectionAssessment, top: Float): Float {
            val inner = { c: Canvas?, t: Float ->
                var y = t
                val u = a.uncertainty
                // Traffic light (size 8) and the headline.
                val size = 8f
                val lightW = size * 3 + size * 0.3f * 2 + size * 0.8f
                val lightH = size + size * 0.6f
                if (c != null) {
                    val capsule = RectF(MARGIN + 8, y, MARGIN + 8 + lightW, y + lightH)
                    c.drawRoundRect(capsule, lightH / 2, lightH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBF000000.toInt() })
                    var cx = capsule.left + size * 0.4f + size / 2
                    for (l in listOf(QualityLevel.poor, QualityLevel.fair, QualityLevel.good)) {
                        val col = if (l == a.quality.overall) l.argb else ChartColors.withAlpha(l.argb, 0.15f)
                        c.drawCircle(cx, capsule.centerY(), size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
                        cx += size * 1.3f
                    }
                }
                val headline = "Calidad: ${QualityPipeline.levelText(a.quality.overall)}"
                val hx = MARGIN + 8 + lightW + 6
                text(c, headline, hx, y, 10f, bold = true, width = 200f)
                val qx = hx + paint(10f, true).measureText(headline) + 6
                val qText = "Q = ${units.dischargeText(u.discharge, 2)} ± ${units.dischargeText(u.expanded, 2)} (95 %, ${Fmt.percent(u.relative, 1)}), " +
                    "intervalo ${units.dischargeText(u.lower, 2)} – ${units.dischargeText(u.upper, 2)}"
                val hq = text(c, qText, qx, y + 1, 9f, width = MARGIN + CONTENT - 8 - qx)
                y += max(max(hq, lineHeight(10f, true)), lightH) + 3
                for (check in a.quality.checks) {
                    val lh = lineHeight(8f)
                    c?.drawCircle(MARGIN + 11, y + lh / 2, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = check.level.argb })
                    text(c, QualityPipeline.title(check.criterion), MARGIN + 19, y, 8f, width = CONTENT - 120)
                    text(c, QualityPipeline.valueText(check), MARGIN + CONTENT - 8, y, 8f, align = Paint.Align.RIGHT)
                    y += lh + 3
                }
                if (a.quality.doubtful.isNotEmpty()) {
                    val s = "Estaciones dudosas: " + a.quality.doubtful.joinToString("; ") { d ->
                        "${d.index + 1} (${d.reasons.joinToString(", ") { QualityPipeline.reasonText(it) }})"
                    }
                    y += text(c, s, MARGIN + 8, y, 8f, color = ChartColors.orange, width = CONTENT - 16) + 3
                }
                y - t
            }
            val h = inner(null, top + 8) + 16
            if (canvas != null) {
                canvas.drawRoundRect(RectF(MARGIN, top, MARGIN + CONTENT, top + h), 4f, 4f, stroke(ChartColors.withAlpha(0xFF8E8E93.toInt(), 0.4f), 0.5f))
                inner(canvas, top + 8)
            }
            return h
        }

        fun stationTable(canvas: Canvas?, r: SectionResult, top: Float): Float {
            var y = top
            val rowH = lineHeight(8f) + 2
            val gap = 4f
            val colW = (CONTENT - gap * 5) / 6
            fun row(cells: List<String>, bold: Boolean) {
                if (canvas != null) {
                    val left = paint(8f, bold)
                    val rightP = paint(8f, bold, align = Paint.Align.RIGHT)
                    val baseline = y - left.fontMetrics.ascent
                    cells.forEachIndexed { k, s ->
                        val x0 = MARGIN + k * (colW + gap)
                        if (k == 0) canvas.drawText(s, x0, baseline, left) else canvas.drawText(s, x0 + colW, baseline, rightP)
                    }
                }
                y += rowH
            }
            row(
                listOf("#", "x ${units.lengthUnit}", "d ${units.lengthUnit}", "A ${units.areaUnit}", "Vs ${units.velocityUnit}", "Q ${units.dischargeUnit}"),
                bold = true,
            )
            for (i in 0 until r.stationCount) {
                val unchecked = i < r.check.size && !r.check[i]
                row(
                    listOf(
                        "${i + 1}" + (if (unchecked) "*" else ""),
                        Fmt.num(r.value(r.distance, i)?.let { units.length(it) }, 2),
                        Fmt.num(r.value(r.depth, i)?.let { units.length(it) }, 2),
                        Fmt.num(r.value(r.area, i)?.let { units.area(it) }, 2),
                        Fmt.num(r.value(r.velocity, i)?.let { units.velocity(it) }, 2),
                        Fmt.num(r.value(r.q, i)?.let { units.discharge(it) }, 2),
                    ),
                    bold = false,
                )
            }
            if (r.check.contains(false)) y += text(canvas, "* estación excluida del caudal", MARGIN, y, 7f, color = secondary)
            return y - top
        }

        fun method(canvas: Canvas?, top: Float): Float {
            var y = top
            y += text(canvas, "Cómo se midió", MARGIN, y, 12f, bold = true) + 3
            val processing = settings.json["processing"]?.objectValue
            val ia1 = processing?.get("interrogation_area_1")?.doubleValue?.let { Fmt.num(it, 0) } ?: "—"
            val ia2 = processing?.get("interrogation_area_2")?.doubleValue?.let { Fmt.num(it, 0) } ?: "—"
            fun line(title: String, value: String) {
                text(canvas, title, MARGIN, y, 9f, color = secondary, width = 88f)
                y += max(text(canvas, value, MARGIN + 90, y, 9f, width = CONTENT - 90), lineHeight(9f)) + 3
            }
            line("Toma", settings.footage?.label ?: "—")
            line(
                "Frames",
                "${project.frameFiles.size}, paso ${settings.videoRange?.step ?: 1}, " +
                    "${Fmt.num(settings.video?.fps, 2)} fps, Δt ${Fmt.num(settings.frameInterval, 3)} s",
            )
            line("PIV", "LSPIV, ventanas $ia1 / $ia2 px")
            for (r in results) {
                line(
                    r.name,
                    "nivel ${units.lengthText(r.level, 3)}, ancho ${units.lengthText(r.totalW)}, área ${units.areaText(r.totalA)}, " +
                        "velocidad media ${units.velocityText(r.meanV, 2)}",
                )
            }
            return y - top
        }
    }
}
