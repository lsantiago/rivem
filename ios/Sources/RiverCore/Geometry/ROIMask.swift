import Foundation

/// Binary frame mask, `uint8` values 0/1, row-major `(height, width)` like desktop's `mask.npy`, `usr_mask_<N>.npy`
/// and `final_mask.npy`: `1` = analysed, `0` = excluded.
public struct ROIMask: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var values: [UInt8]

    public init(width: Int, height: Int, values: [UInt8]) {
        precondition(width >= 0 && height >= 0 && values.count == width * height, "ROIMask size mismatch")
        self.width = width
        self.height = height
        self.values = values
    }

    public init(width: Int, height: Int, fill: UInt8) {
        self.init(width: width, height: height, values: [UInt8](repeating: fill, count: width * height))
    }

    public subscript(x: Int, y: Int) -> UInt8 {
        get { values[y * width + x] }
        set { values[y * width + x] = newValue }
    }

    /// Number of pixels set to 1.
    public var count: Int { values.reduce(0) { $0 + ($1 != 0 ? 1 : 0) } }

    /// `[xMin, yMin, xMax, yMax]` (inclusive) of the non-zero pixels; nil if none.
    public var nonZeroBounds: [Int]? {
        var x0 = Int.max, y0 = Int.max, x1 = -1, y1 = -1
        for y in 0..<height {
            let row = y * width
            for x in 0..<width where values[row + x] != 0 {
                if x < x0 { x0 = x }
                if x > x1 { x1 = x }
                if y < y0 { y0 = y }
                if y > y1 { y1 = y }
            }
        }
        return x1 < 0 ? nil : [x0, y0, x1, y1]
    }

    /// Number of non-zero pixels in each row.
    public var rowCounts: [Int] {
        var out = [Int](repeating: 0, count: height)
        for y in 0..<height {
            var c = 0
            let row = y * width
            for x in 0..<width where values[row + x] != 0 { c += 1 }
            out[y] = c
        }
        return out
    }

    /// Pixel-wise AND (0 dominant), values normalised to 0/1 (`combine_binary_masks_and`).
    public func and(_ other: ROIMask) -> ROIMask {
        precondition(other.width == width && other.height == height, "mask sizes differ")
        var out = self
        for i in 0..<values.count { out.values[i] = (values[i] != 0 && other.values[i] != 0) ? 1 : 0 }
        return out
    }

    /// Desktop's `mask.png` / `final_mask.png` overlay (`get_png_mask` in `river/cli/commands/define_roi_masks.py`):
    /// pixels with value 0 are black with alpha 111, the rest fully transparent (all channels 0).
    public func overlayImage() -> RGBAImage {
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<values.count where values[i] == 0 { bytes[4 * i + 3] = 111 }
        return RGBAImage(width: width, height: height, bytes: bytes)
    }
}

/// One cross-section as used for the ROI: the section line in real-world coordinates and, optionally, the wet
/// segments (stations, m, along the bathymetry) that make dry islands be excluded from the mask.
public struct ROISection: Equatable, Sendable {
    public var left: Point2
    public var right: Point2
    /// Wet stretches of the bathymetry at the section's water level, in station units (`find_wet_segments` in
    /// `compute_section.py`, ported by the section code). Two or more segments create island exclusions; empty
    /// means "no island handling".
    public var wetSegments: [ClosedRange<Double>]
    /// `rw_length` of the section (m); defaults to the length of the line.
    public var realWorldLength: Double

    public init(left: Point2, right: Point2, wetSegments: [ClosedRange<Double>] = [], realWorldLength: Double? = nil) {
        self.left = left
        self.right = right
        self.wetSegments = wetSegments
        self.realWorldLength = realWorldLength ?? left.distance(to: right)
    }

    /// From a desktop cross-section (`east_l, north_l, east_r, north_r`, `rw_length`); nil if the line is missing.
    public init?(_ section: CrossSection, wetSegments: [ClosedRange<Double>] = []) {
        guard let l = section.worldLeft, let r = section.worldRight else { return nil }
        self.init(left: l, right: r, wetSegments: wetSegments, realWorldLength: section.realWorldLength)
    }
}

