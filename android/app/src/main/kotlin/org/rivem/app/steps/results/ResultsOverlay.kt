package org.rivem.app.steps.results

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rivem.app.common.CanvasLayout
import org.rivem.app.common.VelocityColors
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.SectionResult
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.section.DischargeUncertainty
import org.rivem.core.section.QualityLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A station of a section, as selected on the canvas. */
data class StationRef(val section: Int, val station: Int)

/** Kotlin's `%` keeps the sign like Swift's `truncatingRemainder`. */
private fun Double.rem1(): Double = this % 1.0

/**
 * Animated chevrons along each section: one group per station with velocity, pointing downstream
 * (`streamwise_x/y`), moving at a speed proportional to the surface velocity and coloured on `range`.
 * Unchecked stations are grey. The section line and its banks (I red, D green) are drawn underneath.
 *
 * Drawn on an `android.graphics.Canvas` (screen: `drawIntoCanvas`; report: `PdfDocument`); [d] is pixels per point.
 */
object ChevronLayer {
    private val bankRed: Int = 0xFFFF3B30.toInt()
    private val bankGreen: Int = 0xFF34C759.toInt()
    private val yellow: Int = 0xFFFFCC00.toInt()
    private val gray: Int = 0xFF8E8E93.toInt()

    fun draw(
        canvas: Canvas,
        layout: CanvasLayout,
        results: List<SectionResult>,
        worldToPixel: Matrix3?,
        range: ClosedFloatingPointRange<Double>,
        time: Double,
        selected: StationRef?,
        d: Float,
        accent: Int,
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        for ((si, r) in results.withIndex()) {
            val n = min(r.stationX.size, r.stationY.size)
            if (n < 2) continue
            val a = layout.view(Point2(r.stationX[0], r.stationY[0]))
            val b = layout.view(Point2(r.stationX[n - 1], r.stationY[n - 1]))
            stroke.color = ChartColors.withAlpha(0xFF000000.toInt(), 0.35f)
            stroke.strokeWidth = 5 * d
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
            stroke.color = yellow
            stroke.strokeWidth = 2.5f * d
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
            bank(canvas, a, "I", bankRed, d)
            bank(canvas, b, "D", bankGreen, d)

            if (worldToPixel != null && r.east.size >= n && r.north.size >= n && r.distance.size >= n) {
                drawGlyphs(canvas, layout, r, si, worldToPixel, range, time, selected, d, accent)
                continue
            }
            val span = max(range.endInclusive - range.start, 1e-9)
            for (i in 0 until minOf(n, r.streamX.size, r.streamY.size)) {
                if (!contributes(r, i)) continue
                val v = r.value(r.velocity, i) ?: continue
                if (!(r.stationX[i].isFinite() && r.stationY[i].isFinite() && r.streamX[i].isFinite() && r.streamY[i].isFinite())) continue
                val p = layout.view(Point2(r.stationX[i], r.stationY[i]))
                val q = layout.view(Point2(r.stationX[i] + r.streamX[i], r.stationY[i] + r.streamY[i]))
                val length = hypot(q.x - p.x, q.y - p.y)
                if (length <= 1e-6f) continue
                val u = Offset((q.x - p.x) / length, (q.y - p.y) / length)
                val t = (v - range.start) / span
                val color = if (r.isChecked(i)) VelocityColors.argb(t) else gray
                chevrons(canvas, p, u, color, 5 + 28 * min(max(t, 0.0), 1.0), time, d)
                if (selected == StationRef(si, i)) marker(canvas, p, i + 1, d, accent)
            }
        }
    }

