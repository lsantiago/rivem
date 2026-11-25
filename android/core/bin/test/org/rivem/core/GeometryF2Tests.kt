package org.rivem.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rivem.core.geometry.CameraCalibration
import org.rivem.core.geometry.CameraMatrix
import org.rivem.core.geometry.GroundPoint3D
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.ObliqueDistances
import org.rivem.core.geometry.ObliqueTransform
import org.rivem.core.geometry.Orthorectifier
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.ROIMask
import org.rivem.core.geometry.ROIMasks
import org.rivem.core.geometry.ROISection
import org.rivem.core.geometry.UAVTransform
import org.rivem.core.imaging.NPY
import org.rivem.core.imaging.NPYError
import org.rivem.core.imaging.PNG
import org.rivem.core.imaging.RGBAImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Twin of `ios/Tests/RiverCoreTests/GeometryF2Tests.swift`: oblique, 3-D camera, UAV golden, ROI masks,
 * orthorectification and NPY/PNG. Each Swift test class is a group of methods with its name as prefix.
 */
class GeometryF2Tests {
    // MARK: - Helpers

    private fun assertClose(a: Point2, b: Point2, tol: Double, message: String = "") {
        assertEquals(message, b.x, a.x, tol)
        assertEquals(message, b.y, a.y, tol)
    }

