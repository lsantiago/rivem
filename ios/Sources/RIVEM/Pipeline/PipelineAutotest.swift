import CoreGraphics
import Foundation
import RiverCore

/// End-to-end check of the measuring pipeline without a camera or a video (`simctl launch … -autotest_pipeline 1`):
/// a synthetic UAV project is built in the workspace and goes through the same pipeline types the screens use —
/// frames → `GeometryPipeline.applyUAV` → `SectionsPipeline` (bathymetry import, `xsections.json`, masks) →
/// `ProcessingPipeline` (PIV) → `ResultsPipeline` (discharge) — and the discharge is compared with the analytic value.
///
/// Synthetic case (all exact by construction except the PIV estimate):
/// - 960×540 frames of Gaussian particles (σ = 1 px) moving 3.6 px "up" (north) per frame, 12 frames, 30 fps,
///   step 1 → dt = 1/30 s; written as desktop-named JPEGs (quality 0.95).
/// - UAV geometry: points (80, 270) and (880, 270) are 12 m apart → 0.015 m/px, so V = 3.6 · 0.015 · 30 = 1.62 m/s.
/// - Trapezoidal bathymetry 0/2, 2/1, 10/1, 12/2 (station/stage, m) with the default level 2 m → wetted area
///   (12 + 8)/2 · 1 = 10 m²; 13 stations every 1 m put the breaks of the bed on stations, so the mid-section sum
///   equals the exact area.
/// - Q = α · V · A = 0.85 · 1.62 · 10 = 13.77 m³/s.
///
/// Tolerance 3 %: geometry, area and the station sampling are exact for a uniform field, so the only error source is
/// the PIV estimate on JPEG-compressed particles. The core's PIV criterion is < 0.05 px on a known displacement
/// (1.4 % of 3.6 px); 3 % leaves twice that margin while still catching any real defect (a wrong dt, fps, pixel size,
/// axis or sign changes Q by far more than 3 %).
enum PipelineAutotest {
    static let projectName = "autotest_sintetico"
    static let width = 960
    static let height = 540
    static let frameCount = 12
    static let fps: Double = 30
    static let metresPerPixel: Double = 0.015
    /// Pixels per frame along y (negative = up = north).
    static let shiftY: Double = -3.6
    static let alpha: Double = 0.85
    static let lineLeft = Point2(80, 270)
    static let lineRight = Point2(880, 270)
    static let bathymetryCSV = "station,stage\n0,2\n2,1\n10,1\n12,2\n"
    static let expectedArea: Double = 10
    static let numStations = 13
    static let tolerance: Double = 0.03

    static var expectedVelocity: Double { abs(shiftY) * metresPerPixel * fps }
    static var expectedQ: Double { alpha * expectedVelocity * expectedArea }

