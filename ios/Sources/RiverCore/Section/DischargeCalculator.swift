import Foundation

/// Options of desktop's `river-cli update-xsection` (`getResultData.ts`): frame step and fps of the extracted
/// frames, `--interpolate`, `--artificial-seeding`, `--alpha`, `--num-stations`.
public struct DischargeOptions: Equatable, Sendable {
    /// Frames between the two images of a PIV pair (`video_range.step`).
    public var frameStep: Int
    /// Frames per second of the video.
    public var fps: Double
    /// Fill missing/unchecked stations from the Froude-number profile.
    public var interpolate: Bool
    /// Use the gradient-filtered ("artificial seeding") velocity profile.
    public var artificialSeeding: Bool
    /// Overrides (and is written to) the section's `alpha` when set.
    public var alpha: Double?
    /// Overrides (and is written to) the section's `num_stations` when set.
    public var numStations: Int?

    public init(frameStep: Int, fps: Double, interpolate: Bool = false, artificialSeeding: Bool = false,
                alpha: Double? = nil, numStations: Int? = nil) {
        self.frameStep = frameStep
        self.fps = fps
        self.interpolate = interpolate
        self.artificialSeeding = artificialSeeding
        self.alpha = alpha
        self.numStations = numStations
    }

    /// `step / fps`, seconds between the two frames of a pair.
    public var timeBetweenFrames: Double { Double(frameStep) / fps }
}

/// Everything `update_current_x_section` writes into a section, plus the upstream values of the quantities this
/// port fixes.
public struct SectionDischarge: Equatable, Sendable {
    // Station geometry
    public var id: [Int] = []
    public var east: [Double] = []
    public var north: [Double] = []
    /// m from the water's left edge.
    public var distance: [Double] = []
    /// (start, end) of each wet stretch, in the same station coordinates as `distance`.
    public var wetSegments: [[Double]] = []
    /// Station pixels.
    public var x: [Double] = []
    public var y: [Double] = []
    // Median field at the stations
    public var displacementX: [Double] = []
    public var displacementY: [Double] = []
    public var displacementEast: [Double] = []
    public var displacementNorth: [Double] = []
    public var streamwiseEast: [Double] = []
    public var streamwiseNorth: [Double] = []
    public var crosswiseEast: [Double] = []
    public var crosswiseNorth: [Double] = []
    /// Signed streamwise surface velocity, m/s (positive to the left of the left→right section direction).
    public var streamwiseVelocity: [Double] = []
    public var streamwiseX: [Double] = []
    public var streamwiseY: [Double] = []
    public var depth: [Double] = []
    public var check: [Bool] = []
    /// True when `check` was missing or had the wrong length and was reset to all `true` (then it is written).
    public var checkWasReset = false
    // Statistics over the frame pairs
    public var minusStd: [Double] = []
    public var plusStd: [Double] = []
    public var percentile5: [Double] = []
    public var percentile95: [Double] = []
    public var seededProfile: [Double] = []
    public var filledStreamwiseVelocity: [Double]? = nil
    public var filledSeededProfile: [Double]? = nil
    // Mid-section discharge
    public var w: [Double] = []
    public var a: [Double] = []
    public var q: [Double] = []
    public var qMinusStd: [Double] = []
    public var qPlusStd: [Double] = []
    public var qPortion: [Double] = []
    /// Per-station velocity standard deviation used for `totalQStd` (see there).
    public var stationStd: [Double] = []
    // Totals
    public var totalQ: Double = 0
    public var totalQStd: Double = 0
    public var measuredQ: Double = 0
    public var interpolatedQ: Double = 0
    public var totalA: Double = 0
    public var totalW: Double = 0
    public var maxDepth: Double = 0
    public var averageDepth: Double = 0
    public var meanV: Double = 0
    public var meanVs: Double = 0
    /// In seeding + interpolation mode upstream overwrites `streamwise_velocity_magnitude` with the seeded profile
    /// before writing it (a side effect of `add_interpolated_velocity({**table, ...})`); kept for compatibility.
    public var writtenStreamwiseVelocity: [Double] = []

    /// Upstream (RIVeR 3.8.3) values of the quantities fixed here.
    public struct Upstream: Equatable, Sendable {
        public var totalQStd: Double = 0
        public var totalA: Double = 0
        public var totalW: Double = 0
        public var averageDepth: Double = 0
        public var meanV: Double = 0
    }
    public var upstream = Upstream()

    public init() {}
}

