import Foundation

/// Frame preprocessing before PIV, as `river/core/image_preprocessing.py` → `preprocess_image`:
/// grayscale (8-bit) → optional background subtraction → optional CLAHE.
///
/// Upstream works on the `uint8` images OpenCV decodes; `GrayImage` carries Float samples, so every frame is first
/// quantised to 0…255 (round half to even, clamped). With integer-valued input this is the identity.
public enum PIVPreprocessing {
    /// Full chain for one frame. `background` (if any) must have the frame's size; it is used only when
    /// `parameters.removeBackground` is true.
    public static func preprocess(_ image: GrayImage, parameters: PIVParameters, background: GrayImage?) -> GrayImage {
        var px = quantize(image)
        if parameters.removeBackground, let bg = background, bg.width == image.width, bg.height == image.height {
            let b = quantize(bg)
            subtractBackground(&px, background: b)
        }
        if parameters.clahe {
            px = clahe(px, width: image.width, height: image.height, clipLimit: parameters.clipLimit)
        }
        var out = [Float](repeating: 0, count: px.count)
        for i in 0..<px.count { out[i] = Float(px[i]) }
        return GrayImage(width: image.width, height: image.height, pixels: out)
    }

    /// 0…255 bytes, round half to even (like `saturate_cast<uchar>`), NaN → 0.
    public static func quantize(_ image: GrayImage) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: image.pixels.count)
        image.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                for i in 0..<src.count {
                    let v: Float = src[i]
                    if !(v > 0) { dst[i] = 0; continue }
                    if v >= 255 { dst[i] = 255; continue }
                    dst[i] = UInt8(v.rounded(.toNearestOrEven))
                }
            }
        }
        return out
    }

    /// `cv2.subtract(img, bg)` on `uint8`: saturating `max(img − bg, 0)`.
    public static func subtractBackground(_ px: inout [UInt8], background: [UInt8]) {
        precondition(px.count == background.count, "background size mismatch")
        for i in 0..<px.count {
            let a = px[i], b = background[i]
            px[i] = a > b ? a - b : 0
        }
    }

    /// CLAHE exactly as OpenCV's `createCLAHE(clipLimit).apply()` on 8-bit images (default 8×8 tiles), checked
    /// bit-exact against OpenCV 4.11 (including sizes not divisible by the tile count):
    /// - if the size is not a multiple of the tile grid, the image is extended right/bottom with BORDER_REFLECT_101
    ///   by `tiles − size % tiles` for computing the histograms;
    /// - per tile: histogram, clip at `max(Int(clipLimit · tileArea / 256), 1)`, redistribute the excess
    ///   (uniform batch plus a strided residual), cumulative LUT `round(cdf · 255 / tileArea)` in Float;
    /// - bilinear interpolation between the four neighbouring tile LUTs in Float, rounded half to even.
    public static func clahe(_ src: [UInt8], width w: Int, height h: Int, clipLimit: Double, tiles: Int = 8) -> [UInt8] {
        precondition(src.count == w * h, "CLAHE size mismatch")
        if w == 0 || h == 0 { return src }
        let tx = tiles, ty = tiles
        let extW: Int = (w % tx == 0) ? w : w + tx - (w % tx)
        let extH: Int = (h % ty == 0) ? h : h + ty - (h % ty)
        let tw = extW / tx, th = extH / ty
        let area = tw * th
        let lutScale: Float = Float(255.0 / Double(area))
        var clip = 0
        if clipLimit > 0 {
            clip = max(Int(clipLimit * Double(area) / 256.0), 1)
        }
        // Reflect-101 index maps for the extension (clamped for degenerate 1-pixel images).
        var colMap = [Int](repeating: 0, count: extW)
        for x in 0..<extW { colMap[x] = reflect101(x, w) }
        var rowMap = [Int](repeating: 0, count: extH)
        for y in 0..<extH { rowMap[y] = reflect101(y, h) }

        var lut = [UInt8](repeating: 0, count: tx * ty * 256)
        var hist = [Int](repeating: 0, count: 256)
        src.withUnsafeBufferPointer { s in
            for j in 0..<ty {
                for i in 0..<tx {
                    for k in 0..<256 { hist[k] = 0 }
                    for yy in (j * th)..<((j + 1) * th) {
                        let row = rowMap[yy] * w
                        for xx in (i * tw)..<((i + 1) * tw) {
                            hist[Int(s[row + colMap[xx]])] += 1
                        }
                    }
                    if clip > 0 {
                        var clipped = 0
                        for k in 0..<256 where hist[k] > clip {
                            clipped += hist[k] - clip
                            hist[k] = clip
                        }
                        let batch = clipped / 256
                        var residual = clipped - batch * 256
                        for k in 0..<256 { hist[k] += batch }
                        if residual != 0 {
                            let residualStep = max(256 / residual, 1)
                            var k = 0
                            while k < 256 && residual > 0 {
                                hist[k] += 1
                                k += residualStep
                                residual -= 1
                            }
                        }
                    }
                    var sum = 0
                    let base = (j * tx + i) * 256
                    for k in 0..<256 {
                        sum += hist[k]
                        let v: Float = Float(sum) * lutScale
                        lut[base + k] = saturateByte(v)
                    }
                }
            }
        }

        // Interpolation tables along x.
        let invTW: Float = 1.0 / Float(tw)
        let invTH: Float = 1.0 / Float(th)
        var ind1 = [Int](repeating: 0, count: w)
        var ind2 = [Int](repeating: 0, count: w)
        var xa = [Float](repeating: 0, count: w)
        var xa1 = [Float](repeating: 0, count: w)
        for x in 0..<w {
            let txf: Float = Float(x) * invTW - 0.5
            var t1 = Int(txf.rounded(.down))
            var t2 = t1 + 1
            let a: Float = txf - Float(t1)
            xa[x] = a
            xa1[x] = 1.0 - a
            t1 = max(t1, 0)
            t2 = min(t2, tx - 1)
            ind1[x] = t1 * 256
            ind2[x] = t2 * 256
        }
        var dst = [UInt8](repeating: 0, count: w * h)
        src.withUnsafeBufferPointer { s in
            lut.withUnsafeBufferPointer { l in
                dst.withUnsafeMutableBufferPointer { d in
                    for y in 0..<h {
                        let tyf: Float = Float(y) * invTH - 0.5
                        var t1 = Int(tyf.rounded(.down))
                        var t2 = t1 + 1
                        let ya: Float = tyf - Float(t1)
                        let ya1: Float = 1.0 - ya
                        t1 = max(t1, 0)
                        t2 = min(t2, ty - 1)
                        let p1 = t1 * tx * 256
                        let p2 = t2 * tx * 256
                        let row = y * w
                        for x in 0..<w {
                            let v = Int(s[row + x])
                            let a: Float = Float(l[p1 + ind1[x] + v])
                            let b: Float = Float(l[p1 + ind2[x] + v])
                            let c: Float = Float(l[p2 + ind1[x] + v])
                            let e: Float = Float(l[p2 + ind2[x] + v])
                            let top: Float = a * xa1[x] + b * xa[x]
                            let bottom: Float = c * xa1[x] + e * xa[x]
                            let res: Float = top * ya1 + bottom * ya
                            d[row + x] = saturateByte(res)
                        }
                    }
                }
            }
        }
        return dst
    }

    @inline(__always)
    static func saturateByte(_ v: Float) -> UInt8 {
        let r = v.rounded(.toNearestOrEven)
        if !(r > 0) { return 0 }
        if r >= 255 { return 255 }
        return UInt8(r)
    }

    @inline(__always)
    static func reflect101(_ i: Int, _ n: Int) -> Int {
        if n <= 1 { return 0 }
        var j = i
        if j >= n { j = 2 * n - 2 - j }
        return min(max(j, 0), n - 1)
    }
}

