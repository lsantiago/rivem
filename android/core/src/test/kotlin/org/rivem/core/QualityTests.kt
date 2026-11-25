package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rivem.core.section.DischargeUncertainty
import org.rivem.core.section.DoubtReason
import org.rivem.core.section.DoubtfulStation
import org.rivem.core.section.GaugingQuality
import org.rivem.core.section.GaugingStation
import org.rivem.core.section.GeoreferenceFit
import org.rivem.core.section.QualityCriterion
import org.rivem.core.section.QualityLevel
import org.rivem.core.section.UncertaintySource
import org.rivem.core.section.UncertaintySources
import kotlin.math.sqrt

/** RIVEM addition: discharge uncertainty (Monte Carlo) and gauging quality (`section/GaugingQuality.kt`). Swift `QualityTests`. */
class QualityTests {
    /** Ten wet stations of 1 m depth, 2 m² and 1 m/s (α = 0.85 → 1.7 m³/s each), between two dry banks. */
    private fun channel(std: Double = 0.1): MutableList<GaugingStation> {
        val out = mutableListOf(
            GaugingStation(
                distance = 0.0, depth = 0.0, area = 0.0, velocity = Double.NaN, velocityStd = Double.NaN,
                discharge = 0.0, included = true, interpolated = false,
            ),
        )
        for (i in 1..10) {
            out.add(
                GaugingStation(
                    distance = i.toDouble() * 2, depth = 1.0, area = 2.0, velocity = 1.0, velocityStd = std,
                    discharge = 1.7, included = true, interpolated = false,
                ),
            )
        }
        out.add(
            GaugingStation(
                distance = 22.0, depth = 0.0, area = 0.0, velocity = Double.NaN, velocityStd = Double.NaN, discharge = 0.0,
                included = true, interpolated = false,
            ),
        )
        return out
    }

    private fun sources(
        level: Double = 0.0,
        depth: Double = 0.0,
        alpha: Double = 0.0,
        scale: Double = 0.0,
        pairs: Int = 50,
    ): UncertaintySources = UncertaintySources(
        levelStd = level, depthStd = depth, alphaStd = alpha, scaleRelativeStd = scale,
        interpolatedRelativeStd = 0.2, pairCount = pairs,
    )

    @Test
    fun noErrorsGiveNoUncertainty() {
        val u = DischargeUncertainty.estimate(channel(std = 0.0), 0.85, sources())
        assertEquals(17.0, u.discharge, 1e-9)
        assertEquals(17.0, u.mean, 1e-9)
        assertEquals(0.0, u.expanded, 1e-9)
    }

    @Test
    fun alphaErrorPropagatesLinearly() {
        val u = DischargeUncertainty.estimate(channel(std = 0.0), 0.85, sources(0.0, 0.0, 0.05))
        assertEquals(1.96 * 0.05 / 0.85, u.relative, 0.008)
        assertEquals(1.0, u.shares[UncertaintySource.alpha] ?: 0.0, 1e-9)
    }

    @Test
    fun scaleErrorCountsTwice() {
        // Velocity and width both scale: Q ∝ s², so σ_Q / Q ≈ 2 σ_s.
        val u = DischargeUncertainty.estimate(channel(std = 0.0), 0.85, sources(0.0, 0.0, 0.0, 0.01))
        assertEquals(1.96 * 0.02, u.relative, 0.003)
    }

    @Test
    fun levelErrorIsCommonAndBathymetryIndependent() {
        // Level: all depths move together → σ_Q / Q = σ_h / d = 2 %. Bathymetry: independent → 2 % / √10.
        val level = DischargeUncertainty.estimate(channel(std = 0.0), 0.85, sources(0.02))
        assertEquals(0.02, level.std / 17, 0.002)
        val bed = DischargeUncertainty.estimate(channel(std = 0.0), 0.85, sources(0.0, 0.02))
        assertEquals(0.02 / sqrt(10.0), bed.std / 17, 0.0012)
    }

    @Test
    fun velocityErrorShrinksWithPairs() {
        val few = DischargeUncertainty.estimate(channel(std = 0.3), 0.85, sources(pairs = 4))
        val many = DischargeUncertainty.estimate(channel(std = 0.3), 0.85, sources(pairs = 100))
        assertTrue(few.std > 4 * many.std)
        // σ_v / √(pairs/2) per station, independent: (0.3 / √50) / √10 relative.
        assertEquals(0.3 / sqrt(50.0) / sqrt(10.0), many.std / 17, 0.002)
    }