/// Port of `update_current_x_section` (`compute_section.py:1337-1739`) and the functions it uses: stations along
/// the section, depth, the PIV field sampled at the stations, statistics over the frame pairs, mid-section
/// discharge, interpolation of missing stations and the totals. Everything not listed below is numerically the
/// same as RIVeR 3.8.3.
///
/// Fixes (docs/PLAN.md, "Errores de RIVeR corregidos"):
/// - **#6 `left_station`** — not a defect. Upstream computes `filtered − left_station − (filtered[0] − left_station)`,
///   which cancels it, and that is right: the GUI already applied `left_station` when it placed `east_l/north_l` at
///   the water's left edge (`east_l = dir_east_l − left_station·û`, see `SectionBuilder`), and the stations start at
///   `east_l` ↔ the first wet bathymetry point. Applying it again would shift the stations twice. The same
///   arithmetic is kept here, so the value has no effect, as in desktop.
/// - **#7 `total_q_std`** — upstream takes `Σ Q_plus_std − total_Q`, which mixes populations: unchecked stations are
///   left out of `total_Q` but not of `Σ Q_plus_std` (std inflated), stations filled by interpolation are in
///   `total_Q` but their `Q_plus_std` is NaN and dropped by `nansum` (std reduced, possibly negative), and with
///   artificial seeding `Q_plus_std` is built on the median velocity while `Q` uses the seeded one (any sign).
///   Here `total_q_std = Σ_{i in total_Q} |A_i·α|·σ_i`: the same stations as `total_Q`; σ_i is the standard
///   deviation of the station velocity over the frame pairs (NaN pairs ignored); stations whose velocity was filled
///   by interpolation, or whose σ is undefined, take σ interpolated over the station index from the measured ones
///   (the same rule used to fill the velocity); the two end stations forced to zero velocity take σ = 0. It is a
///   sum of non-negative terms, never negative, and equals upstream's value when every station is checked and
///   measured (fully correlated errors, as upstream assumes). The per-station `minus_std`/`plus_std`/`Q_*_std`
///   arrays are unchanged.
/// - **#8 area and width** — `total_A`, `total_W`, `max_depth`, `average_depth` come from `SectionProfile.properties`
///   (exact water-level crossings, the same crossing points the stations use); `mean_V = total_Q / total_A`.
/// - **#9 caches** — nothing is cached: upstream reuses `minus_std…seeded_vel_profile` already stored in the
///   section whenever their length matches `num_stations` (so a new PIV run, level, bathymetry or fps keeps the old
///   statistics) and keeps per-frame profiles keyed by a hash that leaves out the bathymetry. This port recomputes
///   everything from the inputs it is given on every call.
/// - Divisions by zero are guarded (flat bed segments never create crossings, zero-length sections and
///   `num_stations < 2` throw).
public enum DischargeCalculator {
    /// Points of the dense grid used for the per-frame statistics (`N_CACHE_POINTS`).
    public static let denseGridPoints = 200
    /// Percentile of the gradient above which a pair counts as "seeded" (`get_artificial_seeded_profile_optimized`).
    public static let seedingPercentile = 85.0

    static let stivColumns = ["stiv_velocity_profile", "stiv_sigma_profile", "stiv_angle_profile", "stiv_sign_profile",
                              "stiv_skip_profile", "stiv_angle_manual_profile"]
    static let iwaveColumns = ["iwave_velocity_profile", "iwave_quality_profile", "iwave_depth_profile",
                               "iwave_valid_profile", "iwave_skip_profile", "iwave_params"]

    // MARK: - Public entry points

