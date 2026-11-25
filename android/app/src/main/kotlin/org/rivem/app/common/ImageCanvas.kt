package org.rivem.app.common

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import org.rivem.core.geometry.Point2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point drawn on the canvas, in frame pixel coordinates. */
data class CanvasPoint(val id: Int, val point: Point2, val label: String, val color: Color)

/** A segment (section line, vector, residual) in frame pixel coordinates. [width] is in dp. */
data class CanvasSegment(
    val a: Point2,
    val b: Point2,
    val color: Color,
    val width: Float = 2f,
    val dashed: Boolean = false,
    val arrow: Boolean = false,
)

/** Maps frame pixels ↔ view pixels for a given zoom/offset. */
class CanvasLayout(val origin: Offset, /** View pixels per frame pixel. */ val scale: Float, val imageSize: Size) {
    companion object {
        /** The frame fitted (aspect) in [viewSize], times [zoom], centred and moved by [offset] (view pixels). */
        fun fit(viewSize: Size, imageSize: Size, zoom: Float, offset: Offset): CanvasLayout {
            var fit = 1f
            if (imageSize.width > 0f && imageSize.height > 0f) {
                fit = min(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
            }
            val scale = fit * zoom
            val w = imageSize.width * scale
            val h = imageSize.height * scale
            return CanvasLayout(Offset((viewSize.width - w) / 2 + offset.x, (viewSize.height - h) / 2 + offset.y), scale, imageSize)
        }
    }

    val imageRect: Rect get() = Rect(origin, Size(imageSize.width * scale, imageSize.height * scale))

    fun view(p: Point2): Offset = Offset(origin.x + p.x.toFloat() * scale, origin.y + p.y.toFloat() * scale)

    fun image(v: Offset): Point2 {
        if (scale <= 0f) return Point2(0.0, 0.0)
        return Point2(((v.x - origin.x) / scale).toDouble(), ((v.y - origin.y) / scale).toDouble())
    }
}

/** Zoom (1…20) and pan of an [ImageCanvas]; hoist it to reset or drive the view from outside. */
@Stable
class ImageCanvasState {
    var zoom: Float by mutableFloatStateOf(1f)
    var offset: Offset by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = 1f
        offset = Offset.Zero
    }

    /** Zoom about the view centre (the zoom buttons). */
    fun zoomTo(z: Float) {
        val clamped = z.coerceIn(1f, 20f)
        val ratio = clamped / zoom
        offset = Offset(offset.x * ratio, offset.y * ratio)
        zoom = clamped
    }

    /** Zoom keeping the frame point under [centroid] in place (pinch). */
    fun zoomAround(viewSize: Size, imageSize: Size, centroid: Offset, factor: Float) {
        val old = CanvasLayout.fit(viewSize, imageSize, zoom, offset)
        if (old.scale <= 0f || zoom <= 0f) return
        val newZoom = (zoom * factor).coerceIn(1f, 20f)
        val newScale = old.scale / zoom * newZoom
        val px = (centroid.x - old.origin.x) / old.scale
        val py = (centroid.y - old.origin.y) / old.scale
        val ox = centroid.x - px * newScale
        val oy = centroid.y - py * newScale
        offset = Offset(ox - (viewSize.width - imageSize.width * newScale) / 2, oy - (viewSize.height - imageSize.height * newScale) / 2)
        zoom = newZoom
    }
}

private class Loupe(val finger: Offset, val target: Point2, val color: Color)

private const val LOUPE_MAGNIFICATION = 4f

/**
 * Zoomable, pannable frame with points, segments and an optional overlay (mask). One finger: tap places a point
 * ([onTap]), dragging a point moves it ([onMove]), dragging elsewhere pans; two fingers zoom. While a point is
 * dragged a 4× loupe above the finger shows it magnified with a crosshair (the finger hides the point itself).
 * Coordinates handed to the callbacks are frame pixels (x right, y down), the space of every desktop geometry file.
 *
 * @param image display image (may be a downscaled copy of the frame).
 * @param imageSize size of the full-resolution frame.
 * @param animated layer redrawn every frame (≤ 30 fps) on top of everything else, given the elapsed time in seconds.
 *   With animations turned off in the system settings it is drawn once with time 0 (a still frame).
 */
