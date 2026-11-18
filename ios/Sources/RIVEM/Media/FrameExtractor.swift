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

        let ciContext = CIContext(options: [.cacheIntermediates: false])
        let wanted = Set(range.frameIndices)
        let total = max(range.frameIndices.count, 1)
        var index = 0
        var written = 0
        while index < range.end, let sample = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            defer { index += 1 }
            guard CMSampleBufferGetNumSamples(sample) > 0, wanted.contains(index) else { continue }
            guard let pixels = CMSampleBufferGetImageBuffer(sample) else { throw Failure.reader("frame \(index) vacío") }
            let image = try render(pixels, transform: transform, context: ciContext)
            let url = framesFolder.appendingPathComponent(RiverProject.frameFileName(index))
            try autoreleasepool { try ImageCoding.writeJPEG(image, to: url) }
            written += 1
            progress(Double(written) / Double(total))
        }
        if reader.status == .failed { throw Failure.reader(reader.error?.localizedDescription ?? "desconocido") }
        guard written > 0 else { throw Failure.nothingExtracted }
        return written
    }

    /// Upright (track rotation applied) and scaled by `range.factor`.
    private func render(_ buffer: CVPixelBuffer, transform: CGAffineTransform, context: CIContext) throws -> CGImage {
        var image = CIImage(cvPixelBuffer: buffer).transformed(by: transform)
        image = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        if range.factor > 0, range.factor < 1 {
            image = image.transformed(by: CGAffineTransform(scaleX: range.factor, y: range.factor))
        }
        let extent = CGRect(x: 0, y: 0, width: image.extent.width.rounded(.down), height: image.extent.height.rounded(.down))
        guard let cg = context.createCGImage(image, from: extent) else { throw Failure.reader("no se pudo convertir un frame") }
        return cg
    }
}