    /**
     * Desktop's glyph (`drawVectors.ts` + `chevronGlyph.ts`): built in real-world metres at the station and projected
     * through the homography, so perspective is exact. Arrow length ∝ |v| (2 station spacings at the top of the
     * scale), glyph width half a spacing, up to 6 filled chevrons at a constant pitch, travelling along the streamwise
     * direction with one 1.7 s period for every station (so apparent speed ∝ v) and a per-station phase offset,
     * fading in and out with sin(πf), with a soft shadow. Static (time 0, animations off, report): evenly laid out.
     */
    private fun drawGlyphs(
        canvas: Canvas,
        layout: CanvasLayout,
        r: SectionResult,
        si: Int,
        inverse: Matrix3,
        range: ClosedFloatingPointRange<Double>,
        time: Double,
        selected: StationRef?,
        d: Float,
        accent: Int,
    ) {
        val n = minOf(r.east.size, r.north.size, r.distance.size)
        if (n < 2) return
        val spacing = abs(r.distance[n - 1] - r.distance[0]) / (n - 1)
        val maxLength = spacing * 2
        val width = spacing * 0.5
        val thickness = width * 0.16 * 3.3
        val halfWing = width * 0.7
        val back = width * 0.5
        val top = maxOf(abs(range.start), abs(range.endInclusive), 1e-9)
        val span = max(range.endInclusive - range.start, 1e-9)
        val animated = time != 0.0
        val phase = if (animated) (time / 1.7).rem1() else 0.0
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * d
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (i in 0 until n) {
            if (!contributes(r, i)) continue
            val v = r.value(r.velocity, i) ?: continue
            if (!(r.east[i].isFinite() && r.north[i].isFinite())) continue
            val checked = r.isChecked(i)
            if (!checked && !r.interpolated) continue
            val length = abs(v) / top * maxLength
            if (length <= 0) continue
            // Across: the section tangent. Along: RIVeR's streamwise vector (perpendicular as fallback), reversed for v < 0.
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            var tx = r.east[b] - r.east[a]
            var ty = r.north[b] - r.north[a]
            val tl = hypot(tx, ty)
            if (tl > 0) {
                tx /= tl
                ty /= tl
            } else {
                tx = 1.0
                ty = 0.0
            }
            var dx = 0.0
            var dy = 0.0
            if (i < r.streamEast.size && i < r.streamNorth.size && r.streamEast[i].isFinite() && r.streamNorth[i].isFinite()) {
                val l = hypot(r.streamEast[i], r.streamNorth[i])
                if (l > 0) {
                    dx = r.streamEast[i] / l
                    dy = r.streamNorth[i] / l
                }
            }
            if (dx == 0.0 && dy == 0.0) {
                dx = -ty
                dy = tx
            }
            if (v < 0) {
                dx = -dx
                dy = -dy
            }
            val east0 = r.east[i]
            val north0 = r.north[i]
            fun place(along: Double, across: Double): Offset =
                layout.view(inverse.apply(Point2(east0 + dx * along + tx * across, north0 + dy * along + ty * across)))
            val base = place(0.0, 0.0)
            val tip = place(length, 0.0)
            if (hypot(tip.x - base.x, tip.y - base.y) < 2f) continue
            val color = if (checked) VelocityColors.argb((v - range.start) / span) else gray
            val count = max(1, min(6, (length / (width * 0.8)).roundToInt()))
            val offset = (i * 0.618).rem1()
            val isSelected = selected == StationRef(si, i)
            for (k in 0 until count) {
                val f = if (animated) (phase + offset + k.toDouble() / count).rem1() else (k + 0.5) / count
                val apex = max(0.0, length * f)
                val wing = max(0.0, apex - back)
                val innerApex = max(0.0, apex - thickness)
                val innerWing = max(0.0, wing - thickness)
                path.reset()
                place(wing, halfWing).let { path.moveTo(it.x, it.y) }
                place(apex, 0.0).let { path.lineTo(it.x, it.y) }
                place(wing, -halfWing).let { path.lineTo(it.x, it.y) }
                place(innerWing, -halfWing).let { path.lineTo(it.x, it.y) }
                place(innerApex, 0.0).let { path.lineTo(it.x, it.y) }
                place(innerWing, halfWing).let { path.lineTo(it.x, it.y) }
                path.close()
                val fade = sin(PI * f)
                val fillOpacity = if (isSelected) 1.0 else if (animated) fade * 0.95 else 0.85
                val strokeOpacity = if (isSelected) 1.0 else if (animated) fade * 0.5 else 0.5
                fill.color = ChartColors.withAlpha(color, fillOpacity.toFloat())
                fill.setShadowLayer(1.4f * d, 0f, 1f * d, ChartColors.withAlpha(0xFF000000.toInt(), (0.45 * max(fillOpacity, 0.0)).toFloat()))
                canvas.drawPath(path, fill)
                edge.color = ChartColors.withAlpha(color, strokeOpacity.toFloat())
                canvas.drawPath(path, edge)
            }
            if (isSelected) marker(canvas, base, i + 1, d, accent)
        }
    }

