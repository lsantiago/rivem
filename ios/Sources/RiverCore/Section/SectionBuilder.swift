import Foundation

public enum SectionError: Error, Equatable, CustomStringConvertible {
    /// The water level does not cross the bed profile (desktop: `bathimetryNotValid`).
    case levelDoesNotCrossBathymetry
    /// No bathymetry point is at or below the water level.
    case dryCrossSection
    /// `left_station` puts the drawn left point outside the bathymetry (desktop: `CrossSections.Errors.leftBank`).
    case leftStationOutOfRange
    case missingKey(String)
    case invalidValue(String)

    public var description: String {
        switch self {
        case .levelDoesNotCrossBathymetry: return "El nivel del agua no corta la batimetría."
        case .dryCrossSection: return "La sección está seca: ningún punto de la batimetría queda bajo el nivel del agua."
        case .leftStationOutOfRange: return "La progresiva de la margen izquierda está fuera de la batimetría."
        case let .missingKey(k): return "Falta la clave '\(k)' en la sección."
        case let .invalidValue(why): return "Valor no válido: \(why)"
        }
    }
}

/// Builds a cross-section exactly as RIVeR desktop's GUI writes it to `xsections.json`
/// (`adapterCrossSections.ts`, `useSectionSlice.ts` `onUpdateSectionPoints`/`onSetSections`, `getBathimetryValues.ts`).
///
/// Meaning of the keys (and of `left_station`):
/// - The user draws a line on the frame: its pixel end points are `dir_xl…dir_yr`, mapped to the world by the
///   matrix as `dir_east_l…dir_north_r`. The line only gives the section's position and direction.
/// - `left_station` ("Left Bank Station" in the GUI) is the distance, along the line, from the water's left edge
///   (the first crossing of the level with the bed, `x1Intersection`) to the drawn left point. The bathymetry chart
///   draws the drawn left point at bathymetry station `x1Intersection + left_station` (`Graphs/Bathimetry.tsx`).
/// - The section stored for the computation starts at the water's left edge:
///   `east_l = dir_east_l − left_station·û` and runs the wetted width `rw_length = x2Intersection − x1Intersection`
///   along û (the unit vector of the drawn line), so `east_r = east_l + rw_length·û`. `xl…yr` are those two points
///   in pixels (world → pixel with the inverse matrix), and `east_l…north_r` come back through the matrix once more,
///   as `onSetSections` does.
///
/// Hence `left_station` is fully consumed here: the discharge computation must not apply it again (see
/// `DischargeCalculator`, PLAN #6).
public enum SectionBuilder {
    /// Desktop defaults (`constants.ts`).
    public static let defaultAlpha = 0.85
    public static let defaultNumStations = 15

    /// Values the GUI derives from the bathymetry (`getBathimetryValues`).
    public struct BathymetryLimits: Equatable, Sendable {
        public var level: Double
        public var x1Intersection: Double
        public var x2Intersection: Double
        public var width: Double { x2Intersection - x1Intersection }
        public var xMin: Double
        public var xMax: Double
    }

    /// JavaScript's `parseFloat(v.toFixed(2))`, used by the GUI on bed elevations, default level and alpha.
    /// `toFixed` rounds the exact binary value (1.765 is stored as 1.76499… → "1.76", where `(v*100).rounded()`
    /// would give 1.77) and breaks exact ties away from zero (0.125 → "0.13"; C's `%.2f` gives "0.12").
    static func round2(_ v: Double) -> Double {
        guard v.isFinite else { return v }
        let negative = v < 0
        let x = abs(v)
        let t = x * 100
        let eighths = x * 8
        let text: String
        if t - t.rounded(.down) == 0.5 && eighths == eighths.rounded() {
            text = String(format: "%.2f", t.rounded(.up) / 100)
        } else {
            text = String(format: "%.2f", x)
        }
        let r: Double = Double(text) ?? x
        return negative ? -r : r
    }

