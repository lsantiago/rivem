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
        case black(String)
        var description: String {
            switch self {
            case let .black(why): return "Los frames salen negros (\(why)). Exporta el registro desde Diagnóstico."
            case .noVideoTrack: return "El archivo no tiene pista de video."
            case let .reader(why): return "No se pudo leer el video: \(why)"
            case .nothingExtracted: return "No se extrajo ningún frame: revisa el rango."
            }
        }
    }

    enum Method: String {
        /// AVAssetReader decoding to 32BGRA (fast, sequential).
        case reader
        /// AVAssetImageGenerator at each frame's exact presentation time (Apple's own conversion, colour managed).
        case generator
    }

    let video: URL
    let range: VideoRange
    /// Skips the reader (CI uses it to check the fallback path gives the same frames).
    var forceGenerator = false

    /// Raised internally when the reader's first frame is black: the extraction is redone with the generator.
    private struct BlackFrame: Error { let diagnosis: String }

    /// Returns the number of frames written. `progress` receives values in 0…1.
    func extract(into framesFolder: URL, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> Int {
        try await extractReporting(into: framesFolder, progress: progress).written
    }

    /// Same as `extract`, also returning the method that produced the frames.
    ///
    /// On some devices the reader hands back all-zero BGRA buffers for some files (seen on an iPhone 14 Pro, iOS 27,
    /// with a video imported from Photos: mean brightness 0.0 with both Core Image and a plain CPU copy). When the
    /// first frame is black the diagnosis is logged and the frames are extracted again with AVAssetImageGenerator;
    /// if that is black too, the extraction fails with the diagnosis instead of leaving black frames.
    func extractReporting(into framesFolder: URL,
                          progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> (written: Int, method: Method) {
        let asset = AVURLAsset(url: video)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw Failure.noVideoTrack }
        let trackInfo: String = await Self.describe(track: track)
        AppLog.info("Video \(video.lastPathComponent): \(trackInfo)")
        if !forceGenerator {
            do {
                let n: Int = try await extractWithReader(asset: asset, track: track, into: framesFolder, progress: progress)
                return (n, .reader)
            } catch let black as BlackFrame {
                AppLog.error("Lector de video: primer frame negro (\(black.diagnosis)); se reintenta con AVAssetImageGenerator")
            }
        }
        let n: Int = try await extractWithGenerator(asset: asset, track: track, into: framesFolder, progress: progress)
        return (n, .generator)
    }

    private static func cleanFolder(_ framesFolder: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: framesFolder, withIntermediateDirectories: true)
        // Desktop lists every file in frames/: start from a clean folder.
        for name in (try? fm.contentsOfDirectory(atPath: framesFolder.path)) ?? [] {
            try fm.removeItem(at: framesFolder.appendingPathComponent(name))
        }
    }

    private func extractWithReader(asset: AVURLAsset, track: AVAssetTrack, into framesFolder: URL,
                                   progress: @escaping @Sendable (Double) -> Void) async throws -> Int {
        try Self.cleanFolder(framesFolder)
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
            var blackDiagnosis: String? = nil
            try autoreleasepool {
                let image: CGImage = try render(pixels, transform: transform)
                if first {
                    let mean: Double = ImageCoding.meanLuminance(image)
                    AppLog.info("Primer frame \(url.lastPathComponent) (lector): \(image.width)x\(image.height), brillo medio \(String(format: "%.1f", mean))")
                    if mean < 3 {
                        blackDiagnosis = "brillo medio " + String(format: "%.2f", mean) + ", " + Self.describe(buffer: pixels)
                        return
                    }
                }
                try ImageCoding.writeJPEG(image, to: url)
            }
            if let diagnosis = blackDiagnosis { throw BlackFrame(diagnosis: diagnosis) }
            written += 1
            progress(Double(written) / Double(total))
        }
        if reader.status == .failed { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        guard written > 0 else { throw Failure.nothingExtracted }
        return written
    }

    /// Fallback path: every wanted frame is requested at its exact presentation time (zero tolerance), so file `N.jpg`
    /// still holds source frame N. Frame times come from the compressed samples (no decoding), sorted into
    /// presentation order.
    private func extractWithGenerator(asset: AVURLAsset, track: AVAssetTrack, into framesFolder: URL,
                                      progress: @escaping @Sendable (Double) -> Void) async throws -> Int {
        try Self.cleanFolder(framesFolder)
        let times: [CMTime] = try await Self.presentationTimes(asset: asset, track: track)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let factor: CGFloat = (range.factor > 0 && range.factor < 1) ? CGFloat(range.factor) : 1
        let indices: [Int] = range.frameIndices.filter { $0 < times.count }
        let total: Int = max(indices.count, 1)
        var written = 0
        for index in indices {
            try Task.checkCancellation()
            let url: URL = framesFolder.appendingPathComponent(RiverProject.frameFileName(index))
            let generated: CGImage
            do {
                generated = try await generator.image(at: times[index]).image
            } catch {
                throw Failure.reader("frame \(index): \(error.localizedDescription)")
            }
            let image: CGImage = factor == 1 ? generated : try Self.orient(generated, transform: .identity, factor: factor)
            if written == 0 {
                let mean: Double = ImageCoding.meanLuminance(image)
                AppLog.info("Primer frame \(url.lastPathComponent) (AVAssetImageGenerator): \(image.width)x\(image.height), "
                    + "brillo medio \(String(format: "%.1f", mean))")
                if mean < 0.5 { throw Failure.black("brillo medio " + String(format: "%.2f", mean) + " también con AVAssetImageGenerator") }
            }
            try ImageCoding.writeJPEG(image, to: url)
            written += 1
            progress(Double(written) / Double(total))
        }
        guard written > 0 else { throw Failure.nothingExtracted }
        return written
    }

    /// Presentation times of the displayable samples, in presentation order (read without decoding).
    static func presentationTimes(asset: AVAsset, track: AVAssetTrack) async throws -> [CMTime] {
        let reader: AVAssetReader
        do { reader = try AVAssetReader(asset: asset) } catch { throw Failure.reader(error.localizedDescription) }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        var times: [CMTime] = []
        while let sample = output.copyNextSampleBuffer() {
            guard CMSampleBufferGetNumSamples(sample) > 0 else { continue }
            if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
               let first = attachments.first, (first[kCMSampleAttachmentKey_DoNotDisplay] as? Bool) == true {
                continue
            }
            let t: CMTime = CMSampleBufferGetOutputPresentationTimeStamp(sample)
            if t.isValid, t.isNumeric, t.seconds >= 0 { times.append(t) }
        }
        if reader.status == .failed { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        times.sort { CMTimeCompare($0, $1) < 0 }
        var unique: [CMTime] = []
        for t in times where unique.last.map({ CMTimeCompare($0, t) != 0 }) ?? true { unique.append(t) }
        return unique
    }

    /// Codec, size and colour tags of the video track (logged with every extraction).
    static func describe(track: AVAssetTrack) async -> String {
        guard let descriptions = try? await track.load(.formatDescriptions), let d = descriptions.first else {
            return "sin descripción de formato"
        }
        let codec: String = fourCC(CMFormatDescriptionGetMediaSubType(d))
        let dims: CMVideoDimensions = CMVideoFormatDescriptionGetDimensions(d)
        let ext: [CFString: Any] = (CMFormatDescriptionGetExtensions(d) as? [CFString: Any]) ?? [:]
        let transfer: String = (ext[kCMFormatDescriptionExtension_TransferFunction] as? String) ?? "-"
        let primaries: String = (ext[kCMFormatDescriptionExtension_ColorPrimaries] as? String) ?? "-"
        let matrix: String = (ext[kCMFormatDescriptionExtension_YCbCrMatrix] as? String) ?? "-"
        let depth: String = (ext[kCMFormatDescriptionExtension_Depth] as? NSNumber)?.stringValue ?? "-"
        let fullRange: String = (ext[kCMFormatDescriptionExtension_FullRangeVideo] as? Bool).map { $0 ? "sí" : "no" } ?? "-"
        return "códec \(codec), \(dims.width)x\(dims.height), profundidad \(depth), transferencia \(transfer), "
            + "primarios \(primaries), matriz \(matrix), rango completo \(fullRange)"
    }

    /// Pixel format, layout, byte ranges per channel (sampled grid) and colour attachments of a decoded buffer.
    static func describe(buffer: CVPixelBuffer) -> String {
        let formatType: OSType = CVPixelBufferGetPixelFormatType(buffer)
        let width: Int = CVPixelBufferGetWidth(buffer)
        let height: Int = CVPixelBufferGetHeight(buffer)
        let bpr: Int = CVPixelBufferGetBytesPerRow(buffer)
        let planes: Int = CVPixelBufferGetPlaneCount(buffer)
        let surface: Bool = CVPixelBufferGetIOSurface(buffer) != nil
        var ranges = "-"
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        if formatType == kCVPixelFormatType_32BGRA, let base = CVPixelBufferGetBaseAddress(buffer) {
            let bytes: UnsafeMutablePointer<UInt8> = base.assumingMemoryBound(to: UInt8.self)
            var lo: [Int] = [255, 255, 255, 255]
            var hi: [Int] = [0, 0, 0, 0]
            for y in Swift.stride(from: 0, to: height, by: 37) {
                for x in Swift.stride(from: 0, to: width, by: 41) {
                    for c in 0..<4 {
                        let v: Int = Int(bytes[y * bpr + 4 * x + c])
                        lo[c] = min(lo[c], v)
                        hi[c] = max(hi[c], v)
                    }
                }
            }
            ranges = "B \(lo[0])-\(hi[0]) G \(lo[1])-\(hi[1]) R \(lo[2])-\(hi[2]) A \(lo[3])-\(hi[3])"
        }
        CVPixelBufferUnlockBaseAddress(buffer, .readOnly)
        let attachments: [String: Any] = (CVBufferCopyAttachments(buffer, .shouldPropagate) as? [String: Any]) ?? [:]
        let transfer: String = (attachments[kCVImageBufferTransferFunctionKey as String] as? String) ?? "-"
        let primaries: String = (attachments[kCVImageBufferColorPrimariesKey as String] as? String) ?? "-"
        return "píxeles \(fourCC(formatType)) \(width)x\(height), \(bpr) bytes/fila, planos \(planes), IOSurface \(surface ? "sí" : "no"), "
            + "bytes \(ranges), transferencia \(transfer), primarios \(primaries)"
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