    /// Computes the section's results (does not modify it).
    public static func compute(section: CrossSection, bathymetry: Bathymetry, piv: PIVResults, matrix: Matrix3,
                               options: DischargeOptions) throws -> SectionDischarge {
        let json = section.json
        func number(_ k: String) throws -> Double {
            guard let v = json[k]?.doubleValue else { throw SectionError.missingKey(k) }
            return v
        }
        let eastL = try number("east_l")
        let northL = try number("north_l")
        let eastR = try number("east_r")
        let northR = try number("north_r")
        let level = try number("level")
        let leftStation: Double = json["left_station"]?.doubleValue ?? 0
        let alpha: Double
        if let a = options.alpha { alpha = a } else { alpha = try number("alpha") }
        let ns: Int
        if let n = options.numStations {
            ns = n
        } else {
            guard let n = json["num_stations"]?.intValue else { throw SectionError.missingKey("num_stations") }
            ns = n
        }
        guard ns >= 2 else { throw SectionError.invalidValue("num_stations debe ser al menos 2") }
        let dt = options.timeBetweenFrames
        guard dt > 0, dt.isFinite else { throw SectionError.invalidValue("step/fps") }

        var r = SectionDischarge()

        // --- calculate_station_coordinates
        let aug = SectionProfile.augmented(stations: bathymetry.stations, stages: bathymetry.elevations, level: level)
        var filtered: [Double] = []
        var filteredStages: [Double] = []
        for i in 0..<aug.stations.count where aug.wet[i] {
            filtered.append(aug.stations[i])
            filteredStages.append(aug.stages[i])
        }
        guard !filtered.isEmpty else { throw SectionError.dryCrossSection }
        // Upstream arithmetic kept as is: `left_station` cancels (PLAN #6, see the type documentation).
        var shifted: [Double] = filtered.map { $0 - leftStation }
        let shiftOffset = shifted[0]
        shifted = shifted.map { $0 - shiftOffset }
        let segments = SectionProfile.wetSegments(stations: bathymetry.stations, stages: bathymetry.elevations, level: level)
        r.wetSegments = segments.map { [$0.0 - leftStation - shiftOffset, $0.1 - leftStation - shiftOffset] }

        let ddx: Double = eastR - eastL
        let ddy: Double = northR - northL
        let totalDistance: Double = (ddx * ddx + ddy * ddy).squareRoot()
        guard totalDistance > 0, totalDistance.isFinite else { throw SectionError.invalidValue("la sección tiene longitud nula") }
        let dirX: Double = ddx / totalDistance
        let dirY: Double = ddy / totalDistance
        let last = shifted[shifted.count - 1]
        // A single wet point gives a zero-length section (upstream divides by zero and fills everything with NaN).
        guard last > 0 else { throw SectionError.dryCrossSection }
        let extEastL: Double = eastL + shifted[0] * dirX
        let extNorthL: Double = northL + shifted[0] * dirY
        let extEastR: Double = eastL + last * dirX
        let extNorthR: Double = northL + last * dirY

        // --- divide_segment_to_dict
        let stations = divide(extEastL, extNorthL, extEastR, extNorthR, ns)
        r.id = Array(1...ns)
        r.east = stations.east
        r.north = stations.north
        r.distance = stations.distance

        // --- add_pixel_coordinates
        let inverse = try matrix.inverse()
        for i in 0..<ns {
            let p = inverse.apply(Point2(r.east[i], r.north[i]))
            r.x.append(p.x)
            r.y.append(p.y)
        }

        // --- compute_transformation_matrix (real world <-> section)
        let rot = Rotation(originEast: r.east[0], originNorth: r.north[0], endEast: r.east[ns - 1], endNorth: r.north[ns - 1])

        // --- add_median_results
        let n = piv.x.count
        var nodesPixel: [Point2] = []
        var nodesWorld: [Point2] = []
        nodesPixel.reserveCapacity(n)
        nodesWorld.reserveCapacity(n)
        for k in 0..<n {
            let p = Point2(piv.x[k], piv.y[k])
            nodesPixel.append(p)
            nodesWorld.append(matrix.apply(p))
        }
        let stationPixels: [Point2] = (0..<ns).map { Point2(r.x[$0], r.y[$0]) }
        let stationWorld: [Point2] = (0..<ns).map { Point2(r.east[$0], r.north[$0]) }
        let pixelInterp = SectionLinearInterpolator(nodes: nodesPixel, queries: stationPixels)
        let worldInterp = SectionLinearInterpolator(nodes: nodesWorld, queries: stationWorld)
        r.displacementX = pixelInterp.interpolate(piv.uMedian)
        r.displacementY = pixelInterp.interpolate(piv.vMedian)
        let medianField = worldDisplacements(nodes: nodesPixel, world: nodesWorld, u: piv.uMedian, v: piv.vMedian, matrix: matrix)
        r.displacementEast = worldInterp.interpolate(medianField.east)
        r.displacementNorth = worldInterp.interpolate(medianField.north)
        var crosswise: [Double] = []
        var streamwise: [Double] = []
        for i in 0..<ns {
            let cs = rot.toSection(r.displacementEast[i], r.displacementNorth[i])
            crosswise.append(cs.0)
            streamwise.append(cs.1)
        }
        for i in 0..<ns {
            let s = rot.toWorld(crosswise[i] * 0, streamwise[i])
            let c = rot.toWorld(crosswise[i], streamwise[i] * 0)
            r.streamwiseEast.append(s.0)
            r.streamwiseNorth.append(s.1)
            r.crosswiseEast.append(c.0)
            r.crosswiseNorth.append(c.1)
        }
        let pixStream = pixelDisplacement(r.streamwiseEast, r.streamwiseNorth, r, inverse)
        r.streamwiseX = pixStream.0
        r.streamwiseY = pixStream.1
        r.streamwiseVelocity = streamwise.map { $0 / dt }

        // --- add_depth
        r.depth = r.distance.map { level - SectionMath.interp($0, shifted, filteredStages) }

        // --- check
        var check: [Bool]? = nil
        if let arr = json["check"]?.arrayValue, arr.count == ns {
            check = arr.map { v -> Bool in
                if let b = v.boolValue { return b }
                if let d = v.doubleValue { return d != 0 }
                return true
            }
        }
        if let c = check {
            r.check = c
        } else {
            r.check = [Bool](repeating: true, count: ns)
            r.checkWasReset = true
        }

        // --- statistics over the frame pairs (always recomputed: PLAN #9)
        let stats = frameStatistics(piv: piv, matrix: matrix, nodesPixel: nodesPixel, nodesWorld: nodesWorld,
                                    extEastL: extEastL, extNorthL: extNorthL, extEastR: extEastR, extNorthR: extNorthR,
                                    rotation: rot, dt: dt, stationDistances: r.distance)
        let medianVelocity = r.streamwiseVelocity
        r.minusStd = (0..<ns).map { medianVelocity[$0] - stats.std[$0] }
        r.plusStd = (0..<ns).map { medianVelocity[$0] + stats.std[$0] }
        r.percentile5 = stats.p5
        r.percentile95 = stats.p95
        r.seededProfile = stats.seeded

        // --- discharge
        discharge(&r, alpha: alpha, dt: dt, options: options, rotation: rot, inverse: inverse, nanStd: stats.nanStd)

        // --- section properties (PLAN #8) and mean velocities
        let props = SectionProfile.properties(stations: bathymetry.stations, stages: bathymetry.elevations, level: level)
        r.totalA = props.area
        r.totalW = props.width
        r.maxDepth = props.maxDepth
        r.averageDepth = props.averageDepth
        r.meanV = r.totalA > 0 ? r.totalQ / r.totalA : 0
        let up = SectionProfile.upstreamProperties(stations: bathymetry.stations, stages: bathymetry.elevations, level: level)
        r.upstream.totalA = up.area
        r.upstream.totalW = up.width
        r.upstream.averageDepth = up.averageDepth
        r.upstream.meanV = up.area > 0 ? r.totalQ / up.area : 0
        return r
    }

