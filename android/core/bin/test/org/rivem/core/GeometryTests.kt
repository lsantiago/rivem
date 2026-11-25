package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.geometry.Homography
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.UAVTransform

/** Twin of `ios/Tests/RiverCoreTests/GeometryTests.swift` (`UAVTransformTests` and `HomographyTests`). */
class GeometryTests {
    private fun assertClose(a: Point2, b: Point2, tol: Double = 1e-6) {
        assertEquals(b.x, a.x, tol)
        assertEquals(b.y, a.y, tol)
    }

    // UAVTransformTests

    /** Regression for the RIVeR 3.8.3 defect: desktop sends this point to (0, -10). */
    @Test
    fun uavNorthPointingSegmentIsNotMirrored() {
        val h = UAVTransform.matrix(pixel1 = Point2(0.0, 0.0), pixel2 = Point2(100.0, 0.0),
                                    world1 = Point2(0.0, 0.0), world2 = Point2(0.0, 10.0))
        assertClose(h.apply(Point2(0.0, 0.0)), Point2(0.0, 0.0))
        assertClose(h.apply(Point2(100.0, 0.0)), Point2(0.0, 10.0))
    }

    @Test
    fun uavArbitraryRotationInUTMCoordinates() {
        val p1 = Point2(120.0, 340.0)
        val p2 = Point2(900.0, 610.0)
        val w1 = Point2(500_100.0, 9_560_200.0)
        val w2 = Point2(500_131.2, 9_560_180.7)
        val h = UAVTransform.matrix(pixel1 = p1, pixel2 = p2, world1 = w1, world2 = w2)
        assertClose(h.apply(p1), w1, tol = 1e-6)
        assertClose(h.apply(p2), w2, tol = 1e-6)
    }

    /**
     * Image y grows downwards, real-world y (North) upwards: a point below p1 in the image must be south of it
     * when the 1→2 segment points East.
     */
    @Test
    fun uavImageDownIsSouthForEastSegment() {
        val h = UAVTransform.matrix(pixel1 = Point2(10.0, 10.0), pixel2 = Point2(110.0, 10.0),
                                    world1 = Point2(5.0, 5.0), world2 = Point2(15.0, 5.0))
        assertTrue(h.apply(Point2(10.0, 60.0)).y < 5)
    }

    /** For an East–West segment the fixed formula equals desktop's matrix: diag(s, -s) plus translation. */
    @Test
    fun uavEastWestSegmentMatchesDesktop() {
        val h = UAVTransform.matrix(pixel1 = Point2(10.0, 10.0), pixel2 = Point2(110.0, 10.0),
                                    world1 = Point2(5.0, 5.0), world2 = Point2(15.0, 5.0))
        val desktop = Matrix3.fromRows(listOf(listOf(0.1, 0.0, 4.0), listOf(0.0, -0.1, 6.0), listOf(0.0, 0.0, 1.0)))
        for ((a, b) in h.m.zip(desktop.m)) assertEquals(b, a, 1e-12)
    }

    @Test
    fun uavPixelSize() {
        val s = UAVTransform.pixelSize(pixel1 = Point2(0.0, 0.0), pixel2 = Point2(300.0, 400.0),
                                       world1 = Point2(0.0, 0.0), world2 = Point2(6.0, 8.0))
        assertEquals(0.02, s, 1e-12)
    }

    @Test
    fun uavCoincidentPointsThrow() {
        assertThrows(RiverCoreError::class.java) {
            UAVTransform.matrix(pixel1 = Point2(1.0, 1.0), pixel2 = Point2(1.0, 1.0),
                                world1 = Point2(0.0, 0.0), world2 = Point2(1.0, 0.0))
        }
    }

    // HomographyTests

    private val truth = Matrix3.fromRows(listOf(listOf(0.021, -0.004, 500_100.0), listOf(0.002, -0.03, 9_560_200.0),
                                                listOf(1e-5, 4e-5, 1.0)))
    private val pixels = listOf(Point2(100.0, 800.0), Point2(1800.0, 820.0), Point2(1500.0, 300.0),
                                Point2(400.0, 280.0), Point2(950.0, 550.0), Point2(1200.0, 700.0))

    @Test
    fun homographyRecoversKnownHomographyFromSixPoints() {
        val world = pixels.map { truth.apply(it) }
        val h = Homography.fit(pixel = pixels, world = world)
        val errors = Homography.reprojectionErrors(matrix = h, pixel = pixels, world = world)
        assertTrue((errors.maxOrNull() ?: Double.POSITIVE_INFINITY) < 1e-3) // < 1 mm in UTM coordinates
    }

    @Test
    fun homographyExactFitWithFourPoints() {
        val four = pixels.take(4)
        val h = Homography.fit(pixel = four, world = four.map { truth.apply(it) })
        val check = pixels.map { h.apply(it) }
        for ((a, b) in check.zip(pixels.map { truth.apply(it) })) assertTrue(a.distanceTo(b) < 1e-3)
    }

    @Test
    fun homographyInverseRoundTrip() {
        val inv = truth.inverse()
        for (p in pixels) {
            val back = inv.apply(truth.apply(p))
            assertEquals(p.x, back.x, 1e-6)
            assertEquals(p.y, back.y, 1e-6)
        }
    }

    @Test
    fun homographyThreeAlignedOfFourThrows() {
        val px = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(20.0, 0.0), Point2(5.0, 9.0))
        assertThrows(RiverCoreError::class.java) { Homography.fit(pixel = px, world = px) }
    }

    @Test
    fun homographyTooFewPointsThrow() {
        val px = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(5.0, 9.0))
        val error = assertThrows(RiverCoreError.NotEnoughPoints::class.java) { Homography.fit(pixel = px, world = px) }
        assertEquals(4, error.needed)
        assertEquals(3, error.got)
    }
}
