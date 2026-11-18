import Foundation

/// Output grid of an orthorectified image: real-world extent sampled like `np.linspace(min, max, n)` on both axes.
/// With `flipY` (desktop default) row 0 is the northern edge (north up); with `flipX` column 0 is the eastern edge.
public struct OrthoGrid: Equatable, Sendable {
    /// `[xMin, xMax, yMin, yMax]` (East, North), desktop's `extent`.
    public var extent: [Double]
    public var width: Int
    public var height: Int
    public var flipX: Bool
    public var flipY: Bool

    public init(extent: [Double], width: Int, height: Int, flipX: Bool = false, flipY: Bool = true) {
        precondition(extent.count == 4, "extent needs 4 values")
        self.extent = extent
        self.width = width
        self.height = height
        self.flipX = flipX
        self.flipY = flipY
    }

    /// Real-world units per output pixel as desktop reports it (`output_resolution`):
    /// `max(|xMax − xMin| / width, |yMax − yMin| / height)`.
    public var resolution: Double {
        let rx: Double = width > 0 ? abs(extent[1] - extent[0]) / Double(width) : .infinity
        let ry: Double = height > 0 ? abs(extent[3] - extent[2]) / Double(height) : .infinity
        return max(rx, ry)
    }

    /// Real-world position sampled by output pixel (column, row).
    public func world(column: Int, row: Int) -> Point2 {
        let i = flipX ? width - 1 - column : column
        let j = flipY ? height - 1 - row : row
        return Point2(Self.linspace(extent[0], extent[1], width, i), Self.linspace(extent[2], extent[3], height, j))
    }

    /// Element `i` of `np.linspace(start, stop, n)` (last element exactly `stop`).
    static func linspace(_ start: Double, _ stop: Double, _ n: Int, _ i: Int) -> Double {
        if n <= 1 { return start }
        if i == n - 1 { return stop }
        return start + Double(i) * ((stop - start) / Double(n - 1))
    }
}

/// Orthorectified images (desktop's `transformed_image.png` and the 3-D `ortho_image.png`). Port of
/// `orthorectify_image_with_size_limit` and `orthorectify_image` in `river/core/coordinate_transform.py`
/// (RIVeR 3.8.3).
public enum Orthorectifier {
    /// Grid for a pixel → world `matrix` restricted to the pixel window [xMin, xMax) × [yMin, yMax)
    /// (`orthorectify_image_with_size_limit`): the four window corners are mapped to the world, the box is widened by
    /// 5 % on each side, the resolution is the larger of range/`maxDimension` on both axes but not below
    /// `minResolution`, and the size is `int(range / resolution)`.
    public static func sizeLimitedGrid(matrix: Matrix3, xMin: Int, xMax: Int, yMin: Int, yMax: Int,
                                       maxDimension: Int = 500, minResolution: Double = 0.01,
                                       flipX: Bool = false, flipY: Bool = true) throws -> OrthoGrid {
        let corners: [Point2] = [Point2(Double(xMin), Double(yMin)), Point2(Double(xMax), Double(yMin)),
                                 Point2(Double(xMin), Double(yMax)), Point2(Double(xMax), Double(yMax))]
        let world: [Point2] = corners.map { matrix.apply($0) }
        guard world.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else {
            throw RiverCoreError.degenerateGeometry("una esquina de la imagen cae en el horizonte")
        }
        var x0: Double = world.map { $0.x }.min() ?? 0
        var x1: Double = world.map { $0.x }.max() ?? 0
        var y0: Double = world.map { $0.y }.min() ?? 0
        var y1: Double = world.map { $0.y }.max() ?? 0
        let margin = 0.05
        let xr0 = x1 - x0, yr0 = y1 - y0
        x0 -= margin * xr0
        x1 += margin * xr0
        y0 -= margin * yr0
        y1 += margin * yr0
        let xr = x1 - x0, yr = y1 - y0
        var res: Double = max(xr / Double(maxDimension), yr / Double(maxDimension))
        res = max(res, minResolution)
        guard res > 0, res.isFinite else { throw RiverCoreError.degenerateGeometry("extensión nula") }
        return OrthoGrid(extent: [x0, x1, y0, y1], width: Int(xr / res), height: Int(yr / res), flipX: flipX, flipY: flipY)
    }

    /// UAV (`get_uav_transformation_matrix` with an image): the whole frame plus `roiPadding` × size on each side.
    ///
    /// **Fix over desktop:** desktop pads the top edge with the *horizontal* padding (`roi = (int(-x_padding), …)`).
    /// Here the vertical padding is used. With the GUI's padding of 0 both are identical.
    public static func uavGrid(matrix: Matrix3, imageWidth: Int, imageHeight: Int, roiPadding: Double = 0,
                               maxDimension: Int = 500, minResolution: Double = 0.01,
                               flipX: Bool = false, flipY: Bool = true) throws -> OrthoGrid {
        let xp = Double(imageWidth) * roiPadding, yp = Double(imageHeight) * roiPadding
        return try sizeLimitedGrid(matrix: matrix, xMin: Int(-xp), xMax: Int(Double(imageWidth) + xp),
                                   yMin: Int(-yp), yMax: Int(Double(imageHeight) + yp),
                                   maxDimension: maxDimension, minResolution: minResolution, flipX: flipX, flipY: flipY)
    }

