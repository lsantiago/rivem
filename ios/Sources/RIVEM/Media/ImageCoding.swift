import CoreGraphics
import Foundation
import ImageIO
import RiverCore
import UniformTypeIdentifiers

/// Conversions between image files / CGImage and the core's pixel buffers (`RGBAImage`, `GrayImage`).
enum ImageCoding {
    enum Failure: Error, CustomStringConvertible {
        case unreadable(URL)
        case encode(String)
        var description: String {
            switch self {
            case let .unreadable(url): return "No se pudo leer la imagen \(url.lastPathComponent)."
            case let .encode(why): return "No se pudo guardar la imagen: \(why)."
            }
        }
    }

    static func cgImage(contentsOf url: URL) throws -> CGImage {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw Failure.unreadable(url) }
        return img
    }

    /// Draws `image` into an RGBA8 buffer of the given size (resampled if it differs).
    static func rgba(_ image: CGImage, width: Int? = nil, height: Int? = nil) -> RGBAImage {
        let w = width ?? image.width, h = height ?? image.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        bytes.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return RGBAImage(width: w, height: h, bytes: bytes)
    }

    static func gray(contentsOf url: URL) throws -> GrayImage {
        rgba(try cgImage(contentsOf: url)).toGray()
    }

    static func cgImage(_ image: RGBAImage) -> CGImage? {
        let data = Data(image.bytes) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// JPEG at quality 0.95, like RIVeR desktop's extracted frames (`cv2.IMWRITE_JPEG_QUALITY, 95`).
    static func writeJPEG(_ image: CGImage, to url: URL, quality: Double = 0.95) throws {
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw Failure.encode(url.lastPathComponent)
        }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw Failure.encode(url.lastPathComponent) }
    }

    static func writePNG(_ image: CGImage, to url: URL) throws {
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw Failure.encode(url.lastPathComponent)
        }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { throw Failure.encode(url.lastPathComponent) }
    }

    /// CGImage over an `RGBAImage` with straight (non-premultiplied) alpha, which is what the core produces
    /// (`transformed_image.png` has colour under alpha 0; `mask.png` is black with alpha 111).
    static func cgImageStraightAlpha(_ image: RGBAImage) -> CGImage? {
        let data = Data(image.bytes) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
                       bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// PNG through ImageIO (deflate-compressed, unlike the core's stored-block `PNG.encode`). Falls back to the core
    /// encoder if ImageIO refuses the image.
    static func writePNG(_ image: RGBAImage, to url: URL) throws {
        if let cg = cgImageStraightAlpha(image) {
            do {
                try writePNG(cg, to: url)
                return
            } catch {
                AppLog.error("ImageIO no pudo escribir \(url.lastPathComponent): \(error); se usa el codificador del núcleo")
            }
        }
        try PNG.encode(image).write(to: url, options: .atomic)
    }

    /// Gray 0…255 buffer as an opaque RGBA image (synthetic frames, previews).
    static func rgba(fromGray gray: GrayImage) -> RGBAImage {
        var bytes = [UInt8](repeating: 255, count: gray.width * gray.height * 4)
        for i in 0..<(gray.width * gray.height) {
            let v: Float = gray.pixels[i]
            let b: UInt8 = v <= 0 ? 0 : (v >= 255 ? 255 : UInt8(v.rounded()))
            bytes[4 * i] = b
            bytes[4 * i + 1] = b
            bytes[4 * i + 2] = b
        }
        return RGBAImage(width: gray.width, height: gray.height, bytes: bytes)
    }

    /// Mean luminance (0–255) of an image, on a 64-pixel-wide downscaled copy (cheap "is it black?" check).
    static func meanLuminance(_ image: CGImage) -> Double {
        guard image.width > 0, image.height > 0 else { return 0 }
        let w = 64
        let h: Int = max(1, Int((Double(w) * Double(image.height) / Double(image.width)).rounded()))
        let small: RGBAImage = rgba(image, width: w, height: h)
        var sum = 0.0
        for i in 0..<(w * h) {
            let r = Double(small.bytes[4 * i]), g = Double(small.bytes[4 * i + 1]), b = Double(small.bytes[4 * i + 2])
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return sum / Double(w * h)
    }

    /// Pixel size of an image file without decoding it.
    static func pixelSize(of url: URL) -> (width: Int, height: Int)? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return nil }
        let w: Int? = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue
        let h: Int? = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue
        guard let width = w, let height = h else { return nil }
        // Stored pixel grid, ignoring any EXIF orientation, exactly as `cgImage(contentsOf:)` / OpenCV decode it
        // (frames written by the app and by desktop carry none).
        return (width, height)
    }

    /// Downscaled copy for display (longest side ≤ `maxPixel`), decoded by ImageIO.
    static func thumbnail(contentsOf url: URL, maxPixel: Int = 2048) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: false,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary)
    }
}
