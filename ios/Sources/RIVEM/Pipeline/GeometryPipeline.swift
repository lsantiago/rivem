import Foundation
import RiverCore

/// Rectified preview written with the geometry (`transformed_image.png` / `ortho_image.png`).
struct GeometryPreview: Equatable {
    let imageFile: String
    /// Real-world units per output pixel (`transformation.resolution`).
    let resolution: Double
    /// `[Emin, Emax, Nmin, Nmax]`.
    let extent: [Double]
}

/// Ground reference point of the fixed-station (ipcam) mode, as imported from a GRP file (§4.2).
struct GRPPoint: Identifiable, Equatable {
    /// Position in the imported file (desktop's original index).
    let id: Int
    var label: String
    var X: Double
    var Y: Double
    var Z: Double
    /// Pixel position on the first frame; nil = not marked yet.
    var pixel: Point2?
}

struct UAVOutcome {
    let matrix: Matrix3
    let pixelSize: Double
    let preview: GeometryPreview
}

struct ObliqueOutcome {
    let solution: ObliqueSolution
    /// Distance (m) between each point's solved world position and where the matrix sends its pixel.
    let reprojection: [Double]
    let preview: GeometryPreview
}

struct CameraOutcome {
    let solution: CameraSolution
    /// GRP ids of the points used (marked), in the order of `solution.reprojectionErrors`.
    let usedIDs: [Int]
    let waterLevel: Double
    let preview: GeometryPreview
}

/// Step 2 (desktop "Pixel Size" / "Control Points" / ipcam): matrix, settings keys and rectified preview, written
/// as desktop does (docs/desktop-format.md §2.1, §2.3, §2.8, §3). Writing a matrix discards sections, masks and
/// PIV results (`RiverProject.setMatrix`, like desktop's `createMatrix`).
enum GeometryPipeline {
    static func loadFirstFrame(_ project: RiverProject) throws -> RGBAImage {
        guard let url = project.frameFiles.first else {
            throw PipelineError("No hay frames en el proyecto: extrae el video primero.")
        }
        return ImageCoding.rgba(try ImageCoding.cgImage(contentsOf: url))
    }

    // MARK: UAV

    /// Two points + their real-world coordinates (default (0,0) and (L,0) for a measured length L).
    static func applyUAV(_ project: inout RiverProject, pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2,
                         frame: RGBAImage) throws -> UAVOutcome {
        let size: Double = try UAVTransform.pixelSize(pixel1: pixel1, pixel2: pixel2, world1: world1, world2: world2)
        let matrix: Matrix3 = try UAVTransform.matrix(pixel1: pixel1, pixel2: pixel2, world1: world1, world2: world2)
        let grid: OrthoGrid = try Orthorectifier.uavGrid(matrix: matrix, imageWidth: frame.width, imageHeight: frame.height)
        let ortho: RGBAImage = try Orthorectifier.render(frame, grid: grid, matrix: matrix)

        try project.setMatrix(matrix)
        project.settings.json["control_points"] = nil
        clearIpcamKeys(&project)
        project.settings.pixelSize = PixelSizeSetup(size: size, realWorldLength: world1.distance(to: world2),
                                                    pixel1: pixel1, pixel2: pixel2, world1: world1, world2: world2)
        project.settings.json["transformation"] = .object(JSONObject([
            ("matrix", .string(RiverProject.matrixFile)),
            ("resolution", .double(grid.resolution)),
            ("extent", JSONValue.doubles(grid.extent)),
        ]))
        try project.save()
        try ImageCoding.writePNG(ortho, to: ProjectFiles.url(project, ProjectFiles.transformedImage))
        AppLog.info(String(format: "Geometría UAV: tamaño de píxel %.6f m/px, resolución orto %.4f m/px", size, grid.resolution))
        return UAVOutcome(matrix: matrix, pixelSize: size,
                          preview: GeometryPreview(imageFile: ProjectFiles.transformedImage, resolution: grid.resolution, extent: grid.extent))
    }

    // MARK: Oblique