    /** A station with water and a velocity: dry bank stations (depth 0) carry no discharge and get no chevron. */
    fun contributes(r: SectionResult, i: Int): Boolean {
        if (r.value(r.velocity, i) == null) return false
        val depth = r.value(r.depth, i)
        return depth == null || depth > 0
    }

    /** Three chevrons travelling through the station, fading in and out (sections without world coordinates). */
    private fun chevrons(canvas: Canvas, p: Offset, u: Offset, color: Int, speed: Double, time: Double, d: Float) {
        val spacing = 11f * d
        val size = 6f * d
        val normal = Offset(-u.y, u.x)
        val phase = ((time * speed * d) % spacing.toDouble()).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (k in 0 until 3) {
            val s = (k - 1) * spacing + phase - spacing / 2
            val fade = max(0f, 1 - abs(s) / (spacing * 1.6f))
            val c = Offset(p.x + u.x * s, p.y + u.y * s)
            path.reset()
            path.moveTo(c.x - u.x * size * 0.5f + normal.x * size, c.y - u.y * size * 0.5f + normal.y * size)
            path.lineTo(c.x + u.x * size * 0.6f, c.y + u.y * size * 0.6f)
            path.lineTo(c.x - u.x * size * 0.5f - normal.x * size, c.y - u.y * size * 0.5f - normal.y * size)
            paint.color = ChartColors.withAlpha(0xFF000000.toInt(), 0.35f * fade)
            paint.strokeWidth = 4.5f * d
            canvas.drawPath(path, paint)
            paint.color = ChartColors.withAlpha(color, fade)
            paint.strokeWidth = 2.8f * d
            canvas.drawPath(path, paint)
        }
    }

    private fun centeredText(canvas: Canvas, text: String, at: Offset, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(text, at.x, at.y - (fm.ascent + fm.descent) / 2, paint)
    }

