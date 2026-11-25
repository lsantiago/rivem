package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.project.Bathymetry
import org.rivem.core.project.CrossSection
import org.rivem.core.project.CrossSections
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import org.rivem.core.project.PIVResults
import org.rivem.core.section.DischargeCalculator
import org.rivem.core.section.DischargeOptions
import org.rivem.core.section.SectionBuilder
import org.rivem.core.section.SectionDelaunay
import org.rivem.core.section.SectionError
import org.rivem.core.section.SectionLinearInterpolator
import org.rivem.core.section.SectionMath
import org.rivem.core.section.SectionProfile
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * F4: cross-section builder and discharge (`section/`). Analytic cases plus RIVeR's `pisco` example against the
 * golden references written by `tools/golden/section.py` (CI) in `ci/testdata/golden/section/`. Swift `SectionTests`.
 */
class SectionTests {
    // MARK: - Synthetic setup

    /**
     * 21 x 21 PIV nodes every 50 px; matrix east = 0.01·x, north = 10 − 0.01·y (no perspective, so a uniform
     * pixel field is a uniform real-world field and linear interpolation is exact on any triangulation).
     */
    private val matrix = Matrix3.fromRows(listOf(listOf(0.01, 0.0, 0.0), listOf(0.0, -0.01, 10.0), listOf(0.0, 0.0, 1.0)))

    private fun grid(): Pair<List<Double>, List<Double>> {
        val x = ArrayList<Double>()
        val y = ArrayList<Double>()
        for (r in 0 until 21) {
            for (c in 0 until 21) {
                x.add(c.toDouble() * 50)
                y.add(r.toDouble() * 50)
            }
        }
        return Pair(x, y)
    }

    /** Every node moves `v` px per pair (negative = north); one array per frame pair. */
    private fun piv(frames: List<Double>, median: Double, nanNode: Int? = null): PIVResults {
        val g = grid()
        val n = g.first.size
        val zero = List(n) { 0.0 }
        val vm = MutableList(n) { median }
        val um = zero.toMutableList()
        val vs: List<MutableList<Double>> = frames.map { f -> MutableList(n) { f } }
        val us: List<MutableList<Double>> = frames.map { zero.toMutableList() }
        if (nanNode != null) {
            vm[nanNode] = Double.NaN
            um[nanNode] = Double.NaN
            for (f in frames.indices) {
                vs[f][nanNode] = Double.NaN
                us[f][nanNode] = Double.NaN
            }
        }
        return PIVResults(
            rows = 21, columns = 21, x = g.first, y = g.second, u = us, v = vs, uMedian = um, vMedian = vm,
            gradient = frames.map { zero },
        )
    }

    /** Horizontal section at north = 5 m, drawn from east 2 to east 8 (flow to the north is positive). */
    private fun section(stations: Int, alpha: Double = 0.8, level: Double = 1.0, check: List<Boolean>? = null): CrossSection {
        val o = JSONObject(
            "bath" to JSONValue.string("bath.csv"), "level" to JSONValue.double(level), "left_station" to JSONValue.int(0),
            "east_l" to JSONValue.double(2.0), "north_l" to JSONValue.double(5.0), "east_r" to JSONValue.double(8.0),
            "north_r" to JSONValue.double(5.0), "num_stations" to JSONValue.int(stations), "alpha" to JSONValue.double(alpha),
        )
        if (check != null) o["check"] = JSONValue.array(check.map { JSONValue.bool(it) })
        return CrossSection("CS", o)
    }

    /** step 1 at 10 fps: dt = 0.1 s, so −10 px (0.1 m north) per pair is 1 m/s. */
    private fun options(interpolate: Boolean): DischargeOptions = DischargeOptions(frameStep = 1, fps = 10.0, interpolate = interpolate)

    private val flatBed = Bathymetry(listOf(0.0, 6.0), listOf(0.0, 0.0))

    // MARK: - Analytic cases