    static func applyOblique(_ project: inout RiverProject, pixels: [Point2], distances: ObliqueDistances,
                             frame: RGBAImage) throws -> ObliqueOutcome {
        let solution: ObliqueSolution = try ObliqueTransform.solve(pixels: pixels, distances: distances)
        let grid: OrthoGrid = try Orthorectifier.obliqueGrid(matrix: solution.matrix, roi: solution.roi)
        let ortho: RGBAImage = try Orthorectifier.render(frame, grid: grid, matrix: solution.matrix)
        let reprojection: [Double] = Homography.reprojectionErrors(matrix: solution.matrix, pixel: pixels, world: solution.world)

        try project.setMatrix(solution.matrix)
        project.settings.json["pixel_size"] = nil
        clearIpcamKeys(&project)
        project.settings.controlPoints = ControlPointsSetup(pixels: pixels, distances: distances.values)
        project.settings.json["transformation"] = .object(JSONObject([
            ("matrix", .string(RiverProject.matrixFile)),
            ("resolution", .double(grid.resolution)),
            ("extent", JSONValue.doubles(grid.extent)),
            ("roi", JSONValue.doubles(solution.roi)),
        ]))
        try project.save()
        try ImageCoding.writePNG(ortho, to: ProjectFiles.url(project, ProjectFiles.transformedImage))
        let worst: Double = solution.distanceResiduals.map { abs($0) }.max() ?? 0
        AppLog.info(String(format: "Geometría oblicua: residuo máximo de distancias %.4f m", worst))
        return ObliqueOutcome(solution: solution, reprojection: reprojection,
                              preview: GeometryPreview(imageFile: ProjectFiles.transformedImage, resolution: grid.resolution, extent: grid.extent))
    }

    /// Distances CSV like desktop (§4.3): six values in canonical order (one column) or `label,value` rows in any
    /// order (`d12`, `12`, `1-2`, `1_2`, … ; `14` = `d41`). Header optional. Values in metres, or feet if `feet`.
    static func parseDistances(_ text: String, feet: Bool = false) throws -> ObliqueDistances {
        let keys: [String] = ["12", "23", "34", "41", "13", "24"]
        var byKey: [String: Double] = [:]
        var plain: [Double] = []
        let lines: [String] = text.split(whereSeparator: \.isNewline).map { String($0).trimmingCharacters(in: .whitespaces) }
        for line in lines where !line.isEmpty {
            let usesComma: Bool = !line.contains(";") && !line.contains("\t")
            let fields: [String] = line.split(whereSeparator: { usesComma ? $0 == "," : ($0 == ";" || $0 == "\t") })
                .map { String($0).trimmingCharacters(in: .whitespaces) }
            guard let firstField = fields.first else { continue }
            let normalise: (String) -> String = { usesComma ? $0 : $0.replacingOccurrences(of: ",", with: ".") }
            if fields.count >= 2, let value = Double(normalise(fields[1])) {
                var label: String = firstField.lowercased()
                if label.hasPrefix("d") { label.removeFirst() }
                label = label.filter { $0.isNumber }
                if label == "14" { label = "41" }
                if label == "31" { label = "13" }
                if label == "42" { label = "24" }
                if label == "21" { label = "12" }
                if label == "32" { label = "23" }
                if label == "43" { label = "34" }
                if keys.contains(label) {
                    byKey[label] = value
                    continue
                }
            }
            if let value = Double(normalise(firstField)) { plain.append(value) }
        }
        var values: [Double] = []
        if byKey.count == 6 {
            values = keys.compactMap { byKey[$0] }
        } else if plain.count == 6 {
            values = plain
        } else {
            throw PipelineError("Se esperaban 6 distancias (d12, d23, d34, d41, d13, d24).")
        }
        if feet { values = values.map { $0 * 0.3048 } }
        guard values.allSatisfy({ $0.isFinite && $0 >= 0 }), let d = ObliqueDistances(values) else {
            throw PipelineError("Hay distancias no válidas.")
        }
        return d
    }

    // MARK: Fixed station (ipcam, 3-D)

    /// GRP file (§4.2): `Label, X, Y, Z [, x, y]`; header row only if the first cell is `Label`. Pixel (0, 0) means
    /// "not marked". Separators `,`, `;` or tab (decimal comma with the last two).
    static func parseGRP(_ text: String, feet: Bool = false) throws -> [GRPPoint] {
        var out: [GRPPoint] = []
        let lines: [String] = text.split(whereSeparator: \.isNewline).map { String($0).trimmingCharacters(in: .whitespaces) }
        for (n, line) in lines.enumerated() where !line.isEmpty {
            let usesComma: Bool = !line.contains(";") && !line.contains("\t")
            let fields: [String] = line.split(separator: usesComma ? "," : ";", omittingEmptySubsequences: false)
                .flatMap { $0.split(separator: "\t", omittingEmptySubsequences: false) }
                .map { String($0).trimmingCharacters(in: .whitespaces) }
            if n == 0, fields.first?.uppercased() == "LABEL" { continue }
            guard fields.count >= 4 else { throw PipelineError("Línea \(n + 1): se esperan al menos 4 columnas (Label, X, Y, Z).") }
            let normalise: (String) -> String = { usesComma ? $0 : $0.replacingOccurrences(of: ",", with: ".") }
            guard let x = Double(normalise(fields[1])), let y = Double(normalise(fields[2])), let z = Double(normalise(fields[3])) else {
                throw PipelineError("Línea \(n + 1): X, Y, Z no son números.")
            }
            let scale: Double = feet ? 0.3048 : 1
            var point = GRPPoint(id: out.count, label: fields[0], X: x * scale, Y: y * scale, Z: z * scale, pixel: nil)
            if fields.count >= 6, let px = Double(normalise(fields[4])), let py = Double(normalise(fields[5])), !(px == 0 && py == 0) {
                point.pixel = Point2(px, py)
            }
            out.append(point)
        }
        guard !out.isEmpty else { throw PipelineError("El archivo no contiene puntos.") }
        return out
    }

