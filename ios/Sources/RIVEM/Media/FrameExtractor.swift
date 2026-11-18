import AVFoundation
import CoreGraphics
import CoreImage
import Foundation
import RiverCore

/// What the app needs to know about a video to create a project (desktop's `settings.video` block).
struct VideoMetadata: Equatable {
    let duration: Double
    /// Nominal frame rate as reported by the file (desktop stores it rounded to an integer).
    let fps: Double
    /// Size after applying the track's rotation (portrait iPhone videos are tall), like desktop's metadata.
    let width: Int
    let height: Int
    /// Estimated number of frames (duration × fps).
    var frameCount: Int { Int((duration * fps).rounded()) }

    static func load(_ url: URL) async throws -> VideoMetadata {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw FrameExtractor.Failure.noVideoTrack
        }
        let (size, transform, rate) = try await track.load(.naturalSize, .preferredTransform, .nominalFrameRate)
        let duration = try await asset.load(.duration).seconds
        let rect = CGRect(origin: .zero, size: size).applying(transform)
        return VideoMetadata(duration: duration, fps: Double(rate),
                             width: Int(abs(rect.width).rounded()), height: Int(abs(rect.height).rounded()))
    }
}

/// Extracts frames from a video into a project's `frames/` folder with desktop's naming and selection rules:
/// source frame index `i` is written as `{i:010d}.jpg` when `i % step == 0` and `start <= i < end`
/// (`video_to_frames.py`), JPEG quality 95, optionally downscaled by `factor`.
///
/// Fixes over desktop (docs/PLAN.md #2): frames are decoded sequentially and counted one by one, so file `N.jpg`
/// really holds source frame N (desktop's file N holds frame N+1), and any decoding error stops the extraction with
/// an error instead of leaving silent gaps.
struct FrameExtractor {
    enum Failure: Error, CustomStringConvertible {
        case noVideoTrack
        case reader(String)
        case nothingExtracted
        var description: String {
            switch self {
            case .noVideoTrack: return "El archivo no tiene pista de video."
            case let .reader(why): return "No se pudo leer el video: \(why)"
            case .nothingExtracted: return "No se extrajo ningún frame: revisa el rango."
            }
        }
    }

    let video: URL
    let range: VideoRange