/// ROI masks for PIV. Port of `river/core/define_roi_masks.py` (RIVeR 3.8.3): `create_rw_box`, `rw_box_to_pixel`,
/// `create_mask` (`cv2.fillPoly` reproduced in integer arithmetic, see `fillPolygon`), `create_island_exclusion_mask`,
/// `create_mask_and_bbox`, `recommend_height_roi`, `create_user_polygon_mask` and `compile_masks`.
public enum ROIMasks {
    /// Corners of the rectangle of height `height` (m) centred on the section line and aligned with it
    /// (`create_rw_box`), in order (−w/2,−h/2), (w/2,−h/2), (w/2,h/2), (−w/2,h/2) rotated and translated.
    public static func worldBox(left: Point2, right: Point2, height: Double) -> [Point2] {
        let length: Double = hypot(right.x - left.x, right.y - left.y)
        let angle: Double = atan2(right.y - left.y, right.x - left.x)
        let cx = (left.x + right.x) / 2, cy = (left.y + right.y) / 2
        let c = cos(angle), s = sin(angle)
        let hw = length / 2, hh = height / 2
        let local: [Point2] = [Point2(-hw, -hh), Point2(hw, -hh), Point2(hw, hh), Point2(-hw, hh)]
        return local.map { Point2(c * $0.x - s * $0.y + cx, s * $0.x + c * $0.y + cy) }
    }

    /// World → pixel through the inverse of the pixel → world `matrix` (`rw_box_to_pixel`).
    public static func pixelBox(_ worldBox: [Point2], matrix: Matrix3) throws -> [Point2] {
        let inv = try matrix.inverse()
        return worldBox.map { inv.apply($0) }
    }

