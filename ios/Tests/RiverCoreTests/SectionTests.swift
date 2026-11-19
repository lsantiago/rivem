import XCTest
@testable import RiverCore

/// F4: cross-section builder and discharge (`Section/`). Analytic cases plus RIVeR's `pisco` example against the
/// golden references written by `tools/golden/section.py` (CI) in `ci/testdata/golden/section/`.
final class SectionTests: XCTestCase {
    // MARK: - Synthetic setup

    /// 21 x 21 PIV nodes every 50 px; matrix east = 0.01·x, north = 10 − 0.01·y (no perspective, so a uniform
    /// pixel field is a uniform real-world field and linear interpolation is exact on any triangulation).
    private let matrix = Matrix3(rows: [[0.01, 0, 0], [0, -0.01, 10], [0, 0, 1]])

    private func grid() -> (x: [Double], y: [Double]) {
        var x: [Double] = [], y: [Double] = []
        for r in 0..<21 {
            for c in 0..<21 {
                x.append(Double(c) * 50)
                y.append(Double(r) * 50)
            }
        }
        return (x, y)
    }

    /// Every node moves `v` px per pair (negative = north); one array per frame pair.
    private func piv(frames: [Double], median: Double, nanNode: Int? = nil) -> PIVResults {
        let g = grid()
        let n = g.x.count
        let zero = [Double](repeating: 0, count: n)
        var vm = [Double](repeating: median, count: n)
        var um = zero
        var vs: [[Double]] = frames.map { [Double](repeating: $0, count: n) }
        var us: [[Double]] = frames.map { _ in zero }
        if let k = nanNode {
            vm[k] = .nan
            um[k] = .nan
            for f in 0..<frames.count {
                vs[f][k] = .nan
                us[f][k] = .nan
            }
        }
        return PIVResults(rows: 21, columns: 21, x: g.x, y: g.y, u: us, v: vs, uMedian: um, vMedian: vm,
                          gradient: frames.map { _ in zero })
    }

    /// Horizontal section at north = 5 m, drawn from east 2 to east 8 (flow to the north is positive).
    private func section(stations: Int, alpha: Double = 0.8, level: Double = 1, check: [Bool]? = nil) -> CrossSection {
        var o = JSONObject([
            ("bath", .string("bath.csv")), ("level", .double(level)), ("left_station", .int(0)),
            ("east_l", .double(2)), ("north_l", .double(5)), ("east_r", .double(8)), ("north_r", .double(5)),
            ("num_stations", .int(stations)), ("alpha", .double(alpha)),
        ])
        if let check { o["check"] = .array(check.map { JSONValue.bool($0) }) }
        return CrossSection(name: "CS", json: o)
    }

    /// step 1 at 10 fps: dt = 0.1 s, so −10 px (0.1 m north) per pair is 1 m/s.
    private func options(interpolate: Bool) -> DischargeOptions {
        DischargeOptions(frameStep: 1, fps: 10, interpolate: interpolate)
    }

    private let flatBed = Bathymetry(stations: [0, 6], elevations: [0, 0])

    // MARK: - Analytic cases