    /// Oblique: the window is the control-point ROI `[x, y, w, h]` (`oblique_view_transformation_matrix`), with
    /// desktop's truncation `int(y)`, `int(y + h)`, `int(x)`, `int(x + w)`.
    public static func obliqueGrid(matrix: Matrix3, roi: [Double], maxDimension: Int = 500, minResolution: Double = 0.01,
                                   flipX: Bool = false, flipY: Bool = true) throws -> OrthoGrid {
        guard roi.count == 4 else { throw RiverCoreError.degenerateGeometry("roi necesita 4 valores") }
        return try sizeLimitedGrid(matrix: matrix, xMin: Int(roi[0]), xMax: Int(roi[0] + roi[2]),
                                   yMin: Int(roi[1]), yMax: Int(roi[1] + roi[3]),
                                   maxDimension: maxDimension, minResolution: minResolution, flipX: flipX, flipY: flipY)
    }

    /// 3-D camera ortho image (`orthorectify_image`): X/Y range of the solving points plus 30 % on each side,
    /// `int(range / resolution)` pixels (CLI default resolution 0.1 m), scaled down so no side exceeds `maxSize`.
    /// Render it with `render(_:grid:camera:z:)` at `z` = mean Z of the points (`meanZ`).
    public static func cameraGrid(points: [GroundPoint3D], resolution: Double = 0.1, maxSize: Int = 500,
                                  flipX: Bool = false, flipY: Bool = true) throws -> (grid: OrthoGrid, meanZ: Double) {
        guard !points.isEmpty, resolution > 0 else { throw RiverCoreError.notEnoughPoints(needed: 1, got: 0) }
        var x0: Double = points.map { $0.X }.min() ?? 0
        var x1: Double = points.map { $0.X }.max() ?? 0
        var y0: Double = points.map { $0.Y }.min() ?? 0
        var y1: Double = points.map { $0.Y }.max() ?? 0
        let margin = 0.3
        let xr = x1 - x0, yr = y1 - y0
        x0 -= margin * xr
        x1 += margin * xr
        y0 -= margin * yr
        y1 += margin * yr
        var xs = Int((x1 - x0) / resolution)
        var ys = Int((y1 - y0) / resolution)
        if xs > maxSize || ys > maxSize {
            let scale: Double = max(Double(xs) / Double(maxSize), Double(ys) / Double(maxSize))
            xs = Int(Double(xs) / scale)
            ys = Int(Double(ys) / scale)
        }
        let meanZ: Double = points.reduce(0) { $0 + $1.Z } / Double(points.count)
        return (OrthoGrid(extent: [x0, x1, y0, y1], width: xs, height: ys, flipX: flipX, flipY: flipY), meanZ)
    }

    /// Renders the grid by sampling `source` at `worldToPixel(world)` with bilinear interpolation over a black
    /// border (OpenCV `remap` with `INTER_LINEAR` and `BORDER_CONSTANT` 0). Alpha is 255 where the sample position lies
    /// in [0, W) × [0, H) and 0 elsewhere, as desktop adds it; the source alpha is ignored (desktop reads RGB).
    /// Desktop's `remap` interpolates in 1/32-pixel fixed point, so values can differ by ±1.
    public static func render(_ source: RGBAImage, grid: OrthoGrid, worldToPixel: (Point2) -> Point2) -> RGBAImage {
        var out = RGBAImage(width: grid.width, height: grid.height)
        let w = source.width, h = source.height
        guard w > 0, h > 0 else { return out }
        for row in 0..<grid.height {
            for col in 0..<grid.width {
                let p: Point2 = worldToPixel(grid.world(column: col, row: row))
                // remap maps are float32 in desktop.
                let fx = Double(Float(p.x)), fy = Double(Float(p.y))
                guard fx.isFinite, fy.isFinite else { continue }
                let o = 4 * (row * grid.width + col)
                let fxf = fx.rounded(.down), fyf = fy.rounded(.down)
                guard fxf > -2, fyf > -2, fxf < Double(w + 1), fyf < Double(h + 1) else { continue }
                let x0 = Int(fxf), y0 = Int(fyf)
                let ax = fx - fxf, ay = fy - fyf
                let weights: [(Int, Int, Double)] = [
                    (x0, y0, (1 - ax) * (1 - ay)), (x0 + 1, y0, ax * (1 - ay)),
                    (x0, y0 + 1, (1 - ax) * ay), (x0 + 1, y0 + 1, ax * ay),
                ]
                for c in 0..<3 {
                    var acc = 0.0
                    for t in weights where t.0 >= 0 && t.0 < w && t.1 >= 0 && t.1 < h {
                        acc += t.2 * Double(source.bytes[4 * (t.1 * w + t.0) + c])
                    }
                    out.bytes[o + c] = UInt8(max(0, min(255, acc.rounded())))
                }
                let valid: Bool = fx >= 0 && fx < Double(w) && fy >= 0 && fy < Double(h)
                out.bytes[o + 3] = valid ? 255 : 0
            }
        }
        return out
    }

    /// Render with a pixel → world `matrix` (desktop's `transformed_image.png`).
    public static func render(_ source: RGBAImage, grid: OrthoGrid, matrix: Matrix3) throws -> RGBAImage {
        let inv = try matrix.inverse()
        return render(source, grid: grid, worldToPixel: { inv.apply($0) })
    }

    /// Render the water plane Z = `z` seen by a 3-D camera (desktop's `ortho_image.png`).
    public static func render(_ source: RGBAImage, grid: OrthoGrid, camera: CameraMatrix, z: Double) -> RGBAImage {
        render(source, grid: grid, worldToPixel: { camera.project(X: $0.x, Y: $0.y, Z: z) })
    }
}
