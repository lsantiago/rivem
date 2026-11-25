package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.geometry.Matrix3
import org.rivem.core.geometry.Point2

/** Base types of the port (Swift `Matrix3.swift`); also checks that the shared fixtures are reachable. */
class Matrix3Tests {
    @Test
    fun inverseRoundTrip() {
        val m = Matrix3.rowMajor(0.01, 0.002, 500_000.0, -0.001, -0.01, 9_000_000.0, 1e-6, 2e-6, 1.0)
        val p = Point2(812.0, 433.0)
        val back = m.inverse().apply(m.apply(p))
        assertEquals(p.x, back.x, 1e-6)
        assertEquals(p.y, back.y, 1e-6)
        val id = m * m.inverse()
        for (i in 0 until 3) for (j in 0 until 3) assertEquals(if (i == j) 1.0 else 0.0, id[i, j], 1e-6)
    }

    @Test(expected = RiverCoreError.SingularMatrix::class)
    fun singularThrows() {
        Matrix3.rowMajor(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0).inverse()
    }

    @Test
    fun fixturesAreReachable() {
        assertTrue(TestData.file("river-examples").isDirectory)
    }
}