    /// Rectangular channel (flat bed wet to both ends, depth D = 1, width L = 6) and uniform velocity V = 1 m/s.
    /// Upstream's mid-section gives the end stations the full spacing (`w[0] = x[1] − x[0]`), so the stations'
    /// widths add up to W = L·n/(n − 1) = 7 m and Q = α·V·W·D exactly; with interpolation the end velocities are
    /// forced to 0 and W = L·(n − 2)/(n − 1) = 5 m.
    func testRectangularChannelUniformVelocity() throws {
        let p = piv(frames: [-10, -10], median: -10)
        let r = try DischargeCalculator.compute(section: section(stations: 7), bathymetry: flatBed, piv: p, matrix: matrix,
                                                options: options(interpolate: false))
        for i in 0..<7 {
            XCTAssertEqual(r.distance[i], Double(i), accuracy: 1e-12)
            XCTAssertEqual(r.streamwiseVelocity[i], 1, accuracy: 1e-12)
            XCTAssertEqual(r.depth[i], 1, accuracy: 1e-12)
            XCTAssertEqual(r.east[i], 2 + Double(i), accuracy: 1e-12)
            XCTAssertEqual(r.x[i], 100 * (2 + Double(i)), accuracy: 1e-9)
            XCTAssertEqual(r.y[i], 500, accuracy: 1e-9)
            XCTAssertEqual(r.minusStd[i], 1, accuracy: 1e-12)
            XCTAssertEqual(r.percentile95[i], 1, accuracy: 1e-12)
        }
        let w = r.w.reduce(0, +)
        XCTAssertEqual(w, 7, accuracy: 1e-12)
        XCTAssertEqual(r.totalQ, 0.8 * 1 * w * 1, accuracy: 1e-12)
        XCTAssertEqual(r.totalA, 6, accuracy: 1e-12)
        XCTAssertEqual(r.totalW, 6, accuracy: 1e-12)
        XCTAssertEqual(r.maxDepth, 1, accuracy: 1e-12)
        XCTAssertEqual(r.averageDepth, 1, accuracy: 1e-12)
        XCTAssertEqual(r.meanV, 5.6 / 6, accuracy: 1e-12)
        XCTAssertEqual(r.meanVs, 1, accuracy: 1e-12)
        XCTAssertEqual(r.totalQStd, 0, accuracy: 1e-12)
        XCTAssertEqual(r.measuredQ, 1, accuracy: 1e-12)
        XCTAssertEqual(r.qPortion.reduce(0, +), 1, accuracy: 1e-12)

        let ri = try DischargeCalculator.compute(section: section(stations: 7), bathymetry: flatBed, piv: p, matrix: matrix,
                                                 options: options(interpolate: true))
        let filled = try XCTUnwrap(ri.filledStreamwiseVelocity)
        let expectedFilled: [Double] = [0, 1, 1, 1, 1, 1, 0]
        for i in 0..<7 { XCTAssertEqual(filled[i], expectedFilled[i], accuracy: 1e-12) }
        XCTAssertEqual(ri.totalQ, 0.8 * 1 * 5 * 1, accuracy: 1e-12)
    }

    /// Sloping banks: the water-level crossings must be used for depth, area and width (PLAN #8).
    func testTriangularBankUsesCrossings() throws {
        // Bed 2 → 0 → 2 at stations 0, 4, 10; level 1 crosses at 2 and 7.
        let bed = Bathymetry(stations: [0, 4, 10], elevations: [2, 0, 2])
        let props = SectionProfile.properties(stations: bed.stations, stages: bed.elevations, level: 1)
        XCTAssertEqual(props.area, 2.5, accuracy: 1e-12)
        XCTAssertEqual(props.width, 5, accuracy: 1e-12)
        XCTAssertEqual(props.maxDepth, 1, accuracy: 1e-12)
        XCTAssertEqual(props.averageDepth, 0.5, accuracy: 1e-12)
        // Upstream counts whole segments: twice the area and one extra metre of width.
        let up = SectionProfile.upstreamProperties(stations: bed.stations, stages: bed.elevations, level: 1)
        XCTAssertEqual(up.area, 5, accuracy: 1e-12)
        XCTAssertEqual(up.width, 6, accuracy: 1e-12)

        let p = piv(frames: [-10], median: -10)
        let r = try DischargeCalculator.compute(section: section(stations: 6), bathymetry: bed, piv: p, matrix: matrix,
                                                options: options(interpolate: false))
        XCTAssertEqual(r.wetSegments.count, 1)
        XCTAssertEqual(r.wetSegments[0][0], 0, accuracy: 1e-12)
        XCTAssertEqual(r.wetSegments[0][1], 5, accuracy: 1e-12)
        let expected: [Double] = [0, 0.5, 1, 2.0 / 3.0, 1.0 / 3.0, 0]
        for i in 0..<6 { XCTAssertEqual(r.depth[i], expected[i], accuracy: 1e-12) }
        XCTAssertEqual(r.totalA, 2.5, accuracy: 1e-12)
        XCTAssertEqual(r.totalW, 5, accuracy: 1e-12)
        XCTAssertEqual(r.upstream.totalA, 5, accuracy: 1e-12)
        XCTAssertEqual(r.upstream.totalW, 6, accuracy: 1e-12)
        XCTAssertEqual(r.meanV, r.totalQ / 2.5, accuracy: 1e-12)
        // The last station sits at the right crossing (5 m from the left one).
        XCTAssertEqual(r.east[5], 7, accuracy: 1e-12)
    }