    /// Points stored in `grp_3d.json` (selected + not selected, rebuilt in their original order).
    static func loadGRP(_ project: RiverProject) -> [GRPPoint]? {
        guard let obj = (try? JSON.read(ProjectFiles.url(project, ProjectFiles.grp3D)))?.objectValue else { return nil }
        var found: [(Int, GRPPoint)] = []
        for prefix in ["", "not_selected_"] {
            guard let xs = obj[prefix + "X"]?.doubleArray, let ys = obj[prefix + "Y"]?.doubleArray,
                  let zs = obj[prefix + "Z"]?.doubleArray, let px = obj[prefix + "x"]?.doubleArray,
                  let py = obj[prefix + "y"]?.doubleArray else { continue }
            let labels: [JSONValue] = obj[prefix + "label"]?.arrayValue ?? []
            let n: Int = min(xs.count, ys.count, zs.count, px.count, py.count)
            for i in 0..<n {
                var label = "P\(found.count + 1)"
                var index: Int = 1000 + found.count
                if i < labels.count, let pair = labels[i].arrayValue, pair.count == 2 {
                    label = pair[0].stringValue ?? label
                    index = pair[1].intValue ?? index
                }
                let marked: Bool = !(px[i] == 0 && py[i] == 0) && px[i].isFinite && py[i].isFinite
                found.append((index, GRPPoint(id: index, label: label, X: xs[i], Y: ys[i], Z: zs[i],
                                              pixel: marked ? Point2(px[i], py[i]) : nil)))
            }
        }
        guard !found.isEmpty else { return nil }
        return found.sorted { $0.0 < $1.0 }.map { $0.1 }
    }

