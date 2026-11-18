import Foundation
import RiverCore

/// A cross-section being edited (what the desktop "Cross Sections" form holds before `set-sections`).
struct SectionDraft: Identifiable, Equatable {
    let id: UUID
    var name: String
    /// Drawn line on the first frame (pixels), left bank first.
    var left: Point2?
    var right: Point2?
    /// Bathymetry CSV inside the project folder (`station,stage`, SI).
    var bathymetryFile: String?
    /// Water level (m); nil = desktop's default (lower of the two end elevations).
    var level: Double?
    var leftStation: Double
    var numStations: Int
    var alpha: Double

    init(name: String) {
        id = UUID()
        self.name = name
        left = nil
        right = nil
        bathymetryFile = nil
        level = nil
        leftStation = 0
        numStations = SectionBuilder.defaultNumStations
        alpha = SectionBuilder.defaultAlpha
    }

    var isComplete: Bool { left != nil && right != nil && bathymetryFile != nil }
}

struct SectionsOutcome {
    let sections: CrossSections
    let heightROI: Double
    let bbox: [Double]
    let analysedPixels: Int
}

/// Step 3 (desktop "Cross Sections"): bathymetry copied into the project, `xsections.json` with the 22 GUI keys
/// (`SectionBuilder`), then the ROI mask (`mask.npy`, `mask.png`, `bbox.json`) and `processing.roi_height`
/// (docs/desktop-format.md §2.4, §2.6, §2.7).
enum SectionsPipeline {
    /// Reads a bathymetry file (CSV/TSV, `Bathymetry.parse`) and writes it normalised (`station,stage`, comma, SI)
    /// into the project folder. A different file with the same name is never overwritten (`_2`, `_3`…), like
    /// desktop's `copyIntoProject`. Returns the file name stored in `bath`.
    static func importBathymetry(from source: URL, into folder: URL, feet: Bool) throws -> (file: String, bathymetry: Bathymetry) {
        let scoped: Bool = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: source)
        let text: String = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        let bathymetry: Bathymetry = try Bathymetry.parse(csv: text, feet: feet)
        let base: String = ProjectCreation.safeName(source.deletingPathExtension().lastPathComponent, fallback: "batimetria")
        let csv: String = bathymetry.csv
        var name = base + ".csv"
        var n = 2
        while true {
            let url: URL = folder.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: url.path) { break }
            if let existing = try? String(contentsOf: url, encoding: .utf8), existing == csv { break }
            name = "\(base)_\(n).csv"
            n += 1
        }
        try bathymetry.write(folder.appendingPathComponent(name))
        AppLog.info("Batimetría importada como \(name): \(bathymetry.stations.count) puntos")
        return (name, bathymetry)
    }

    static func readBathymetry(_ project: RiverProject, file: String) throws -> Bathymetry {
        try Bathymetry.read(project.resolve(file))
    }

    /// File name of a bathymetry made in the app's editor for a section.
    static func editorFileName(section: String) -> String {
        ProjectCreation.safeName(section, fallback: "seccion") + "_batimetria.csv"
    }

    /// Writes a bathymetry made in the app (editor, template, pasted CSV) as the same normalised `station,stage` CSV
    /// the import produces. The editor owns `fileName`, so an existing file with that name is replaced.
    static func writeBathymetry(_ bathymetry: Bathymetry, fileName: String, into folder: URL) throws -> String {
        // Round trip through the parser: same normalisation (increasing stations, ≥ 2 points) as an imported file.
        let normalised: Bathymetry = try Bathymetry.parse(csv: bathymetry.csv)
        try normalised.write(folder.appendingPathComponent(fileName))
        AppLog.info("Batimetría escrita como \(fileName): \(normalised.stations.count) puntos")
        return fileName
    }

    /// Desktop's default water level for a bathymetry (`getBathimetryLimitsY`), nil if the level does not cross it.
    static func defaultLevel(_ bathymetry: Bathymetry) -> Double? {
        (try? SectionBuilder.limits(bathymetry: bathymetry, level: nil))?.level
    }

    /// Drafts from an existing `xsections.json` (desktop or app), to edit them again.
    static func drafts(from sections: CrossSections) -> [SectionDraft] {
        sections.sections.map { (s: CrossSection) -> SectionDraft in
            var d = SectionDraft(name: s.name)
            let j = s.json
            if let x = j["dir_xl"]?.doubleValue, let y = j["dir_yl"]?.doubleValue { d.left = Point2(x, y) } else { d.left = s.pixelLeft }
            if let x = j["dir_xr"]?.doubleValue, let y = j["dir_yr"]?.doubleValue { d.right = Point2(x, y) } else { d.right = s.pixelRight }
            if let b = s.bathymetryPath { d.bathymetryFile = RiverProject.basename(b) }
            d.level = s.level
            d.leftStation = s.leftStation ?? 0
            d.numStations = s.numStations ?? SectionBuilder.defaultNumStations
            d.alpha = s.alpha ?? SectionBuilder.defaultAlpha
            return d
        }
    }

    /// Builds and writes every section, then the masks. In ipcam projects the matrix depends on the water level:
    /// it is recomputed at the level of the first section and every section uses that level (desktop shares one
    /// level per ipcam project). PIV results are discarded because the mask changes.
    static func save(_ project: inout RiverProject, drafts: [SectionDraft], windowSize: Int = 128) throws -> SectionsOutcome {
        guard !drafts.isEmpty else { throw PipelineError("Agrega al menos una sección.") }
        var names = Set<String>()
        var list: [SectionDraft] = []
        for d in drafts {
            var copy = d
            copy.name = CrossSections.safeName(d.name)
            guard !names.contains(copy.name) else { throw PipelineError("Hay dos secciones llamadas \(copy.name).") }
            names.insert(copy.name)
            guard copy.left != nil, copy.right != nil else { throw PipelineError("\(copy.name): dibuja la línea con dos toques.") }
            guard copy.bathymetryFile != nil else { throw PipelineError("\(copy.name): importa la batimetría.") }
            guard copy.numStations >= 2 else { throw PipelineError("\(copy.name): se necesitan al menos 2 estaciones.") }
            list.append(copy)
        }

        let matrix: Matrix3
        if project.settings.footage == .ipcam {
            guard let camera = ProjectFiles.readCamera(project) else {
                throw PipelineError("Falta camera_solution_3d.json: resuelve la cámara en el paso de geometría.")
            }
            let first: SectionDraft = list[0]
            var level: Double? = first.level
            if level == nil, let file = first.bathymetryFile, let b = try? readBathymetry(project, file: file) {
                level = defaultLevel(b)
            }
            guard let z = level else { throw PipelineError("Indica el nivel del agua de la primera sección.") }
            matrix = try GeometryPipeline.setIpcamLevel(&project, camera: camera, level: z)
            for i in list.indices { list[i].level = z }
        } else {
            guard let m = try project.readMatrix() else { throw PipelineError("Falta la matriz: completa el paso de geometría.") }
            matrix = m
        }

        var built: [CrossSection] = []
        for d in list {
            guard let file = d.bathymetryFile, let l = d.left, let r = d.right else { continue }
            let bathymetry: Bathymetry
            do {
                bathymetry = try readBathymetry(project, file: file)
            } catch {
                throw PipelineError("\(d.name): no se pudo leer la batimetría \(file): \(error)")
            }
            do {
                let section: CrossSection = try SectionBuilder.build(
                    name: d.name, drawnLeft: l, drawnRight: r, matrix: matrix, bathymetry: bathymetry,
                    bathymetryPath: file, leftStation: d.leftStation, level: d.level,
                    numStations: d.numStations, alpha: d.alpha)
                built.append(section)
            } catch {
                throw PipelineError("\(d.name): \(error)")
            }
        }
        let sections = CrossSections(sections: built)

        // The mask (and so the PIV field) changes: drop old results.
        ProjectFiles.remove(project, [RiverProject.pivResultsFile, "_stats_cache.json"])
        project.settings.pivResultsPath = nil
        try project.writeCrossSections(sections)
        let masks = try writeMasks(&project, sections: sections, matrix: matrix, windowSize: windowSize)
        AppLog.info("Secciones guardadas: \(built.count); altura ROI \(Fmt.num(masks.heightROI, 1)) m; bbox \(masks.bbox.map { Fmt.num($0, 1) })")
        return SectionsOutcome(sections: sections, heightROI: masks.heightROI, bbox: masks.bbox, analysedPixels: masks.mask.count)
    }

    /// `recommend-height-roi` + `create-mask-and-bbox` (dry islands from the wet segments excluded), written as
    /// `mask.npy`, `mask.png`, `bbox.json`, and `processing.roi_height`. User masks are not supported in the app:
    /// any `final_mask.*` left by desktop is removed so the new ROI is the one used.
    static func writeMasks(_ project: inout RiverProject, sections: CrossSections, matrix: Matrix3,
                           windowSize: Int) throws -> (heightROI: Double, bbox: [Double], mask: ROIMask) {
        guard let size = ProjectFiles.frameSize(project) else { throw PipelineError("No hay frames para dimensionar la máscara.") }
        var roiSections: [ROISection] = []
        for s in sections.sections {
            var wet: [ClosedRange<Double>] = []
            if let url = project.bathymetryURL(for: s), let b = try? Bathymetry.read(url), let level = s.level {
                for seg in SectionProfile.wetSegments(stations: b.stations, stages: b.elevations, level: level) where seg.1 >= seg.0 {
                    wet.append(seg.0...seg.1)
                }
            }
            guard let roi = ROISection(s, wetSegments: wet) else { throw PipelineError("\(s.name): faltan los extremos de la sección.") }
            roiSections.append(roi)
        }
        let height: Double = try ROIMasks.recommendHeightROI(sections: roiSections, matrix: matrix, windowSize: windowSize)
        let result = try ROIMasks.sectionMask(width: size.width, height: size.height, sections: roiSections,
                                              matrix: matrix, heightROI: height)
        guard result.mask.count > 0 else { throw PipelineError("La máscara quedó vacía: la sección cae fuera de la imagen.") }
        try NPY.encode(result.mask).write(to: ProjectFiles.url(project, ProjectFiles.maskNPY), options: .atomic)
        try ImageCoding.writePNG(result.mask.overlayImage(), to: ProjectFiles.url(project, ProjectFiles.maskPNG))
        try JSONWriter(indent: nil, nonFinite: .fail).write(JSONValue.doubles(result.bbox), to: ProjectFiles.url(project, ProjectFiles.bbox))
        ProjectFiles.remove(project, [ProjectFiles.finalMaskNPY, ProjectFiles.finalMaskPNG])
        let parameters: PIVParameters = ProcessingPipeline.parameters(project.settings)
        let existing: JSONObject? = project.settings.json["processing"]?.objectValue
        project.settings.json["processing"] = .object(ProcessingPipeline.processingJSON(parameters, roiHeight: height, existing: existing))
        try project.save()
        return (height, result.bbox, result.mask)
    }
}