    /// Returns `PASS …` / `FAIL …` lines. The project is kept so the CI can screenshot every step.
    static func run(root: URL) -> [String] {
        var lines: [String] = []
        func report(_ name: String, _ ok: Bool, _ detail: String) {
            let line = "\(ok ? "PASS" : "FAIL") pipeline \(name): \(detail)"
            lines.append(line)
            if ok { AppLog.info(line) } else { AppLog.error(line) }
        }
        let started = Date()
        do {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(projectName))
            let info = VideoInfo(filepath: "sintetico.mp4", name: "sintetico.mp4", duration: Double(frameCount) / fps,
                                 fps: fps, width: width, height: height)
            var project = try RiverProject.create(in: root, name: projectName, footage: .uav, video: info)
            project.settings.json["river_name"] = .string("Canal sintético")

            // 1. Frames (extraction-free: written directly with desktop names) + video_range.
            try FramesPipeline.saveRange(&project, VideoRange(start: 0, end: frameCount, step: 1, factor: 1))
            try writeFrames(project)
            report("frames", project.frameFiles.count == frameCount, "\(project.frameFiles.count) frames \(width)x\(height)")

            // 2. Geometry (UAV, 2 points, known length).
            let frame: RGBAImage = try GeometryPipeline.loadFirstFrame(project)
            let length: Double = lineLeft.distance(to: lineRight) * metresPerPixel
            let uav: UAVOutcome = try GeometryPipeline.applyUAV(&project, pixel1: lineLeft, pixel2: lineRight,
                                                               world1: Point2(0, 0), world2: Point2(length, 0), frame: frame)
            let p2: Point2 = uav.matrix.apply(lineRight)
            let geometryOK: Bool = abs(uav.pixelSize - metresPerPixel) < 1e-12 && abs(p2.x - length) < 1e-9 && abs(p2.y) < 1e-9
                && ProjectFiles.exists(project, ProjectFiles.transformedImage)
            report("geometria UAV", geometryOK, String(format: "%.6f m/px, punto 2 → (%.4f, %.4f) m", uav.pixelSize, p2.x, p2.y))

            // 3. Cross section: bathymetry import, xsections.json, masks.
            let csvURL: URL = FileManager.default.temporaryDirectory.appendingPathComponent("autotest_batimetria.csv")
            try Data(bathymetryCSV.utf8).write(to: csvURL, options: .atomic)
            let imported = try SectionsPipeline.importBathymetry(from: csvURL, into: project.folder, feet: false)
            // Same profile from the in-app editor's trapezoidal template (water width 12 m, depth 1 m, banks 2:1,
            // no freeboard, level 2 m): it must give the very same normalised CSV, and it is the one the section uses.
            let template: Bathymetry = try BathymetryTemplates.make(.trapezoidal, waterWidth: 12, depth: 1, bankSlope: 2,
                                                                    freeboard: 0, level: 2)
            let templateFile: String = try SectionsPipeline.writeBathymetry(
                template, fileName: SectionsPipeline.editorFileName(section: "CS_autotest"), into: project.folder)
            let importedText: String = (try? String(contentsOf: project.resolve(imported.file), encoding: .utf8)) ?? "?"
            let templateText: String = (try? String(contentsOf: project.resolve(templateFile), encoding: .utf8)) ?? "!"
            let templateArea: Double = BathymetryTemplates.area(.trapezoidal, waterWidth: 12, depth: 1, bankSlope: 2)
            report("batimetria plantilla", importedText == templateText && templateText.hasPrefix("station,stage\n")
                   && abs(templateArea - expectedArea) < 1e-12,
                   "\(templateFile) = \(imported.file) (\(template.stations.count) puntos), área de la plantilla \(Fmt.num(templateArea, 3)) m2")
            var draft = SectionDraft(name: "CS_autotest")
            draft.left = lineLeft
            draft.right = lineRight
            draft.bathymetryFile = templateFile
            draft.level = 2
            draft.numStations = numStations
            draft.alpha = alpha
            let sections: SectionsOutcome = try SectionsPipeline.save(&project, drafts: [draft])
            let maskFiles: Bool = ProjectFiles.exists(project, ProjectFiles.maskNPY) && ProjectFiles.exists(project, ProjectFiles.maskPNG)
                && ProjectFiles.exists(project, ProjectFiles.bbox)
            report("secciones y mascara", maskFiles && sections.analysedPixels > 0,
                   "altura ROI \(Fmt.num(sections.heightROI, 1)) m, bbox \(sections.bbox.map { Fmt.num($0, 0) }.joined(separator: ",")), "
                   + "\(sections.analysedPixels) px analizados")

            // 4. PIV.
            let piv: ProcessingOutcome = try ProcessingPipeline.run(&project, parameters: PIVParameters(),
                                                                    progress: { _, _ in }, cancel: CancellationFlag())
            let us: [Double] = piv.results.uMedian.filter { $0.isFinite }
            let vs: [Double] = piv.results.vMedian.filter { $0.isFinite }
            let mu: Double = median(us)
            let mv: Double = median(vs)
            let pivOK: Bool = piv.failed.isEmpty && !vs.isEmpty && abs(mv - shiftY) < 0.1 && abs(mu) < 0.1
            report("PIV", pivOK, String(format: "%ld pares, %ld fallidos, %ld vectores válidos, mediana (u, v) = (%.3f, %.3f) px, esperado (0, %.1f), %.1f s",
                                        piv.pairs, piv.failed.count, vs.count, mu, mv, shiftY, piv.seconds))

            // 5. Discharge.
            let outcome: ResultsOutcome = try ResultsPipeline.compute(&project, interpolate: false, artificialSeeding: false)
            guard let r = outcome.results.first, let q = r.totalQ, let area = r.totalA else {
                throw PipelineError("sin resultados de caudal: \(outcome.errors.joined(separator: "; "))")
            }
            let error: Double = abs(q - expectedQ) / expectedQ
            report("area", abs(area - expectedArea) < 1e-6, String(format: "A = %.6f m2, esperado %.1f", area, expectedArea))
            report("caudal", error <= tolerance && outcome.errors.isEmpty,
                   String(format: "Q = %.4f m3/s, esperado %.4f (alpha %.2f x V %.3f m/s x A %.1f m2), error %.2f %% (tolerancia %.0f %%)",
                          q, expectedQ, alpha, expectedVelocity, expectedArea, error * 100, tolerance * 100))

            // 6. Desktop compatibility of what was written.
            let reopened = try RiverProject.open(project.folder)
            let issues = reopened.compatibilityIssues()
            report("compatibilidad escritorio", issues.isEmpty && reopened.settings.stage == .results,
                   issues.isEmpty ? "etapa \(reopened.settings.stage), sin problemas" : issues.map { $0.message }.joined(separator: "; "))
        } catch {
            report("ejecucion", false, "\(error)")
        }
        AppLog.info("Autotest del pipeline: \(String(format: "%.1f", Date().timeIntervalSince(started))) s")
        return lines
    }

    static func median(_ values: [Double]) -> Double {
        guard !values.isEmpty else { return .nan }
        let s = values.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /// Gaussian particles on a dark background, shifted by `shiftY` px per frame (sub-pixel, rendered analytically).
    static func writeFrames(_ project: RiverProject) throws {
        var rng = SplitMix64(seed: 20_261_118)
        let margin: Double = 20
        let travel: Double = abs(shiftY) * Double(frameCount) + 2 * margin
        let areaHeight: Double = Double(height) + travel
        let density: Double = 0.012
        let count = Int(Double(width + 20) * areaHeight * density)
        var xs: [Double] = []
        var ys: [Double] = []
        var amplitude: [Double] = []
        xs.reserveCapacity(count)
        ys.reserveCapacity(count)
        amplitude.reserveCapacity(count)
        for _ in 0..<count {
            xs.append(rng.uniform() * Double(width + 20) - 10)
            ys.append(rng.uniform() * areaHeight - margin)
            amplitude.append(140 + 90 * rng.uniform())
        }
        let sigma: Double = 1.0
        let twoSigma2: Double = 2 * sigma * sigma
        let framesFolder: URL = project.folder.appendingPathComponent(RiverProject.framesFolder)
        for k in 0..<frameCount {
            var px = [Float](repeating: 25, count: width * height)
            let dy: Double = Double(k) * shiftY
            for p in 0..<count {
                let cx: Double = xs[p]
                let cy: Double = ys[p] + dy
                if cy < -4 || cy > Double(height) + 4 || cx < -4 || cx > Double(width) + 4 { continue }
                let x0: Int = max(Int(cx.rounded(.down)) - 3, 0)
                let x1: Int = min(Int(cx.rounded(.down)) + 4, width - 1)
                let y0: Int = max(Int(cy.rounded(.down)) - 3, 0)
                let y1: Int = min(Int(cy.rounded(.down)) + 4, height - 1)
                if x0 > x1 || y0 > y1 { continue }
                let a: Double = amplitude[p]
                for yy in y0...y1 {
                    let ddy: Double = Double(yy) - cy
                    for xx in x0...x1 {
                        let ddx: Double = Double(xx) - cx
                        px[yy * width + xx] += Float(a * exp(-(ddx * ddx + ddy * ddy) / twoSigma2))
                    }
                }
            }
            for i in 0..<px.count where px[i] > 255 { px[i] = 255 }
            let rgba: RGBAImage = ImageCoding.rgba(fromGray: GrayImage(width: width, height: height, pixels: px))
            guard let cg = ImageCoding.cgImage(rgba) else { throw PipelineError("no se pudo crear el frame sintético \(k)") }
            try ImageCoding.writeJPEG(cg, to: framesFolder.appendingPathComponent(RiverProject.frameFileName(k)))
        }
    }
}