/// Background for `remove_background`: `np.uint8(mean of all frames)` (`image_preprocessing.calculate_average`),
/// i.e. the pixel-wise mean of the 8-bit frames truncated towards zero. Accumulates one frame at a time.
///
/// Deviation: upstream crashes on an unreadable frame; here the caller simply skips it (the mean is over the frames
/// actually added, see `PIVSequence`).
public struct PIVBackgroundAccumulator {
    public let width: Int
    public let height: Int
    public private(set) var count: Int = 0
    private var sum: [Double]

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
        sum = [Double](repeating: 0, count: width * height)
    }

    /// Adds a frame (quantised to 8 bits like a decoded JPEG). Returns false if the size does not match.
    @discardableResult
    public mutating func add(_ image: GrayImage) -> Bool {
        guard image.width == width, image.height == height else { return false }
        let px = PIVPreprocessing.quantize(image)
        sum.withUnsafeMutableBufferPointer { s in
            for i in 0..<px.count { s[i] += Double(px[i]) }
        }
        count += 1
        return true
    }

    /// Truncated mean (0…255); nil if no frame was added.
    public func background() -> GrayImage? {
        guard count > 0 else { return nil }
        var out = [Float](repeating: 0, count: width * height)
        let n = Double(count)
        for i in 0..<out.count {
            out[i] = Float(min(255.0, (sum[i] / n).rounded(.towardZero)))
        }
        return GrayImage(width: width, height: height, pixels: out)
    }
}
