import Foundation
import RiverCore

/// Step 0 (desktop "New project"): project folder + `settings.json` + the video copied inside the project.
enum ProjectCreation {
    /// ASCII folder/file name (desktop paths go through Latin-1 on Windows, docs/desktop-format.md §6.1).
    static func safeName(_ proposed: String, fallback: String = "aforo") -> String {
        let folded: String = proposed.folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
        var out = ""
        for ch in folded.unicodeScalars {
            if ch.isASCII, CharacterSet.alphanumerics.contains(ch) || ch == "-" || ch == "_" {
                out.unicodeScalars.append(ch)
            } else if ch == " " || ch == "." {
                out.append("_")
            }
        }
        while out.hasPrefix("_") { out.removeFirst() }
        return out.isEmpty ? fallback : out
    }

    /// Video file name inside the project: sanitised base name + lower-case extension.
    static func videoFileName(for source: URL) -> String {
        let ext: String = source.pathExtension.lowercased()
        let base: String = safeName(source.deletingPathExtension().lastPathComponent, fallback: "video")
        return ext.isEmpty ? base + ".mov" : base + "." + ext
    }

    /// Creates `<root>/<name>/<YYYYMMDDTHHMM>/` with `settings.json`, empty `frames/` and the video. `video.filepath`
    /// stores only the file name: desktop relocates project-owned paths by name, and for the video it opens the
    /// project anyway and offers "Locate" when the path does not exist (§6.2).
    /// - Parameter moveSource: move (temporary copies from Photos / the camera) instead of copying.
    static func create(root: URL, name: String, footage: Footage, source: URL, metadata: VideoMetadata,
                       moveSource: Bool, riverName: String = "", site: String = "") throws -> RiverProject {
        let fileName: String = videoFileName(for: source)
        let info = VideoInfo(filepath: fileName, name: fileName, duration: metadata.duration, fps: metadata.fps,
                             width: metadata.width, height: metadata.height)
        var project = try RiverProject.create(in: root, name: safeName(name), footage: footage, video: info)
        if !riverName.isEmpty || !site.isEmpty {
            // Desktop writes these metadata keys at the report step (`setProjectMetadata.ts`).
            if !riverName.isEmpty { project.settings.json["river_name"] = .string(riverName) }
            if !site.isEmpty { project.settings.json["site"] = .string(site) }
            try project.save()
        }
        let dest: URL = project.folder.appendingPathComponent(fileName)
        do {
            if moveSource {
                try FileManager.default.moveItem(at: source, to: dest)
            } else {
                try FileManager.default.copyItem(at: source, to: dest)
            }
        } catch {
            try? FileManager.default.removeItem(at: project.folder)
            throw PipelineError("No se pudo copiar el video al proyecto: \(error.localizedDescription)")
        }
        AppLog.info("Proyecto creado \(project.folder.lastPathComponent) (\(footage.rawValue)), video \(fileName) "
            + "\(metadata.width)x\(metadata.height) \(String(format: "%.3f", metadata.fps)) fps \(String(format: "%.2f", metadata.duration)) s")
        return project
    }
}

/// Step 1 (desktop "Video Range"): `video_range` and `frames/`.
enum FramesPipeline {
    /// Desktop defaults: whole video, step 1, factor 1 (`projectSlice.ts`), `end = floor(duration · fps)`.
    static func defaultRange(_ video: VideoInfo) -> VideoRange {
        let fps: Double = video.fps ?? 30
        let duration: Double = video.duration ?? 0
        return VideoRange(start: 0, end: Int((duration * fps).rounded(.down)), step: 1, factor: 1)
    }

    /// Desktop converts the times typed in the form with `floor(t · fps)` (`useProjectSlice.ts`).
    static func range(startTime: Double, endTime: Double, step: Int, factor: Double, fps: Double) -> VideoRange {
        let start: Int = Int((max(startTime, 0) * fps).rounded(.down))
        let end: Int = Int((max(endTime, 0) * fps).rounded(.down))
        return VideoRange(start: start, end: end, step: max(step, 1), factor: factor)
    }

    /// Writes `video_range` (+ the keys desktop writes with it). No lens correction or stabilisation is applied.
    static func saveRange(_ project: inout RiverProject, _ range: VideoRange) throws {
        project.settings.videoRange = range
        project.settings.json["lens_correction"] = .null
        project.settings.json["stabilization"] = .null
        project.settings.json["stabilization_regions_path"] = .null
        try project.save()
    }

    struct Outcome {
        let project: RiverProject
        let written: Int
        /// The frame size changed while a geometry existed: the pixel coordinates no longer match the frames.
        let sizeChanged: Bool
    }

    /// Extracts the frames (`FrameExtractor`), then writes `video_range`. PIV results are discarded (they belong
    /// to the previous frames); geometry and sections are kept.
    static func extract(_ project: RiverProject, range: VideoRange,
                        progress: @escaping @Sendable (Double) -> Void) async throws -> Outcome {
        guard let video = ProjectFiles.videoURL(project) else {
            throw PipelineError("El video no está en la carpeta del proyecto (\(project.settings.video?.name ?? "?")).")
        }
        guard range.end > range.start, !range.frameIndices.isEmpty else {
            throw PipelineError("El rango no contiene frames: revisa inicio, fin y paso.")
        }
        let before = ProjectFiles.frameSize(project)
        let hadMatrix: Bool = (try? project.readMatrix()) != nil
        let started = Date()
        let extractor = FrameExtractor(video: video, range: range)
        let framesFolder: URL = project.folder.appendingPathComponent(RiverProject.framesFolder)
        let written: Int = try await extractor.extract(into: framesFolder, progress: progress)
        var updated = project
        try saveRange(&updated, range)
        if updated.settings.pivResultsPath != nil {
            ProjectFiles.remove(updated, [RiverProject.pivResultsFile, "_stats_cache.json"])
            updated.settings.pivResultsPath = nil
            try updated.save()
        }
        let after = ProjectFiles.frameSize(updated)
        var changed = false
        if let b = before, let a = after, hadMatrix, b.width != a.width || b.height != a.height { changed = true }
        AppLog.info("Frames extraídos: \(written) (inicio \(range.start), fin \(range.end), paso \(range.step), factor \(range.factor)) "
            + "en \(String(format: "%.1f", Date().timeIntervalSince(started))) s")
        return Outcome(project: updated, written: written, sizeChanged: changed)
    }
}
