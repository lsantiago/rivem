package org.rivem.app.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rivem.app.pipeline.Fmt
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.project.PIVResults
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Velocity colour scale of the Results screen, desktop's `createColorMap` (`commons/vectors.ts`): light blue →
 * green → orange-yellow → red-orange at equal spacing, for t in 0…1.
 */
object VelocityColors {
    private class Stop(val t: Double, val r: Double, val g: Double, val b: Double)

    private val stops: List<Stop> = listOf(
        Stop(0.0, 108.0 / 255, 212.0 / 255, 255.0 / 255),
        Stop(1.0 / 3, 98.0 / 255, 198.0 / 255, 85.0 / 255),
        Stop(2.0 / 3, 245.0 / 255, 191.0 / 255, 97.0 / 255),
        Stop(1.0, 237.0 / 255, 107.0 / 255, 87.0 / 255),
    )

    /** (r, g, b) in 0…1. */
    private fun rgb(t: Double): Triple<Double, Double, Double> {
        val x = if (t.isFinite()) min(max(t, 0.0), 1.0) else 0.0
        for (k in 1 until stops.size) {
            if (x <= stops[k].t) {
                val a = stops[k - 1]
                val b = stops[k]
                val f = (x - a.t) / max(b.t - a.t, 1e-9)
                return Triple(a.r + f * (b.r - a.r), a.g + f * (b.g - a.g), a.b + f * (b.b - a.b))
            }
        }
        val last = stops.last()
        return Triple(last.r, last.g, last.b)
    }

    fun color(t: Double): Color {
        val (r, g, b) = rgb(t)
        return Color(r.toFloat(), g.toFloat(), b.toFloat())
    }

    /** Opaque ARGB for `android.graphics` drawing. */
    fun argb(t: Double): Int {
        val (r, g, b) = rgb(t)
        return (0xFF shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    }

    val gradient: Brush
        get() = Brush.horizontalGradient(*stops.map { it.t.toFloat() to Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) }.toTypedArray())
}

/** One arrow of a PIV field and its speed (m/s, or px per pair without calibration). */
data class QuiverVector(val a: Point2, val b: Point2, val speed: Double)

data class QuiverField(val vectors: List<QuiverVector>, val range: ClosedFloatingPointRange<Double>)

/** Builds the arrows of a PIV field (median or one pair) for the canvas, with their speed. */
object QuiverBuilder {
    /** Arrow scale so the median vector is ~4 % of the frame width. */
    fun scale(piv: PIVResults, imageWidth: Double): Double {
        val magnitudes = ArrayList<Double>()
        val n = min(piv.uMedian.size, piv.vMedian.size)
        for (k in 0 until n) {
            val m = hypot(piv.uMedian[k], piv.vMedian[k])
            if (m.isFinite()) magnitudes.add(m)
        }
        if (magnitudes.isEmpty()) return 10.0
        magnitudes.sort()
        val typical = max(magnitudes[magnitudes.size / 2], 1e-6)
        return min(max(0.04 * imageWidth / typical, 1.0), 200.0)
    }

    /**
     * Arrows of the median field (`pair == null`) or of one pair, at most ~1200, with speed in m/s (or px per pair
     * without matrix / frame interval).
     */
    fun vectors(piv: PIVResults, pair: Int?, scale: Double, matrix: Matrix3?, frameInterval: Double?): List<QuiverVector> {
        val us: List<Double>
        val vs: List<Double>
        if (pair != null && pair >= 0 && pair < piv.u.size && pair < piv.v.size) {
            us = piv.u[pair]
            vs = piv.v[pair]
        } else {
            us = piv.uMedian
            vs = piv.vMedian
        }
        val n = minOf(piv.x.size, piv.y.size, us.size, vs.size)
        val stride = max(1, ceil(sqrt(n.toDouble() / 1200)).toInt())
        val out = ArrayList<QuiverVector>()
        var row = 0
        while (row < piv.rows) {
            var col = 0
            while (col < piv.columns) {
                val k = row * piv.columns + col
                col += stride
                if (k >= n) continue
                val x = piv.x[k]
                val y = piv.y[k]
                val u = us[k]
                val v = vs[k]
                if (!(x.isFinite() && y.isFinite() && u.isFinite() && v.isFinite())) continue
                var speed = hypot(u, v)
                if (matrix != null && frameInterval != null && frameInterval > 0) {
                    speed = matrix.apply(Point2(x, y)).distanceTo(matrix.apply(Point2(x + u, y + v))) / frameInterval
                }
                if (!speed.isFinite()) continue
                out.add(QuiverVector(Point2(x, y), Point2(x + u * scale, y + v * scale), speed))
            }
            row += stride
        }
        return out
    }

    /** 2nd–98th percentile of the speeds (colour range). */
    fun range(vectors: List<QuiverVector>): ClosedFloatingPointRange<Double> {
        val speeds = vectors.map { it.speed }.sorted()
        if (speeds.isEmpty()) return 0.0..1.0
        val lo = speeds[((speeds.size - 1) * 0.02).toInt()]
        val hi = max(speeds[((speeds.size - 1) * 0.98).toInt()], lo + 1e-6)
        return lo..hi
    }

    fun segments(vectors: List<QuiverVector>, range: ClosedFloatingPointRange<Double>): List<CanvasSegment> {
        val lo = range.start
        val span = max(range.endInclusive - lo, 1e-9)
        return vectors.map { f -> CanvasSegment(f.a, f.b, VelocityColors.color((f.speed - lo) / span), width = 1.4f, arrow = true) }
    }

    /** Median field, scaled so the median vector is ~4 % of the frame width, with its colour range. */
    fun quiver(piv: PIVResults, imageWidth: Double, matrix: Matrix3?, frameInterval: Double?): QuiverField {
        val s = scale(piv, imageWidth)
        val v = vectors(piv, null, s, matrix, frameInterval)
        return QuiverField(v, range(v))
    }
}

/** Colour bar with the range and unit, drawn over the canvas. */
@Composable
fun VelocityLegend(range: ClosedFloatingPointRange<Double>, units: UnitDisplay, modifier: Modifier = Modifier) {
    val lo = Fmt.num(units.velocity(range.start), 2)
    val hi = Fmt.num(units.velocity(range.endInclusive), 2)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
    Row(
        modifier.padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = "Escala de velocidad de $lo a $hi ${units.velocityUnit}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lo, style = style)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(80.dp).height(6.dp).background(VelocityColors.gradient, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text(hi, style = style)
        Spacer(Modifier.width(6.dp))
        Text(units.velocityUnit, style = style.copy(color = Color.White.copy(alpha = 0.7f)))
    }
}
