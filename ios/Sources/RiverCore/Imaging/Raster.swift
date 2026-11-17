import Foundation

/// Single-channel image with Float samples on the 0–255 scale (what OpenCV gives RIVeR after `cv2.imread(...,
/// IMREAD_GRAYSCALE)` or a BGR→gray conversion). Row-major, origin top-left, y down: `pixels[y * width + x]`.
///
/// Shared by the geometry (rectification, masks), PIV and STIV code. Decoding/encoding JPEG or video frames is done
/// by the app (CoreGraphics / AVFoundation) and handed to the core as one of these buffers.
public struct GrayImage: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [Float]

    public init(width: Int, height: Int, pixels: [Float]) {
        precondition(width >= 0 && height >= 0 && pixels.count == width * height, "GrayImage size mismatch")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    public init(width: Int, height: Int, fill: Float = 0) {
        self.init(width: width, height: height, pixels: [Float](repeating: fill, count: width * height))
    }

    @inline(__always)
    public subscript(x: Int, y: Int) -> Float {
        get { pixels[y * width + x] }
        set { pixels[y * width + x] = newValue }
    }

    /// Bilinear sample at a sub-pixel position (pixel centres at integer coordinates); nil outside the image.
    public func bilinear(_ x: Double, _ y: Double) -> Float? {
        guard x >= 0, y >= 0, x <= Double(width - 1), y <= Double(height - 1) else { return nil }
        let x0 = min(Int(x), width - 1), y0 = min(Int(y), height - 1)
        let x1 = min(x0 + 1, width - 1), y1 = min(y0 + 1, height - 1)
        let fx = Float(x - Double(x0)), fy = Float(y - Double(y0))
        let top = self[x0, y0] * (1 - fx) + self[x1, y0] * fx
        let bottom = self[x0, y1] * (1 - fx) + self[x1, y1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

/// 8-bit RGBA image, row-major, 4 bytes per pixel (R, G, B, A).
public struct RGBAImage: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public var bytes: [UInt8]

    public init(width: Int, height: Int, bytes: [UInt8]) {
        precondition(bytes.count == width * height * 4, "RGBAImage size mismatch")
        self.width = width
        self.height = height
        self.bytes = bytes
    }

    public init(width: Int, height: Int) {
        self.init(width: width, height: height, bytes: [UInt8](repeating: 0, count: width * height * 4))
    }

    /// Luminance like OpenCV's `COLOR_BGR2GRAY` / `COLOR_RGB2GRAY`: 0.299 R + 0.587 G + 0.114 B.
    public func toGray() -> GrayImage {
        var px = [Float](repeating: 0, count: width * height)
        for i in 0..<(width * height) {
            let r = Float(bytes[4 * i]), g = Float(bytes[4 * i + 1]), b = Float(bytes[4 * i + 2])
            px[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        return GrayImage(width: width, height: height, pixels: px)
    }
}
