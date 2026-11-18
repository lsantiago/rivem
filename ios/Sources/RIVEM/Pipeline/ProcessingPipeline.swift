import Foundation
import RiverCore

struct ProcessingOutcome {
    let pairs: Int
    let failed: [PIVFailedPair]
    let seconds: Double
    let rows: Int
    let columns: Int
    let parallel: Bool
    let maskUsed: Bool
    let results: PIVResults
}

/// Step 4 (desktop "Processing" → `analyze-all`): PIV over every pair of consecutive frames with the mask and
/// bounding box of the sections, written as `piv_results.json` + `settings.piv_results` + `settings.processing`
/// (docs/desktop-format.md §2.1, §2.5).
enum ProcessingPipeline {
    /// First-pass windows offered by desktop.
    static let interrogationAreas: [Int] = [512, 256, 128, 64]

    /// Parameters stored in `settings.processing`, or desktop's defaults.
    static func parameters(_ settings: ProjectSettings) -> PIVParameters {
        if let o = settings.json["processing"]?.objectValue { return PIVParameters(processing: o) }
        return PIVParameters()
    }

    /// `settings.processing` with desktop's keys and order (`getQuiver.ts`). STIV/iWave flags already present are
    /// kept (the app does not run them); otherwise false.
    static func processingJSON(_ p: PIVParameters, roiHeight: Double?, existing: JSONObject?) -> JSONObject {
        let roi: Double = roiHeight ?? existing?["roi_height"]?.doubleValue ?? p.roiHeight ?? 0
        let roiValue: JSONValue = roi == roi.rounded() && abs(roi) < 1e9 ? .int(Int(roi)) : .double(roi)
        let stiv: JSONValue = existing?["stiv"] ?? .bool(false)
        let iwave: JSONValue = existing?["iwave"] ?? .bool(false)
        let iwaveManual: JSONValue = existing?["iwave_manual"] ?? .null
        return JSONObject([
            ("interrogation_area_1", .int(p.interrogationArea1)),
            ("interrogation_area_2", .int(p.interrogationArea1 / 2)),
            ("roi_height", roiValue),
            ("remove_background", .bool(p.removeBackground)),
            ("clahe", .bool(p.clahe)),
            ("clip_limit", .double(p.clipLimit)),
            ("std_filtering", .bool(p.stdFiltering)),
            ("std_threshold", .double(p.stdThreshold)),
            ("median_test_filtering", .bool(p.medianTestFiltering)),
            ("median_test_epsilon", .double(p.medianTestEpsilon)),
            ("median_test_threshold", .double(p.medianTestThreshold)),
            ("stiv", stiv),
            ("iwave", iwave),
            ("iwave_manual", iwaveManual),
        ])
    }

    /// Pairs run concurrently only for frames up to ~1080p: each worker holds two decoded frames plus their
    /// preprocessed copies (~25 MB per 1080p frame), so 4K frames run one pair at a time to stay far from the
    /// memory limit.
    static func shouldRunParallel(width: Int, height: Int) -> Bool {
        width * height <= 2_100_000 && ProcessInfo.processInfo.activeProcessorCount > 1
    }

    /// Runs PIV and writes the results. Synchronous: call it off the main thread.
    /// - Parameter progress: `(pairsDone, pairsTotal)`, called from worker threads.
    static func run(_ project: inout RiverProject, parameters: PIVParameters, parallel: Bool? = nil,
                    progress: @escaping (Int, Int) -> Void, cancel: CancellationFlag) throws -> ProcessingOutcome {
        try parameters.validate()
        let frames: [URL] = project.frameFiles
        guard frames.count >= 2 else { throw PipelineError("Se necesitan al menos 2 frames (hay \(frames.count)).") }
        guard let size = ImageCoding.pixelSize(of: frames[0]) else { throw PipelineError("No se pudo leer el primer frame.") }
        let bbox: [Double]? = ProjectFiles.readBBox(project)
        var mask: PIVMask? = nil
        if let m = ProjectFiles.readMask(project) {
            if m.width == size.width && m.height == size.height && m.width > 0 && m.height > 0 {
                mask = PIVMask(width: m.width, height: m.height, values: m.values)
            } else {
                AppLog.error("La máscara (\(m.width)x\(m.height)) no coincide con los frames (\(size.width)x\(size.height)); se ignora")
            }
        }
        let useParallel: Bool = parallel ?? shouldRunParallel(width: size.width, height: size.height)
        let bboxText: String = bbox.map { (b: [Double]) -> String in b.map { Fmt.num($0, 0) }.joined(separator: ",") } ?? "-"
        AppLog.info("PIV: \(frames.count) frames \(size.width)x\(size.height), IA \(parameters.interrogationArea1)/\(parameters.effectiveInterrogationArea2), "
            + "paralelo \(useParallel), máscara \(mask != nil), bbox \(bboxText)")
        let started = Date()
        let frameReader: (Int) throws -> GrayImage = { (i: Int) throws -> GrayImage in
            try autoreleasepool { () throws -> GrayImage in
                try ImageCoding.gray(contentsOf: frames[i])
            }
        }
        let result: PIVSequenceResult
        do {
            result = try PIVSequence.run(frameCount: frames.count, frame: frameReader, parameters: parameters,
                                         bbox: bbox, mask: mask, background: nil, parallel: useParallel,
                                         progress: progress, isCancelled: { cancel.isCancelled })
        } catch PIVError.cancelled {
            AppLog.info("PIV cancelado por el usuario")
            throw PIVError.cancelled
        }
        try result.results.write(project.folder.appendingPathComponent(RiverProject.pivResultsFile))
        ProjectFiles.remove(project, ["_stats_cache.json"])
        project.settings.pivResultsPath = RiverProject.pivResultsFile
        let existing: JSONObject? = project.settings.json["processing"]?.objectValue
        project.settings.json["processing"] = .object(processingJSON(parameters, roiHeight: nil, existing: existing))
        try project.save()
        let seconds: Double = Date().timeIntervalSince(started)
        for f in result.failedPairs { AppLog.error("PIV: par \(f.index) fallido: \(f.reason)") }
        AppLog.info("PIV terminado: \(frames.count - 1) pares, \(result.failedPairs.count) fallidos, rejilla "
            + "\(result.results.rows)x\(result.results.columns), \(String(format: "%.1f", seconds)) s")
        return ProcessingOutcome(pairs: frames.count - 1, failed: result.failedPairs, seconds: seconds,
                                 rows: result.results.rows, columns: result.results.columns,
                                 parallel: useParallel, maskUsed: mask != nil, results: result.results)
    }
}