    /// ROI mask and bounding box of all sections (`create_mask_and_bbox`).
    /// - Returns: the mask (1 inside any section box minus its islands) and `[x, y, width, height]` of the pixel
    ///   boxes, each bound clipped to `[0, width−1]` / `[0, height−1]` (desktop's `bbox.json`).
    public static func sectionMask(width: Int, height: Int, sections: [ROISection], matrix: Matrix3,
                                   heightROI: Double) throws -> (mask: ROIMask, bbox: [Double]) {
        guard !sections.isEmpty else { throw RiverCoreError.notEnoughPoints(needed: 1, got: 0) }
        var combined = ROIMask(width: width, height: height, fill: 0)
        var minX = Double.infinity, minY = Double.infinity, maxX = -Double.infinity, maxY = -Double.infinity
        for section in sections {
            let box: [Point2] = try pixelBox(worldBox(left: section.left, right: section.right, height: heightROI), matrix: matrix)
            for p in box {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            var mask = ROIMask(width: width, height: height, fill: 0)
            try fillPolygon(&mask, polygon: truncated(box), value: 1)
            let islands: ROIMask = try islandExclusionMask(width: width, height: height, section: section,
                                                           matrix: matrix, heightROI: heightROI)
            for i in 0..<mask.values.count where mask.values[i] != 0 && islands.values[i] != 0 {
                combined.values[i] = 1
            }
        }
        let wMax = Double(max(width - 1, 0)), hMax = Double(max(height - 1, 0))
        let x0 = clamp(minX, 0, wMax), x1 = clamp(maxX, 0, wMax)
        let y0 = clamp(minY, 0, hMax), y1 = clamp(maxY, 0, hMax)
        return (combined, [x0, y0, x1 - x0, y1 - y0])
    }

    /// 0 over each dry stretch between consecutive wet segments, 1 elsewhere (`create_island_exclusion_mask`).
    /// Stations are placed on the section line measured from the left point, with the first wet station at the
    /// left point (desktop's convention).
    public static func islandExclusionMask(width: Int, height: Int, section: ROISection, matrix: Matrix3,
                                           heightROI: Double) throws -> ROIMask {
        var mask = ROIMask(width: width, height: height, fill: 1)
        let wet = section.wetSegments
        guard wet.count >= 2 else { return mask }
        let total: Double = section.left.distance(to: section.right)
        guard total > 0 else { return mask }
        let ux = (section.right.x - section.left.x) / total
        let uy = (section.right.y - section.left.y) / total
        let first: Double = wet[0].lowerBound
        let place: (Double) -> Point2 = { station in
            let off = station - first
            return Point2(section.left.x + off * ux, section.left.y + off * uy)
        }
        for i in 0..<(wet.count - 1) {
            let a = place(wet[i].upperBound), b = place(wet[i + 1].lowerBound)
            let box: [Point2] = try pixelBox(worldBox(left: a, right: b, height: heightROI), matrix: matrix)
            var island = ROIMask(width: width, height: height, fill: 0)
            try fillPolygon(&island, polygon: truncated(box), value: 1)
            for k in 0..<mask.values.count where island.values[k] != 0 { mask.values[k] = 0 }
        }
        return mask
    }

    /// User exclusion polygon (`create_user_polygon_mask`): vertices rounded to the nearest pixel (half to even,
    /// like Python's `round`) and clipped to the frame; 0 inside the polygon (and on its outline), 1 elsewhere.
    public static func userPolygonMask(width: Int, height: Int, points: [Point2]) throws -> ROIMask {
        guard points.count >= 3 else { throw RiverCoreError.notEnoughPoints(needed: 3, got: points.count) }
        var poly: [(Int, Int)] = []
        for p in points {
            guard p.x.isFinite, p.y.isFinite else { throw RiverCoreError.degenerateGeometry("vértice no finito") }
            let rx: Double = p.x.rounded(.toNearestOrEven)
            let ry: Double = p.y.rounded(.toNearestOrEven)
            let x = Int(clamp(rx, 0, Double(max(width - 1, 0))))
            let y = Int(clamp(ry, 0, Double(max(height - 1, 0))))
            poly.append((x, y))
        }
        var mask = ROIMask(width: width, height: height, fill: 1)
        try fillPolygon(&mask, polygon: poly, value: 0)
        return mask
    }

    /// `compile_masks`: ROI AND every user mask.
    public static func compile(roi: ROIMask, userMasks: [ROIMask]) -> ROIMask {
        var out = roi.and(roi)
        for m in userMasks { out = out.and(m) }
        return out
    }

    /// `recommend_height_roi`: the box height (m, rounded up to an integer) whose smallest pixel side is closest to
    /// 1.5 × `windowSize` px, searched with the same bounded Brent minimiser as SciPy's
    /// `minimize_scalar(method="Bounded")` on [0.1, rw_length]. The desktop GUI uses `windowSize` 128.
    ///
    /// **Fix over desktop:** the upper bound is meant to be the shortest section, but desktop's loop keeps only
    /// the *last* section's `rw_length` (`max_rw_length` is never updated). Here it is the minimum over all
    /// sections; for a single section both are equal.
    public static func recommendHeightROI(sections: [ROISection], matrix: Matrix3, windowSize: Int = 128) throws -> Double {
        guard !sections.isEmpty else { throw RiverCoreError.notEnoughPoints(needed: 1, got: 0) }
        let upper: Double = sections.map { $0.realWorldLength }.min() ?? 0
        guard upper > 0.1 else { throw RiverCoreError.degenerateGeometry("sección demasiado corta") }
        let inv = try matrix.inverse()
        let desired = 1.5 * Double(windowSize)
        let objective: (Double) -> Double = { h in
            var minSide = Double.infinity
            for s in sections {
                let box: [Point2] = worldBox(left: s.left, right: s.right, height: h).map { inv.apply($0) }
                for i in 0..<box.count { minSide = min(minSide, box[i].distance(to: box[(i + 1) % box.count])) }
            }
            return abs(desired - minSide)
        }
        let result = boundedBrent(objective, lower: 0.1, upper: upper)
        return result.converged ? result.x.rounded(.up) : 1
    }

    // MARK: - Rasterisation (cv2.fillPoly, LINE_8, shift 0)

    /// `np.array(box, dtype=np.int32)`: truncation toward zero. Coordinates are clamped to ±10⁶ px first, which
    /// leaves any realistic frame unchanged and keeps the integer arithmetic below from overflowing.
    static func truncated(_ pts: [Point2]) throws -> [(Int, Int)] {
        var out: [(Int, Int)] = []
        for p in pts {
            guard p.x.isFinite, p.y.isFinite else {
                throw RiverCoreError.degenerateGeometry("la caja de la sección cae fuera del plano de la imagen")
            }
            out.append((Int(clamp(p.x, -1e6, 1e6)), Int(clamp(p.y, -1e6, 1e6))))
        }
        return out
    }

    /// Fills an integer polygon the way OpenCV's `fillPoly` does with `LINE_8` and `shift = 0`: every edge is drawn
    /// with OpenCV's 8-connected line iterator (after `clipLine`), then each scanline y in [y0, y1) of every
    /// non-horizontal edge is filled between pairs of edge crossings computed in 16.16 fixed point, from
    /// ceil(x_left) to floor(x_right). Matches OpenCV pixel for pixel on polygons inside the frame (checked
    /// against cv2 4.11 on random quadrilaterals); polygons clipped by the frame differ in ~0.05 % of pixels.
    static func fillPolygon(_ mask: inout ROIMask, polygon: [(Int, Int)], value: UInt8) throws {
        let n = polygon.count
        guard n >= 1 else { return }
        let w = mask.width, h = mask.height
        guard w > 0, h > 0 else { return }
        let shift = 16
        let one = 1 << shift
        // (yStart, yEnd, xStartFixed, dxFixed)
        var edges: [(Int, Int, Int, Int)] = []
        for i in 0..<n {
            let p0 = polygon[i], p1 = polygon[(i + 1) % n]
            drawLine(&mask, p0.0, p0.1, p1.0, p1.1, value)
            if p0.1 == p1.1 { continue }
            let fx0 = p0.0 << shift, fx1 = p1.0 << shift
            let dx: Int = (fx1 - fx0) / (p1.1 - p0.1) // truncates toward zero, like C++
            if p0.1 < p1.1 {
                edges.append((p0.1, p1.1, fx0, dx))
            } else {
                edges.append((p1.1, p0.1, fx1, dx))
            }
        }
        guard !edges.isEmpty else { return }
        var yMin = Int.max, yMax = Int.min
        for e in edges {
            yMin = min(yMin, e.0)
            yMax = max(yMax, e.1)
        }
        let yStart = max(yMin, 0), yStop = min(yMax, h)
        if yStart >= yStop { return }
        var xs: [Int] = []
        for y in yStart..<yStop {
            xs.removeAll(keepingCapacity: true)
            for e in edges where e.0 <= y && y < e.1 { xs.append(e.2 + (y - e.0) * e.3) }
            xs.sort()
            var k = 0
            while k + 1 < xs.count {
                var a = (xs[k] + one - 1) >> shift
                var b = xs[k + 1] >> shift
                if a < w && b >= 0 {
                    a = max(a, 0)
                    b = min(b, w - 1)
                    if a <= b {
                        let row = y * w
                        for x in a...b { mask.values[row + x] = value }
                    }
                }
                k += 2
            }
        }
    }

    /// OpenCV `Line` with 8-connectivity: clip to the frame (`clipLine`), then the `LineIterator` stepping from the
    /// left end point.
    static func drawLine(_ mask: inout ROIMask, _ ax: Int, _ ay: Int, _ bx: Int, _ by: Int, _ value: UInt8) {
        let w = mask.width, h = mask.height
        var x0 = ax, y0 = ay, x1 = bx, y1 = by
        let inside: (Int, Int) -> Bool = { x, y in x >= 0 && x < w && y >= 0 && y < h }
        if !(inside(x0, y0) && inside(x1, y1)) {
            guard let clipped = clipLine(width: w, height: h, x0, y0, x1, y1) else { return }
            x0 = clipped.0
            y0 = clipped.1
            x1 = clipped.2
            y1 = clipped.3
        }
        if x1 < x0 {
            swap(&x0, &x1)
            swap(&y0, &y1)
        }
        let dx = x1 - x0
        var dy = y1 - y0
        let sy = dy >= 0 ? 1 : -1
        dy = abs(dy)
        let xMajor = dx >= dy
        let dMajor = xMajor ? dx : dy
        let dMinor = xMajor ? dy : dx
        var err = dMajor - 2 * dMinor
        var x = x0, y = y0
        for _ in 0...dMajor {
            if inside(x, y) { mask.values[y * w + x] = value }
            if err < 0 {
                err += 2 * dMajor - 2 * dMinor
                x += 1
                y += sy
            } else {
                err -= 2 * dMinor
                if xMajor { x += 1 } else { y += sy }
            }
        }
    }

    /// OpenCV `clipLine` (integer Cohen–Sutherland variant). Nil when the segment misses the frame.
    static func clipLine(width: Int, height: Int, _ ax: Int, _ ay: Int, _ bx: Int, _ by: Int) -> (Int, Int, Int, Int)? {
        let right = width - 1, bottom = height - 1
        var x1 = ax, y1 = ay, x2 = bx, y2 = by
        let code: (Int, Int) -> Int = { x, y in
            (x < 0 ? 1 : 0) + (x > right ? 2 : 0) + (y < 0 ? 4 : 0) + (y > bottom ? 8 : 0)
        }
        var c1 = code(x1, y1), c2 = code(x2, y2)
        if (c1 & c2) == 0 && (c1 | c2) != 0 {
            if (c1 & 12) != 0 {
                let a = c1 < 8 ? 0 : bottom
                x1 += Int(Double(a - y1) * Double(x2 - x1) / Double(y2 - y1))
                y1 = a
                c1 = (x1 < 0 ? 1 : 0) + (x1 > right ? 2 : 0)
            }
            if (c2 & 12) != 0 {
                let a = c2 < 8 ? 0 : bottom
                x2 += Int(Double(a - y2) * Double(x2 - x1) / Double(y2 - y1))
                y2 = a
                c2 = (x2 < 0 ? 1 : 0) + (x2 > right ? 2 : 0)
            }
            if (c1 & c2) == 0 && (c1 | c2) != 0 {
                if c1 != 0 {
                    let a = c1 == 1 ? 0 : right
                    y1 += Int(Double(a - x1) * Double(y2 - y1) / Double(x2 - x1))
                    x1 = a
                    c1 = 0
                }
                if c2 != 0 {
                    let a = c2 == 1 ? 0 : right
                    y2 += Int(Double(a - x2) * Double(y2 - y1) / Double(x2 - x1))
                    x2 = a
                    c2 = 0
                }
            }
        }
        return (c1 | c2) == 0 ? (x1, y1, x2, y2) : nil
    }

    // MARK: - Helpers

    static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(max(v, lo), hi) }