@Composable
fun ImageCanvas(
    image: ImageBitmap?,
    imageSize: IntSize,
    modifier: Modifier = Modifier,
    points: List<CanvasPoint> = emptyList(),
    segments: List<CanvasSegment> = emptyList(),
    overlay: ImageBitmap? = null,
    onTap: ((Point2) -> Unit)? = null,
    onMove: ((Int, Point2) -> Unit)? = null,
    animated: (DrawScope.(layout: CanvasLayout, time: Double) -> Unit)? = null,
    state: ImageCanvasState = remember { ImageCanvasState() },
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
    val imgSize = Size(imageSize.width.toFloat(), imageSize.height.toFloat())
    val currentImageSize by rememberUpdatedState(imgSize)
    val currentPoints by rememberUpdatedState(points)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMove by rememberUpdatedState(onMove)
    var loupe by remember { mutableStateOf<Loupe?>(null) }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var time by remember { mutableDoubleStateOf(0.0) }

    val animating = animated != null && !reduceMotion
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (t - last >= 33_000_000L) {
                    last = t
                    time = t / 1e9
                }
            }
        }
    }

    Box(modifier.clipToBounds().background(Color.Black).onSizeChanged { viewSize = it.toSize() }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val view = Size(size.width.toFloat(), size.height.toFloat())
                    val img = currentImageSize
                    val startLayout = CanvasLayout.fit(view, img, state.zoom, state.offset)
                    val hit: Int? = if (currentOnMove != null) hitPoint(down.position, startLayout, currentPoints, 30.dp.toPx()) else null
                    val startOffset = state.offset
                    val slop = 8.dp.toPx()
                    var moved = false
                    var pinching = false
                    var lastPosition = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            val up = event.changes.firstOrNull()?.position ?: lastPosition
                            if (!moved && !pinching && hit == null) {
                                state.offset = startOffset
                                val layout = CanvasLayout.fit(view, img, state.zoom, startOffset)
                                val p = layout.image(up)
                                if (p.x >= 0 && p.y >= 0 && p.x <= img.width && p.y <= img.height) currentOnTap?.invoke(p)
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            pinching = true
                            loupe = null
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid != Offset.Unspecified) state.zoomAround(view, img, centroid, zoomChange)
                            state.offset = state.offset + pan
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pinching) continue
                        val change = pressed.first()
                        lastPosition = change.position
                        if (hit != null) {
                            val layout = CanvasLayout.fit(view, img, state.zoom, state.offset)
                            val p = clamp(layout.image(change.position), img)
                            if (change.positionChanged()) moved = true
                            currentOnMove?.invoke(hit, p)
                            loupe = Loupe(change.position, p, currentPoints.firstOrNull { it.id == hit }?.color ?: Color.Yellow)
                            change.consume()
                        } else {
                            val total = change.position - down.position
                            if (moved || total.getDistance() >= slop) {
                                moved = true
                                state.offset = startOffset + total
                                change.consume()
                            }
                        }
                    }
                    loupe = null
                }
            },
        ) {
            val layout = CanvasLayout.fit(size, imgSize, state.zoom, state.offset)
            drawFrame(layout, image, overlay, segments, points, includePoints = true, textMeasurer = textMeasurer)
            animated?.invoke(this, layout, if (animating) time else 0.0)
        }

        loupe?.let { l ->
            val d = 120.dp
            val dPx = with(density) { d.toPx() }
            val above = l.finger.y - dPx * 1.1f > 0
            val cx = if (viewSize.width >= dPx) l.finger.x.coerceIn(dPx / 2, viewSize.width - dPx / 2) else viewSize.width / 2
            val cy = if (above) l.finger.y - dPx * 0.95f else l.finger.y + dPx * 0.95f
            Box(
                Modifier.offset { IntOffset((cx - dPx / 2).roundToInt(), (cy - dPx / 2).roundToInt()) }
                    .size(d)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(2.dp, Color.White, CircleShape),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val main = CanvasLayout.fit(viewSize, imgSize, state.zoom, state.offset)
                    val scale = main.scale * LOUPE_MAGNIFICATION
                    val center = Offset(size.width / 2, size.height / 2)
                    val origin = Offset(center.x - l.target.x.toFloat() * scale, center.y - l.target.y.toFloat() * scale)
                    drawFrame(CanvasLayout(origin, scale, imgSize), image, overlay, segments, points, includePoints = false, textMeasurer = textMeasurer)
                    val a = 14.dp.toPx()
                    val b = 3.dp.toPx()
                    val cross = Path().apply {
                        moveTo(center.x - a, center.y); lineTo(center.x - b, center.y)
                        moveTo(center.x + b, center.y); lineTo(center.x + a, center.y)
                        moveTo(center.x, center.y - a); lineTo(center.x, center.y - b)
                        moveTo(center.x, center.y + b); lineTo(center.x, center.y + a)
                    }
                    drawPath(cross, Color.Black.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
                    drawPath(cross, l.color, style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
        ) {
            IconButton(onClick = { state.zoomTo(state.zoom / 1.6f) }) {
                Icon(Icons.Filled.ZoomOut, contentDescription = "Alejar", tint = Color.White)
            }
            IconButton(onClick = { state.zoomTo(state.zoom * 1.6f) }) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Acercar", tint = Color.White)
            }
            IconButton(onClick = { state.reset() }) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = "Ver todo el frame", tint = Color.White)
            }
        }
    }
}

