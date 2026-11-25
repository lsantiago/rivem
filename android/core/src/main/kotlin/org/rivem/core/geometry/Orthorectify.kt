package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import org.rivem.core.imaging.RGBAImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Output grid of an orthorectified image: real-world extent sampled like `np.linspace(min, max, n)` on both axes.
 * With `flipY` (desktop default) row 0 is the northern edge (north up); with `flipX` column 0 is the eastern edge.
 */
data class OrthoGrid(
    /** `[xMin, xMax, yMin, yMax]` (East, North), desktop's `extent`. */
    val extent: List<Double>,
    val width: Int,
    val height: Int,
    val flipX: Boolean = false,
    val flipY: Boolean = true,
) {
    init {
        require(extent.size == 4) { "extent needs 4 values" }
    }

    /**
     * Real-world units per output pixel as desktop reports it (`output_resolution`):
     * `max(|xMax − xMin| / width, |yMax − yMin| / height)`.
     */
    val resolution: Double
        get() {
            val rx: Double = if (width > 0) abs(extent[1] - extent[0]) / width.toDouble() else Double.POSITIVE_INFINITY
            val ry: Double = if (height > 0) abs(extent[3] - extent[2]) / height.toDouble() else Double.POSITIVE_INFINITY
            return max(rx, ry)
        }

    /** Real-world position sampled by output pixel (column, row). */
    fun world(column: Int, row: Int): Point2 {
        val i = if (flipX) width - 1 - column else column
        val j = if (flipY) height - 1 - row else row
        return Point2(linspace(extent[0], extent[1], width, i), linspace(extent[2], extent[3], height, j))
    }

    companion object {
        /** Element `i` of `np.linspace(start, stop, n)` (last element exactly `stop`). */
        internal fun linspace(start: Double, stop: Double, n: Int, i: Int): Double {
            if (n <= 1) return start
            if (i == n - 1) return stop
            return start + i.toDouble() * ((stop - start) / (n - 1).toDouble())
        }
    }
}

/** `Orthorectifier.cameraGrid` result (Swift's `(grid:, meanZ:)` tuple). */
data class CameraOrthoGrid(val grid: OrthoGrid, val meanZ: Double)

/**
 * Orthorectified images (desktop's `transformed_image.png` and the 3-D `ortho_image.png`). Port of
 * `orthorectify_image_with_size_limit` and `orthorectify_image` in `river/core/coordinate_transform.py`
 * (RIVeR 3.8.3).
 */
object Orthorectifier {
    /**
     * Grid for a pixel → world `matrix` restricted to the pixel window [xMin, xMax) × [yMin, yMax)
     * (`orthorectify_image_with_size_limit`): the four window corners are mapped to the world, the box is widened by
     * 5 % on each side, the resolution is the larger of range/`maxDimension` on both axes but not below
     * `minResolution`, and the size is `int(range / resolution)`.
     */
    fun sizeLimitedGrid(matrix: Matrix3, xMin: Int, xMax: Int, yMin: Int, yMax: Int,
                        maxDimension: Int = 500, minResolution: Double = 0.01,
                        flipX: Boolean = false, flipY: Boolean = true): OrthoGrid {
        val corners: List<Point2> = listOf(Point2(xMin.toDouble(), yMin.toDouble()), Point2(xMax.toDouble(), yMin.toDouble()),
                                           Point2(xMin.toDouble(), yMax.toDouble()), Point2(xMax.toDouble(), yMax.toDouble()))
        val world: List<Point2> = corners.map { matrix.apply(it) }
        if (!world.all { it.x.isFinite() && it.y.isFinite() }) {
            throw RiverCoreError.DegenerateGeometry("una esquina de la imagen cae en el horizonte")
        }
        var x0: Double = world.minOf { it.x }
        var x1: Double = world.maxOf { it.x }
        var y0: Double = world.minOf { it.y }
        var y1: Double = world.maxOf { it.y }
        val margin = 0.05
        val xr0 = x1 - x0
        val yr0 = y1 - y0
        x0 -= margin * xr0
        x1 += margin * xr0
        y0 -= margin * yr0
        y1 += margin * yr0
        val xr = x1 - x0
        val yr = y1 - y0
        var res: Double = max(xr / maxDimension.toDouble(), yr / maxDimension.toDouble())
        res = max(res, minResolution)
        if (!(res > 0 && res.isFinite())) throw RiverCoreError.DegenerateGeometry("extensión nula")
        return OrthoGrid(extent = listOf(x0, x1, y0, y1), width = (xr / res).toInt(), height = (yr / res).toInt(),
                         flipX = flipX, flipY = flipY)
    }