    /// Computes section `index` and writes its results into it (same keys as desktop, plus the GUI's
    /// `interpolated`/`artificial_seeding` flags), then refreshes `summary`.
    @discardableResult
    public static func update(_ sections: inout CrossSections, index: Int, bathymetry: Bathymetry, piv: PIVResults,
                              matrix: Matrix3, options: DischargeOptions) throws -> SectionDischarge {
        guard sections.sections.indices.contains(index) else { throw SectionError.invalidValue("índice de sección") }
        var section = sections.sections[index]
        let result = try compute(section: section, bathymetry: bathymetry, piv: piv, matrix: matrix, options: options)
        apply(result, to: &section, options: options)
        sections.sections[index] = section
        sections.summary = summary(of: sections)
        return result
    }

    /// Writes `result` into `section` as `update_current_x_section` does (key order of a first write included).
    public static func apply(_ r: SectionDischarge, to section: inout CrossSection, options: DischargeOptions) {
        var o = section.json
        for key in o.keys where key.hasPrefix("filled_") { o[key] = nil }
        o["_stats_cache"] = nil
        let previous = o["num_stations"]?.intValue
        if let n = options.numStations { o["num_stations"] = .int(n) }
        let current = o["num_stations"]?.intValue
        if let p = previous, let c = current, p != c {
            for key in stivColumns + iwaveColumns { o[key] = nil }
        }
        if let a = options.alpha { o["alpha"] = .double(a) }

        func put(_ k: String, _ v: [Double]) { o[k] = JSONValue.doubles(v) }
        o["id"] = .array(r.id.map { JSONValue.int($0) })
        put("east", r.east)
        put("north", r.north)
        put("distance", r.distance)
        o["wet_segments"] = .array(r.wetSegments.map { JSONValue.doubles($0) })
        put("x", r.x)
        put("y", r.y)
        put("displacement_x", r.displacementX)
        put("displacement_y", r.displacementY)
        put("displacement_east", r.displacementEast)
        put("displacement_north", r.displacementNorth)
        put("streamwise_east", r.streamwiseEast)
        put("streamwise_north", r.streamwiseNorth)
        put("crosswise_east", r.crosswiseEast)
        put("crosswise_north", r.crosswiseNorth)
        put("streamwise_velocity_magnitude", r.writtenStreamwiseVelocity)
        put("streamwise_x", r.streamwiseX)
        put("streamwise_y", r.streamwiseY)
        put("depth", r.depth)
        if r.checkWasReset { o["check"] = .array(r.check.map { JSONValue.bool($0) }) }
        put("minus_std", r.minusStd)
        put("plus_std", r.plusStd)
        put("5th_percentile", r.percentile5)
        put("95th_percentile", r.percentile95)
        put("seeded_vel_profile", r.seededProfile)
        if let f = r.filledStreamwiseVelocity { put("filled_streamwise_velocity_magnitude", f) }
        if let f = r.filledSeededProfile { put("filled_seeded_vel_profile", f) }
        if options.interpolate {
            put("W", r.w); put("A", r.a); put("Q", r.q)
            put("Q_minus_std", r.qMinusStd); put("Q_plus_std", r.qPlusStd); put("Q_portion", r.qPortion)
        } else {
            put("W", r.w); put("A", r.a); put("Q", r.q)
            put("Q_portion", r.qPortion); put("Q_minus_std", r.qMinusStd); put("Q_plus_std", r.qPlusStd)
        }
        o["total_Q"] = .double(r.totalQ)
        o["total_q_std"] = .double(r.totalQStd)
        o["measured_Q"] = .double(r.measuredQ)
        o["interpolated_Q"] = .double(r.interpolatedQ)
        o["total_A"] = .double(r.totalA)
        o["total_W"] = .double(r.totalW)
        o["max_depth"] = .double(r.maxDepth)
        o["average_depth"] = .double(r.averageDepth)
        o["mean_V"] = .double(r.meanV)
        o["mean_Vs"] = .double(r.meanVs)
        // Written by desktop's Electron handler right after the CLI (`getResultData.ts`).
        o["interpolated"] = .bool(options.interpolate)
        o["artificial_seeding"] = .bool(options.artificialSeeding)
        section.json = o
    }