    /**
     * Rectangular channel (flat bed wet to both ends, depth D = 1, width L = 6) and uniform velocity V = 1 m/s.
     * Upstream's mid-section gives the end stations the full spacing (`w[0] = x[1] − x[0]`), so the stations'
     * widths add up to W = L·n/(n − 1) = 7 m and Q = α·V·W·D exactly; with interpolation the end velocities are
     * forced to 0 and W = L·(n − 2)/(n − 1) = 5 m.
     */
    @Test
    fun rectangularChannelUniformVelocity() {
        val p = piv(listOf(-10.0, -10.0), -10.0)
        val r = DischargeCalculator.compute(section(7), flatBed, p, matrix, options(false))
        for (i in 0 until 7) {
            assertEquals(i.toDouble(), r.distance[i], 1e-12)
            assertEquals(1.0, r.streamwiseVelocity[i], 1e-12)
            assertEquals(1.0, r.depth[i], 1e-12)
            assertEquals(2 + i.toDouble(), r.east[i], 1e-12)
            assertEquals(100 * (2 + i.toDouble()), r.x[i], 1e-9)
            assertEquals(500.0, r.y[i], 1e-9)
            assertEquals(1.0, r.minusStd[i], 1e-12)
            assertEquals(1.0, r.percentile95[i], 1e-12)
        }
        val w = r.w.sum()
        assertEquals(7.0, w, 1e-12)
        assertEquals(0.8 * 1 * w * 1, r.totalQ, 1e-12)
        assertEquals(6.0, r.totalA, 1e-12)
        assertEquals(6.0, r.totalW, 1e-12)
        assertEquals(1.0, r.maxDepth, 1e-12)
        assertEquals(1.0, r.averageDepth, 1e-12)
        assertEquals(5.6 / 6, r.meanV, 1e-12)
        assertEquals(1.0, r.meanVs, 1e-12)
        assertEquals(0.0, r.totalQStd, 1e-12)
        assertEquals(1.0, r.measuredQ, 1e-12)
        assertEquals(1.0, r.qPortion.sum(), 1e-12)

        val ri = DischargeCalculator.compute(section(7), flatBed, p, matrix, options(true))
        val filled = ri.filledStreamwiseVelocity!!
        val expectedFilled = listOf(0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0)
        for (i in 0 until 7) assertEquals(expectedFilled[i], filled[i], 1e-12)
        assertEquals(0.8 * 1 * 5 * 1, ri.totalQ, 1e-12)
    }

    /** Sloping banks: the water-level crossings must be used for depth, area and width (PLAN #8). */
    @Test
    fun triangularBankUsesCrossings() {
        // Bed 2 → 0 → 2 at stations 0, 4, 10; level 1 crosses at 2 and 7.
        val bed = Bathymetry(listOf(0.0, 4.0, 10.0), listOf(2.0, 0.0, 2.0))
        val props = SectionProfile.properties(bed.stations, bed.elevations, 1.0)
        assertEquals(2.5, props.area, 1e-12)
        assertEquals(5.0, props.width, 1e-12)
        assertEquals(1.0, props.maxDepth, 1e-12)
        assertEquals(0.5, props.averageDepth, 1e-12)
        // Upstream counts whole segments: twice the area and one extra metre of width.
        val up = SectionProfile.upstreamProperties(bed.stations, bed.elevations, 1.0)
        assertEquals(5.0, up.area, 1e-12)
        assertEquals(6.0, up.width, 1e-12)

        val p = piv(listOf(-10.0), -10.0)
        val r = DischargeCalculator.compute(section(6), bed, p, matrix, options(false))
        assertEquals(1, r.wetSegments.size)
        assertEquals(0.0, r.wetSegments[0][0], 1e-12)
        assertEquals(5.0, r.wetSegments[0][1], 1e-12)
        val expected = listOf(0.0, 0.5, 1.0, 2.0 / 3.0, 1.0 / 3.0, 0.0)
        for (i in 0 until 6) assertEquals(expected[i], r.depth[i], 1e-12)
        assertEquals(2.5, r.totalA, 1e-12)
        assertEquals(5.0, r.totalW, 1e-12)
        assertEquals(5.0, r.upstream.totalA, 1e-12)
        assertEquals(6.0, r.upstream.totalW, 1e-12)
        assertEquals(r.totalQ / 2.5, r.meanV, 1e-12)
        // The last station sits at the right crossing (5 m from the left one).
        assertEquals(7.0, r.east[5], 1e-12)
    }