private fun hitPoint(location: Offset, layout: CanvasLayout, points: List<CanvasPoint>, radius: Float): Int? {
    var best: Int? = null
    var bestDistance = radius
    for (p in points) {
        val v = layout.view(p.point)
        val d = hypot(v.x - location.x, v.y - location.y)
        if (d < bestDistance) {
            bestDistance = d
            best = p.id
        }
    }
    return best
}

private fun clamp(p: Point2, imageSize: Size): Point2 =
    Point2(min(max(p.x, 0.0), imageSize.width.toDouble()), min(max(p.y, 0.0), imageSize.height.toDouble()))

private fun DrawScope.drawFrame(
    layout: CanvasLayout,
    image: ImageBitmap?,
    overlay: ImageBitmap?,
    segments: List<CanvasSegment>,
    points: List<CanvasPoint>,
    includePoints: Boolean,
    textMeasurer: TextMeasurer,
) {
    val rect = layout.imageRect
    val dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
    val dstSize = IntSize(max(1, rect.width.roundToInt()), max(1, rect.height.roundToInt()))
    if (image != null) {
        drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), dstOffset, dstSize, filterQuality = FilterQuality.Low)
    }
    if (overlay != null) {
        drawImage(overlay, IntOffset.Zero, IntSize(overlay.width, overlay.height), dstOffset, dstSize, filterQuality = FilterQuality.Low)
    }
    for (s in segments) drawSegment(s, layout)
    if (!includePoints) return
    val r = 9.dp.toPx()
    val arm = 4.dp.toPx()
    val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    for (p in points) {
        val c = layout.view(p.point)
        drawCircle(p.color.copy(alpha = 0.35f), r, c)
        drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
        drawLine(p.color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 1.5.dp.toPx())
        drawLine(p.color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 1.5.dp.toPx())
        if (p.label.isNotEmpty()) {
            val text = textMeasurer.measure(p.label, labelStyle)
            val at = Offset(c.x + 14.dp.toPx() - text.size.width / 2f, c.y - 14.dp.toPx() - text.size.height / 2f)
            drawText(text, topLeft = at)
        }
    }
}

private fun DrawScope.drawSegment(s: CanvasSegment, layout: CanvasLayout) {
    val a = layout.view(s.a)
    val b = layout.view(s.b)
    val width = s.width.dp.toPx()
    val effect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
    drawLine(s.color, a, b, strokeWidth = width, cap = StrokeCap.Round, pathEffect = effect)
    if (!s.arrow) return
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = hypot(dx, dy)
    if (length <= 2f) return
    val ux = dx / length
    val uy = dy / length
    val head = min(8.dp.toPx(), length * 0.4f)
    drawLine(s.color, b, Offset(b.x - head * (ux - 0.5f * uy), b.y - head * (uy + 0.5f * ux)), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(s.color, b, Offset(b.x - head * (ux + 0.5f * uy), b.y - head * (uy - 0.5f * ux)), strokeWidth = width, cap = StrokeCap.Round)
}

/**
 * Canvas on top (portrait) or on the left (landscape), controls in the rest of the screen. The canvas is outside the
 * scrolling form so its gestures never fight with scrolling.
 */
@Composable
fun CanvasStepLayout(
    modifier: Modifier = Modifier,
    canvas: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        if (width > height) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(width * 0.58f)) { canvas() }
                Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(height * 0.45f)) { canvas() }
                Box(Modifier.weight(1f).fillMaxWidth()) { controls() }
            }
        }
    }
}