    /// `get_general_statistics`: mean, population std and coefficient of variation of the section totals over the
    /// sections that have all of them (`total_W` is taken from `rw_length`, as upstream does).
    public static func summary(of sections: CrossSections) -> JSONValue {
        let keys = ["total_W", "total_A", "total_Q", "mean_V", "alpha", "mean_Vs", "max_depth", "average_depth", "measured_Q"]
        var data: [String: [Double]] = [:]
        for k in keys { data[k] = [] }
        for s in sections.sections {
            if keys.contains(where: { s.json[$0] == nil }) { continue }
            for k in keys {
                let source = k == "total_W" ? "rw_length" : k
                let v: Double = s.json[source]?.doubleValue ?? .nan
                data[k, default: []].append(v)
            }
        }
        var mean = JSONObject()
        var std = JSONObject()
        var cov = JSONObject()
        for k in keys {
            let values = data[k] ?? []
            if values.isEmpty {
                mean[k] = JSONValue.null
                std[k] = JSONValue.null
                cov[k] = JSONValue.null
                continue
            }
            let m = SectionMath.mean(values)
            let sd = SectionMath.std(values)
            mean[k] = .double(m)
            std[k] = .double(sd)
            // Python: `std / mean if mean and mean != 0` (NaN is truthy).
            cov[k] = m != 0 ? JSONValue.double(sd / m) : JSONValue.null
        }
        return .object(JSONObject([("mean", .object(mean)), ("std", .object(std)), ("cov", .object(cov))]))
    }

    // MARK: - Pieces

    struct Rotation {
        let c: Double
        let s: Double
        /// Rotation part of the inverse affine matrix (`np.linalg.inv`), i.e. the transpose over c² + s².
        let i00: Double, i01: Double, i10: Double, i11: Double