    /// Level and wetted limits as the GUI computes them.
    /// - `level == nil`: the default level is the lower of the two end elevations rounded to cm
    ///   (`getBathimetryLimitsY`), and the limits are the first and last intersection points
    ///   (`getIntersectionPoints`, bed elevations rounded to cm).
    /// - `level` given (the user typed it): the limits are the ends of the first and last wet segments
    ///   (`findWetSegments`, as `onUpdateSection({level})` does).
    /// A flat stretch exactly at the level would make the GUI divide 0/0 (NaN limits); here its first end is used.
    public static func limits(bathymetry: Bathymetry, level: Double?) throws -> BathymetryLimits {
        let xs = bathymetry.stations
        let ys = bathymetry.elevations
        guard xs.count >= 2, xs.count == ys.count else { throw SectionError.invalidValue("batimetría con menos de dos puntos") }
        let xMin = xs.min() ?? 0
        let xMax = xs.max() ?? 0
        if let level = level {
            let segments = wetSegmentsGUI(xs: xs, ys: ys, level: level)
            guard let first = segments.first, let last = segments.last else { throw SectionError.levelDoesNotCrossBathymetry }
            return BathymetryLimits(level: level, x1Intersection: first.0, x2Intersection: last.1, xMin: xMin, xMax: xMax)
        }
        let yFirst = ys[0], yLast = ys[ys.count - 1]
        let yMax: Double = round2(yFirst >= yLast ? yLast : yFirst)
        let points = intersectionPointsGUI(xs: xs, ys: ys, level: yMax)
        guard let first = points.first, let last = points.last else { throw SectionError.levelDoesNotCrossBathymetry }
        return BathymetryLimits(level: yMax, x1Intersection: first, x2Intersection: last, xMin: xMin, xMax: xMax)
    }

    /// `getIntersectionPoints`.
    static func intersectionPointsGUI(xs: [Double], ys: [Double], level: Double) -> [Double] {
        var out: [Double] = []
        for i in 0..<(xs.count - 1) {
            let cy = round2(ys[i])
            let ny = round2(ys[i + 1])
            if (cy <= level && ny >= level) || (cy >= level && ny <= level) {
                var x: Double = xs[i]
                if ny != cy {
                    let t: Double = (level - cy) / (ny - cy)
                    x = xs[i] + t * (xs[i + 1] - xs[i])
                }
                if out.isEmpty || out[out.count - 1] != x { out.append(x) }
            }
        }
        return out
    }

    /// `findWetSegments` (GUI copy of Python's `find_wet_segments`, with bed elevations rounded to cm).
    static func wetSegmentsGUI(xs: [Double], ys: [Double], level: Double) -> [(Double, Double)] {
        var merged: [(Double, Double)] = []
        for i in 0..<xs.count {
            let cy = round2(ys[i])
            merged.append((xs[i], cy))
            if i < xs.count - 1 {
                let ny = round2(ys[i + 1])
                if (cy < level && ny > level) || (cy > level && ny < level) {
                    let t: Double = (level - cy) / (ny - cy)
                    merged.append((xs[i] + t * (xs[i + 1] - xs[i]), level))
                }
            }
        }
        // JS Array.prototype.sort is stable.
        let sorted = merged.enumerated().sorted { l, r in
            l.element.0 != r.element.0 ? l.element.0 < r.element.0 : l.offset < r.offset
        }.map { $0.element }
        var segments: [(Double, Double)] = []
        var start: Double? = nil
        for i in 0..<sorted.count {
            let wet = sorted[i].1 <= level
            if wet && start == nil {
                start = sorted[i].0
            } else if !wet, let s = start {
                segments.append((s, sorted[i - 1].0))
                start = nil
            }
        }
        if let s = start, let last = sorted.last { segments.append((s, last.0)) }
        return segments
    }