    @Test
    fun islandGivesTwoWetSegments() {
        val bed = Bathymetry(listOf(0.0, 2.0, 4.0, 6.0, 8.0), listOf(2.0, 0.0, 2.0, 0.0, 2.0))
        val segs = SectionProfile.wetSegments(bed.stations, bed.elevations, 1.0)
        assertEquals(2, segs.size)
        assertEquals(1.0, segs[0].first, 1e-12)
        assertEquals(3.0, segs[0].second, 1e-12)
        assertEquals(5.0, segs[1].first, 1e-12)
        assertEquals(7.0, segs[1].second, 1e-12)
        val props = SectionProfile.properties(bed.stations, bed.elevations, 1.0)
        assertEquals(2.0, props.area, 1e-12)
        assertEquals(4.0, props.width, 1e-12)
        val p = piv(listOf(-10.0), -10.0)
        val r = DischargeCalculator.compute(section(7), bed, p, matrix, options(false))
        assertEquals(2, r.wetSegments.size)
        val expectedSegments = listOf(listOf(0.0, 2.0), listOf(4.0, 6.0))
        for ((a, e) in r.wetSegments.zip(expectedSegments)) {
            assertEquals(e[0], a[0], 1e-12)
            assertEquals(e[1], a[1], 1e-12)
        }
        assertEquals(0.0, r.depth[3], 1e-12) // over the island (station 4 of the bathymetry)
    }

    /**
     * Two pairs at 0.8 and 1.2 m/s (σ = 0.2), median 1 m/s, station 3 unchecked. Without interpolation the
     * unchecked station is out of total_Q and of its std (PLAN #7); upstream keeps its Q_plus_std.
     */
    @Test
    fun uncheckedStationWithoutInterpolation() {
        val p = piv(listOf(-8.0, -12.0), -10.0)
        val check = listOf(true, true, true, false, true, true, true)
        val r = DischargeCalculator.compute(section(7, check = check), flatBed, p, matrix, options(false))
        assertFalse(r.checkWasReset)
        assertTrue(r.q[3].isNaN())
        assertEquals(1.2, r.plusStd[3], 1e-12)
        assertEquals(0.82, r.percentile5[0], 1e-12)
        assertEquals(0.8 * 6, r.totalQ, 1e-12)
        assertEquals(0.8 * 0.2 * 6, r.totalQStd, 1e-12)
        assertEquals(0.8 * 1.2 * 7 - 0.8 * 6, r.upstream.totalQStd, 1e-12)
        assertEquals(1.0, r.measuredQ, 1e-12)
        assertEquals(0.0, r.interpolatedQ, 1e-12)
        assertEquals(1.0, r.meanVs, 1e-12)
    }

    /**
     * With interpolation the unchecked station is filled (Froude) and its σ is interpolated from its neighbours;
     * the end stations, forced to 0 m/s, carry no σ. Upstream subtracts a total that has no std for the forced
     * ends: 0.96·7 − 4.0 = 2.72 instead of 0.8.
     */
    @Test
    fun uncheckedStationWithInterpolation() {
        val p = piv(listOf(-8.0, -12.0), -10.0)
        val check = listOf(true, true, true, false, true, true, true)
        val r = DischargeCalculator.compute(section(7, check = check), flatBed, p, matrix, options(true))
        assertEquals(1.0, r.filledStreamwiseVelocity!![3], 1e-12)
        assertEquals(0.8 * 5, r.totalQ, 1e-12)
        assertEquals(0.8 * 0.2 * 5, r.totalQStd, 1e-12)
        assertEquals(0.8 * 1.2 * 7 - 0.8 * 5, r.upstream.totalQStd, 1e-12)
        assertEquals(0.8, r.measuredQ, 1e-12)
        assertEquals(0.2, r.interpolatedQ, 1e-12)
    }