        init(originEast: Double, originNorth: Double, endEast: Double, endNorth: Double) {
            let dx: Double = endEast - originEast
            let dy: Double = endNorth - originNorth
            let length: Double = (dx * dx + dy * dy).squareRoot()
            c = dx / length
            s = dy / length
            let det: Double = c * c + s * s
            i00 = c / det
            i01 = -s / det
            i10 = s / det
            i11 = c / det
        }

        /// (crosswise, streamwise) = R · (east, north), R = [[c, s], [-s, c]].
        func toSection(_ e: Double, _ n: Double) -> (Double, Double) {
            let cross: Double = c * e + s * n
            let stream: Double = -s * e + c * n
            return (cross, stream)
        }

        /// (east, north) = R⁻¹ · (crosswise, streamwise).
        func toWorld(_ cross: Double, _ stream: Double) -> (Double, Double) {
            let e: Double = i00 * cross + i01 * stream
            let n: Double = i10 * cross + i11 * stream
            return (e, n)
        }
    }

    struct Stations {
        var east: [Double]
        var north: [Double]
        var distance: [Double]
    }

    /// `divide_segment_to_dict` (same operation order, so the distances match NumPy bit for bit).
    static func divide(_ el: Double, _ nl: Double, _ er: Double, _ nr: Double, _ count: Int) -> Stations {
        let vx: Double = er - el
        let vy: Double = nr - nl
        let stepSize: Double = 1.0 / Double(count - 1)
        let length: Double = (vx * vx + vy * vy).squareRoot()
        var s = Stations(east: [], north: [], distance: [])
        for i in 0..<count {
            let f: Double = Double(i) * stepSize
            s.east.append(el + f * vx)
            s.north.append(nl + f * vy)
            s.distance.append(f * length)
        }
        return s
    }

    /// `convert_displacement_field`: real-world displacement of every PIV node.
    static func worldDisplacements(nodes: [Point2], world: [Point2], u: [Double], v: [Double],
                                   matrix: Matrix3) -> (east: [Double], north: [Double]) {
        var de: [Double] = []
        var dn: [Double] = []
        de.reserveCapacity(nodes.count)
        dn.reserveCapacity(nodes.count)
        for k in 0..<nodes.count {
            let moved = matrix.apply(Point2(nodes[k].x + u[k], nodes[k].y + v[k]))
            de.append(moved.x - world[k].x)
            dn.append(moved.y - world[k].y)
        }
        return (de, dn)
    }

    /// `get_pix_displacement`.
    static func pixelDisplacement(_ de: [Double], _ dn: [Double], _ r: SectionDischarge, _ inverse: Matrix3) -> ([Double], [Double]) {
        var px: [Double] = []
        var py: [Double] = []
        for i in 0..<de.count {
            let p = inverse.apply(Point2(r.east[i] + de[i], r.north[i] + dn[i]))
            px.append(p.x - r.x[i])
            py.append(p.y - r.y[i])
        }
        return (px, py)
    }

    struct FrameStatistics {
        var std: [Double]
        var nanStd: [Double]
        var p5: [Double]
        var p95: [Double]
        var seeded: [Double]
    }