    func testIslandGivesTwoWetSegments() throws {
        let bed = Bathymetry(stations: [0, 2, 4, 6, 8], elevations: [2, 0, 2, 0, 2])
        let segs = SectionProfile.wetSegments(stations: bed.stations, stages: bed.elevations, level: 1)
        XCTAssertEqual(segs.count, 2)
        XCTAssertEqual(segs[0].0, 1, accuracy: 1e-12)
        XCTAssertEqual(segs[0].1, 3, accuracy: 1e-12)
        XCTAssertEqual(segs[1].0, 5, accuracy: 1e-12)
        XCTAssertEqual(segs[1].1, 7, accuracy: 1e-12)
        let props = SectionProfile.properties(stations: bed.stations, stages: bed.elevations, level: 1)
        XCTAssertEqual(props.area, 2, accuracy: 1e-12)
        XCTAssertEqual(props.width, 4, accuracy: 1e-12)
        let p = piv(frames: [-10], median: -10)
        let r = try DischargeCalculator.compute(section: section(stations: 7), bathymetry: bed, piv: p, matrix: matrix,
                                                options: options(interpolate: false))
        XCTAssertEqual(r.wetSegments.count, 2)
        let expectedSegments: [[Double]] = [[0, 2], [4, 6]]
        for (a, e) in zip(r.wetSegments, expectedSegments) {
            XCTAssertEqual(a[0], e[0], accuracy: 1e-12)
            XCTAssertEqual(a[1], e[1], accuracy: 1e-12)
        }
        XCTAssertEqual(r.depth[3], 0, accuracy: 1e-12) // over the island (station 4 of the bathymetry)
    }

    /// Two pairs at 0.8 and 1.2 m/s (σ = 0.2), median 1 m/s, station 3 unchecked. Without interpolation the
    /// unchecked station is out of total_Q and of its std (PLAN #7); upstream keeps its Q_plus_std.
    func testUncheckedStationWithoutInterpolation() throws {
        let p = piv(frames: [-8, -12], median: -10)
        let check = [true, true, true, false, true, true, true]
        let r = try DischargeCalculator.compute(section: section(stations: 7, check: check), bathymetry: flatBed, piv: p,
                                                matrix: matrix, options: options(interpolate: false))
        XCTAssertFalse(r.checkWasReset)
        XCTAssertTrue(r.q[3].isNaN)
        XCTAssertEqual(r.plusStd[3], 1.2, accuracy: 1e-12)
        XCTAssertEqual(r.percentile5[0], 0.82, accuracy: 1e-12)
        XCTAssertEqual(r.totalQ, 0.8 * 6, accuracy: 1e-12)
        XCTAssertEqual(r.totalQStd, 0.8 * 0.2 * 6, accuracy: 1e-12)
        XCTAssertEqual(r.upstream.totalQStd, 0.8 * 1.2 * 7 - 0.8 * 6, accuracy: 1e-12)
        XCTAssertEqual(r.measuredQ, 1, accuracy: 1e-12)
        XCTAssertEqual(r.interpolatedQ, 0, accuracy: 1e-12)
        XCTAssertEqual(r.meanVs, 1, accuracy: 1e-12)
    }

    /// With interpolation the unchecked station is filled (Froude) and its σ is interpolated from its neighbours;
    /// the end stations, forced to 0 m/s, carry no σ. Upstream subtracts a total that has no std for the forced
    /// ends: 0.96·7 − 4.0 = 2.72 instead of 0.8.
    func testUncheckedStationWithInterpolation() throws {
        let p = piv(frames: [-8, -12], median: -10)
        let check = [true, true, true, false, true, true, true]
        let r = try DischargeCalculator.compute(section: section(stations: 7, check: check), bathymetry: flatBed, piv: p,
                                                matrix: matrix, options: options(interpolate: true))
        XCTAssertEqual(try XCTUnwrap(r.filledStreamwiseVelocity)[3], 1, accuracy: 1e-12)
        XCTAssertEqual(r.totalQ, 0.8 * 5, accuracy: 1e-12)
        XCTAssertEqual(r.totalQStd, 0.8 * 0.2 * 5, accuracy: 1e-12)
        XCTAssertEqual(r.upstream.totalQStd, 0.8 * 1.2 * 7 - 0.8 * 5, accuracy: 1e-12)
        XCTAssertEqual(r.measuredQ, 0.8, accuracy: 1e-12)
        XCTAssertEqual(r.interpolatedQ, 0.2, accuracy: 1e-12)
    }