    /**
     * A NaN vector at the node under station 3 (east 5 m): its velocity and std are NaN, it is filled with
     * interpolation, and its std is interpolated too. Upstream drops its Q_plus_std but keeps its Q.
     */
    @Test
    fun nanStationStdIsConsistent() {
        val node = 10 * 21 + 10 // pixel (500, 500) = world (5, 5)
        val p = piv(listOf(-8.0, -12.0), -10.0, nanNode = node)
        val r = DischargeCalculator.compute(section(7), flatBed, p, matrix, options(true))
        assertTrue(r.checkWasReset)
        assertTrue(r.streamwiseVelocity[3].isNaN())
        assertTrue(r.plusStd[3].isNaN())
        assertEquals(1.0, r.streamwiseVelocity[2], 1e-12)
        assertEquals(1.0, r.filledStreamwiseVelocity!![3], 1e-12)
        assertEquals(0.8 * 5, r.totalQ, 1e-12)
        assertEquals(0.2, r.stationStd[3], 1e-12)
        assertEquals(0.8 * 0.2 * 5, r.totalQStd, 1e-12)
        assertEquals(0.8 * 1.2 * 6 - 0.8 * 5, r.upstream.totalQStd, 1e-12)

        val rn = DischargeCalculator.compute(section(7), flatBed, p, matrix, options(false))
        assertEquals(0.8 * 6, rn.totalQ, 1e-12)
        assertEquals(0.8 * 0.2 * 6, rn.totalQStd, 1e-12)
        assertTrue(rn.totalQStd >= 0)
    }

    /** `left_station` has no effect on the computation (PLAN #6: the GUI already applied it to east_l). */
    @Test
    fun leftStationDoesNotShiftStations() {
        val p = piv(listOf(-10.0), -10.0)
        val s = section(7)
        val r0 = DischargeCalculator.compute(s, flatBed, p, matrix, options(false))
        s.json["left_station"] = JSONValue.double(1.5)
        val r1 = DischargeCalculator.compute(s, flatBed, p, matrix, options(false))
        assertEquals(r0.east, r1.east)
        assertEquals(r0.totalQ, r1.totalQ, 0.0)
    }

    @Test
    fun drySectionThrows() {
        val p = piv(listOf(-10.0), -10.0)
        assertThrows(SectionError::class.java) {
            DischargeCalculator.compute(section(7, level = -1.0), flatBed, p, matrix, options(false))
        }
    }

    @Test
    fun updateWritesDesktopKeysAndSummary() {
        val p = piv(listOf(-8.0, -12.0), -10.0)
        val xs = CrossSections(listOf(section(7)))
        xs.sections[0].json["filled_streamwise_velocity_magnitude"] = JSONValue.doubles(listOf(1.0))
        xs.sections[0].json["stiv_velocity_profile"] = JSONValue.doubles(listOf(1.0, 2.0))
        xs.sections[0].json["rw_length"] = JSONValue.double(6.0)
        val opts = DischargeOptions(frameStep = 1, fps = 10.0, interpolate = false, numStations = 5)
        DischargeCalculator.update(xs, 0, flatBed, p, matrix, opts)
        val o = xs.sections[0].json
        assertNull(o["filled_streamwise_velocity_magnitude"])
        assertNull(o["stiv_velocity_profile"]) // num_stations changed 7 → 5
        assertEquals(JSONValue.int(5), o["num_stations"])
        assertEquals(5, o["id"]?.arrayValue?.size)
        assertEquals(5, o["check"]?.arrayValue?.size)
        for (key in listOf(
            "distance", "streamwise_velocity_magnitude", "depth", "minus_std", "plus_std", "5th_percentile",
            "95th_percentile", "seeded_vel_profile", "W", "A", "Q", "Q_portion", "Q_minus_std", "Q_plus_std",
        )) {
            assertEquals(key, 5, o[key]?.doubleArray?.size)
        }
        for (key in listOf(
            "total_Q", "total_q_std", "measured_Q", "interpolated_Q", "total_A", "total_W", "max_depth",
            "average_depth", "mean_V", "mean_Vs",
        )) {
            assertNotNull(key, o[key]?.doubleValue)
        }
        val summary = xs.summary?.objectValue!!
        assertEquals(6.0, summary["mean"]?.objectValue?.get("total_W")?.doubleValue)
        // 5 stations over 6 m: spacing 1.5 m, Σw = 7.5 m (full spacing at the ends), V = D = 1.
        assertEquals(0.8 * 7.5, summary["mean"]?.objectValue?.get("total_Q")?.doubleValue!!, 1e-12)
        assertEquals(0.0, summary["std"]?.objectValue?.get("total_Q")?.doubleValue)
        // Round trip through the desktop writer.
        val file = File(TestData.tempDirectory(), "xsections.json")
        xs.write(file)
        assertEquals(xs, CrossSections.read(file))
    }