    /// Builds the 22 keys desktop's GUI writes for a section, in the same order.
    /// - Parameters:
    ///   - drawnLeft/drawnRight: pixel end points of the line drawn on the frame (left bank first).
    ///   - matrix: pixel → real-world matrix (`transformation_matrix.json`).
    ///   - bathymetryPath: value stored in `bath`.
    ///   - leftStation: distance from the water's left edge to the drawn left point (GUI default 0).
    ///   - level: water level; nil uses the GUI default (see `limits`).
    ///   - alpha: rounded to cm as the GUI does (`toFixed(2)`).
    public static func build(name: String,
                             drawnLeft: Point2,
                             drawnRight: Point2,
                             matrix: Matrix3,
                             bathymetry: Bathymetry,
                             bathymetryPath: String,
                             leftStation: Double = 0,
                             level: Double? = nil,
                             numStations: Int = defaultNumStations,
                             alpha: Double = defaultAlpha) throws -> CrossSection {
        guard numStations >= 2 else { throw SectionError.invalidValue("num_stations < 2") }
        guard drawnLeft != drawnRight else { throw SectionError.invalidValue("los dos extremos de la línea coinciden") }
        let lim = try limits(bathymetry: bathymetry, level: level)
        guard lim.width > 0 else { throw SectionError.levelDoesNotCrossBathymetry }
        let shifted: Double = lim.x1Intersection + leftStation
        guard shifted >= lim.xMin, shifted <= lim.xMax else { throw SectionError.leftStationOutOfRange }

        let rwLeft = matrix.apply(drawnLeft)
        let rwRight = matrix.apply(drawnRight)
        let dx: Double = rwRight.x - rwLeft.x
        let dy: Double = rwRight.y - rwLeft.y
        let magnitude: Double = (dx * dx + dy * dy).squareRoot()
        guard magnitude > 0, magnitude.isFinite else { throw SectionError.invalidValue("línea de longitud nula en el mundo real") }
        let ux: Double = dx / magnitude
        let uy: Double = dy / magnitude
        let offset: Double = leftStation != 0 ? -leftStation : 0
        let wl = Point2(rwLeft.x + ux * offset, rwLeft.y + uy * offset)
        let wrx: Double = rwLeft.x + ux * lim.width + ux * offset
        let wry: Double = rwLeft.y + uy * lim.width + uy * offset
        let wr = Point2(wrx, wry)

        let inverse = try matrix.inverse()
        let pl = inverse.apply(wl)
        let pr = inverse.apply(wr)
        let el = matrix.apply(pl)
        let er = matrix.apply(pr)

        // JSON.stringify writes integral numbers without a decimal part.
        let leftValue: JSONValue
        if leftStation == leftStation.rounded() && abs(leftStation) < 1e15 {
            leftValue = JSONValue.int(Int(leftStation))
        } else {
            leftValue = JSONValue.double(leftStation)
        }
        let pairs: [(String, JSONValue)] = [
            ("bath", .string(bathymetryPath)),
            ("rw_length", .double(lim.width)),
            ("level", .double(lim.level)),
            ("left_station", leftValue),
            ("east_l", .double(el.x)),
            ("north_l", .double(el.y)),
            ("east_r", .double(er.x)),
            ("north_r", .double(er.y)),
            ("xl", .double(pl.x)),
            ("yl", .double(pl.y)),
            ("xr", .double(pr.x)),
            ("yr", .double(pr.y)),
            ("dir_east_l", .double(rwLeft.x)),
            ("dir_north_l", .double(rwLeft.y)),
            ("dir_east_r", .double(rwRight.x)),
            ("dir_north_r", .double(rwRight.y)),
            ("dir_xl", .double(drawnLeft.x)),
            ("dir_yl", .double(drawnLeft.y)),
            ("dir_xr", .double(drawnRight.x)),
            ("dir_yr", .double(drawnRight.y)),
            ("num_stations", .int(numStations)),
            ("alpha", .double(round2(alpha))),
        ]
        return CrossSection(name: name, json: JSONObject(pairs))
    }
}