    /// A NaN vector at the node under station 3 (east 5 m): its velocity and std are NaN, it is filled with
    /// interpolation, and its std is interpolated too. Upstream drops its Q_plus_std but keeps its Q.
    func testNaNStationStdIsConsistent() throws {
        let node = 10 * 21 + 10 // pixel (500, 500) = world (5, 5)
        let p = piv(frames: [-8, -12], median: -10, nanNode: node)
        let r = try DischargeCalculator.compute(section: section(stations: 7), bathymetry: flatBed, piv: p, matrix: matrix,
                                                options: options(interpolate: true))
        XCTAssertTrue(r.checkWasReset)
        XCTAssertTrue(r.streamwiseVelocity[3].isNaN)
        XCTAssertTrue(r.plusStd[3].isNaN)
        XCTAssertEqual(r.streamwiseVelocity[2], 1, accuracy: 1e-12)
        XCTAssertEqual(try XCTUnwrap(r.filledStreamwiseVelocity)[3], 1, accuracy: 1e-12)
        XCTAssertEqual(r.totalQ, 0.8 * 5, accuracy: 1e-12)
        XCTAssertEqual(r.stationStd[3], 0.2, accuracy: 1e-12)
        XCTAssertEqual(r.totalQStd, 0.8 * 0.2 * 5, accuracy: 1e-12)
        XCTAssertEqual(r.upstream.totalQStd, 0.8 * 1.2 * 6 - 0.8 * 5, accuracy: 1e-12)

        let rn = try DischargeCalculator.compute(section: section(stations: 7), bathymetry: flatBed, piv: p, matrix: matrix,
                                                 options: options(interpolate: false))
        XCTAssertEqual(rn.totalQ, 0.8 * 6, accuracy: 1e-12)
        XCTAssertEqual(rn.totalQStd, 0.8 * 0.2 * 6, accuracy: 1e-12)
        XCTAssertGreaterThanOrEqual(rn.totalQStd, 0)
    }

    /// `left_station` has no effect on the computation (PLAN #6: the GUI already applied it to east_l).
    func testLeftStationDoesNotShiftStations() throws {
        let p = piv(frames: [-10], median: -10)
        var s = section(stations: 7)
        let r0 = try DischargeCalculator.compute(section: s, bathymetry: flatBed, piv: p, matrix: matrix, options: options(interpolate: false))
        s.json["left_station"] = .double(1.5)
        let r1 = try DischargeCalculator.compute(section: s, bathymetry: flatBed, piv: p, matrix: matrix, options: options(interpolate: false))
        XCTAssertEqual(r0.east, r1.east)
        XCTAssertEqual(r0.totalQ, r1.totalQ)
    }

    func testDrySectionThrows() {
        let p = piv(frames: [-10], median: -10)
        XCTAssertThrowsError(try DischargeCalculator.compute(section: section(stations: 7, level: -1), bathymetry: flatBed,
                                                             piv: p, matrix: matrix, options: options(interpolate: false)))
    }