    @Test
    fun numPyEquivalents() {
        assertEquals(15.0, SectionMath.interp(1.5, listOf(0.0, 1.0, 2.0), listOf(0.0, 10.0, 20.0)), 0.0)
        assertEquals(5.0, SectionMath.interp(-1.0, listOf(0.0, 1.0, 2.0), listOf(5.0, 10.0, 20.0)), 0.0)
        assertEquals(20.0, SectionMath.interp(3.0, listOf(0.0, 1.0, 2.0), listOf(5.0, 10.0, 20.0)), 0.0)
        assertEquals(10.0, SectionMath.interp(1.0, listOf(0.0, 1.0, 2.0), listOf(Double.NaN, 10.0, Double.NaN)), 0.0)
        assertTrue(SectionMath.interp(0.5, listOf(0.0, 1.0, 2.0), listOf(Double.NaN, 10.0, 20.0)).isNaN())
        assertEquals(3.85, SectionMath.percentile(listOf(1.0, 2.0, 3.0, 4.0), 95.0), 1e-12)
        assertEquals(1.0, SectionMath.std(listOf(1.0, 3.0)), 0.0)
        assertTrue(SectionMath.std(listOf(1.0, Double.NaN)).isNaN())
        assertEquals(
            listOf(1.0, 2.0, 3.0),
            SectionMath.fillByIndex(listOf(1.0, Double.NaN, 3.0), listOf(false, true, false)),
        )
    }

    @Test
    fun linearInterpolatorMatchesPlaneAndIsNaNOutside() {
        // Irregular points; a linear field is reproduced exactly inside the hull.
        val nodes = listOf(
            Point2(0.0, 0.0), Point2(3.0, 0.2), Point2(6.0, -0.1), Point2(0.3, 2.0), Point2(3.1, 2.5), Point2(5.8, 2.1),
            Point2(-0.2, 4.1), Point2(2.9, 3.9), Point2(6.2, 4.0),
        )
        val values = nodes.map { 2 * it.x - 3 * it.y + 1 }
        val queries = listOf(Point2(1.0, 1.0), Point2(4.4, 3.1), Point2(20.0, 20.0))
        val r = SectionLinearInterpolator(nodes, queries).interpolate(values)
        assertEquals(2.0 - 3 + 1, r[0], 1e-12)
        assertEquals(8.8 - 9.3 + 1, r[1], 1e-12)
        assertTrue(r[2].isNaN())
        // 2n − 2 − h triangles, with h = 4 hull points (the four corners).
        assertEquals(2 * nodes.size - 2 - 4, SectionDelaunay(nodes).triangles.size)
    }

    // MARK: - Builder (GUI side)

    @Test
    fun roundLikeJavaScriptToFixed() {
        assertEquals(1.76, SectionBuilder.round2(1.765), 0.0)
        assertEquals(0.13, SectionBuilder.round2(0.125), 0.0)
        assertEquals(-0.13, SectionBuilder.round2(-0.125), 0.0)
        assertEquals(0.85, SectionBuilder.round2(0.854), 0.0)
    }