    /// Solves the camera with every marked point (desktop "direct-solve"), writes `grp_3d.json`,
    /// `full_grp_3d.json` (when some point is not marked), `camera_solution_3d.json`, `ortho_image.png` and the
    /// matrix of the water plane at `waterLevel` (§2.8, §2.3).
    static func applyCamera3D(_ project: inout RiverProject, points: [GRPPoint], waterLevel: Double,
                              frame: RGBAImage) throws -> CameraOutcome {
        let marked: [GRPPoint] = points.filter { $0.pixel != nil }
        guard marked.count >= 6 else {
            throw PipelineError("Marca al menos 6 puntos en la imagen (hay \(marked.count)).")
        }
        let ground: [GroundPoint3D] = marked.map { p in
            let px: Point2 = p.pixel ?? Point2(0, 0)
            return GroundPoint3D(X: p.X, Y: p.Y, Z: p.Z, x: px.x, y: px.y)
        }
        let solution: CameraSolution = try CameraCalibration.solve(points: ground)
        let cam = try Orthorectifier.cameraGrid(points: ground)
        let ortho: RGBAImage = Orthorectifier.render(frame, grid: cam.grid, camera: solution.camera, z: cam.meanZ)
        let matrix: Matrix3 = try solution.camera.pixelToWorld(z: waterLevel)

        // grp_3d.json (desktop's calculate-3d-rectification, indent 2)
        let unmarked: [GRPPoint] = points.filter { $0.pixel == nil }
        var grp = JSONObject()
        grp["X"] = JSONValue.doubles(marked.map { $0.X })
        grp["Y"] = JSONValue.doubles(marked.map { $0.Y })
        grp["Z"] = JSONValue.doubles(marked.map { $0.Z })
        grp["x"] = JSONValue.doubles(marked.map { $0.pixel?.x ?? 0 })
        grp["y"] = JSONValue.doubles(marked.map { $0.pixel?.y ?? 0 })
        grp["label"] = .array(marked.map { JSONValue.array([.string($0.label), .int($0.id)]) })
        grp["image"] = .array(marked.map { _ in JSONValue.null })
        grp["not_selected_X"] = JSONValue.doubles(unmarked.map { $0.X })
        grp["not_selected_Y"] = JSONValue.doubles(unmarked.map { $0.Y })
        grp["not_selected_Z"] = JSONValue.doubles(unmarked.map { $0.Z })
        grp["not_selected_x"] = JSONValue.doubles(unmarked.map { _ in 0 })
        grp["not_selected_y"] = JSONValue.doubles(unmarked.map { _ in 0 })
        grp["not_selected_label"] = .array(unmarked.map { JSONValue.array([.string($0.label), .int($0.id)]) })
        grp["not_selected_image"] = .array(unmarked.map { _ in JSONValue.null })
        grp["solution"] = .string("direct-solve")
        let writer2 = JSONWriter(indent: 2, nonFinite: .fail)
        try writer2.write(.object(grp), to: ProjectFiles.url(project, ProjectFiles.grp3D))
        if unmarked.isEmpty {
            ProjectFiles.remove(project, [ProjectFiles.fullGRP3D])
        } else {
            let full = JSONObject([
                ("X", JSONValue.doubles(marked.map { $0.X })), ("Y", JSONValue.doubles(marked.map { $0.Y })),
                ("Z", JSONValue.doubles(marked.map { $0.Z })),
                ("x", JSONValue.doubles(marked.map { $0.pixel?.x ?? 0 })), ("y", JSONValue.doubles(marked.map { $0.pixel?.y ?? 0 })),
            ])
            try writer2.write(.object(full), to: ProjectFiles.url(project, ProjectFiles.fullGRP3D))
        }

        // camera_solution_3d.json (Python's get_camera_solution + CLI, indent 4; JSON.parse-safe: no NaN)
        let ellipses: [JSONValue] = solution.uncertaintyEllipses.map { e in
            JSONValue.object(JSONObject([
                ("center", .array([.finiteOrNull(e.center.x), .finiteOrNull(e.center.y)])),
                ("width", .finiteOrNull(e.width)), ("height", .finiteOrNull(e.height)), ("angle", .finiteOrNull(e.angle)),
            ]))
        }
        let solutionJSON = JSONObject([
            ("camera_matrix", .array(solution.camera.rows.map { JSONValue.doubles($0) })),
            ("camera_position", .array(solution.cameraPosition.map { JSONValue.finiteOrNull($0) })),
            ("error", .finiteOrNull(solution.meanError)),
            ("reprojection_errors", .array(solution.reprojectionErrors.map { JSONValue.finiteOrNull($0) })),
            ("projected_points", .array(solution.projectedPoints.map { JSONValue.array([.finiteOrNull($0.x), .finiteOrNull($0.y)]) })),
            ("uncertainty_ellipses", .array(ellipses)),
            ("ortho_extent", JSONValue.doubles(cam.grid.extent)),
            ("ortho_image_path", .string(ProjectFiles.orthoImage)),
        ])
        try JSONWriter(indent: 4, nonFinite: .fail).write(.object(solutionJSON), to: ProjectFiles.url(project, ProjectFiles.cameraSolution))
        try ImageCoding.writePNG(ortho, to: ProjectFiles.url(project, ProjectFiles.orthoImage))

        try project.setMatrix(matrix)
        project.settings.json["pixel_size"] = nil
        project.settings.json["control_points"] = nil
        project.settings.json["transformation"] = .object(JSONObject([("matrix", .string(RiverProject.matrixFile))]))
        project.settings.json["grp_3d"] = .string(ProjectFiles.grp3D)
        project.settings.json["camera_solution_3d"] = .string(ProjectFiles.cameraSolution)
        try project.save()
        AppLog.info(String(format: "Geometría 3D: %ld puntos, error medio %.3f px, nivel %.3f m", marked.count, solution.meanError, waterLevel))
        return CameraOutcome(solution: solution, usedIDs: marked.map { $0.id }, waterLevel: waterLevel,
                             preview: GeometryPreview(imageFile: ProjectFiles.orthoImage, resolution: cam.grid.resolution, extent: cam.grid.extent))
    }

    /// ipcam: matrix of the water plane at `level` (desktop recomputes and re-saves it whenever the level changes;
    /// like desktop's `createMatrix`, this discards sections, masks and PIV results).
    static func setIpcamLevel(_ project: inout RiverProject, camera: CameraMatrix, level: Double) throws -> Matrix3 {
        let matrix: Matrix3 = try camera.pixelToWorld(z: level)
        try project.setMatrix(matrix)
        project.settings.json["transformation"] = .object(JSONObject([("matrix", .string(RiverProject.matrixFile))]))
        try project.save()
        return matrix
    }

    private static func clearIpcamKeys(_ project: inout RiverProject) {
        for key in ["grp_3d", "camera_solution_3d", "rectification_3d_images"] { project.settings.json[key] = nil }
    }
}