    /**
     * UAV (`get_uav_transformation_matrix` with an image): the whole frame plus `roiPadding` × size on each side.
     *
     * **Fix over desktop:** desktop pads the top edge with the *horizontal* padding (`roi = (int(-x_padding), …)`).
     * Here the vertical padding is used. With the GUI's padding of 0 both are identical.
     */
    fun uavGrid(matrix: Matrix3, imageWidth: Int, imageHeight: Int, roiPadding: Double = 0.0,
                maxDimension: Int = 500, minResolution: Double = 0.01,
                flipX: Boolean = false, flipY: Boolean = true): OrthoGrid {
        val xp = imageWidth.toDouble() * roiPadding
        val yp = imageHeight.toDouble() * roiPadding
        return sizeLimitedGrid(matrix, xMin = (-xp).toInt(), xMax = (imageWidth.toDouble() + xp).toInt(),
                               yMin = (-yp).toInt(), yMax = (imageHeight.toDouble() + yp).toInt(),
                               maxDimension = maxDimension, minResolution = minResolution, flipX = flipX, flipY = flipY)
    }

    /**
     * Oblique: the window is the control-point ROI `[x, y, w, h]` (`oblique_view_transformation_matrix`), with
     * desktop's truncation `int(y)`, `int(y + h)`, `int(x)`, `int(x + w)`.
     */
    fun obliqueGrid(matrix: Matrix3, roi: List<Double>, maxDimension: Int = 500, minResolution: Double = 0.01,
                    flipX: Boolean = false, flipY: Boolean = true): OrthoGrid {
        if (roi.size != 4) throw RiverCoreError.DegenerateGeometry("roi necesita 4 valores")
        return sizeLimitedGrid(matrix, xMin = roi[0].toInt(), xMax = (roi[0] + roi[2]).toInt(),
                               yMin = roi[1].toInt(), yMax = (roi[1] + roi[3]).toInt(),
                               maxDimension = maxDimension, minResolution = minResolution, flipX = flipX, flipY = flipY)
    }

    /**
     * 3-D camera ortho image (`orthorectify_image`): X/Y range of the solving points plus 30 % on each side,
     * `int(range / resolution)` pixels (CLI default resolution 0.1 m), scaled down so no side exceeds `maxSize`.
     * Render it with `render(source, grid, camera, z)` at `z` = mean Z of the points (`meanZ`).
     */
    fun cameraGrid(points: List<GroundPoint3D>, resolution: Double = 0.1, maxSize: Int = 500,
                   flipX: Boolean = false, flipY: Boolean = true): CameraOrthoGrid {
        if (points.isEmpty() || !(resolution > 0)) throw RiverCoreError.NotEnoughPoints(needed = 1, got = 0)
        var x0: Double = points.minOf { it.X }
        var x1: Double = points.maxOf { it.X }
        var y0: Double = points.minOf { it.Y }
        var y1: Double = points.maxOf { it.Y }
        val margin = 0.3
        val xr = x1 - x0
        val yr = y1 - y0
        x0 -= margin * xr
        x1 += margin * xr
        y0 -= margin * yr
        y1 += margin * yr
        var xs = ((x1 - x0) / resolution).toInt()
        var ys = ((y1 - y0) / resolution).toInt()
        if (xs > maxSize || ys > maxSize) {
            val scale: Double = max(xs.toDouble() / maxSize.toDouble(), ys.toDouble() / maxSize.toDouble())
            xs = (xs.toDouble() / scale).toInt()
            ys = (ys.toDouble() / scale).toInt()
        }
        val meanZ: Double = points.fold(0.0) { acc, p -> acc + p.Z } / points.size.toDouble()
        return CameraOrthoGrid(OrthoGrid(extent = listOf(x0, x1, y0, y1), width = xs, height = ys, flipX = flipX, flipY = flipY), meanZ)
    }

