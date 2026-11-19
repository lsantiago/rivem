import Foundation
import RiverCore

/// One gauging of a site, for the rating curve.
struct SiteGauging: Identifiable, Hashable {
    let folder: URL
    var id: URL { folder }
    /// `YYYYMMDDTHHMM` folder name.
    let stamp: String
    /// Water level of the first section (m).
    let level: Double
    /// Mean discharge over the sections (m³/s), like desktop's summary.
    let discharge: Double

    var date: Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd'T'HHmm"
        return f.date(from: String(stamp.prefix(13)))
    }
}

/// RIVEM addition: a **site** is a project-name folder of the workspace (`RIVeR/<name>/`), the same grouping desktop
/// uses; every dated folder inside is a visit. A new visit reuses the georeferencing, sections, bathymetry, mask and
/// PIV settings of an earlier one, so on site only the video and the water level are needed.
enum SitePipeline {
    /// Settings keys that belong to one visit and are not carried over.
    static let visitKeys: Set<String> = ["creation_date", "video", "video_range", "piv_results", "lens_correction", "stabilization",
                                         "stabilization_regions_path", "footage", templateKey]
    /// Folder of the visit the configuration came from (RIVEM key, desktop ignores it).
    static let templateKey = "rivem_site_template"
    /// Files of the template that are not copied (results and things of that visit).
    static let skippedFiles: Set<String> = [RiverProject.settingsFile, RiverProject.pivResultsFile, "_stats_cache.json"]
    static let videoExtensions: Set<String> = ["mov", "mp4", "m4v", "avi", "mkv", "mts"]
    /// `xsections.json` keys written by the discharge computation (`DischargeCalculator.apply`).
    static let resultKeys: Set<String> = [
        "id", "east", "north", "distance", "wet_segments", "x", "y", "displacement_x", "displacement_y", "displacement_east",
        "displacement_north", "streamwise_east", "streamwise_north", "crosswise_east", "crosswise_north",
        "streamwise_velocity_magnitude", "streamwise_x", "streamwise_y", "depth", "check", "minus_std", "plus_std",
        "5th_percentile", "95th_percentile", "seeded_vel_profile", "W", "A", "Q", "Q_minus_std", "Q_plus_std", "Q_portion",
        "total_Q", "total_q_std", "measured_Q", "interpolated_Q", "total_A", "total_W", "max_depth", "average_depth", "mean_V",
        "mean_Vs", "interpolated", "artificial_seeding", "_stats_cache",
    ]

    static func siteName(_ project: RiverProject) -> String { project.folder.deletingLastPathComponent().lastPathComponent }
    static func siteFolder(_ project: RiverProject) -> URL { project.folder.deletingLastPathComponent() }

    /// New visit of the template's site with this video (moved or copied into it) and, optionally, the new water
    /// level (oblique / UAV; a fixed camera changes its level in the Sections step, which recomputes its matrix).
    static func newVisit(template: RiverProject, source: URL, metadata: VideoMetadata, moveSource: Bool,
                         level: Double?) throws -> RiverProject {
        guard let footage = template.settings.footage else { throw PipelineError("El aforo de referencia no tiene tipo de toma.") }
        if let v = template.settings.video, let w = v.width, let h = v.height, w > 0, h > 0,
           w != metadata.width || h != metadata.height {
            throw PipelineError("El video nuevo es de \(metadata.width)×\(metadata.height) y el de referencia de \(w)×\(h): "
                + "los puntos de la imagen no coincidirían. Graba con la misma resolución y orientación.")
        }
        let root: URL = siteFolder(template).deletingLastPathComponent()
        var project: RiverProject = try ProjectCreation.create(root: root, name: siteName(template), footage: footage,
                                                               source: source, metadata: metadata, moveSource: moveSource)
        do {
            try carry(from: template, into: &project, level: level)
        } catch {
            try? FileManager.default.removeItem(at: project.folder)
            throw error
        }
        AppLog.info("Visita nueva \(project.folder.lastPathComponent) del sitio \(siteName(template)) con la configuración de "
            + "\(template.folder.lastPathComponent)" + (level.map { String(format: ", nivel %.3f m", $0) } ?? ""))
        return project
    }

    /// Copies the template's configuration into a project that has its own video: settings (except the visit's own
    /// keys), files (except video and results), sections without results, the same frame step and resize factor.
    static func carry(from template: RiverProject, into project: inout RiverProject, level: Double?) throws {
        let fm = FileManager.default
        let ownVideo: String? = project.settings.video?.filepath.map(RiverProject.basename)
        let items: [URL] = (try? fm.contentsOfDirectory(at: template.folder, includingPropertiesForKeys: [.isRegularFileKey])) ?? []
        for url in items {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
            let name: String = url.lastPathComponent
            if skippedFiles.contains(name) || videoExtensions.contains(url.pathExtension.lowercased()) || name == ownVideo { continue }
            let dest: URL = project.folder.appendingPathComponent(name)
            if fm.fileExists(atPath: dest.path) { continue }
            try fm.copyItem(at: url, to: dest)
        }

        var json: JSONObject = project.settings.json
        for entry in template.settings.json where !visitKeys.contains(entry.key) { json[entry.key] = entry.value }
        json[templateKey] = .string(template.folder.lastPathComponent)
        project.settings.json = json
        if let previous = template.settings.videoRange, let video = project.settings.video {
            var range: VideoRange = FramesPipeline.defaultRange(video)
            range.step = previous.step
            range.factor = previous.factor
            project.settings.videoRange = range
        }
        try project.save()

        if var sections = try project.readCrossSections() {
            for i in sections.sections.indices {
                var o: JSONObject = sections.sections[i].json
                for key in o.keys where resultKeys.contains(key) || key.hasPrefix("filled_") { o[key] = nil }
                if let l = level, project.settings.footage != .ipcam { o["level"] = .double(l) }
                sections.sections[i].json = o
            }
            sections.summary = nil
            try project.writeCrossSections(sections)
        }
    }

    /// Visits of the site with a discharge (results computed), oldest first.
    static func gaugings(siteFolder: URL) -> [SiteGauging] {
        let folders: [URL] = RiverWorkspace.projectFolders(in: siteFolder.deletingLastPathComponent())
            .filter { $0.deletingLastPathComponent().standardizedFileURL == siteFolder.standardizedFileURL }
        var out: [SiteGauging] = []
        for f in folders {
            guard let p = try? RiverProject.open(f), p.settings.pivResultsPath != nil,
                  let sections = try? p.readCrossSections() else { continue }
            let qs: [Double] = sections.sections.compactMap { $0.totalDischarge }.filter { $0.isFinite && $0 > 0 }
            guard !qs.isEmpty, let level = sections.sections.compactMap({ $0.level }).first(where: { $0.isFinite }) else { continue }
            out.append(SiteGauging(folder: f, stamp: f.lastPathComponent, level: level, discharge: qs.reduce(0, +) / Double(qs.count)))
        }
        return out.sorted { $0.stamp < $1.stamp }
    }
}