/// Real-video check (`simctl launch … -autotest_video canuelas.mp4`, the video copied into Documents by the CI): a
/// project is created and its frames extracted through the same code as the screens (`ProjectCreation`,
/// `FramesPipeline.extract` → `FrameExtractor` with AVAssetReader), 2 s at step 2; then the first frame and the
/// thumbnail the canvases draw are checked not to be black. A UAV geometry and a section with a template bathymetry
/// are added so the CI can screenshot the geometry and sections steps on a real frame.
enum VideoAutotest {
    static let projectName = "autotest_video"

    static func run(root: URL, videoName: String) async -> [String] {
        var lines: [String] = []
        func report(_ name: String, _ ok: Bool, _ detail: String) {
            let line = "\(ok ? "PASS" : "FAIL") video \(name): \(detail)"
            lines.append(line)
            if ok { AppLog.info(line) } else { AppLog.error(line) }
        }
        let docs: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let source: URL = docs.appendingPathComponent(videoName)
        guard FileManager.default.fileExists(atPath: source.path) else {
            report("archivo", false, "no está \(videoName) en Documents")
            return lines
        }
        do {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(projectName))
            // Evidence for the black-frame investigation (old Core Image path vs. current CPU path).
            let diagnosis: String = await FrameExtractor.diagnoseFirstFrame(source)
            report("diagnostico primer frame", !diagnosis.hasPrefix("error"), diagnosis)

            let metadata: VideoMetadata = try await VideoMetadata.load(source)
            var project: RiverProject = try ProjectCreation.create(root: root, name: projectName, footage: .uav, source: source,
                                                                   metadata: metadata, moveSource: false,
                                                                   riverName: "Cañuelas (ejemplo de RIVeR)", site: "autotest")
            report("metadatos", metadata.width == 1920 && metadata.height == 1080 && metadata.fps > 0,
                   "\(metadata.width)x\(metadata.height), \(Fmt.num(metadata.fps, 3)) fps, \(Fmt.num(metadata.duration, 2)) s")

            let range: VideoRange = FramesPipeline.range(startTime: 0, endTime: 2, step: 2, factor: 1, fps: Double(Int(metadata.fps.rounded())))
            let outcome: FramesPipeline.Outcome = try await FramesPipeline.extract(project, range: range, progress: { _ in })
            project = outcome.project
            let frames: [URL] = project.frameFiles
            let size = frames.first.flatMap { ImageCoding.pixelSize(of: $0) }
            let sizeOK: Bool = size?.width == 1920 && size?.height == 1080
            report("frames", outcome.written == range.frameIndices.count && frames.count == outcome.written && sizeOK,
                   "\(outcome.written) frames (\(range.frameIndices.count) esperados), \(size?.width ?? 0)x\(size?.height ?? 0), "
                   + "primero \(frames.first?.lastPathComponent ?? "-")")

            var firstMean: Double = -1
            if let first = frames.first, let cg = try? ImageCoding.cgImage(contentsOf: first) {
                firstMean = ImageCoding.meanLuminance(cg)
            }
            report("brillo primer frame", firstMean > 20 && firstMean < 235, "brillo medio \(Fmt.num(firstMean, 1)) (0-255)")

            // Fallback path (AVAssetImageGenerator, used when the reader returns black buffers on a device): same
            // frame count and file N.jpg must hold the same source frame as the reader's N.jpg (closer to it than
            // to the next extracted frame).
            let altFolder: URL = FileManager.default.temporaryDirectory.appendingPathComponent("autotest-generator", isDirectory: true)
            var alt = FrameExtractor(video: source, range: range)
            alt.forceGenerator = true
            let altOutcome = try await alt.extractReporting(into: altFolder)
            let altFrames: [URL] = ((try? FileManager.default.contentsOfDirectory(at: altFolder, includingPropertiesForKeys: nil)) ?? [])
                .filter { $0.pathExtension == "jpg" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
            var same: Double = -1
            var next: Double = -1
            if frames.count >= 2, altFrames.count == frames.count,
               let a0 = try? ImageCoding.cgImage(contentsOf: altFrames[0]),
               let r0 = try? ImageCoding.cgImage(contentsOf: frames[0]),
               let r1 = try? ImageCoding.cgImage(contentsOf: frames[1]) {
                same = meanAbsDiff(a0, r0)
                next = meanAbsDiff(a0, r1)
            }
            report("extraccion alternativa", altOutcome.method == .generator && altOutcome.written == outcome.written
                   && altFrames.map(\.lastPathComponent) == frames.map(\.lastPathComponent) && same >= 0 && same < next && same < 4,
                   "\(altOutcome.written) frames con AVAssetImageGenerator; diferencia media con el mismo frame del lector "
                   + "\(Fmt.num(same, 2)), con el siguiente \(Fmt.num(next, 2)) (0-255)")
            try? FileManager.default.removeItem(at: altFolder)

            // The image the canvases draw: WorkflowModel's thumbnail.
            let model = WorkflowModel(folder: project.folder)
            let thumbMean: Double = model.firstFrame.map { ImageCoding.meanLuminance($0) } ?? -1
            report("miniatura del lienzo", model.firstFrame != nil && thumbMean > 20,
                   "\(model.firstFrame?.width ?? 0)x\(model.firstFrame?.height ?? 0), brillo medio \(Fmt.num(thumbMean, 1)), "
                   + "tamaño de frame \(Int(model.frameSize.width))x\(Int(model.frameSize.height))")

            // Geometry + a section with a template bathymetry (for the screenshots of steps 2 and 3).
            let frame: RGBAImage = try GeometryPipeline.loadFirstFrame(project)
            _ = try GeometryPipeline.applyUAV(&project, pixel1: Point2(360, 540), pixel2: Point2(1560, 540),
                                              world1: Point2(0, 0), world2: Point2(24, 0), frame: frame)
            let bathymetry: Bathymetry = try BathymetryTemplates.make(.rectangular, waterWidth: 20, depth: 1.2, bankSlope: 0,
                                                                      freeboard: 0.5, level: 10)
            let file: String = try SectionsPipeline.writeBathymetry(
                bathymetry, fileName: SectionsPipeline.editorFileName(section: "CS_1"), into: project.folder)
            var draft = SectionDraft(name: "CS_1")
            draft.left = Point2(410, 620)
            draft.right = Point2(1510, 600)
            draft.bathymetryFile = file
            draft.level = 10
            let sections: SectionsOutcome = try SectionsPipeline.save(&project, drafts: [draft])
            let width: Double = sections.sections.sections.first?.realWorldLength ?? 0
            report("geometria y seccion", abs(width - 20) < 0.01 && sections.analysedPixels > 0,
                   "ancho mojado \(Fmt.num(width, 3)) m (plantilla rectangular 20 m), altura ROI \(Fmt.num(sections.heightROI, 1)) m")
        } catch {
            report("ejecucion", false, "\(error)")
        }
        return lines
    }