    func testUpdateWritesDesktopKeysAndSummary() throws {
        let p = piv(frames: [-8, -12], median: -10)
        var xs = CrossSections(sections: [section(stations: 7)])
        xs.sections[0].json["filled_streamwise_velocity_magnitude"] = JSONValue.doubles([1])
        xs.sections[0].json["stiv_velocity_profile"] = JSONValue.doubles([1, 2])
        xs.sections[0].json["rw_length"] = .double(6)
        let opts = DischargeOptions(frameStep: 1, fps: 10, interpolate: false, numStations: 5)
        try DischargeCalculator.update(&xs, index: 0, bathymetry: flatBed, piv: p, matrix: matrix, options: opts)
        let o = xs.sections[0].json
        XCTAssertNil(o["filled_streamwise_velocity_magnitude"])
        XCTAssertNil(o["stiv_velocity_profile"]) // num_stations changed 7 → 5
        XCTAssertEqual(o["num_stations"], .int(5))
        XCTAssertEqual(o["id"]?.arrayValue?.count, 5)
        XCTAssertEqual(o["check"]?.arrayValue?.count, 5)
        for key in ["distance", "streamwise_velocity_magnitude", "depth", "minus_std", "plus_std", "5th_percentile",
                    "95th_percentile", "seeded_vel_profile", "W", "A", "Q", "Q_portion", "Q_minus_std", "Q_plus_std"] {
            XCTAssertEqual(o[key]?.doubleArray?.count, 5, key)
        }
        for key in ["total_Q", "total_q_std", "measured_Q", "interpolated_Q", "total_A", "total_W", "max_depth",
                    "average_depth", "mean_V", "mean_Vs"] {
            XCTAssertNotNil(o[key]?.doubleValue, key)
        }
        let summary = try XCTUnwrap(xs.summary?.objectValue)
        XCTAssertEqual(summary["mean"]?.objectValue?["total_W"]?.doubleValue, 6)
        // 5 stations over 6 m: spacing 1.5 m, Σw = 7.5 m (full spacing at the ends), V = D = 1.
        XCTAssertEqual(try XCTUnwrap(summary["mean"]?.objectValue?["total_Q"]?.doubleValue), 0.8 * 7.5, accuracy: 1e-12)
        XCTAssertEqual(summary["std"]?.objectValue?["total_Q"]?.doubleValue, 0)
        // Round trip through the desktop writer.
        let url = try TestData.tempDirectory().appendingPathComponent("xsections.json")
        try xs.write(url)
        XCTAssertEqual(try CrossSections.read(url), xs)
    }

    func testNumPyEquivalents() {
        XCTAssertEqual(SectionMath.interp(1.5, [0, 1, 2], [0, 10, 20]), 15)
        XCTAssertEqual(SectionMath.interp(-1, [0, 1, 2], [5, 10, 20]), 5)
        XCTAssertEqual(SectionMath.interp(3, [0, 1, 2], [5, 10, 20]), 20)
        XCTAssertEqual(SectionMath.interp(1, [0, 1, 2], [.nan, 10, .nan]), 10)
        XCTAssertTrue(SectionMath.interp(0.5, [0, 1, 2], [.nan, 10, 20]).isNaN)
        XCTAssertEqual(SectionMath.percentile([1, 2, 3, 4], 95), 3.85, accuracy: 1e-12)
        XCTAssertEqual(SectionMath.std([1, 3]), 1)
        XCTAssertTrue(SectionMath.std([1, .nan]).isNaN)
        XCTAssertEqual(SectionMath.fillByIndex([1, .nan, 3], invalid: [false, true, false]), [1, 2, 3])
    }

    func testLinearInterpolatorMatchesPlaneAndIsNaNOutside() {
        // Irregular points; a linear field is reproduced exactly inside the hull.
        let nodes = [Point2(0, 0), Point2(3, 0.2), Point2(6, -0.1), Point2(0.3, 2), Point2(3.1, 2.5), Point2(5.8, 2.1),
                     Point2(-0.2, 4.1), Point2(2.9, 3.9), Point2(6.2, 4)]
        let values = nodes.map { 2 * $0.x - 3 * $0.y + 1 }
        let queries = [Point2(1, 1), Point2(4.4, 3.1), Point2(20, 20)]
        let r = SectionLinearInterpolator(nodes: nodes, queries: queries).interpolate(values)
        XCTAssertEqual(r[0], 2 - 3 + 1, accuracy: 1e-12)
        XCTAssertEqual(r[1], 8.8 - 9.3 + 1, accuracy: 1e-12)
        XCTAssertTrue(r[2].isNaN)
        // 2n − 2 − h triangles, with h = 4 hull points (the four corners).
        XCTAssertEqual(SectionDelaunay(points: nodes).triangles.count, 2 * nodes.count - 2 - 4)
    }

    // MARK: - Builder (GUI side)

    func testRoundLikeJavaScriptToFixed() {
        XCTAssertEqual(SectionBuilder.round2(1.765), 1.76)
        XCTAssertEqual(SectionBuilder.round2(0.125), 0.13)
        XCTAssertEqual(SectionBuilder.round2(-0.125), -0.13)
        XCTAssertEqual(SectionBuilder.round2(0.854), 0.85)
    }