    /// SciPy's `_minimize_scalar_bounded` (Brent's method with golden-section fallback, `xatol = 1e-5`,
    /// `maxiter = 500`).
    static func boundedBrent(_ f: (Double) -> Double, lower: Double, upper: Double,
                             xatol: Double = 1e-5, maxIter: Int = 500) -> (x: Double, converged: Bool) {
        let sqrtEps: Double = (2.220446049250313e-16).squareRoot()
        let goldenMean: Double = 0.5 * (3.0 - 5.0.squareRoot())
        var a = lower, b = upper
        var fulc = a + goldenMean * (b - a)
        var nfc = fulc, xf = fulc
        var rat = 0.0, e = 0.0
        var x = xf
        var fx = f(x)
        var num = 1
        var ffulc = fx, fnfc = fx
        var xm = 0.5 * (a + b)
        var tol1 = sqrtEps * abs(xf) + xatol / 3.0
        var tol2 = 2.0 * tol1
        var converged = true
        while abs(xf - xm) > (tol2 - 0.5 * (b - a)) {
            var golden = true
            if abs(e) > tol1 {
                golden = false
                var r = (xf - nfc) * (fx - ffulc)
                var q = (xf - fulc) * (fx - fnfc)
                var p = (xf - fulc) * q - (xf - nfc) * r
                q = 2.0 * (q - r)
                if q > 0.0 { p = -p }
                q = abs(q)
                r = e
                e = rat
                if abs(p) < abs(0.5 * q * r) && p > q * (a - xf) && p < q * (b - xf) {
                    rat = (p + 0.0) / q
                    x = xf + rat
                    if (x - a) < tol2 || (b - x) < tol2 {
                        let d = xm - xf
                        let si: Double = d > 0 ? 1 : (d < 0 ? -1 : 1)
                        rat = tol1 * si
                    }
                } else {
                    golden = true
                }
            }
            if golden {
                e = xf >= xm ? a - xf : b - xf
                rat = goldenMean * e
            }
            let si: Double = rat > 0 ? 1 : (rat < 0 ? -1 : 1)
            x = xf + si * max(abs(rat), tol1)
            let fu = f(x)
            num += 1
            if fu <= fx {
                if x >= xf { a = xf } else { b = xf }
                fulc = nfc
                ffulc = fnfc
                nfc = xf
                fnfc = fx
                xf = x
                fx = fu
            } else {
                if x < xf { a = x } else { b = x }
                if fu <= fnfc || nfc == xf {
                    fulc = nfc
                    ffulc = fnfc
                    nfc = x
                    fnfc = fu
                } else if fu <= ffulc || fulc == xf || fulc == nfc {
                    fulc = x
                    ffulc = fu
                }
            }
            xm = 0.5 * (a + b)
            tol1 = sqrtEps * abs(xf) + xatol / 3.0
            tol2 = 2.0 * tol1
            if num >= maxIter {
                converged = false
                break
            }
        }
        return (xf, converged)
    }
}