    private fun bank(canvas: Canvas, p: Offset, label: String, color: Int, d: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(p.x, p.y, 8 * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(p.x, p.y, 8 * d, paint)
        centeredText(canvas, label, p, 9 * d, 0xFFFFFFFF.toInt())
    }

    private fun marker(canvas: Canvas, p: Offset, number: Int, d: Float, accent: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.withAlpha(0xFF000000.toInt(), 0.6f) }
        canvas.drawCircle(p.x, p.y, 10 * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = accent
        canvas.drawCircle(p.x, p.y, 10 * d, paint)
        centeredText(canvas, "$number", p, 10 * d, 0xFFFFFFFF.toInt())
    }

    /** Nearest station to an image point, within [radius] image pixels. */
    fun hit(point: Point2, results: List<SectionResult>, radius: Double): StationRef? {
        var best: StationRef? = null
        var bestDistance = radius
        for ((si, r) in results.withIndex()) {
            for (i in 0 until min(r.stationX.size, r.stationY.size)) {
                val dd = hypot(r.stationX[i] - point.x, r.stationY[i] - point.y)
                if (dd.isFinite() && dd < bestDistance) {
                    bestDistance = dd
                    best = StationRef(si, i)
                }
            }
        }
        return best
    }

    /**
     * 0 … the highest velocity of the stations that carry discharge (desktop's results scale: bank stations with
     * zero depth are left out, as desktop shows them without value).
     */
    fun stationRange(results: List<SectionResult>): ClosedFloatingPointRange<Double> {
        var top = 0.0
        for (r in results) {
            for (i in 0 until r.stationCount) {
                if (!contributes(r, i)) continue
                r.value(r.velocity, i)?.let { top = max(top, it) }
            }
        }
        return 0.0..max(top, 0.01)
    }
}

/**
 * Card of a tapped station (desktop's station tooltip): discharge and share, depth and area, bed stage, surface
 * velocity ± σ and its 5–95 % band.
 */
@Composable
fun StationCard(result: SectionResult, station: Int, units: UnitDisplay, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val i = station
    Surface(
        modifier.padding(8.dp).widthIn(max = 360.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estación ${i + 1}", style = MaterialTheme.typography.titleMedium)
                result.value(result.distance, i)?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("x ${units.lengthText(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cerrar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CardRow("Caudal", dischargeText(result, i, units))
            CardRow("Profundidad · área", "${units.lengthText(result.value(result.depth, i))} · ${units.areaText(result.value(result.area, i))}")
            val level = result.level
            val depth = result.value(result.depth, i)
            CardRow("Cota del fondo", units.lengthText(if (level != null && depth != null) level - depth else null))
            CardRow("Velocidad superficial", velocityText(result, i, units))
            val lo = result.value(result.p5, i)
            val hi = result.value(result.p95, i)
            if (lo != null && hi != null) {
                CardRow("P5–P95", "${Fmt.num(units.velocity(lo), 2)}–${Fmt.num(units.velocity(hi), 2)} ${units.velocityUnit}")
            }
            if (i < result.check.size && !result.check[i]) {
                Text("Estación desmarcada: no entra en el caudal.", style = MaterialTheme.typography.bodySmall, color = QualityColors.orange)
            }
        }
    }
}

@Composable
private fun CardRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            textAlign = TextAlign.End,
        )
    }
}

private fun dischargeText(result: SectionResult, i: Int, units: UnitDisplay): String {
    val q = result.value(result.q, i) ?: return "—"
    var text = units.dischargeText(q, 2)
    val total = result.totalQ
    if (total != null && total.isFinite() && abs(total) > 1e-12) text += " · " + Fmt.percent(q / total, 1)
    return text
}

private fun velocityText(result: SectionResult, i: Int, units: UnitDisplay): String {
    val v = result.value(result.velocity, i) ?: return "—"
    var text = units.velocityText(v, 2)
    result.value(result.plusStd, i)?.let { hi -> text += " ± " + Fmt.num(units.velocity(abs(hi - v)), 2) }
    return text
}

/** Big total discharge with the measured / interpolated share (desktop's results header). */
@Composable
fun DischargeHero(
    result: SectionResult,
    units: UnitDisplay,
    showName: Boolean,
    uncertainty: DischargeUncertainty? = null,
    quality: QualityLevel? = null,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        if (showName) Text(result.name, style = MaterialTheme.typography.labelMedium, color = secondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Fmt.num(result.totalQ?.let { units.discharge(it) }, 2),
                style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(units.dischargeUnit, style = MaterialTheme.typography.titleMedium, color = secondary, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                val interpolatedShare = result.interpolatedQ ?: result.measuredQ?.let { 1 - it }
                Text("${Fmt.percent(result.measuredQ, 1)} medido", style = MaterialTheme.typography.labelSmall, color = secondary)
                Text("${Fmt.percent(interpolatedShare, 1)} interpolado", style = MaterialTheme.typography.labelSmall, color = secondary)
            }
        }
        if (uncertainty != null && uncertainty.expanded.isFinite()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (quality != null) {
                    Icon(quality.icon, contentDescription = null, tint = quality.color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "± ${units.dischargeText(uncertainty.expanded, 2)} (95 %, ${Fmt.percent(uncertainty.relative, 0)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val std = result.totalQStd
        if (std != null && std.isFinite()) {
            Text("± ${units.dischargeText(std, 2)} (desvío entre pares)", style = MaterialTheme.typography.bodySmall, color = secondary)
        }
    }
}
