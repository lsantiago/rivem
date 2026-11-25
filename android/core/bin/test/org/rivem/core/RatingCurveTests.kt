package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.section.RatingCurve
import org.rivem.core.section.RatingPoint
import kotlin.math.pow

/** RIVEM addition: stage–discharge curve (`section/RatingCurve.kt`). Swift `RatingCurveTests`. */
class RatingCurveTests {
    private fun exact(levels: List<Double>, a: Double = 20.0, b: Double = 1.6, h0: Double = 1.0): List<RatingPoint> =
        levels.map { RatingPoint(level = it, discharge = a * (it - h0).pow(b)) }

    @Test
    fun recoversExactCurve() {
        val c = RatingCurve.fit(exact(listOf(1.4, 1.6, 1.76, 2.0, 2.3, 2.8)))!!
        assertEquals(1.0, c.h0, 0.01)
        assertEquals(1.6, c.b, 0.02)
        assertEquals(20.0, c.a, 0.3)
        assertEquals(1.0, c.r2, 1e-6)
        assertTrue(c.relativeRMS < 1e-3)
        assertEquals(20 * 1.5.pow(1.6), c.discharge(at = 2.5), 0.05)
        assertEquals(2.1, c.level(discharge = c.discharge(at = 2.1)), 1e-9)
        assertEquals(0.0, c.discharge(at = 0.5), 0.0)
    }

    @Test
    fun noisyGaugingsStayClose() {
        val noise = listOf(0.04, -0.03, 0.02, -0.05, 0.03, -0.01, 0.02, -0.02)
        val levels = listOf(1.3, 1.45, 1.6, 1.8, 2.0, 2.2, 2.5, 2.9)
        val pts = exact(levels)
        for (i in pts.indices) pts[i].discharge *= 1 + noise[i]
        val c = RatingCurve.fit(pts)!!
        assertTrue(c.r2 > 0.98)
        assertTrue(c.relativeRMS < 0.05)
        assertEquals(20.0, c.discharge(at = 2.0), 1.5)
        assertTrue(c.h0 < 1.3)
    }

    @Test
    fun needsThreeGaugingsAtDistinctLevels() {
        assertNull(RatingCurve.fit(exact(listOf(1.5, 2.0))))
        assertNull(
            RatingCurve.fit(
                listOf(
                    RatingPoint(level = 1.5, discharge = 3.0), RatingPoint(level = 1.5, discharge = 3.2),
                    RatingPoint(level = 1.5, discharge = 2.9),
                ),
            ),
        )
        assertNull(RatingCurve.fit(exact(listOf(1.5, 2.0)) + listOf(RatingPoint(level = 1.7, discharge = Double.NaN))))
        assertNotNull(RatingCurve.fit(exact(listOf(1.5, 1.7, 2.0))))
    }
}
