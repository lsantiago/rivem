package org.rivem.app.steps.results

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rivem.app.common.VelocityColors
import org.rivem.app.pipeline.SectionResult
import org.rivem.app.pipeline.UnitDisplay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The charts of Results, the report and the site screen, drawn on an `android.graphics.Canvas` so the screen (Compose
 * `drawIntoCanvas`) and the PDF (`PdfDocument`) share one implementation (Swift Charts on iOS).
 *
 * @param d pixels per point (screen density; 1 in the PDF).
 */
class ChartStyle(val d: Float, val textColor: Int, val gridColor: Int, val fontSize: Float)

object ChartColors {
    val blue: Int = 0xFF007AFF.toInt()
    val cyan: Int = 0xFF32ADE6.toInt()
    val brown: Int = 0xFFA2845E.toInt()
    val orange: Int = 0xFFFF9500.toInt()
    val gray: Int = 0xFF8E8E93.toInt()
    val green: Int = 0xFF34C759.toInt()
    val red: Int = 0xFFFF3B30.toInt()

    fun withAlpha(color: Int, alpha: Float): Int = (((color ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}

/** Data → canvas mapping of a chart's plot area. */
class ChartMap(val plot: RectF, val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
    fun x(v: Double): Float = plot.left + ((v - x0) / (x1 - x0)).toFloat() * plot.width()
    fun y(v: Double): Float = plot.bottom - ((v - y0) / (y1 - y0)).toFloat() * plot.height()
}

object Charts {
    /** Ticks at 1, 2 or 5 × 10ⁿ covering [lo, hi]. */
    fun tickStep(lo: Double, hi: Double, target: Int = 4): Double {
        val span = max(hi - lo, 1e-12)
        val raw = span / target
        val p = 10.0.pow(floor(log10(raw)))
        val f = raw / p
        val nice = when {
            f <= 1 -> 1.0
            f <= 2 -> 2.0
            f <= 5 -> 5.0
            else -> 10.0
        }
        return nice * p
    }

    private fun tickText(v: Double, step: Double): String {
        val decimals = max(0, min(4, -floor(log10(step)).toInt()))
        val s = String.format(Locale.ROOT, "%.${decimals}f", v)
        return if (s == "-0" || s.matches(Regex("-0\\.0*"))) s.substring(1) else s
    }

    private fun textPaint(style: ChartStyle, color: Int = style.textColor): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = style.fontSize
    }

    /**
     * Grid, ticks and axis titles (y title above the plot, x title below), domain extended to whole ticks. Returns
     * the mapping of the plot area.
     */
    fun axes(
        canvas: Canvas,
        rect: RectF,
        xRange: Pair<Double, Double>,
        yRange: Pair<Double, Double>,
        xLabel: String,
        yLabel: String,
        style: ChartStyle,
    ): ChartMap {
        fun widen(r: Pair<Double, Double>): Pair<Double, Double> {
            var (lo, hi) = r
            if (!lo.isFinite() || !hi.isFinite()) return 0.0 to 1.0
            if (hi - lo < 1e-9) {
                val pad = max(abs(lo) * 0.1, 0.5)
                lo -= pad
                hi += pad
            }
            return lo to hi
        }
        val (xa, xb) = widen(xRange)
        val (ya, yb) = widen(yRange)
        val xs = tickStep(xa, xb, 5)
        val ys = tickStep(ya, yb, 4)
        val x0 = floor(xa / xs) * xs
        val x1 = ceil(xb / xs) * xs
        val y0 = floor(ya / ys) * ys
        val y1 = ceil(yb / ys) * ys
        val paint = textPaint(style)
        val yTicks = generateSequence(y0) { it + ys }.takeWhile { it <= y1 + ys * 1e-6 }.toList()
        val xTicks = generateSequence(x0) { it + xs }.takeWhile { it <= x1 + xs * 1e-6 }.toList()
        val labelWidth = yTicks.maxOfOrNull { paint.measureText(tickText(it, ys)) } ?: 0f
        val d = style.d
        val plot = RectF(
            rect.left + labelWidth + 5 * d,
            rect.top + style.fontSize * 1.7f,
            rect.right - max(paint.measureText(tickText(x1, xs)) / 2, 4 * d),
            rect.bottom - style.fontSize * 2.8f,
        )
        val map = ChartMap(plot, x0, x1, y0, y1)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.gridColor
            strokeWidth = 0.5f * d
            this.style = Paint.Style.STROKE
        }
        val fm = paint.fontMetrics
        paint.textAlign = Paint.Align.RIGHT
        for (t in yTicks) {
            val y = map.y(t)
            canvas.drawLine(plot.left, y, plot.right, y, grid)
            canvas.drawText(tickText(t, ys), plot.left - 3 * d, y - (fm.ascent + fm.descent) / 2, paint)
        }
        paint.textAlign = Paint.Align.CENTER
        for (t in xTicks) {
            val x = map.x(t)
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            canvas.drawText(tickText(t, xs), x, plot.bottom + 2 * d - fm.ascent, paint)
        }
        canvas.drawText(xLabel, (plot.left + plot.right) / 2, rect.bottom - fm.descent, paint)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(yLabel, rect.left, rect.top - fm.ascent, paint)
        return map
    }

    private fun empty(canvas: Canvas, rect: RectF, style: ChartStyle) {
        val paint = textPaint(style).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Sin datos", rect.centerX(), rect.centerY(), paint)
    }

    /**
     * Discharge per station as bars coloured by surface velocity (desktop's "Discharge per station"); unchecked
     * stations in grey.
     */
    fun dischargeBars(canvas: Canvas, rect: RectF, result: SectionResult, units: UnitDisplay, style: ChartStyle) {
        var top = 0.0
        for (i in 0 until result.stationCount) {
            if (!ChevronLayer.contributes(result, i)) continue
            result.value(result.velocity, i)?.let { top = max(top, it) }
        }
        class Bar(val x: Double, val q: Double, val color: Int)
        val bars = ArrayList<Bar>()
        for (i in 0 until result.stationCount) {
            val d = result.value(result.distance, i) ?: continue
            val q = result.value(result.q, i) ?: continue
            val v = result.value(result.velocity, i) ?: 0.0
            val color = if (result.isChecked(i)) VelocityColors.argb(if (top > 0) v / top else 0.0) else ChartColors.gray
            bars.add(Bar(units.length(d), units.discharge(q), color))
        }
        if (bars.isEmpty()) return empty(canvas, rect, style)
        val map = axes(
            canvas, rect,
            bars.minOf { it.x } to bars.maxOf { it.x },
            min(0.0, bars.minOf { it.q }) to max(0.0, bars.maxOf { it.q }),
            "Distancia (${units.lengthUnit})", "Caudal (${units.dischargeUnit})", style,
        )
        val width = min(10 * style.d, map.plot.width() / bars.size * 0.8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (b in bars) {
            paint.color = b.color
            val x = map.x(b.x)
            val y0 = map.y(0.0)
            val y1 = map.y(b.q)
            canvas.drawRect(x - width / 2, min(y0, y1), x + width / 2, max(y0, y1), paint)
        }
    }

    /** Surface velocity per station with its ±σ band. */
    fun velocityProfile(canvas: Canvas, rect: RectF, result: SectionResult, units: UnitDisplay, style: ChartStyle) {
        class Sample(val x: Double, val y: Double, val lo: Double, val hi: Double)
        val samples = ArrayList<Sample>()
        for (i in 0 until result.stationCount) {
            val d = result.value(result.distance, i) ?: continue
            val v = result.value(result.velocity, i) ?: continue
            val lo = result.value(result.minusStd, i) ?: v
            val hi = result.value(result.plusStd, i) ?: v
            samples.add(Sample(units.length(d), units.velocity(v), units.velocity(lo), units.velocity(hi)))
        }
        if (samples.isEmpty()) return empty(canvas, rect, style)
        val map = axes(
            canvas, rect,
            samples.minOf { it.x } to samples.maxOf { it.x },
            min(0.0, samples.minOf { min(it.lo, it.y) }) to max(0.0, samples.maxOf { max(it.hi, it.y) }),
            "Distancia (${units.lengthUnit})", "Velocidad (${units.velocityUnit})", style,
        )
        val band = Path()
        samples.forEachIndexed { k, s -> if (k == 0) band.moveTo(map.x(s.x), map.y(s.hi)) else band.lineTo(map.x(s.x), map.y(s.hi)) }
        for (s in samples.asReversed()) band.lineTo(map.x(s.x), map.y(s.lo))
        band.close()
        canvas.drawPath(band, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.withAlpha(ChartColors.blue, 0.15f) })
        val line = Path()
        samples.forEachIndexed { k, s -> if (k == 0) line.moveTo(map.x(s.x), map.y(s.y)) else line.lineTo(map.x(s.x), map.y(s.y)) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartColors.blue
            this.style = Paint.Style.STROKE
            strokeWidth = 1.6f * style.d
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(line, stroke)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.blue }
        for (s in samples) canvas.drawCircle(map.x(s.x), map.y(s.y), 2.2f * style.d, dot)
    }

    /** Bed profile below the water surface (depth negative). */
    fun depthProfile(canvas: Canvas, rect: RectF, result: SectionResult, units: UnitDisplay, style: ChartStyle) {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in 0 until result.stationCount) {
            val d = result.value(result.distance, i) ?: continue
            val h = result.value(result.depth, i) ?: continue
            xs.add(units.length(d))
            ys.add(-units.length(h))
        }
        if (xs.isEmpty()) return empty(canvas, rect, style)
        val map = axes(
            canvas, rect, xs.min() to xs.max(), min(0.0, ys.min()) to 0.0,
            "Distancia (${units.lengthUnit})", "Profundidad (${units.lengthUnit})", style,
        )
        val area = Path()
        area.moveTo(map.x(xs[0]), map.y(0.0))
        for (k in xs.indices) area.lineTo(map.x(xs[k]), map.y(ys[k]))
        area.lineTo(map.x(xs.last()), map.y(0.0))
        area.close()
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.withAlpha(ChartColors.cyan, 0.3f) })
        val line = Path()
        for (k in xs.indices) if (k == 0) line.moveTo(map.x(xs[k]), map.y(ys[k])) else line.lineTo(map.x(xs[k]), map.y(ys[k]))
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartColors.brown
            this.style = Paint.Style.STROKE
            strokeWidth = 1.6f * style.d
            strokeJoin = Paint.Join.ROUND
        })
    }

    /** Point of the rating-curve chart (display units). */
    class RatingPointMark(val q: Double, val h: Double, val included: Boolean)

    /** Stage–discharge chart: the fitted curve (accent) and the gaugings (orange, grey when left out). */
    fun ratingCurve(
        canvas: Canvas,
        rect: RectF,
        points: List<RatingPointMark>,
        curve: List<Pair<Double, Double>>,
        units: UnitDisplay,
        accent: Int,
        style: ChartStyle,
    ) {
        val allQ = points.map { it.q } + curve.map { it.first }
        val allH = points.map { it.h } + curve.map { it.second }
        if (allQ.isEmpty()) return empty(canvas, rect, style)
        val map = axes(
            canvas, rect, min(0.0, allQ.min()) to allQ.max(), allH.min() to allH.max(),
            "Caudal (${units.dischargeUnit})", "Nivel (${units.lengthUnit})", style,
        )
        if (curve.size >= 2) {
            val path = Path()
            curve.forEachIndexed { k, p -> if (k == 0) path.moveTo(map.x(p.first), map.y(p.second)) else path.lineTo(map.x(p.first), map.y(p.second)) }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                this.style = Paint.Style.STROKE
                strokeWidth = 2f * style.d
                strokeJoin = Paint.Join.ROUND
            })
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (p in points) {
            dot.color = if (p.included) ChartColors.orange else ChartColors.gray
            canvas.drawCircle(map.x(p.q), map.y(p.h), 4.5f * style.d, dot)
        }
    }
}

/** A chart drawn with [Charts] in Compose, in the theme's text colour. */
@Composable
fun ChartView(height: Dp, modifier: Modifier = Modifier, draw: (Canvas, RectF, ChartStyle) -> Unit) {
    val density = LocalDensity.current
    val text = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f).toArgb()
    val style = ChartStyle(density.density, text, grid, 10f * density.density * density.fontScale)
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp, vertical = 6.dp)) {
        drawIntoCanvas { c -> draw(c.nativeCanvas, RectF(0f, 0f, size.width, size.height), style) }
    }
}