    private class PiscoInputs(val bath: Bathymetry, val matrix: Matrix3, val piv: PIVResults, val section: CrossSection)

    private fun piscoInputs(): PiscoInputs {
        val dir = "river-examples/pisco/"
        val bath = Bathymetry.read(TestData.file(dir + "pisco_section.csv"))
        val rows = JSON.read(TestData.file(dir + "transformation.json")).arrayValue!!
        val m = Matrix3.fromRows(rows.map { it.doubleArray ?: emptyList() })
        val p = PIVResults.read(TestData.file(dir + "piv_results.json"))
        val xs = CrossSections.read(TestData.file(dir + "section.json"))
        val first = xs.sections.first()
        return PiscoInputs(bath, m, p, first)
    }

    /**
     * The drawn left point lies `left_station` metres to the right of the water's left edge: the stored section
     * starts `left_station` before it and spans the wetted width.
     */
    @Test
    fun builderPlacesSectionLikeTheGUI() {
        val inputs = piscoInputs()
        val bath = inputs.bath
        val m = inputs.matrix
        val ref = inputs.section
        val drawnL = ref.pixelLeft!!
        val drawnR = ref.pixelRight!!
        val s = SectionBuilder.build(
            name = "CS_default_1", drawnLeft = drawnL, drawnRight = drawnR, matrix = m,
            bathymetry = bath, bathymetryPath = "pisco_section.csv", leftStation = 2.0,
            level = 1.76, numStations = 15, alpha = 0.854,
        )
        assertEquals(
            listOf(
                "bath", "rw_length", "level", "left_station", "east_l", "north_l", "east_r", "north_r",
                "xl", "yl", "xr", "yr", "dir_east_l", "dir_north_l", "dir_east_r", "dir_north_r",
                "dir_xl", "dir_yl", "dir_xr", "dir_yr", "num_stations", "alpha",
            ),
            s.json.keys,
        )
        assertEquals(emptyList<String>(), s.missingKeys)
        assertEquals(0.85, s.alpha)
        assertEquals(JSONValue.int(15), s.json["num_stations"])
        assertEquals(JSONValue.int(2), s.json["left_station"])
        // Bed elevations rounded to cm: both ends 1.76 ≤ 1.76, so the whole profile is wet. The notebook's 1.765 is
        // above both rounded banks: desktop could not reopen it (blank results screen), so it is rejected.
        val e = assertThrows(SectionError::class.java) {
            SectionBuilder.build(
                name = "CS", drawnLeft = drawnL, drawnRight = drawnR, matrix = m,
                bathymetry = bath, bathymetryPath = "pisco_section.csv", level = 1.765,
            )
        }
        assertEquals(SectionError.LevelAboveBanks(maximum = 1.76), e)
        assertEquals(22.656012, s.realWorldLength!!, 1e-9)
        val wl = s.worldLeft!!
        val wr = s.worldRight!!
        val dl = m.apply(drawnL)
        val dr = m.apply(drawnR)
        assertEquals(22.656012, wl.distanceTo(wr), 1e-6)
        assertEquals(2.0, wl.distanceTo(dl), 1e-6)
        // East_l lies before the drawn point, on the drawn line.
        val ux = (dr.x - dl.x) / dl.distanceTo(dr)
        val uy = (dr.y - dl.y) / dl.distanceTo(dr)
        assertEquals(dl.x - 2 * ux, wl.x, 1e-6)
        assertEquals(dl.y - 2 * uy, wl.y, 1e-6)
        val back = m.apply(s.pixelLeft!!)
        assertEquals(wl.x, back.x, 1e-9)
        assertEquals(wl.y, back.y, 1e-9)

        // Default level: lower end elevation as toFixed(2).
        val d = SectionBuilder.build(
            name = "CS", drawnLeft = drawnL, drawnRight = drawnR, matrix = m, bathymetry = bath,
            bathymetryPath = "pisco_section.csv",
        )
        assertEquals(1.76, d.level)
        assertEquals(JSONValue.int(0), d.json["left_station"])
        assertEquals(0.0, d.worldLeft!!.distanceTo(dl), 1e-6)
        assertThrows(SectionError::class.java) {
            SectionBuilder.build(
                name = "CS", drawnLeft = drawnL, drawnRight = drawnR, matrix = m,
                bathymetry = bath, bathymetryPath = "p.csv", leftStation = 50.0,
            )
        }
    }