    /// `_compute_stats_cache` + `_compute_stats_from_cache`: streamwise velocity of every frame pair on a dense
    /// 200-point grid, interpolated to the stations; std, 5th/95th percentiles and the artificial-seeding profile.
    static func frameStatistics(piv: PIVResults, matrix: Matrix3, nodesPixel: [Point2], nodesWorld: [Point2],
                                extEastL: Double, extNorthL: Double, extEastR: Double, extNorthR: Double,
                                rotation: Rotation, dt: Double, stationDistances: [Double]) -> FrameStatistics {
        let dense = divide(extEastL, extNorthL, extEastR, extNorthR, denseGridPoints)
        let densePoints: [Point2] = (0..<denseGridPoints).map { Point2(dense.east[$0], dense.north[$0]) }
        let interp = SectionLinearInterpolator(nodes: nodesWorld, queries: densePoints)
        // Nearest PIV node of each dense point (`_interpolate_gradient_numba`: first minimum).
        var nearest: [Int] = []
        for q in densePoints {
            var best = -1
            var bestD = Double.infinity
            for k in 0..<nodesWorld.count {
                let dx: Double = nodesWorld[k].x - q.x
                let dy: Double = nodesWorld[k].y - q.y
                let d: Double = dx * dx + dy * dy
                if best < 0 || d < bestD {
                    best = k
                    bestD = d
                }
            }
            nearest.append(best)
        }
        let ns = stationDistances.count
        let frames = piv.pairCount
        var vel: [[Double]] = Array(repeating: [], count: ns)   // [station][frame]
        var grad: [[Double]] = Array(repeating: [], count: ns)
        for f in 0..<frames {
            let field = worldDisplacements(nodes: nodesPixel, world: nodesWorld, u: piv.u[f], v: piv.v[f], matrix: matrix)
            let ie = interp.interpolate(field.east)
            let inn = interp.interpolate(field.north)
            var denseVel: [Double] = []
            denseVel.reserveCapacity(denseGridPoints)
            for j in 0..<denseGridPoints {
                denseVel.append(rotation.toSection(ie[j], inn[j]).1 / dt)
            }
            var denseGrad: [Double] = []
            if f < piv.gradient.count, piv.gradient[f].count == nodesWorld.count {
                denseGrad = nearest.map { piv.gradient[f][$0] }
            } else {
                denseGrad = [Double](repeating: .nan, count: denseGridPoints)
            }
            for i in 0..<ns {
                vel[i].append(SectionMath.interp(stationDistances[i], dense.distance, denseVel))
                grad[i].append(SectionMath.interp(stationDistances[i], dense.distance, denseGrad))
            }
        }
        var out = FrameStatistics(std: [], nanStd: [], p5: [], p95: [], seeded: [])
        for i in 0..<ns {
            out.std.append(SectionMath.std(vel[i]))
            out.nanStd.append(SectionMath.nanstd(vel[i]))
            out.p5.append(SectionMath.percentile(vel[i], 5))
            out.p95.append(SectionMath.percentile(vel[i], 95))
            out.seeded.append(seededMean(velocity: vel[i], gradient: grad[i]))
        }
        return out
    }

    /// `get_artificial_seeded_profile_optimized` for one station.
    static func seededMean(velocity: [Double], gradient: [Double]) -> Double {
        let valid = gradient.filter { !$0.isNaN }.sorted()
        guard !valid.isEmpty else { return .nan }
        var idx = Int((seedingPercentile / 100.0) * Double(valid.count))
        if idx >= valid.count { idx = valid.count - 1 }
        let threshold = valid[idx]
        var sum = 0.0
        var count = 0
        for j in 0..<gradient.count where !gradient[j].isNaN && gradient[j] > threshold {
            sum += velocity[j]
            count += 1
        }
        return count > 0 ? sum / Double(count) : .nan
    }

    /// `add_interpolated_velocity`: fills NaN/unchecked stations through the Froude number; the end stations are
    /// set to 0. Returns the filled profile and the mask of filled stations.
    static func filledVelocity(_ v: [Double], depth: [Double], check: [Bool]) -> ([Double], [Bool]) {
        let n = v.count
        let g = 9.81
        let d: [Double] = depth.map { $0.isNaN ? $0 : max($0, 1e-6) }
        var fr: [Double] = (0..<n).map { v[$0] / (g * d[$0]).squareRoot() }
        let invalid: [Bool] = (0..<n).map { v[$0].isNaN || !check[$0] }
        if !invalid.contains(false) {
            return ([Double](repeating: 0, count: n), invalid)
        }
        fr = SectionMath.fillByIndex(fr, invalid: invalid)
        var filled: [Double] = (0..<n).map { fr[$0] * (g * d[$0]).squareRoot() }
        filled[0] = 0
        filled[n - 1] = 0
        return (filled, invalid)
    }

    struct WAQ {
        var w: [Double]
        var a: [Double]
        var q: [Double]
        var qMinus: [Double]
        var qPlus: [Double]
        var portion: [Double]
    }

    /// `add_w_a_q`: mid-section widths, areas and discharges.
    static func waq(distance x: [Double], velocity v: [Double], minus: [Double], plus: [Double], depth d: [Double],
                    alpha: Double) -> WAQ {
        let n = x.count
        var w = [Double](repeating: 0, count: n)
        if n >= 3 {
            for i in 1..<(n - 1) { w[i] = (x[i + 1] - x[i - 1]) / 2 }
        }
        w[0] = x[1] - x[0]
        w[n - 1] = x[n - 1] - x[n - 2]
        let a: [Double] = (0..<n).map { w[$0] * d[$0] }
        let q: [Double] = (0..<n).map { a[$0] * v[$0] * alpha }
        let qm: [Double] = (0..<n).map { a[$0] * minus[$0] * alpha }
        let qp: [Double] = (0..<n).map { a[$0] * plus[$0] * alpha }
        let total = SectionMath.nansum(q)
        let portion: [Double] = total != 0 ? q.map { $0 / total } : [Double](repeating: 0, count: n)
        return WAQ(w: w, a: a, q: q, qMinus: qm, qPlus: qp, portion: portion)
    }