    private func piscoInputs() throws -> (Bathymetry, Matrix3, PIVResults, CrossSection) {
        let dir = "river-examples/pisco/"
        let bath = try Bathymetry.read(TestData.url(dir + "pisco_section.csv"))
        let rows = try XCTUnwrap(try JSON.read(TestData.url(dir + "transformation.json")).arrayValue)
        let m = Matrix3(rows: rows.map { $0.doubleArray ?? [] })
        let p = try PIVResults.read(TestData.url(dir + "piv_results.json"))
        let xs = try CrossSections.read(TestData.url(dir + "section.json"))
        let first = try XCTUnwrap(xs.sections.first)
        return (bath, m, p, first)
    }

    /// The drawn left point lies `left_station` metres to the right of the water's left edge: the stored section
    /// starts `left_station` before it and spans the wetted width.
    func testBuilderPlacesSectionLikeTheGUI() throws {
        let (bath, m, _, ref) = try piscoInputs()
        let drawnL = try XCTUnwrap(ref.pixelLeft)
        let drawnR = try XCTUnwrap(ref.pixelRight)
        let s = try SectionBuilder.build(name: "CS_default_1", drawnLeft: drawnL, drawnRight: drawnR, matrix: m,
                                         bathymetry: bath, bathymetryPath: "pisco_section.csv", leftStation: 2,
                                         level: 1.76, numStations: 15, alpha: 0.854)
        XCTAssertEqual(s.json.keys, ["bath", "rw_length", "level", "left_station", "east_l", "north_l", "east_r", "north_r",
                                     "xl", "yl", "xr", "yr", "dir_east_l", "dir_north_l", "dir_east_r", "dir_north_r",
                                     "dir_xl", "dir_yl", "dir_xr", "dir_yr", "num_stations", "alpha"])
        XCTAssertEqual(s.missingKeys, [])
        XCTAssertEqual(s.alpha, 0.85)
        XCTAssertEqual(s.json["num_stations"], .int(15))
        XCTAssertEqual(s.json["left_station"], .int(2))
        // Bed elevations rounded to cm: both ends 1.76 ≤ 1.76, so the whole profile is wet. The notebook's 1.765 is
        // above both rounded banks: desktop could not reopen it (blank results screen), so it is rejected.
        XCTAssertThrowsError(try SectionBuilder.build(name: "CS", drawnLeft: drawnL, drawnRight: drawnR, matrix: m,
                                                      bathymetry: bath, bathymetryPath: "pisco_section.csv", level: 1.765)) { e in
            XCTAssertEqual(e as? SectionError, .levelAboveBanks(maximum: 1.76))
        }
        XCTAssertEqual(try XCTUnwrap(s.realWorldLength), 22.656012, accuracy: 1e-9)
        let wl = try XCTUnwrap(s.worldLeft), wr = try XCTUnwrap(s.worldRight)
        let dl = m.apply(drawnL), dr = m.apply(drawnR)
        XCTAssertEqual(wl.distance(to: wr), 22.656012, accuracy: 1e-6)
        XCTAssertEqual(wl.distance(to: dl), 2, accuracy: 1e-6)
        // East_l lies before the drawn point, on the drawn line.
        let ux = (dr.x - dl.x) / dl.distance(to: dr), uy = (dr.y - dl.y) / dl.distance(to: dr)
        XCTAssertEqual(wl.x, dl.x - 2 * ux, accuracy: 1e-6)
        XCTAssertEqual(wl.y, dl.y - 2 * uy, accuracy: 1e-6)
        let back = m.apply(try XCTUnwrap(s.pixelLeft))
        XCTAssertEqual(back.x, wl.x, accuracy: 1e-9)
        XCTAssertEqual(back.y, wl.y, accuracy: 1e-9)

        // Default level: lower end elevation as toFixed(2).
        let d = try SectionBuilder.build(name: "CS", drawnLeft: drawnL, drawnRight: drawnR, matrix: m, bathymetry: bath,
                                         bathymetryPath: "pisco_section.csv")
        XCTAssertEqual(d.level, 1.76)
        XCTAssertEqual(d.json["left_station"], .int(0))
        XCTAssertEqual(try XCTUnwrap(d.worldLeft).distance(to: dl), 0, accuracy: 1e-6)
        XCTAssertThrowsError(try SectionBuilder.build(name: "CS", drawnLeft: drawnL, drawnRight: drawnR, matrix: m,
                                                      bathymetry: bath, bathymetryPath: "p.csv", leftStation: 50))
    }