    /** XCTUnwrap. */
    private fun <T : Any> unwrap(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** Golden references written by tools/golden/geometry.py (CI, RIVeR's Python core). Skips when not generated yet. */
    private object GeometryGolden {
        fun load(name: String): Map<String, Any?> {
            val file = TestData.file("golden/geometry/$name")
            assumeTrue("golden/geometry/$name todavía no existe (lo genera .github/workflows/golden.yml)", file.isFile)
            @Suppress("UNCHECKED_CAST")
            val obj = MiniJSON.parse(file.readText(Charsets.UTF_8)) as? Map<String, Any?>
            assumeTrue("golden/geometry/$name no es un objeto JSON", obj != null)
            return obj!!
        }

        fun num(a: Any?): Double? = (a as? Number)?.toDouble()

        fun nums(a: Any?): List<Double> = (a as? List<*>)?.mapNotNull { num(it) } ?: emptyList()

        fun matrix(a: Any?): List<List<Double>> = (a as? List<*>)?.map { nums(it) } ?: emptyList()

        fun points(a: Any?): List<Point2> = matrix(a).map { Point2(it[0], it[1]) }

        @Suppress("UNCHECKED_CAST")
        fun obj(a: Any?): Map<String, Any?>? = a as? Map<String, Any?>
    }

    /** Minimal JSON reader for the golden files (objects → Map, arrays → List, numbers → Double). */
    private class MiniJSON private constructor(private val s: String) {
        private var i = 0

        companion object {
            fun parse(text: String): Any? {
                val p = MiniJSON(text)
                p.ws()
                val v = p.value()
                p.ws()
                require(p.i == p.s.length) { "trailing characters in JSON" }
                return v
            }
        }

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE" || s[i].isLetter())) i++
                    val text = s.substring(start, i)
                    when (text) {
                        "NaN" -> Double.NaN
                        "Infinity" -> Double.POSITIVE_INFINITY
                        "-Infinity" -> Double.NEGATIVE_INFINITY
                        else -> text.toDoubleOrNull() ?: error("bad JSON number '$text' (char '$c')")
                    }
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                val key = string()
                ws()
                require(s[i] == ':')
                i++
                out[key] = value()
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                require(s[i] == '}')
                i++
                return out
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                require(s[i] == ']')
                i++
                return out
            }
        }

        private fun string(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(s[i]); i++
                }
            }
            i++
            return sb.toString()
        }
    }

    /** Synthetic frame of tools/golden/geometry.py: R = 255·x / (W−1), G = 255·y / (H−1) (integer division), B = 128. */
    private fun syntheticFrame(width: Int = 1920, height: Int = 1080): RGBAImage {
        val img = RGBAImage(width, height)
        for (y in 0 until height) {
            val g = ((255 * y) / (height - 1)).toByte()
            for (x in 0 until width) {
                val o = 4 * (y * width + x)
                img.bytes[o] = ((255 * x) / (width - 1)).toByte()
                img.bytes[o + 1] = g
                img.bytes[o + 2] = 128.toByte()
                img.bytes[o + 3] = 255.toByte()
            }
        }
        return img
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    /**
     * Compares a rendered image with the golden summary: size exact, opaque count within 0.5 %, sampled pixels within
     * ±2 levels where both are opaque (remap's fixed point), and at most 3 alpha disagreements (edge pixels).
     */
    private fun assertMatchesSummary(img: RGBAImage, summary: Map<String, Any?>) {
        val w = (GeometryGolden.num(summary["width"]) ?: -1.0).toInt()
        val h = (GeometryGolden.num(summary["height"]) ?: -1.0).toInt()
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        var opaque = 0
        for (i in 0 until img.width * img.height) if (u8(img.bytes[4 * i + 3]) == 255) opaque += 1
        val expectedOpaque = GeometryGolden.num(summary["opaque_count"]) ?: 0.0
        assertEquals(expectedOpaque, opaque.toDouble(), max(5.0, 0.005 * expectedOpaque))
        var alphaMismatch = 0
        for (s in GeometryGolden.matrix(summary["samples"])) {
            val r = s[0].toInt()
            val c = s[1].toInt()
            val o = 4 * (r * img.width + c)
            val alpha = u8(img.bytes[o + 3]).toDouble()
            if (alpha != s[5]) {
                alphaMismatch += 1
                continue
            }
            if (alpha == 255.0) {
                for (k in 0 until 3) {
                    assertEquals("pixel ($r, $c) canal $k", s[2 + k], u8(img.bytes[o + k]).toDouble(), 2.0)
                }
            }
        }
        assertTrue("alpha mismatches $alphaMismatch", alphaMismatch <= 3)
    }

    // MARK: - Oblique (ObliqueTransformTests)

    private val quad: List<Point2> = listOf(Point2(0.0, 0.0), Point2(20.0, 0.0), Point2(23.0, 15.0), Point2(-2.0, 17.0))

    private fun distances(q: List<Point2>): ObliqueDistances =
        ObliqueDistances(d12 = q[0].distanceTo(q[1]), d23 = q[1].distanceTo(q[2]), d34 = q[2].distanceTo(q[3]),
                         d41 = q[3].distanceTo(q[0]), d13 = q[0].distanceTo(q[2]), d24 = q[1].distanceTo(q[3]))

    private val obliqueTruth = Matrix3.fromRows(listOf(listOf(0.0103, 0.0040, -5.80), listOf(-0.0031, -0.0230, 21.78),
                                                       listOf(5.8e-5, 1.45e-3, -0.348)))

    @Test
    fun obliqueTrilaterationRecoversExactQuadrilateral() {
        val (p3, p4) = ObliqueTransform.trilaterate(distances(quad))
        assertClose(p3, quad[2], tol = 1e-6)
        assertClose(p4, quad[3], tol = 1e-6)
    }

    @Test
    fun obliqueTrilaterationOfAWideShallowQuadrilateral() {
        val q = listOf(Point2(0.0, 0.0), Point2(42.5, 0.0), Point2(47.1, 6.2), Point2(-3.3, 5.8))
        val (p3, p4) = ObliqueTransform.trilaterate(distances(q))
        assertClose(p3, q[2], tol = 1e-6)
        assertClose(p4, q[3], tol = 1e-6)
    }

    @Test
    fun obliqueSolveMapsControlPointsToTheirWorldPositions() {
        val truth = obliqueTruth
        val inv = truth.inverse()
        val pixels = quad.map { inv.apply(it) }
        val sol = ObliqueTransform.solve(pixels = pixels, distances = distances(quad))
        for ((p, w) in pixels.zip(quad)) assertClose(sol.matrix.apply(p), w, tol = 1e-6)
        // Any other pixel too: same homography.
        assertClose(sol.matrix.apply(Point2(800.0, 900.0)), truth.apply(Point2(800.0, 900.0)), tol = 1e-6)
        for (r in sol.distanceResiduals) assertEquals(0.0, r, 1e-6)
        assertEquals(1.0, sol.matrix[2, 2], 1e-15)
        val xs = pixels.map { it.x }
        val ys = pixels.map { it.y }
        val expectedROI = listOf(xs.minOrNull()!!, ys.minOrNull()!!, xs.maxOrNull()!! - xs.minOrNull()!!,
                                 ys.maxOrNull()!! - ys.minOrNull()!!)
        for ((a, b) in sol.roi.zip(expectedROI)) assertEquals(b, a, 1e-12)
    }

    /**
     * PLAN #18: numbering the quadrilateral the other way round (1→2 along a bank) must not mirror the world, or the
     * flow direction flips and Q comes out negative. The solution equals the truth up to a rigid motion.
     */
    @Test
    fun obliqueReversedNumberingDoesNotMirrorTheWorld() {
        val truth = obliqueTruth
        val inv = truth.inverse()
        for (q in listOf(quad, listOf(quad[0], quad[3], quad[2], quad[1]))) {
            val pixels = q.map { inv.apply(it) }
            val sol = ObliqueTransform.solve(pixels = pixels, distances = distances(q))
            for (r in sol.distanceResiduals) assertEquals(0.0, r, 1e-6)
            assertTrue("mundo espejado", ObliqueTransform.signedArea(sol.world) * ObliqueTransform.signedArea(q) > 0)
            // Rigid motion: distances between arbitrary mapped pixels are preserved.
            val a = Point2(400.0, 800.0)
            val b = Point2(1500.0, 600.0)
            assertEquals(truth.apply(a).distanceTo(truth.apply(b)), sol.matrix.apply(a).distanceTo(sol.matrix.apply(b)), 1e-6)
        }
    }

    @Test
    fun obliqueAnchorsRotateAndTranslateTheSolution() {
        val pixels = listOf(Point2(50.0, 930.0), Point2(1800.0, 690.0), Point2(1400.0, 420.0), Point2(450.0, 480.0))
        val d = distances(quad)
        val theta = 0.7
        val a1 = Point2(500_100.0, 9_560_200.0)
        val a2 = Point2(a1.x + d.d12 * cos(theta), a1.y + d.d12 * sin(theta))
        val sol = ObliqueTransform.solve(pixels = pixels, distances = d, anchors = Pair(a1, a2))
        assertClose(sol.world[0], a1, tol = 1e-9)
        assertClose(sol.world[1], a2, tol = 1e-9)
        // Double precision kept with UTM anchors (desktop's float32 would be off by decimetres).
        assertEquals(d.d13, sol.world[2].distanceTo(a1), 1e-6)
        assertEquals(d.d24, sol.world[3].distanceTo(a2), 1e-6)
        for ((p, w) in pixels.zip(sol.world)) assertClose(sol.matrix.apply(p), w, tol = 1e-5)
    }

    @Test
    fun obliqueAnchorDistanceMismatchThrowsWhenEnforced() {
        val pixels = listOf(Point2(50.0, 930.0), Point2(1800.0, 690.0), Point2(1400.0, 420.0), Point2(450.0, 480.0))
        val d = distances(quad)
        val anchors = Pair(Point2(0.0, 0.0), Point2(d.d12 + 2, 0.0))
        assertThrows(RiverCoreError::class.java) { ObliqueTransform.solve(pixels = pixels, distances = d, anchors = anchors) }
        ObliqueTransform.solve(pixels = pixels, distances = d, anchors = anchors, enforceD12 = false)
    }

    @Test
    fun obliqueROIPadding() {
        val roi = ObliqueTransform.roi(pixels = listOf(Point2(10.0, 20.0), Point2(110.0, 20.0), Point2(110.0, 70.0),
                                                       Point2(10.0, 70.0)), padding = 0.1)
        assertEquals(listOf(0.0, 15.0, 120.0, 60.0), roi)
    }

    @Test
    fun obliqueGoldenPisco() {
        val g = GeometryGolden.load("oblique_pisco.json")
        val pixels = GeometryGolden.points(g["pixels"])
        val d = unwrap(ObliqueDistances(GeometryGolden.nums(g["distances"])))
        val sol = ObliqueTransform.solve(pixels = pixels, distances = d)

        val opt = GeometryGolden.nums(g["optimized_p3_p4"])
        assertClose(sol.world[2], Point2(opt[0], opt[1]), tol = 1e-3, message = "P3")
        assertClose(sol.world[3], Point2(opt[2], opt[3]), tol = 1e-3, message = "P4")

        val desktop = Matrix3.fromRows(GeometryGolden.matrix(g["matrix"]))
        val roi = GeometryGolden.nums(g["roi"])
        for ((a, b) in sol.roi.zip(roi)) assertEquals(b, a, 1e-4)
        val probes = pixels + listOf(Point2(roi[0], roi[1]), Point2(roi[0] + roi[2], roi[1] + roi[3]), Point2(960.0, 700.0))
        for (p in probes) assertClose(sol.matrix.apply(p), desktop.apply(p), tol = 2e-3, message = "pixel $p")

        // Output grid and transformed image, from desktop's own matrix so integer sizes are comparable.
        val grid = Orthorectifier.obliqueGrid(matrix = desktop, roi = roi)
        val extent = GeometryGolden.nums(g["extent"])
        for ((a, b) in grid.extent.zip(extent)) assertEquals(b, a, 1e-6)
        assertEquals(GeometryGolden.num(g["output_resolution"]) ?: 0.0, grid.resolution, 1e-9)
        val img = Orthorectifier.render(syntheticFrame(), grid = grid, matrix = desktop)
        assertMatchesSummary(img, unwrap(GeometryGolden.obj(g["transformed_image"])))

        val anchored = unwrap(GeometryGolden.obj(g["anchored"]))
        val an = GeometryGolden.nums(anchored["anchors"])
        val solA = ObliqueTransform.solve(pixels = pixels, distances = d,
                                          anchors = Pair(Point2(an[0], an[1]), Point2(an[2], an[3])),
                                          enforceD12 = true, distanceTolerance = 1.0)
        val desktopA = Matrix3.fromRows(GeometryGolden.matrix(anchored["matrix"]))
        for (p in probes) assertClose(solA.matrix.apply(p), desktopA.apply(p), tol = 5e-3, message = "anclado $p")
    }

    // MARK: - 3-D camera (CameraCalibrationTests)

    /** K [R | −R C] for a camera at `center` looking roughly north and down, in UTM-sized coordinates. */
    private fun syntheticCamera(center: List<Double>): CameraMatrix {
        val f = 1500.0
        val cx = 960.0
        val cy = 540.0
        // Camera axes in world coordinates: x right (East-ish), y down, z forward (North and down).
        val pitch = 0.2
        val yaw = 0.2
        val fwd = listOf(sin(yaw) * cos(pitch), cos(yaw) * cos(pitch), -sin(pitch))
        val right = listOf(cos(yaw), -sin(yaw), 0.0)
        // down = fwd × right
        val down = listOf(fwd[1] * right[2] - fwd[2] * right[1], fwd[2] * right[0] - fwd[0] * right[2],
                          fwd[0] * right[1] - fwd[1] * right[0])
        val r = listOf(right, down, fwd)
        val p = ArrayList<Double>()
        val k = listOf(listOf(f, 0.0, cx), listOf(0.0, f, cy), listOf(0.0, 0.0, 1.0))
        for (i in 0 until 3) {
            val row = DoubleArray(4)
            for (j in 0 until 3) {
                var kr = 0.0
                for (m in 0 until 3) kr += k[i][m] * r[m][j]
                row[j] = kr
            }
            row[3] = -(row[0] * center[0] + row[1] * center[1] + row[2] * center[2])
            p += row.toList()
        }
        return CameraMatrix(p)
    }

    private val center: List<Double> = listOf(4_355_880.0, 6_520_240.0, 660.0)

    private fun groundPoints(cam: CameraMatrix): List<GroundPoint3D> {
        val pts = ArrayList<GroundPoint3D>()
        val offsets = listOf(listOf(-12.0, 30.0, -11.0), listOf(10.0, 35.0, -12.0), listOf(15.0, 60.0, -10.5),
                             listOf(-8.0, 70.0, -11.8), listOf(0.0, 45.0, -12.5), listOf(20.0, 80.0, -9.0),
                             listOf(-15.0, 55.0, -10.0), listOf(5.0, 25.0, -12.2), listOf(-3.0, 90.0, -11.0))
        for (o in offsets) {
            val X = center[0] + o[0]
            val Y = center[1] + o[1]
            val Z = center[2] + o[2]
            val px = cam.project(X, Y, Z)
            pts.add(GroundPoint3D(X = X, Y = Y, Z = Z, x = px.x, y = px.y))
        }
        return pts
    }

    @Test
    fun cameraRecoversKnownCameraFromProjectedPoints() {
        val truth = syntheticCamera(center)
        val pts = groundPoints(truth)
        for (p in pts) {
            assertTrue("${p.pixel}", p.pixel.x > 0 && p.pixel.x < 1920 && p.pixel.y > 0 && p.pixel.y < 1080)
        }
        val sol = CameraCalibration.solve(points = pts)
        assertTrue(sol.meanError < 1e-4)
        assertTrue((sol.reprojectionErrors.maxOrNull() ?: 1.0) < 1e-3)
        for (k in 0 until 3) assertEquals(center[k], sol.cameraPosition[k], 1e-3)
        // Same projective camera as the truth: any other world point projects to the same pixel.
        val probe = truth.project(center[0] + 7, center[1] + 50, center[2] - 11)
        assertClose(sol.camera.project(center[0] + 7, center[1] + 50, center[2] - 11), probe, tol = 1e-3)
        assertEquals(pts.size, sol.uncertaintyEllipses.size)
    }

    @Test
    fun cameraLevelMatrixSendsPixelsBackToTheWaterPlane() {
        val cam = syntheticCamera(center)
        val z = 648.3
        val m = cam.pixelToWorld(z = z)
        for ((dx, dy) in listOf(-10.0 to 40.0, 12.0 to 70.0, 0.0 to 30.0)) {
            val X = center[0] + dx
            val Y = center[1] + dy
            val px = cam.project(X, Y, z)
            assertClose(m.apply(px), Point2(X, Y), tol = 1e-4)
        }
    }

    @Test
    fun cameraTooFewPointsThrow() {
        val cam = syntheticCamera(center)
        val pts = groundPoints(cam).take(5)
        val error = assertThrows(RiverCoreError.NotEnoughPoints::class.java) {
            CameraCalibration.solveCameraMatrix(points = pts)
        }
        assertEquals(6, error.needed)
        assertEquals(5, error.got)
    }

    @Test
    fun cameraEllipsesOfIdenticalErrorsAreDegenerate() {
        val actual = (0 until 5).map { Point2(it.toDouble(), 0.0) }
        val projected = actual.map { Point2(it.x + 1, it.y + 1) }
        for (e in CameraCalibration.uncertaintyEllipses(actual = actual, projected = projected)) {
            assertEquals(0.0, e.width, 1e-9)
            assertEquals(0.0, e.height, 1e-9)
        }
    }

    @Test
    fun cameraGoldenFullGRP3D() {
        val g = GeometryGolden.load("camera_full_grp_3d.json")
        val lists = unwrap(GeometryGolden.obj(g["points"]))
        val pts = GroundPoint3D.fromLists(X = GeometryGolden.nums(lists["X"]), Y = GeometryGolden.nums(lists["Y"]),
                                          Z = GeometryGolden.nums(lists["Z"]), x = GeometryGolden.nums(lists["x"]),
                                          y = GeometryGolden.nums(lists["y"]))
        val sol = CameraCalibration.solve(points = pts)

        // Desktop's raw-coordinate DLT is ill-conditioned (see CameraCalibration): agreement within that noise.
        assertEquals(GeometryGolden.num(g["error"]) ?: 0.0, sol.meanError, 0.1)
        val errs = GeometryGolden.nums(g["reprojection_errors"])
        assertEquals(sol.reprojectionErrors.size, errs.size)
        for ((a, b) in sol.reprojectionErrors.zip(errs)) assertEquals(b, a, 0.5)
        val pos = GeometryGolden.nums(g["camera_position"])
        for (k in 0 until 3) assertEquals(pos[k], sol.cameraPosition[k], 0.05)

        val z = GeometryGolden.num(g["z_level"]) ?: 0.0
        val probes = GeometryGolden.points(g["probe_pixels"])
        val probeWorld = GeometryGolden.points(g["probe_world"])
        val ours = sol.camera.pixelToWorld(z = z)
        for ((p, w) in probes.zip(probeWorld)) assertClose(ours.apply(p), w, tol = 0.05, message = "nivel $p")

        // With desktop's own P the level matrix formula must match exactly.
        val desktopP = CameraMatrix.fromRows(GeometryGolden.matrix(g["camera_matrix"]))
        val desktopM = Matrix3.fromRows(GeometryGolden.matrix(g["level_matrix"]))
        val fromDesktopP = desktopP.pixelToWorld(z = z)
        for ((p, w) in probes.zip(probeWorld)) {
            assertClose(fromDesktopP.apply(p), w, tol = 1e-6)
            assertClose(desktopM.apply(p), w, tol = 1e-6)
        }

        // Ellipses from desktop's projected points.
        val projected = GeometryGolden.points(g["projected_points"])
        val ellipses = CameraCalibration.uncertaintyEllipses(actual = pts.map { it.pixel }, projected = projected)
        val expected = unwrap(g["uncertainty_ellipses"] as? List<*>).map { unwrap(GeometryGolden.obj(it)) }
        assertEquals(expected.size, ellipses.size)
        for ((e, x) in ellipses.zip(expected)) {
            assertEquals(GeometryGolden.num(x["width"]) ?: -1.0, e.width, 1e-6)
            assertEquals(GeometryGolden.num(x["height"]) ?: -1.0, e.height, 1e-6)
            var dAngle = (e.angle - (GeometryGolden.num(x["angle"]) ?: 0.0)) % 180 // truncatingRemainder
            if (dAngle < 0) dAngle += 180
            assertTrue("ángulo ${e.angle}", dAngle < 1e-4 || dAngle > 180 - 1e-4)
        }

        val ortho = unwrap(GeometryGolden.obj(g["ortho"]))
        val (grid, _) = Orthorectifier.cameraGrid(points = pts, resolution = GeometryGolden.num(ortho["resolution"]) ?: 0.1)
        for ((a, b) in grid.extent.zip(GeometryGolden.nums(ortho["extent"]))) assertEquals(b, a, 1e-6)
        assertEquals((GeometryGolden.num(ortho["width"]) ?: -1.0).toInt(), grid.width)
        assertEquals((GeometryGolden.num(ortho["height"]) ?: -1.0).toInt(), grid.height)
    }

    // MARK: - UAV (golden only; the analytic tests live in GeometryTests.kt) (UAVGoldenTests)

    @Test
    fun uavGolden() {
        val g = GeometryGolden.load("uav.json")
        val ew = unwrap(GeometryGolden.obj(g["east_west"]))
        val pix = GeometryGolden.nums(ew["pix"])
        val rw = GeometryGolden.nums(ew["rw"])
        val m = UAVTransform.matrix(pixel1 = Point2(pix[0], pix[1]), pixel2 = Point2(pix[2], pix[3]),
                                    world1 = Point2(rw[0], rw[1]), world2 = Point2(rw[2], rw[3]))
        val desktop = Matrix3.fromRows(GeometryGolden.matrix(ew["matrix"]))
        for ((a, b) in m.m.zip(desktop.m)) assertEquals(b, a, 1e-9)
        val grid = Orthorectifier.uavGrid(matrix = desktop, imageWidth = 1920, imageHeight = 1080)
        for ((a, b) in grid.extent.zip(GeometryGolden.nums(ew["extent"]))) assertEquals(b, a, 1e-6)
        assertEquals(GeometryGolden.num(ew["output_resolution"]) ?: 0.0, grid.resolution, 1e-9)
        val img = Orthorectifier.render(syntheticFrame(), grid = grid, matrix = desktop)
        assertMatchesSummary(img, unwrap(GeometryGolden.obj(ew["transformed_image"])))

        // Documents desktop defect #1: for a North–South segment desktop sends point 2 elsewhere; RIVEM does not.
        val ns = unwrap(GeometryGolden.obj(g["north_south"]))
        val npix = GeometryGolden.nums(ns["pix"])
        val nrw = GeometryGolden.nums(ns["rw"])
        val desktopP2 = GeometryGolden.nums(ns["desktop_point2_world"])
        assertTrue(Point2(desktopP2[0], desktopP2[1]).distanceTo(Point2(nrw[2], nrw[3])) > 1)
        val fixed = UAVTransform.matrix(pixel1 = Point2(npix[0], npix[1]), pixel2 = Point2(npix[2], npix[3]),
                                        world1 = Point2(nrw[0], nrw[1]), world2 = Point2(nrw[2], nrw[3]))
        assertClose(fixed.apply(Point2(npix[2], npix[3])), Point2(nrw[2], nrw[3]), tol = 1e-9)
    }

    // MARK: - ROI masks (ROIMaskTests)

    /** Pixel → world with world = (x, 100 − y): one metre per pixel, north up. */
    private val flipMatrix = Matrix3.fromRows(listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, -1.0, 100.0), listOf(0.0, 0.0, 1.0)))

    @Test
    fun roiHorizontalSectionGivesTheExpectedRectangle() {
        val section = ROISection(left = Point2(10.0, 50.0), right = Point2(60.0, 50.0))
        val (mask, bbox) = ROIMasks.sectionMask(width = 100, height = 80, sections = listOf(section), matrix = flipMatrix,
                                                heightROI = 10.0)
        // World box x ∈ [10, 60], y ∈ [45, 55] → pixel columns 10…60, rows 45…55, outline included (cv2.fillPoly).
        assertEquals(51 * 11, mask.count)
        assertEquals(listOf(10, 45, 60, 55), mask.nonZeroBounds)
        assertEquals(1, mask[10, 45])
        assertEquals(1, mask[60, 55])
        assertEquals(0, mask[9, 50])
        assertEquals(0, mask[30, 56])
        assertEquals(listOf(10.0, 45.0, 50.0, 10.0), bbox)
    }

    @Test
    fun roiBoundingBoxIsClippedToTheFrame() {
        val section = ROISection(left = Point2(-20.0, 50.0), right = Point2(150.0, 50.0))
        val (mask, bbox) = ROIMasks.sectionMask(width = 100, height = 80, sections = listOf(section), matrix = flipMatrix,
                                                heightROI = 10.0)
        assertEquals(listOf(0.0, 45.0, 99.0, 10.0), bbox)
        assertEquals(100 * 11, mask.count)
    }

    @Test
    fun roiIslandBetweenWetSegmentsIsExcluded() {
        // Wet 0–10 m and 20–50 m along a 50 m section: the 10–20 m stretch is dry.
        val section = ROISection(left = Point2(10.0, 50.0), right = Point2(60.0, 50.0),
                                 wetSegments = listOf(0.0..10.0, 20.0..50.0))
        val (mask, _) = ROIMasks.sectionMask(width = 100, height = 80, sections = listOf(section), matrix = flipMatrix,
                                             heightROI = 10.0)
        assertEquals(1, mask[15, 50])
        assertEquals(0, mask[25, 50])
        assertEquals(1, mask[40, 50])
    }

    @Test
    fun roiUserPolygonIsZeroInsideAndCompileIsAnd() {
        val user = ROIMasks.userPolygonMask(width = 100, height = 80,
                                            points = listOf(Point2(20.0, 40.0), Point2(39.6, 40.0), Point2(40.0, 60.4),
                                                            Point2(20.0, 60.0)))
        assertEquals(100 * 80 - 21 * 21, user.count)
        assertEquals(0, user[30, 50])
        assertEquals(1, user[50, 50])
        val section = ROISection(left = Point2(10.0, 50.0), right = Point2(60.0, 50.0))
        val roi = ROIMasks.sectionMask(width = 100, height = 80, sections = listOf(section), matrix = flipMatrix,
                                       heightROI = 10.0).mask
        val finalMask = ROIMasks.compile(roi = roi, userMasks = listOf(user))
        assertEquals(51 * 11 - 21 * 11, finalMask.count)
        assertEquals(0, finalMask[30, 50])
        assertEquals(1, finalMask[50, 50])
        assertThrows(RiverCoreError::class.java) {
            ROIMasks.userPolygonMask(width = 10, height = 10, points = listOf(Point2(1.0, 1.0), Point2(2.0, 2.0)))
        }
    }

    @Test
    fun roiRecommendedHeight() {
        // 0.01 m per pixel: a 192 px short side (1.5 × 128) is 1.92 m → rounded up to 2.
        val m = Matrix3.fromRows(listOf(listOf(0.01, 0.0, 0.0), listOf(0.0, -0.01, 10.0), listOf(0.0, 0.0, 1.0)))
        val s = ROISection(left = Point2(1.0, 5.0), right = Point2(11.0, 5.0))
        assertEquals(2.0, ROIMasks.recommendHeightROI(sections = listOf(s), matrix = m, windowSize = 128), 0.0)
    }

    @Test
    fun roiOverlayPNGColours() {
        val mask = ROIMask(2, 1, byteArrayOf(0, 1))
        assertArrayEquals(byteArrayOf(0, 0, 0, 111, 0, 0, 0, 0), mask.overlayImage().bytes)
    }

    @Test
    fun roiBoundedBrentFindsParabolaMinimum() {
        val r = ROIMasks.boundedBrent({ (it - 2.345) * (it - 2.345) }, lower = 0.1, upper = 10.0)
        assertTrue(r.converged)
        assertEquals(2.345, r.x, 1e-4)
    }

    private fun assertMask(mask: ROIMask, summary: Map<String, Any?>, exact: Boolean) {
        val count = GeometryGolden.num(summary["count"]) ?: -1.0
        if (exact) {
            assertEquals(count, mask.count.toDouble(), 0.0)
        } else {
            assertEquals(count, mask.count.toDouble(), max(10.0, 0.002 * count))
        }
        val bounds = GeometryGolden.nums(summary["bounds"])
        val ours = mask.nonZeroBounds
        if (ours != null && bounds.size == 4) {
            for (k in 0 until 4) assertEquals(bounds[k], ours[k].toDouble(), if (exact) 0.0 else 1.0)
        } else {
            assertTrue(bounds.isEmpty() && mask.nonZeroBounds == null)
        }
        val rows = mask.rowCounts
        for (rc in GeometryGolden.matrix(summary["row_counts"])) {
            assertEquals("fila ${rc[0].toInt()}", rc[1], rows[rc[0].toInt()].toDouble(), if (exact) 0.0 else 2.0)
        }
    }

    @Test
    fun roiGoldenPisco() {
        val g = GeometryGolden.load("roi_pisco.json")
        val matrix = Matrix3.fromRows(GeometryGolden.matrix(g["matrix"]))
        val s = unwrap(GeometryGolden.obj(g["section"]))
        val section = ROISection(left = Point2(GeometryGolden.num(s["east_l"]) ?: 0.0, GeometryGolden.num(s["north_l"]) ?: 0.0),
                                 right = Point2(GeometryGolden.num(s["east_r"]) ?: 0.0, GeometryGolden.num(s["north_r"]) ?: 0.0),
                                 realWorldLength = GeometryGolden.num(s["rw_length"]))
        val height = GeometryGolden.num(g["height_roi"]) ?: 0.0
        val (mask, bbox) = ROIMasks.sectionMask(width = 1920, height = 1080, sections = listOf(section), matrix = matrix,
                                                heightROI = height)
        for ((a, b) in bbox.zip(GeometryGolden.nums(g["bbox"]))) assertEquals(b, a, 1e-6)
        // Inside the frame the rasteriser reproduces cv2.fillPoly; leave a margin for boxes clipped by the frame.
        assertMask(mask, unwrap(GeometryGolden.obj(g["mask"])), exact = false)

        val recommended = ROIMasks.recommendHeightROI(sections = listOf(section), matrix = matrix,
                                                      windowSize = (GeometryGolden.num(g["window_size"]) ?: 128.0).toInt())
        assertEquals(GeometryGolden.num(g["recommended_height_roi"]) ?: -1.0, recommended, 0.0)

        val user = ROIMasks.userPolygonMask(width = 1920, height = 1080, points = GeometryGolden.points(g["user_polygon"]))
        assertMask(user, unwrap(GeometryGolden.obj(g["user_mask"])), exact = true)
        assertMask(ROIMasks.compile(roi = mask, userMasks = listOf(user)), unwrap(GeometryGolden.obj(g["final_mask"])),
                   exact = false)
    }

    // MARK: - Orthorectification (OrthorectifyTests)

    private val uav = Matrix3.fromRows(listOf(listOf(0.1, 0.0, 0.0), listOf(0.0, -0.1, 5.0), listOf(0.0, 0.0, 1.0))) // 100×50 px frame → 10 m × 5 m

    @Test
    fun orthoSizeLimitedGridLikeDesktop() {
        val grid = Orthorectifier.uavGrid(matrix = uav, imageWidth = 100, imageHeight = 50)
        assertEquals(-0.5, grid.extent[0], 1e-12)
        assertEquals(10.5, grid.extent[1], 1e-12)
        assertEquals(-0.25, grid.extent[2], 1e-12)
        assertEquals(5.25, grid.extent[3], 1e-12)
        // resolution = 11 m / 500 = 0.022 m/px
        assertTrue(grid.width <= 500)
        assertTrue(grid.width >= 499)
        assertEquals(250, grid.height)
        assertTrue(grid.flipY)
        // North up: row 0 is the northern edge.
        assertEquals(5.25, grid.world(column = 0, row = 0).y, 1e-12)
        assertEquals(-0.25, grid.world(column = 0, row = grid.height - 1).y, 1e-12)
    }

    @Test
    fun orthoMinimumResolutionCapsTheSize() {
        val grid = Orthorectifier.uavGrid(matrix = uav, imageWidth = 100, imageHeight = 50, minResolution = 0.1)
        assertEquals(0.1, grid.resolution, 0.002)
        assertEquals(110, grid.width)
    }

    @Test
    fun orthoRenderCopiesColoursAndMarksOutsideTransparent() {
        val src = RGBAImage(100, 50)
        for (i in 0 until 100 * 50) {
            src.bytes[4 * i] = 200.toByte()
            src.bytes[4 * i + 1] = 100
            src.bytes[4 * i + 2] = 50
            src.bytes[4 * i + 3] = 255.toByte()
        }
        val grid = Orthorectifier.uavGrid(matrix = uav, imageWidth = 100, imageHeight = 50, minResolution = 0.1)
        val out = Orthorectifier.render(src, grid = grid, matrix = uav)
        val mid = 4 * ((grid.height / 2) * grid.width + grid.width / 2)
        assertEquals(listOf(200, 100, 50, 255), out.bytes.copyOfRange(mid, mid + 4).map { u8(it) })
        assertEquals(0, u8(out.bytes[3])) // corner lies in the 5 % margin, outside the frame
    }

    // MARK: - NPY and PNG (NPYPNGTests)

    private fun bytesOf(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun npyHeaderMatchesNumPy() {
        val values = bytesOf(0, 1, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1)
        val data = NPY.encodeUInt8(width = 5, height = 3, values = values)
        val bytes = data
        assertArrayEquals(bytesOf(0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59, 1, 0), bytes.copyOfRange(0, 8))
        val headerLength = u8(bytes[8]) or (u8(bytes[9]) shl 8)
        assertEquals(118, headerLength) // what numpy 1.26 writes for a (3, 5) uint8 array
        assertEquals(0, (10 + headerLength) % 64)
        val header = String(bytes, 10, headerLength, Charsets.UTF_8)
        assertTrue(header.startsWith("{'descr': '|u1', 'fortran_order': False, 'shape': (3, 5), }"))
        assertTrue(header.endsWith(" \n"))
        assertEquals(128 + 15, bytes.size)
        assertArrayEquals(values, bytes.copyOfRange(128, bytes.size))
        val back = NPY.decodeUInt8(data)
        assertEquals(5, back.width)
        assertEquals(3, back.height)
        assertArrayEquals(values, back.values)
    }

    @Test
    fun npyRejectsOtherDTypes() {
        var bytes = bytesOf(0x93, 0x4E, 0x55, 0x4D, 0x50, 0x59, 1, 0)
        var header = "{'descr': '<f8', 'fortran_order': False, 'shape': (1, 1), }"
        header += " ".repeat(64 - (10 + header.toByteArray().size + 1) % 64) + "\n"
        val n = header.toByteArray().size
        bytes += bytesOf(n and 0xFF, n shr 8)
        bytes += header.toByteArray() + ByteArray(8)
        val error = assertThrows(NPYError::class.java) { NPY.decodeUInt8(bytes) }
        assertEquals(NPYError.UnsupportedDType("<f8"), error)
    }

    @Test
    fun npyGoldenBytes() {
        val g = GeometryGolden.load("npy_small.json")
        val values = GeometryGolden.nums(g["values"]).map { it.toInt().toByte() }.toByteArray()
        val data = NPY.encodeUInt8(width = (GeometryGolden.num(g["width"]) ?: 0.0).toInt(),
                                   height = (GeometryGolden.num(g["height"]) ?: 0.0).toInt(), values = values)
        val hex = data.joinToString("") { String.format("%02x", u8(it)) }
        assertEquals(g["hex"] as? String, hex)
    }

    @Test
    fun pngChecksums() {
        assertEquals(0xCBF4_3926u, PNG.crc32("123456789".toByteArray()))
        assertEquals(0xAE42_6082u, PNG.crc32("IEND".toByteArray()))
        assertEquals(0x11E6_0398u, PNG.adler32("Wikipedia".toByteArray()))
    }

    private fun be32(b: ByteArray, i: Int): UInt =
        (u8(b[i]).toUInt() shl 24) or (u8(b[i + 1]).toUInt() shl 16) or (u8(b[i + 2]).toUInt() shl 8) or u8(b[i + 3]).toUInt()

    @Test
    fun pngStructureAndStoredDeflate() {
        // 200×100 RGBA = 80 100 raw bytes with filter bytes → two stored blocks.
        val img = RGBAImage(200, 100)
        for (i in img.bytes.indices) img.bytes[i] = (i % 251).toByte()
        val png = PNG.encode(img)
        assertArrayEquals(PNG.signature, png.copyOfRange(0, 8))

        // Walk the chunks, checking each CRC.
        var pos = 8
        val chunks = ArrayList<Pair<String, ByteArray>>()
        while (pos < png.size) {
            val len = be32(png, pos).toInt()
            val type = String(png, pos + 4, 4, Charsets.UTF_8)
            val body = png.copyOfRange(pos + 8, pos + 8 + len)
            assertEquals(type, PNG.crc32(png.copyOfRange(pos + 4, pos + 8 + len)), be32(png, pos + 8 + len))
            chunks.add(Pair(type, body))
            pos += 12 + len
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.first })
        val ihdr = chunks[0].second
        assertEquals(13, ihdr.size)
        assertEquals(200u, be32(ihdr, 0))
        assertEquals(100u, be32(ihdr, 4))
        assertArrayEquals(bytesOf(8, 6, 0, 0, 0), ihdr.copyOfRange(8, 13))

        // Inflate the stored blocks by hand.
        val z = chunks[1].second
        assertArrayEquals(bytesOf(0x78, 0x01), z.copyOfRange(0, 2))
        assertEquals(0, (u8(z[0]) * 256 + u8(z[1])) % 31)
        val raw = java.io.ByteArrayOutputStream()
        var p = 2
        var blocks = 0
        while (true) {
            val header = u8(z[p])
            val len = u8(z[p + 1]) or (u8(z[p + 2]) shl 8)
            val nlen = u8(z[p + 3]) or (u8(z[p + 4]) shl 8)
            assertEquals(nlen, len xor 0xFFFF)
            assertEquals(0, header and 0x06) // BTYPE 00
            raw.write(z, p + 5, len)
            p += 5 + len
            blocks += 1
            if (header and 1 == 1) break
        }
        val rawBytes = raw.toByteArray()
        assertEquals(2, blocks)
        assertEquals(PNG.adler32(rawBytes), be32(z, p))
        assertEquals(z.size, p + 4)
        assertEquals(100 * (1 + 800), rawBytes.size)
        for (y in 0 until 100) {
            assertEquals(0, u8(rawBytes[y * 801]))
            assertArrayEquals(img.bytes.copyOfRange(y * 800, (y + 1) * 800), rawBytes.copyOfRange(y * 801 + 1, (y + 1) * 801))
        }
    }

    @Test
    fun pngMaskRoundTripsThroughOverlay() {
        val mask = ROIMask(3, 2, bytesOf(1, 0, 1, 0, 0, 1))
        val png = PNG.encode(mask.overlayImage())
        assertArrayEquals(PNG.signature, png.copyOfRange(0, 8))
        assertTrue(png.size > 8 + 25 + 12 + 12 + 6 * 4)
    }
}