    // MARK: - pisco against RIVeR (golden)

    /**
     * RIVeR's 05_discharge_calculation notebook: fps 25, step 2, interpolation on → Q = 50.52 m³/s,
     * total_W 21.15 m (upstream) / 22.656 m (fixed, PLAN #8).
     */
    @Test
    fun piscoMatchesNotebook() {
        val i = piscoInputs()
        val r = DischargeCalculator.compute(
            i.section, i.bath, i.piv, i.matrix,
            DischargeOptions(frameStep = 2, fps = 25.0, interpolate = true),
        )
        assertEquals(50.52, r.totalQ, 0.005)
        assertEquals(21.15, r.upstream.totalW, 0.005)
        assertEquals(1.501547555, r.totalW - r.upstream.totalW, 1e-9)
        assertEquals(r.upstream.totalA, r.totalA, 1e-12) // both ends exactly at the level: no crossings
        assertEquals(1.765, r.maxDepth, 1e-12)
    }

    @Test
    fun piscoAgainstGolden() {
        val dir = TestData.file("golden/section")
        val combos = listOf("interp", "nointerp", "nointerp_unchecked", "interp_unchecked", "seeded_interp", "seeded_nointerp")
        var found = 0
        for (combo in combos) {
            val file = File(dir, "pisco_$combo.json")
            if (!file.exists()) continue
            found += 1
            checkGolden(file, combo)
        }
        assumeTrue("No hay referencias en ci/testdata/golden/section (las genera CI: golden.yml)", found > 0)
    }

    /** Equal within `tol`, NaN only equal to NaN. */
    private fun assertClose(a: Double, e: Double, tol: Double, message: String) {
        if (e.isNaN()) {
            assertTrue("$message: $a, se esperaba NaN", a.isNaN())
        } else {
            assertEquals(message, e, a, tol * max(1.0, abs(e)))
        }
    }