    /// Returns the number of frames written. `progress` receives values in 0…1.
    func extract(into framesFolder: URL, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> Int {
        let fm = FileManager.default
        try fm.createDirectory(at: framesFolder, withIntermediateDirectories: true)
        // Desktop lists every file in frames/: start from a clean folder.
        for name in (try? fm.contentsOfDirectory(atPath: framesFolder.path)) ?? [] {
            try fm.removeItem(at: framesFolder.appendingPathComponent(name))
        }

        let asset = AVURLAsset(url: video)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw Failure.noVideoTrack }
        let transform = try await track.load(.preferredTransform)
        let reader: AVAssetReader
        do { reader = try AVAssetReader(asset: asset) } catch { throw Failure.reader(error.localizedDescription) }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ])
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        defer { if reader.status == .reading { reader.cancelReading() } }

        let wanted = Set(range.frameIndices)
        let total = max(range.frameIndices.count, 1)
        var index = 0
        var written = 0
        while index < range.end, let sample = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            defer { index += 1 }
            guard CMSampleBufferGetNumSamples(sample) > 0, wanted.contains(index) else { continue }
            guard let pixels = CMSampleBufferGetImageBuffer(sample) else { throw Failure.reader("frame \(index) vacío") }
            let url = framesFolder.appendingPathComponent(RiverProject.frameFileName(index))
            let first: Bool = written == 0
            try autoreleasepool {
                let image: CGImage = try render(pixels, transform: transform)
                try ImageCoding.writeJPEG(image, to: url)
                if first {
                    let mean: Double = ImageCoding.meanLuminance(image)
                    AppLog.info("Primer frame \(url.lastPathComponent): \(image.width)x\(image.height), brillo medio \(String(format: "%.1f", mean))")
                    if mean < 3 { AppLog.error("El primer frame extraído es casi negro (brillo medio \(String(format: "%.2f", mean)))") }
                }
            }
            written += 1
            progress(Double(written) / Double(total))
        }
        if reader.status == .failed { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        guard written > 0 else { throw Failure.nothingExtracted }
        return written
    }

    /// Upright (track rotation applied) and scaled by `range.factor`.
    ///
    /// CPU path, no Core Image: the BGRA bytes are copied out of the decoder's buffer (the reader recycles it,
    /// `alwaysCopiesSampleData = false`) into a CGImage that *ignores* the alpha byte (`noneSkipFirst`), then rotated
    /// and scaled with Core Graphics only when needed. The previous `CIImage(cvPixelBuffer:)` + `CIContext` path
    /// depended on how Core Image interprets the buffer's alpha and attachments, which differs between decoders/devices.
    private func render(_ buffer: CVPixelBuffer, transform: CGAffineTransform) throws -> CGImage {
        let image: CGImage = try Self.cgImage(from: buffer)
        let factor: CGFloat = (range.factor > 0 && range.factor < 1) ? CGFloat(range.factor) : 1
        if transform.isIdentity && factor == 1 { return image }
        return try Self.orient(image, transform: transform, factor: factor)
    }

    static let sRGB: CGColorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    /// Copy of a 32BGRA pixel buffer as an opaque CGImage.
    static func cgImage(from buffer: CVPixelBuffer) throws -> CGImage {
        let format: OSType = CVPixelBufferGetPixelFormatType(buffer)
        guard format == kCVPixelFormatType_32BGRA else { throw Failure.reader("formato de píxel inesperado (\(format))") }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { throw Failure.reader("frame sin datos") }
        let width: Int = CVPixelBufferGetWidth(buffer)
        let height: Int = CVPixelBufferGetHeight(buffer)
        let bytesPerRow: Int = CVPixelBufferGetBytesPerRow(buffer)
        let data = Data(bytes: base, count: bytesPerRow * height)
        guard let provider = CGDataProvider(data: data as CFData) else { throw Failure.reader("no se pudo copiar un frame") }
        let info = CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)
        guard let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: bytesPerRow,
                                  space: sRGB, bitmapInfo: info, provider: provider, decode: nil,
                                  shouldInterpolate: false, intent: .defaultIntent) else {
            throw Failure.reader("no se pudo crear la imagen de un frame")
        }
        return image
    }

    /// Applies the track's `preferredTransform` (defined in y-down pixel coordinates) and a scale factor.
    static func orient(_ image: CGImage, transform: CGAffineTransform, factor: CGFloat) throws -> CGImage {
        let w = CGFloat(image.width)
        let h = CGFloat(image.height)
        let rect: CGRect = CGRect(x: 0, y: 0, width: w, height: h).applying(transform)
        let outW: Int = max(1, Int((abs(rect.width) * factor).rounded(.down)))
        let outH: Int = max(1, Int((abs(rect.height) * factor).rounded(.down)))
        guard let ctx = CGContext(data: nil, width: outW, height: outH, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            throw Failure.reader("no se pudo girar o escalar un frame")
        }
        ctx.interpolationQuality = .high
        // User space with y down (the space of preferredTransform)…
        ctx.translateBy(x: 0, y: CGFloat(outH))
        ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: factor, y: factor)
        ctx.concatenate(transform.concatenating(CGAffineTransform(translationX: -rect.minX, y: -rect.minY)))
        // …and CGContext.draw expects y up for the image itself.
        ctx.translateBy(x: 0, y: h)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let out = ctx.makeImage() else { throw Failure.reader("no se pudo girar o escalar un frame") }
        return out
    }

    /// Evidence for the "black frame" investigation: decodes the first video frame and compares the previous Core
    /// Image path with the current one. One line: pixel format, size, alpha byte range, mean brightness of both.
    static func diagnoseFirstFrame(_ video: URL) async -> String {
        do {
            let asset = AVURLAsset(url: video)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else { return "sin pista de video" }
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            ])
            output.alwaysCopiesSampleData = false
            reader.add(output)
            guard reader.startReading() else { return "el lector no arrancó" }
            defer { reader.cancelReading() }
            guard let sample = output.copyNextSampleBuffer(), let buffer = CMSampleBufferGetImageBuffer(sample) else {
                return "sin primer frame"
            }
            var alphaMin: Int = 255
            var alphaMax: Int = 0
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                let bytes: UnsafeMutablePointer<UInt8> = base.assumingMemoryBound(to: UInt8.self)
                let bpr: Int = CVPixelBufferGetBytesPerRow(buffer)
                let width: Int = CVPixelBufferGetWidth(buffer)
                let height: Int = CVPixelBufferGetHeight(buffer)
                for y in Swift.stride(from: 0, to: height, by: 37) {
                    for x in Swift.stride(from: 0, to: width, by: 41) {
                        let a: Int = Int(bytes[y * bpr + 4 * x + 3])
                        alphaMin = min(alphaMin, a)
                        alphaMax = max(alphaMax, a)
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, .readOnly)
            let legacyContext = CIContext(options: [.cacheIntermediates: false])
            let ci = CIImage(cvPixelBuffer: buffer)
            var legacy: Double = -1
            if let cg = legacyContext.createCGImage(ci, from: ci.extent) { legacy = ImageCoding.meanLuminance(cg) }
            let current: Double = ImageCoding.meanLuminance(try cgImage(from: buffer))
            let format: String = fourCC(CVPixelBufferGetPixelFormatType(buffer))
            let w: Int = CVPixelBufferGetWidth(buffer)
            let h: Int = CVPixelBufferGetHeight(buffer)
            return "formato \(format), \(w)x\(h), alfa \(alphaMin)-\(alphaMax), brillo medio con CIContext (antes) "
                + String(format: "%.1f", legacy) + ", con copia CPU (ahora) " + String(format: "%.1f", current)
        } catch {
            return "error: \(error)"
        }
    }

    static func fourCC(_ v: OSType) -> String {
        let chars: [UInt8] = [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
        return String(decoding: chars, as: UTF8.self)
    }
}