    // MARK: - pisco against RIVeR (golden)

    /// RIVeR's 05_discharge_calculation notebook: fps 25, step 2, interpolation on → Q = 50.52 m³/s,
    /// total_W 21.15 m (upstream) / 22.656 m (fixed, PLAN #8).
    func testPiscoMatchesNotebook() throws {
        let (bath, m, p, s) = try piscoInputs()
        let r = try DischargeCalculator.compute(section: s, bathymetry: bath, piv: p, matrix: m,
                                                options: DischargeOptions(frameStep: 2, fps: 25, interpolate: true))
        XCTAssertEqual(r.totalQ, 50.52, accuracy: 0.005)
        XCTAssertEqual(r.upstream.totalW, 21.15, accuracy: 0.005)
        XCTAssertEqual(r.totalW - r.upstream.totalW, 1.501547555, accuracy: 1e-9)
        XCTAssertEqual(r.totalA, r.upstream.totalA, accuracy: 1e-12) // both ends exactly at the level: no crossings
        XCTAssertEqual(r.maxDepth, 1.765, accuracy: 1e-12)
    }

    func testPiscoAgainstGolden() throws {
        let dir = TestData.url("golden/section")
        let combos = ["interp", "nointerp", "nointerp_unchecked", "interp_unchecked", "seeded_interp", "seeded_nointerp"]
        var found = 0
        for combo in combos {
            let url = dir.appendingPathComponent("pisco_\(combo).json")
            guard FileManager.default.fileExists(atPath: url.path) else { continue }
            found += 1
            try checkGolden(url, combo: combo)
        }
        if found == 0 { throw XCTSkip("No hay referencias en ci/testdata/golden/section (las genera CI: golden.yml)") }
    }

    /// Equal within `tol`, NaN only equal to NaN.
    private func assertClose(_ a: Double, _ e: Double, _ tol: Double, _ message: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        if e.isNaN {
            XCTAssertTrue(a.isNaN, "\(message): \(a), se esperaba NaN", file: file, line: line)
        } else {
            XCTAssertEqual(a, e, accuracy: tol * max(1, abs(e)), message, file: file, line: line)
        }
    }