    private fun checkGolden(file: File, combo: String) {
        val doc = JSON.read(file).objectValue!!
        val meta = doc["meta"]?.objectValue!!
        val golden = doc["xsections"]?.objectValue?.get("section1")?.objectValue!!
        val inputs = piscoInputs()
        val bath = inputs.bath
        val m = inputs.matrix
        val p = inputs.piv
        val s = inputs.section
        val unchecked = meta["unchecked"]?.arrayValue?.mapNotNull { it.intValue } ?: emptyList()
        val ns = meta["num_stations"]?.intValue!!
        if (unchecked.isNotEmpty()) {
            s.json["check"] = JSONValue.array((0 until ns).map { JSONValue.bool(!unchecked.contains(it)) })
        }
        val step = meta["step"]?.intValue!!
        val fps = meta["fps"]?.doubleValue!!
        val opts = DischargeOptions(
            frameStep = step,
            fps = fps,
            interpolate = meta["interpolate"]?.boolValue ?: false,
            artificialSeeding = meta["artificial_seeding"]?.boolValue ?: false,
            alpha = meta["alpha"]?.doubleValue, numStations = ns,
        )
        val r = DischargeCalculator.compute(s, bath, p, m, opts)
        val written = s.copy()
        DischargeCalculator.apply(r, written, opts)
        val o = written.json

        // Per-station arrays: identical up to floating-point noise (same triangulation as Qhull on this field).
        // displacement_x/y (pixel-space field, degenerate regular grid) are display-only and not compared.
        val arrays = listOf(
            "east", "north", "distance", "x", "y", "displacement_east", "displacement_north", "streamwise_east",
            "streamwise_north", "crosswise_east", "crosswise_north", "streamwise_velocity_magnitude",
            "streamwise_x", "streamwise_y", "depth", "minus_std", "plus_std", "5th_percentile",
            "95th_percentile", "seeded_vel_profile", "filled_streamwise_velocity_magnitude",
            "filled_seeded_vel_profile", "W", "A", "Q", "Q_minus_std", "Q_plus_std", "Q_portion",
        )
        for (key in arrays) {
            val expected = golden[key]?.doubleArray
            if (expected == null) {
                assertNull("$combo $key no está en la referencia", o[key])
                continue
            }
            val actual = o[key]?.doubleArray
            assertNotNull("$combo $key", actual)
            actual!!
            assertEquals("$combo $key", expected.size, actual.size)
            for ((i, pair) in actual.zip(expected).withIndex()) {
                val (a, e) = pair
                if (e.isNaN()) {
                    assertTrue("$combo $key[$i] = $a, se esperaba NaN", a.isNaN())
                } else {
                    assertEquals("$combo $key[$i]", e, a, 1e-7 * max(1.0, abs(e)))
                }
            }
        }
        val goldenSegments: List<JSONValue> = golden["wet_segments"]?.arrayValue ?: emptyList()
        assertEquals(combo, goldenSegments.size, r.wetSegments.size)
        for ((a, e) in r.wetSegments.zip(goldenSegments)) {
            val ev: List<Double> = e.doubleArray ?: emptyList()
            assertEquals(combo, 2, ev.size)
            if (ev.size == 2) {
                assertEquals(combo, ev[0], a[0], 1e-9)
                assertEquals(combo, ev[1], a[1], 1e-9)
            }
        }

        fun g(k: String): Double {
            val v = golden[k]?.doubleValue
            assertNotNull("$combo $k", v)
            return v!!
        }
        val tol = 1e-7
        // Unchanged totals.
        assertClose(r.totalQ, g("total_Q"), tol, combo)
        assertClose(r.measuredQ, g("measured_Q"), tol, combo)
        assertClose(r.interpolatedQ, g("interpolated_Q"), tol, combo)
        assertClose(r.maxDepth, g("max_depth"), tol, combo)
        assertClose(r.meanVs, g("mean_Vs"), tol, combo)
        // Fixed totals: upstream's value is reproduced, and the fix moves it by the expected amount.
        assertClose(r.upstream.totalQStd, g("total_q_std"), tol, combo)
        assertClose(r.upstream.totalA, g("total_A"), tol, combo)
        assertClose(r.upstream.totalW, g("total_W"), tol, combo)
        assertClose(r.upstream.averageDepth, g("average_depth"), tol, combo)
        assertClose(r.upstream.meanV, g("mean_V"), tol, combo)
        // pisco: both ends of the bathymetry are exactly at the level → same area, +1.5015 m of width (#8).
        assertClose(r.totalA, g("total_A"), tol, combo)
        val upstreamW = g("total_W")
        assertEquals(combo, 1.501547555, r.totalW - upstreamW, 1e-9)
        assertClose(r.meanV, g("mean_V"), tol, combo)
        // #7: the std of exactly the stations in total_Q, never negative.
        var expectedStd = 0.0
        val alpha = meta["alpha"]?.doubleValue!!
        for (i in 0 until ns) {
            if (!r.q[i].isNaN() && (opts.interpolate || r.check[i])) {
                expectedStd += abs(r.a[i] * alpha) * r.stationStd[i]
            }
        }
        assertEquals(combo, expectedStd, r.totalQStd, 1e-12)
        assertTrue(combo, r.totalQStd >= 0)
        if (unchecked.isEmpty() && !opts.artificialSeeding) {
            // All stations checked and measured: the fix agrees with upstream.
            assertEquals(combo, g("total_q_std"), r.totalQStd, 1e-6)
        }
        val summary = doc["xsections"]?.objectValue?.get("summary")?.objectValue?.get("mean")?.objectValue
        if (summary != null) {
            assertEquals(combo, r.totalQ, summary["total_Q"]?.doubleValue!!, tol)
        }
    }
}