    /// The discharge part of `update_current_x_section` (`compute_section.py:1589-1732`) plus fix #7.
    static func discharge(_ r: inout SectionDischarge, alpha: Double, dt: Double, options: DischargeOptions,
                          rotation: Rotation, inverse: Matrix3, nanStd: [Double]) {
        let ns = r.distance.count
        let interpolate = options.interpolate
        let seeding = options.artificialSeeding
        var velMasked = r.streamwiseVelocity
        var seededMasked = r.seededProfile
        if !interpolate {
            for i in 0..<ns where !r.check[i] {
                velMasked[i] = .nan
                seededMasked[i] = .nan
            }
        }
        r.writtenStreamwiseVelocity = r.streamwiseVelocity
        let used: [Double]
        var filledMask = [Bool](repeating: false, count: ns)
        if seeding && interpolate {
            let f = filledVelocity(r.seededProfile, depth: r.depth, check: r.check)
            r.filledSeededProfile = f.0
            filledMask = f.1
            used = f.0
            r.writtenStreamwiseVelocity = r.seededProfile
        } else if seeding {
            used = seededMasked
        } else if interpolate {
            let f = filledVelocity(r.streamwiseVelocity, depth: r.depth, check: r.check)
            r.filledStreamwiseVelocity = f.0
            filledMask = f.1
            used = f.0
        } else {
            used = velMasked
        }
        let t = waq(distance: r.distance, velocity: used, minus: r.minusStd, plus: r.plusStd, depth: r.depth, alpha: alpha)
        r.w = t.w
        r.a = t.a
        r.q = t.q
        r.qMinusStd = t.qMinus
        r.qPlusStd = t.qPlus
        r.qPortion = t.portion

        if interpolate || seeding {
            let profile: [Double]
            if seeding {
                profile = interpolate ? (r.filledSeededProfile ?? used) : r.seededProfile
            } else {
                profile = r.filledStreamwiseVelocity ?? used
            }
            var se: [Double] = []
            var sn: [Double] = []
            for i in 0..<ns {
                let disp: Double = profile[i] * dt
                let s = rotation.toWorld(0 * disp, disp)
                se.append(s.0)
                sn.append(s.1)
            }
            let p = pixelDisplacement(se, sn, r, inverse)
            r.streamwiseX = p.0
            r.streamwiseY = p.1
        }

        // Totals
        var included = [Bool](repeating: false, count: ns)
        var totalQ = 0.0
        for i in 0..<ns {
            if !r.q[i].isNaN && (interpolate || r.check[i]) {
                included[i] = true
                totalQ += r.q[i]
            }
        }
        r.totalQ = totalQ
        r.upstream.totalQStd = SectionMath.nansum(r.qPlusStd) - totalQ
        var measured = 0.0
        var interpolated = 0.0
        for i in 0..<ns where !r.q[i].isNaN {
            if r.check[i] { measured += r.q[i] } else { interpolated += r.q[i] }
        }
        r.measuredQ = totalQ > 0 ? measured / totalQ : 0
        r.interpolatedQ = totalQ > 0 ? interpolated / totalQ : 0

        // Fix #7: σ of the stations in total_Q.
        let sigmaInvalid: [Bool] = (0..<ns).map { nanStd[$0].isNaN || filledMask[$0] }
        var sigma: [Double]
        if sigmaInvalid.contains(false) {
            sigma = SectionMath.fillByIndex(nanStd, invalid: sigmaInvalid)
        } else {
            sigma = [Double](repeating: 0, count: ns)
        }
        if interpolate {
            sigma[0] = 0
            sigma[ns - 1] = 0
        }
        var totalStd = 0.0
        for i in 0..<ns where included[i] {
            let term: Double = abs(r.a[i] * alpha) * sigma[i]
            if term.isFinite { totalStd += term }
        }
        r.stationStd = sigma
        r.totalQStd = totalStd

        // mean_Vs
        let surface: [Double]
        if seeding {
            surface = interpolate ? (r.filledSeededProfile ?? used) : seededMasked
        } else {
            surface = interpolate ? (r.filledStreamwiseVelocity ?? used) : velMasked
        }
        r.meanVs = SectionMath.nanmean(surface)
    }
}