    /**
     * Renders the grid by sampling `source` at `worldToPixel(world)` with bilinear interpolation over a black
     * border (OpenCV `remap` with `INTER_LINEAR` and `BORDER_CONSTANT` 0). Alpha is 255 where the sample position lies
     * in [0, W) × [0, H) and 0 elsewhere, as desktop adds it; the source alpha is ignored (desktop reads RGB).
     * Desktop's `remap` interpolates in 1/32-pixel fixed point, so values can differ by ±1.
     */
    fun render(source: RGBAImage, grid: OrthoGrid, worldToPixel: (Point2) -> Point2): RGBAImage {
        val out = RGBAImage(grid.width, grid.height)
        val w = source.width
        val h = source.height
        if (!(w > 0 && h > 0)) return out
        val src = source.bytes
        val dst = out.bytes
        val wx = IntArray(4)
        val wy = IntArray(4)
        val wt = DoubleArray(4)
        for (row in 0 until grid.height) {
            for (col in 0 until grid.width) {
                val p: Point2 = worldToPixel(grid.world(col, row))
                // remap maps are float32 in desktop.
                val fx = p.x.toFloat().toDouble()
                val fy = p.y.toFloat().toDouble()
                if (!(fx.isFinite() && fy.isFinite())) continue
                val o = 4 * (row * grid.width + col)
                val fxf = floor(fx)
                val fyf = floor(fy)
                if (!(fxf > -2 && fyf > -2 && fxf < (w + 1).toDouble() && fyf < (h + 1).toDouble())) continue
                val x0 = fxf.toInt()
                val y0 = fyf.toInt()
                val ax = fx - fxf
                val ay = fy - fyf
                wx[0] = x0; wy[0] = y0; wt[0] = (1 - ax) * (1 - ay)
                wx[1] = x0 + 1; wy[1] = y0; wt[1] = ax * (1 - ay)
                wx[2] = x0; wy[2] = y0 + 1; wt[2] = (1 - ax) * ay
                wx[3] = x0 + 1; wy[3] = y0 + 1; wt[3] = ax * ay
                for (c in 0 until 3) {
                    var acc = 0.0
                    for (t in 0 until 4) {
                        if (wx[t] >= 0 && wx[t] < w && wy[t] >= 0 && wy[t] < h) {
                            acc += wt[t] * (src[4 * (wy[t] * w + wx[t]) + c].toInt() and 0xFF).toDouble()
                        }
                    }
                    dst[o + c] = max(0.0, min(255.0, roundHalfAwayFromZero(acc))).toInt().toByte()
                }
                val valid: Boolean = fx >= 0 && fx < w.toDouble() && fy >= 0 && fy < h.toDouble()
                dst[o + 3] = if (valid) 255.toByte() else 0
            }
        }
        return out
    }

    /** Render with a pixel → world `matrix` (desktop's `transformed_image.png`). */
    fun render(source: RGBAImage, grid: OrthoGrid, matrix: Matrix3): RGBAImage {
        val inv = matrix.inverse()
        return render(source, grid) { inv.apply(it) }
    }

    /** Render the water plane Z = `z` seen by a 3-D camera (desktop's `ortho_image.png`). */
    fun render(source: RGBAImage, grid: OrthoGrid, camera: CameraMatrix, z: Double): RGBAImage =
        render(source, grid) { camera.project(it.x, it.y, z) }

    /** Swift's `Double.rounded()` (`.toNearestOrAwayFromZero`). */
    internal fun roundHalfAwayFromZero(x: Double): Double {
        val t = if (x < 0) -floor(-x) else floor(x)
        return if (abs(x - t) >= 0.5) t + sign(x) else t
    }
}
