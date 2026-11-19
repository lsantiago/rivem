import CoreGraphics
import Foundation
import Observation
import RiverCore

/// Steps of the guided workflow, mirroring desktop's wizard (Footage → Video Range → Pixel Size / Control Points →
/// Cross Sections → Processing → Results → Report).
enum WorkflowStep: String, CaseIterable, Identifiable, Hashable {
    case summary, video, geometry, sections, processing, results, export

    var id: String { rawValue }

    var title: String {
        switch self {
        case .summary: return "Resumen del proyecto"
        case .video: return "1 · Video y frames"
        case .geometry: return "2 · Georreferenciación"
        case .sections: return "3 · Secciones transversales"
        case .processing: return "4 · Procesamiento PIV"
        case .results: return "5 · Resultados"
        case .export: return "6 · Exportar"
        }
    }

    /// Name for the wizard's previous / next buttons.
    var shortTitle: String {
        switch self {
        case .summary: return "Resumen"
        case .video: return "Video"
        case .geometry: return "Georreferenciación"
        case .sections: return "Secciones"
        case .processing: return "PIV"
        case .results: return "Resultados"
        case .export: return "Exportar"
        }
    }

    /// Steps of the wizard bar, in order (the summary is reached from the step menu).
    static let wizard: [WorkflowStep] = [.video, .geometry, .sections, .processing, .results, .export]

    var symbol: String {
        switch self {
        case .summary: return "doc.text.magnifyingglass"
        case .video: return "film.stack"
        case .geometry: return "ruler"
        case .sections: return "chart.xyaxis.line"
        case .processing: return "wind"
        case .results: return "drop.fill"
        case .export: return "square.and.arrow.up"
        }
    }
}

/// State of one project for the workflow screens. Every step writes to the project folder in desktop format
/// through the pipeline types and then calls `reload()`, so what is shown is always what is on disk.
@Observable
final class WorkflowModel {
    let folder: URL
    private(set) var project: RiverProject?
    private(set) var loadError: String?
    private(set) var frameCount: Int = 0
    /// Downscaled first frame for display.
    private(set) var firstFrame: CGImage?
    /// Full-resolution size of the frames (pixel space of the geometry files).
    private(set) var frameSize: CGSize = .zero
    private(set) var hasMatrix = false
    private(set) var hasSections = false
    private(set) var hasMask = false
    private(set) var hasResults = false
    private(set) var hasVideo = false
    private(set) var maskOverlay: CGImage?
    /// Bumped on every reload: views that read files (PIV field…) refresh with `.task(id: model.revision)`.
    private(set) var revision: Int = 0
    private var thumbnailKey: String = ""

    init(folder: URL) {
        self.folder = folder
        reload()
    }

    var settings: ProjectSettings? { project?.settings }
    var footage: Footage? { project?.settings.footage }
    var units: UnitDisplay { UnitDisplay(system: project?.settings.unitSystem ?? .si) }
    var displayName: String {
        project?.settings.riverName ?? folder.deletingLastPathComponent().lastPathComponent
    }

    func reload() {
        revision += 1
        do {
            let p = try RiverProject.open(folder)
            project = p
            loadError = nil
            let frames: [URL] = p.frameFiles
            frameCount = frames.count
            if let first = frames.first {
                let attrs = try? FileManager.default.attributesOfItem(atPath: first.path)
                let date: Date = (attrs?[.modificationDate] as? Date) ?? Date.distantPast
                let key = "\(first.lastPathComponent)-\(date.timeIntervalSince1970)"
                if key != thumbnailKey || firstFrame == nil {
                    thumbnailKey = key
                    firstFrame = ImageCoding.thumbnail(contentsOf: first)
                    if let size = ImageCoding.pixelSize(of: first) {
                        frameSize = CGSize(width: size.width, height: size.height)
                    }
                }
            } else {
                firstFrame = nil
                frameSize = .zero
                thumbnailKey = ""
            }
            hasMatrix = (try? p.readMatrix()) != nil
            hasSections = ((try? p.readCrossSections())?.sections.isEmpty == false)
            hasMask = ProjectFiles.exists(p, ProjectFiles.maskNPY) || ProjectFiles.exists(p, ProjectFiles.finalMaskNPY)
            hasResults = p.settings.pivResultsPath != nil && ProjectFiles.exists(p, RiverProject.pivResultsFile)
            hasVideo = ProjectFiles.videoURL(p) != nil
            let maskName: String = ProjectFiles.exists(p, ProjectFiles.finalMaskPNG) ? ProjectFiles.finalMaskPNG : ProjectFiles.maskPNG
            let maskURL: URL = ProjectFiles.url(p, maskName)
            maskOverlay = hasMask ? ImageCoding.thumbnail(contentsOf: maskURL) : nil
        } catch {
            project = nil
            loadError = "\(error)"
        }
    }

    /// Applies a change to the project (pipeline call), then reloads from disk.
    func update(_ change: (inout RiverProject) throws -> Void) throws {
        guard var p = project else { throw PipelineError("El proyecto no está abierto.") }
        defer { reload() }
        try change(&p)
        project = p
    }

    /// Replaces the project after a background computation, then reloads.
    func replace(_ p: RiverProject) {
        project = p
        reload()
    }

    func isAvailable(_ step: WorkflowStep) -> Bool {
        guard project != nil else { return step == .summary }
        switch step {
        case .summary, .video, .export: return true
        case .geometry: return frameCount > 0
        case .sections: return frameCount > 0 && hasMatrix
        case .processing: return frameCount > 1 && hasSections
        case .results: return hasResults && hasSections
        }
    }

    func isDone(_ step: WorkflowStep) -> Bool {
        switch step {
        case .summary, .export: return false
        case .video: return frameCount > 0 && project?.settings.videoRange != nil
        case .geometry: return hasMatrix
        case .sections: return hasSections && hasMask
        case .processing, .results: return hasResults
        }
    }

    /// The step to continue with when the project is opened.
    var suggestedStep: WorkflowStep? {
        guard project != nil else { return nil }
        let order: [WorkflowStep] = [.video, .geometry, .sections, .processing, .results]
        for s in order where isAvailable(s) && !isDone(s) { return s }
        if hasResults { return .results }
        return nil
    }

    /// Short message for a step that is not available yet.
    func requirement(_ step: WorkflowStep) -> String {
        switch step {
        case .geometry: return "Primero extrae los frames del video."
        case .sections: return "Primero completa la georreferenciación."
        case .processing: return "Primero define al menos una sección."
        case .results: return "Primero ejecuta el procesamiento PIV."
        default: return ""
        }
    }
}