    private func checkGolden(_ url: URL, combo: String) throws {
        let doc = try XCTUnwrap(try JSON.read(url).objectValue)
        let meta = try XCTUnwrap(doc["meta"]?.objectValue)
        let golden = try XCTUnwrap(doc["xsections"]?.objectValue?["section1"]?.objectValue)
        let (bath, m, p, base) = try piscoInputs()
        var s = base
        let unchecked = meta["unchecked"]?.arrayValue?.compactMap { $0.intValue } ?? []
        let ns = try XCTUnwrap(meta["num_stations"]?.intValue)
        if !unchecked.isEmpty {
            s.json["check"] = .array((0..<ns).map { JSONValue.bool(!unchecked.contains($0)) })
        }
        let step = try XCTUnwrap(meta["step"]?.intValue)
        let fps = try XCTUnwrap(meta["fps"]?.doubleValue)
        let opts = DischargeOptions(frameStep: step,
                                    fps: fps,
                                    interpolate: meta["interpolate"]?.boolValue ?? false,
                                    artificialSeeding: meta["artificial_seeding"]?.boolValue ?? false,
                                    alpha: meta["alpha"]?.doubleValue, numStations: ns)
        let r = try DischargeCalculator.compute(section: s, bathymetry: bath, piv: p, matrix: m, options: opts)
        var written = s
        DischargeCalculator.apply(r, to: &written, options: opts)
        let o = written.json

        // Per-station arrays: identical up to floating-point noise (same triangulation as Qhull on this field).
        // displacement_x/y (pixel-space field, degenerate regular grid) are display-only and not compared.
        let arrays = ["east", "north", "distance", "x", "y", "displacement_east", "displacement_north", "streamwise_east",
                      "streamwise_north", "crosswise_east", "crosswise_north", "streamwise_velocity_magnitude",
                      "streamwise_x", "streamwise_y", "depth", "minus_std", "plus_std", "5th_percentile",
                      "95th_percentile", "seeded_vel_profile", "filled_streamwise_velocity_magnitude",
                      "filled_seeded_vel_profile", "W", "A", "Q", "Q_minus_std", "Q_plus_std", "Q_portion"]
        for key in arrays {
            guard let expected = golden[key]?.doubleArray else {
                XCTAssertNil(o[key], "\(combo) \(key) no está en la referencia")
                continue
            }
            let actual = try XCTUnwrap(o[key]?.doubleArray, "\(combo) \(key)")
            XCTAssertEqual(actual.count, expected.count, "\(combo) \(key)")
            for (i, (a, e)) in zip(actual, expected).enumerated() {
                if e.isNaN {
                    XCTAssertTrue(a.isNaN, "\(combo) \(key)[\(i)] = \(a), se esperaba NaN")
                } else {
                    XCTAssertEqual(a, e, accuracy: 1e-7 * max(1, abs(e)), "\(combo) \(key)[\(i)]")
                }
            }
        }
        let goldenSegments: [JSONValue] = golden["wet_segments"]?.arrayValue ?? []
        XCTAssertEqual(r.wetSegments.count, goldenSegments.count, combo)
        for (a, e) in zip(r.wetSegments, goldenSegments) {
            let ev: [Double] = e.doubleArray ?? []
            XCTAssertEqual(ev.count, 2, combo)
            if ev.count == 2 {
                XCTAssertEqual(a[0], ev[0], accuracy: 1e-9, combo)
                XCTAssertEqual(a[1], ev[1], accuracy: 1e-9, combo)
            }
        }

        func g(_ k: String) throws -> Double { try XCTUnwrap(golden[k]?.doubleValue, "\(combo) \(k)") }
        let tol = 1e-7
        // Unchanged totals.
        try assertClose(r.totalQ, g("total_Q"), tol, combo)
        try assertClose(r.measuredQ, g("measured_Q"), tol, combo)
        try assertClose(r.interpolatedQ, g("interpolated_Q"), tol, combo)
        try assertClose(r.maxDepth, g("max_depth"), tol, combo)
        try assertClose(r.meanVs, g("mean_Vs"), tol, combo)
        // Fixed totals: upstream's value is reproduced, and the fix moves it by the expected amount.
        try assertClose(r.upstream.totalQStd, g("total_q_std"), tol, combo)
        try assertClose(r.upstream.totalA, g("total_A"), tol, combo)
        try assertClose(r.upstream.totalW, g("total_W"), tol, combo)
        try assertClose(r.upstream.averageDepth, g("average_depth"), tol, combo)
        try assertClose(r.upstream.meanV, g("mean_V"), tol, combo)
        // pisco: both ends of the bathymetry are exactly at the level → same area, +1.5015 m of width (#8).
        try assertClose(r.totalA, g("total_A"), tol, combo)
        let upstreamW = try g("total_W")
        XCTAssertEqual(r.totalW - upstreamW, 1.501547555, accuracy: 1e-9, combo)
        try assertClose(r.meanV, g("mean_V"), tol, combo)
        // #7: the std of exactly the stations in total_Q, never negative.
        var expectedStd = 0.0
        let alpha = try XCTUnwrap(meta["alpha"]?.doubleValue)
        for i in 0..<ns where !r.q[i].isNaN && (opts.interpolate || r.check[i]) {
            expectedStd += abs(r.a[i] * alpha) * r.stationStd[i]
        }
        XCTAssertEqual(r.totalQStd, expectedStd, accuracy: 1e-12, combo)
        XCTAssertGreaterThanOrEqual(r.totalQStd, 0, combo)
        if unchecked.isEmpty && !opts.artificialSeeding {
            // All stations checked and measured: the fix agrees with upstream.
            XCTAssertEqual(r.totalQStd, try g("total_q_std"), accuracy: 1e-6, combo)
        }
        if let summary = doc["xsections"]?.objectValue?["summary"]?.objectValue?["mean"]?.objectValue {
            XCTAssertEqual(try XCTUnwrap(summary["total_Q"]?.doubleValue), r.totalQ, accuracy: tol, combo)
        }
    }
}