    @Test
    fun deterministicAndSharesSumToOne() {
        val s = sources(0.01, 0.02, 0.05, 0.005)
        val a = DischargeUncertainty.estimate(channel(), 0.85, s)
        val b = DischargeUncertainty.estimate(channel(), 0.85, s)
        assertEquals(a, b)
        assertEquals(1.0, a.shares.values.sum(), 1e-9)
        assertTrue(a.lower < a.discharge)
        assertTrue(a.upper > a.discharge)
        // α dominates with these inputs.
        assertEquals(UncertaintySource.alpha, a.shares.maxByOrNull { it.value }?.key)
    }

    @Test
    fun excludedStationsDoNotCount() {
        val st = channel(std = 0.0)
        st[3].included = false
        val u = DischargeUncertainty.estimate(st, 0.85, sources())
        assertEquals(15.3, u.discharge, 1e-9)
    }

    // MARK: - Quality

    @Test
    fun cleanGaugingIsGood() {
        val st = channel(std = 0.1)
        val u = DischargeUncertainty.estimate(st, 0.85, sources(0.01, 0.02, 0.02, 0.005))
        val q = GaugingQuality.assess(st, 1.0, u, GeoreferenceFit.relativeDistanceResidual(0.002))
        assertEquals(emptyList<DoubtfulStation>(), q.doubtful)
        assertEquals(QualityLevel.good, q.check(QualityCriterion.measured)?.level)
        assertEquals(0.1, q.check(QualityCriterion.maxStationShare)?.value ?: 0.0, 1e-9)
        assertEquals(10.0, q.check(QualityCriterion.wetStations)?.value)
        assertEquals(QualityLevel.fair, q.check(QualityCriterion.wetStations)?.level)
        assertEquals(0.1, q.check(QualityCriterion.variability)?.value ?: 0.0, 1e-9)
        assertEquals(QualityLevel.good, q.check(QualityCriterion.georeference)?.level)
        assertEquals(QualityLevel.fair, q.overall)
    }

    @Test
    fun thresholds() {
        val st = channel()
        val q = GaugingQuality.assess(st, 0.7, null, GeoreferenceFit.reprojectionPixels(2.0))
        assertEquals(QualityLevel.poor, q.check(QualityCriterion.measured)?.level)
        assertEquals(QualityLevel.fair, q.check(QualityCriterion.georeference)?.level)
        assertNull(q.check(QualityCriterion.uncertainty))
        assertEquals(QualityLevel.poor, q.overall)
        val uav = GaugingQuality.assess(st, null, null, GeoreferenceFit.none)
        assertNull(uav.check(QualityCriterion.georeference))
        assertNull(uav.check(QualityCriterion.measured))
    }

    @Test
    fun bankStationFasterThanItsNeighbourIsDoubtful() {
        // Like pisco's station 14 (4.87 m/s next to 2.84 m/s and a dry bank).
        val st = channel(std = 0.1)
        st[10].velocity = 1.8
        assertEquals(listOf(10), GaugingQuality.doubtfulStations(st).map { it.index })
        assertEquals(listOf(DoubtReason.jump), GaugingQuality.doubtfulStations(st).firstOrNull()?.reasons)
        // Slower towards the bank is normal.
        st[10].velocity = 0.4
        assertEquals(emptyList<DoubtfulStation>(), GaugingQuality.doubtfulStations(st))
    }

    @Test
    fun doubtfulStations() {
        val st = channel(std = 0.1)
        st[3].velocity = 2.5 // jump against 1 and 1
        st[3].discharge = 4.25
        st[5].velocity = -0.8 // against the flow (and a jump)
        st[7].velocity = Double.NaN // wet, counted, no velocity
        st[7].discharge = Double.NaN
        st[9].velocityStd = 0.7 // σ / v = 0.7
        val d = GaugingQuality.doubtfulStations(st)
        assertEquals(listOf(3, 5, 7, 9), d.map { it.index })
        assertEquals(listOf(DoubtReason.jump), d[0].reasons)
        assertEquals(listOf(DoubtReason.reverse, DoubtReason.jump), d[1].reasons)
        assertEquals(listOf(DoubtReason.noVelocity), d[2].reasons)
        assertEquals(listOf(DoubtReason.highVariability), d[3].reasons)
        // Interpolated and excluded stations are not flagged.
        st[7].interpolated = true
        st[3].included = false
        assertEquals(listOf(5, 9), GaugingQuality.doubtfulStations(st).map { it.index })
        val q = GaugingQuality.assess(st, 1.0, null, GeoreferenceFit.none)
        assertEquals(2.0, q.check(QualityCriterion.doubtful)?.value)
        assertEquals(QualityLevel.fair, q.check(QualityCriterion.doubtful)?.level)
    }
}
