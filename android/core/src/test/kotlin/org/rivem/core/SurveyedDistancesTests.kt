package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.geometry.AveragedPoint
import org.rivem.core.geometry.ObliqueTransform
import org.rivem.core.geometry.Point2
import org.rivem.core.geometry.Point3
import org.rivem.core.geometry.SurveyedDistances
import kotlin.math.abs
import kotlin.math.sqrt

/** RIVEM addition: oblique distances from 3D-surveyed points (twin of `SurveyedDistancesTests.swift`). */
class SurveyedDistancesTests {
    @Test
    fun horizontalDistancesIgnoreHeight() {
        // 10 m × 6 m rectangle on the water plane (y = −2), point 3 surveyed 0.3 m too high.
        val pts = listOf(Point3(0.0, -2.0, 0.0), Point3(10.0, -2.0, 0.0), Point3(10.0, -1.7, 6.0), Point3(0.0, -2.0, 6.0))
        val s = unwrap(SurveyedDistances.from(pts))
        val d = s.distances
        assertEquals(10.0, d.d12, 1e-12)
        assertEquals(6.0, d.d23, 1e-12)
        assertEquals(10.0, d.d34, 1e-12)
        assertEquals(6.0, d.d41, 1e-12)
        assertEquals(sqrt(136.0), d.d13, 1e-12)
        assertEquals(sqrt(136.0), d.d24, 1e-12)
        assertEquals(0.3, s.heightSpread, 1e-12)
        // d23: slant √(36 + 0.09) − 6.
        assertEquals(sqrt(36.09) - 6, s.largestSlantExcess, 1e-12)
    }

    @Test
    fun averagedPointDropsOutliers() {
        // Ten readings ±1 cm around (1, 0, 2) and one 40 cm off (raycast hit the background).
        val r = ArrayList<Point3>()
        for (k in 0 until 10) {
            val e: Double = if (k % 2 == 0) 0.01 else -0.01
            r.add(Point3(1 + e, 0.0, 2 - e))
        }
        r.add(Point3(1.4, 0.0, 2.0))
        val a = unwrap(AveragedPoint.from(r))
        assertEquals(10, a.used)
        assertEquals(11, a.total)
        assertEquals(1.0, a.point.x, 1e-12)
        assertEquals(2.0, a.point.z, 1e-12)
        assertEquals(sqrt(0.0002), a.spread, 1e-12)
        assertNull(AveragedPoint.from(emptyList()))
        assertEquals(0.0, AveragedPoint.from(listOf(Point3(1.0, 2.0, 3.0)))?.spread)
    }

    @Test
    fun needsFourFinitePoints() {
        assertNull(SurveyedDistances.from(listOf(Point3(0.0, 0.0, 0.0), Point3(1.0, 0.0, 0.0), Point3(1.0, 0.0, 1.0))))
        assertNull(SurveyedDistances.from(listOf(Point3(0.0, 0.0, 0.0), Point3(1.0, 0.0, 0.0), Point3(1.0, 0.0, 1.0),
                                                 Point3(0.0, Double.NaN, 1.0))))
    }

    @Test
    fun surveyedDistancesSolveTheObliqueView() {
        // Distances from a survey feed the oblique solution with no residual.
        val pts = listOf(Point3(0.0, 0.0, 0.0), Point3(12.0, 0.0, 0.5), Point3(11.0, 0.05, 8.0), Point3(-1.0, 0.0, 7.5))
        val s = unwrap(SurveyedDistances.from(pts))
        val pixels = listOf(Point2(100.0, 900.0), Point2(1800.0, 880.0), Point2(1500.0, 300.0), Point2(350.0, 320.0))
        val solution = ObliqueTransform.solve(pixels = pixels, distances = s.distances)
        assertTrue((solution.distanceResiduals.map { abs(it) }.maxOrNull() ?: 1.0) < 1e-3)
    }

    /** XCTUnwrap. */
    private fun <T : Any> unwrap(value: T?): T {
        assertNotNull(null, value)
        return value!!
    }
}