    /// Mean absolute difference (0–255) of the luminance of two images, compared at 480 px wide.
    static func meanAbsDiff(_ a: CGImage, _ b: CGImage) -> Double {
        let w = 480
        let h: Int = max(1, Int((Double(w) * Double(a.height) / Double(max(a.width, 1))).rounded()))
        let x: RGBAImage = ImageCoding.rgba(a, width: w, height: h)
        let y: RGBAImage = ImageCoding.rgba(b, width: w, height: h)
        var sum = 0.0
        for i in 0..<(w * h) {
            let lx: Double = 0.299 * Double(x.bytes[4 * i]) + 0.587 * Double(x.bytes[4 * i + 1]) + 0.114 * Double(x.bytes[4 * i + 2])
            let ly: Double = 0.299 * Double(y.bytes[4 * i]) + 0.587 * Double(y.bytes[4 * i + 1]) + 0.114 * Double(y.bytes[4 * i + 2])
            sum += abs(lx - ly)
        }
        return sum / Double(w * h)
    }
}

/// Small deterministic generator (SplitMix64) so the synthetic frames are identical on every run.
struct SplitMix64 {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z: UInt64 = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Uniform in [0, 1).
    mutating func uniform() -> Double {
        Double(next() >> 11) / 9_007_199_254_740_992.0
    }
}
