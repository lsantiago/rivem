package org.rivem.core.section

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// RIVEM addition (not in desktop RIVeR): quality of a gauging and uncertainty of its discharge. Nothing here is
// written to the project files; it is recomputed from `xsections.json` whenever the results are shown.

/** One station of a computed section, as the quality and uncertainty estimates need it (SI units). */
data class GaugingStation(
    /** Distance along the section (m). */
    var distance: Double,
    /** Water depth (m); 0 on dry banks. */
    var depth: Double,
    /** Wetted area of the station (m²). */
    var area: Double,
    /** Surface velocity used for the discharge (m/s), NaN when the station has none. */
    var velocity: Double,
    /** Standard deviation of the surface velocity over the PIV pairs (m/s), NaN when unknown. */
    var velocityStd: Double,
    /** Discharge of the station (m³/s), NaN when it has none. */
    var discharge: Double,
    /** Counts in the section discharge. */
    var included: Boolean,
    /** Velocity filled from the neighbours (no own measurement). */
    var interpolated: Boolean,
) {
    internal val isWet: Boolean get() = depth.isFinite() && depth > 0
    internal val hasVelocity: Boolean get() = velocity.isFinite()
    internal val contributes: Boolean get() = included && discharge.isFinite()
}

// MARK: - Uncertainty

/**
 * Standard uncertainties of the inputs (1σ). Defaults follow common LSPIV practice: staff gauge read to ±1 cm,
 * bathymetry to ±2 cm, surface coefficient 0.85 ± 0.05 (ISO 748 / Le Coz et al. 2010), 0.5 % scale error of the
 * georeferencing, 20 % on velocities filled by interpolation.
 */
data class UncertaintySources(
    /** Water level (m): common to every station. */
    var levelStd: Double = 0.01,
    /** Bathymetry depth (m): independent per station. */
    var depthStd: Double = 0.02,
    /** Velocity coefficient α (absolute). */
    var alphaStd: Double = 0.05,
    /** Relative scale error of the georeferencing (lengths and velocities scale together). */
    var scaleRelativeStd: Double = 0.005,
    /** Relative error of an interpolated velocity. */
    var interpolatedRelativeStd: Double = 0.20,
    /**
     * PIV pairs averaged per station: the time-averaging error of a measured velocity is σ/√(pairs/2) (consecutive
     * pairs share a frame, so only about half of them are independent).
     */
    var pairCount: Int,
)

enum class UncertaintySource(val rawValue: String) {
    velocity("velocity"), alpha("alpha"), level("level"), bathymetry("bathymetry"), georeference("georeference"),
    interpolation("interpolation");

    companion object {
        /** Swift `allCases`. */
        val allCases: List<UncertaintySource> get() = values().toList()
    }
}

data class DischargeUncertainty(
    /** Section discharge from the stations (m³/s), the same as `total_Q`. */
    var discharge: Double,
    var mean: Double,
    var std: Double,
    /** 2.5 % and 97.5 % quantiles of the simulated discharge. */
    var lower: Double,
    var upper: Double,
    /** Fraction of the variance due to each source (sums to 1 when there is any). */
    var shares: Map<UncertaintySource, Double>,
) {
    /** Expanded uncertainty at 95 %: half the width of the 95 % interval (m³/s). */
    val expanded: Double get() = (upper - lower) / 2

    /** `expanded` relative to the discharge. */
    val relative: Double get() = if (discharge != 0.0) expanded / abs(discharge) else Double.NaN

    companion object {
        /** Swift's default seed `0x52_49_56_45_4D` ("RIVEM"). */
        val defaultSeed: ULong = 0x52_49_56_45_4DuL

        /**
         * Monte Carlo propagation of the input errors through the mid-section discharge: each station's Q = α·v·A is
         * perturbed through α, v (time-averaging or interpolation error), A (level + bathymetry depth) and the
         * georeferencing scale (v and the width both scale, so Q scales with its square). Deterministic for a given
         * seed.
         */
        fun estimate(
            stations: List<GaugingStation>,
            alpha: Double,
            sources: UncertaintySources,
            samples: Int = 4000,
            seed: ULong = defaultSeed,
        ): DischargeUncertainty {
            val active: List<GaugingStation> = stations.filter { it.contributes }
            val nominal: Double = active.fold(0.0) { acc, s -> acc + s.discharge }
            val all: Set<UncertaintySource> = UncertaintySource.allCases.toSet()
            val total: List<Double> = simulate(active, alpha, sources, all, samples, seed)
            val variances = LinkedHashMap<UncertaintySource, Double>()
            for (s in UncertaintySource.allCases) {
                val q: List<Double> = simulate(active, alpha, sources, setOf(s), samples, seed)
                variances[s] = variance(q)
            }
            // Swift sums a Dictionary in hash order; here in case order.
            val sum: Double = variances.values.fold(0.0) { acc, v -> acc + v }
            val shares = LinkedHashMap<UncertaintySource, Double>()
            for ((k, v) in variances) shares[k] = if (sum > 0) v / sum else 0.0
            val sorted: List<Double> = total.sorted()
            val m: Double = if (total.isEmpty()) nominal else total.fold(0.0) { acc, v -> acc + v } / total.size.toDouble()
            return DischargeUncertainty(
                discharge = nominal, mean = m, std = sqrt(variance(total)),
                lower = quantile(sorted, 0.025), upper = quantile(sorted, 0.975), shares = shares,
            )
        }

        internal fun simulate(
            stations: List<GaugingStation>,
            alpha: Double,
            sources: UncertaintySources,
            enabled: Set<UncertaintySource>,
            samples: Int,
            seed: ULong,
        ): List<Double> {
            val rng = GaussianRandom(seed)
            val effectivePairs: Double = max(1.0, sources.pairCount.toDouble() / 2)
            val a0: Double = if (alpha.isFinite() && alpha > 0) alpha else 0.85
            val out = ArrayList<Double>(max(samples, 0))
            repeat(max(samples, 0)) {
                // Draw every variable every time, so a source switched off does not shift the others' random streams.
                val dAlpha: Double = rng.normal() * sources.alphaStd
                val dLevel: Double = rng.normal() * sources.levelStd
                val dScale: Double = rng.normal() * sources.scaleRelativeStd
                val alphaFactor: Double =
                    if (UncertaintySource.alpha in enabled) swiftMax(0.0, a0 + dAlpha) / a0 else 1.0
                val scale: Double = if (UncertaintySource.georeference in enabled) swiftMax(0.0, 1 + dScale) else 1.0
                var q = 0.0
                for (s in stations) {
                    val zVelocity: Double = rng.normal()
                    val zDepth: Double = rng.normal()
                    var velocityFactor = 1.0
                    if (s.hasVelocity && abs(s.velocity) > 1e-9) {
                        if (s.interpolated) {
                            if (UncertaintySource.interpolation in enabled) {
                                velocityFactor = 1 + zVelocity * sources.interpolatedRelativeStd
                            }
                        } else if (UncertaintySource.velocity in enabled && s.velocityStd.isFinite() && s.velocityStd > 0) {
                            val sem: Double = s.velocityStd / sqrt(effectivePairs)
                            velocityFactor = (s.velocity + zVelocity * sem) / s.velocity
                        }
                    }
                    var areaFactor = 1.0
                    if (s.isWet) {
                        var d: Double = s.depth
                        if (UncertaintySource.level in enabled) d += dLevel
                        if (UncertaintySource.bathymetry in enabled) d += zDepth * sources.depthStd
                        areaFactor = swiftMax(0.0, d) / s.depth
                    }
                    q += s.discharge * alphaFactor * velocityFactor * areaFactor * scale * scale
                }
                out.add(q)
            }
            return out
        }

        internal fun variance(x: List<Double>): Double {
            if (x.size <= 1) return 0.0
            val m: Double = x.fold(0.0) { acc, v -> acc + v } / x.size.toDouble()
            return x.fold(0.0) { acc, v -> acc + (v - m) * (v - m) } / (x.size - 1).toDouble()
        }

        /** Linear-interpolated quantile of sorted values. */
        internal fun quantile(sorted: List<Double>, p: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val h: Double = (sorted.size - 1).toDouble() * p
            val i: Int = kotlin.math.floor(h).toInt()
            val f: Double = h - i.toDouble()
            return if (i + 1 < sorted.size) sorted[i] + f * (sorted[i + 1] - sorted[i]) else sorted[i]
        }

        /** Swift `max(a, b)`: `b >= a ? b : a`. */
        private fun swiftMax(a: Double, b: Double): Double = if (b >= a) b else a
    }
}

/**
 * Reproducible normal deviates: SplitMix64 + Box–Muller. Same integer stream as Swift (unsigned 64-bit
 * arithmetic); `ln`/`sin`/`cos` use `StrictMath` (fdlibm) so every JVM/Android device gives the same numbers.
 */
internal class GaussianRandom(seed: ULong) {
    private var state: ULong = seed
    private var spare: Double? = null

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z: ULong = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** Uniform in [0, 1). */
    fun uniform(): Double = (next() shr 11).toLong().toDouble() * (1.0 / 9_007_199_254_740_992.0)

    fun normal(): Double {
        spare?.let {
            spare = null
            return it
        }
        var u1: Double = uniform()
        while (u1 <= 0) u1 = uniform()
        val u2: Double = uniform()
        val r: Double = sqrt(-2 * StrictMath.log(u1))
        spare = r * StrictMath.sin(2 * Math.PI * u2)
        return r * StrictMath.cos(2 * Math.PI * u2)
    }
}

// MARK: - Quality

enum class QualityLevel(val rawValue: Int) {
    good(0), fair(1), poor(2);
    // Comparable by declaration order, as Swift's `<` on rawValue.
}

/** How well the georeferencing fits its own measurements. */
sealed class GeoreferenceFit {
    /** Oblique: RMS of the six distance residuals over their mean length. */
    data class RelativeDistanceResidual(val value: Double) : GeoreferenceFit()

    /** 3D camera: mean reprojection error of the control points (px). */
    data class ReprojectionPixels(val value: Double) : GeoreferenceFit()

    /** UAV (two points): exact by construction, nothing to check. */
    object None : GeoreferenceFit() {
        override fun toString(): String = "None"
    }

    companion object {
        /** Swift `.relativeDistanceResidual(r)`. */
        fun relativeDistanceResidual(value: Double): GeoreferenceFit = RelativeDistanceResidual(value)

        /** Swift `.reprojectionPixels(px)`. */
        fun reprojectionPixels(value: Double): GeoreferenceFit = ReprojectionPixels(value)

        /** Swift `.none`. */
        val none: GeoreferenceFit get() = None
    }
}

enum class QualityCriterion(val rawValue: String) {
    /** Fraction of the discharge measured (not interpolated). */
    measured("measured"),

    /** Largest share of the discharge in one station (ISO 748: ≤ 10 %). */
    maxStationShare("maxStationShare"),

    /** Wet stations with a velocity. */
    wetStations("wetStations"),

    /** Median coefficient of variation of the station velocities over the pairs. */
    variability("variability"),

    /** Stations that look wrong (`DoubtfulStation`). */
    doubtful("doubtful"),

    /** Expanded uncertainty (95 %) relative to the discharge. */
    uncertainty("uncertainty"),

    /** Georeferencing residuals. */
    georeference("georeference"),
}

data class QualityCheck(
    var criterion: QualityCriterion,
    /** Value in the criterion's unit (fractions as 0…1, pixels, counts). */
    var value: Double,
    var level: QualityLevel,
)

enum class DoubtReason(val rawValue: String) {
    /** Wet, counted, but no velocity measured (and not interpolated). */
    noVelocity("noVelocity"),

    /** Velocity varies a lot between pairs (σ / |v| > 0.5). */
    highVariability("highVariability"),

    /**
     * Far from the mean of both neighbours (> 50 % and > 0.25 m/s), or, next to a bank, 50 % faster than its only
     * wet neighbour.
     */
    jump("jump"),

    /** Flows against the rest of the section. */
    reverse("reverse"),
}

data class DoubtfulStation(
    var index: Int,
    var reasons: List<DoubtReason>,
)

data class GaugingQuality(
    var checks: List<QualityCheck>,
    var doubtful: List<DoubtfulStation>,
) {
    /** The worst level of all checks. */
    val overall: QualityLevel get() = checks.map { it.level }.maxOrNull() ?: QualityLevel.good

    fun check(c: QualityCriterion): QualityCheck? = checks.firstOrNull { it.criterion == c }

    companion object {
        fun assess(
            stations: List<GaugingStation>,
            measuredFraction: Double?,
            uncertainty: DischargeUncertainty?,
            georeference: GeoreferenceFit,
        ): GaugingQuality {
            val checks = ArrayList<QualityCheck>()
            fun add(c: QualityCriterion, v: Double, good: Double, fair: Double, higherIsBetter: Boolean) {
                if (!v.isFinite()) return
                val level: QualityLevel = if (higherIsBetter) {
                    if (v >= good) QualityLevel.good else if (v >= fair) QualityLevel.fair else QualityLevel.poor
                } else {
                    if (v <= good) QualityLevel.good else if (v <= fair) QualityLevel.fair else QualityLevel.poor
                }
                checks.add(QualityCheck(c, v, level))
            }

            if (measuredFraction != null) add(QualityCriterion.measured, measuredFraction, 0.9, 0.75, true)

            val contributing: List<GaugingStation> = stations.filter { it.contributes }
            val total: Double = contributing.fold(0.0) { acc, s -> acc + s.discharge }
            if (total != 0.0) {
                // Swift `max()` compares with `<`: the first of equal maxima, NaN never replaces a number.
                var share: Double? = null
                for (s in contributing) {
                    val v = abs(s.discharge / total)
                    if (share == null || share < v) share = v
                }
                add(QualityCriterion.maxStationShare, share ?: 0.0, 0.10, 0.20, false)
            }

            val wet: Int = stations.count { it.isWet && it.hasVelocity && it.included }
            add(QualityCriterion.wetStations, wet.toDouble(), 12.0, 8.0, true)

            val cvs = ArrayList<Double>()
            for (s in stations) {
                if (!(s.isWet && s.included && !s.interpolated && s.hasVelocity && abs(s.velocity) > 0.05)) continue
                if (s.velocityStd.isFinite()) cvs.add(s.velocityStd / abs(s.velocity))
            }
            if (cvs.isNotEmpty()) add(QualityCriterion.variability, median(cvs), 0.25, 0.5, false)

            val doubtful: List<DoubtfulStation> = doubtfulStations(stations)
            add(QualityCriterion.doubtful, doubtful.size.toDouble(), 0.0, 2.0, false)

            if (uncertainty != null) add(QualityCriterion.uncertainty, uncertainty.relative, 0.10, 0.20, false)

            when (georeference) {
                is GeoreferenceFit.RelativeDistanceResidual ->
                    add(QualityCriterion.georeference, georeference.value, 0.005, 0.02, false)
                is GeoreferenceFit.ReprojectionPixels ->
                    add(QualityCriterion.georeference, georeference.value, 1.0, 3.0, false)
                GeoreferenceFit.None -> {}
            }
            return GaugingQuality(checks, doubtful)
        }

        /** Stations whose velocity looks wrong, so the user can review them (and untick them in the table). */
        fun doubtfulStations(stations: List<GaugingStation>): List<DoubtfulStation> {
            val measured: List<Double> = stations.filter { it.isWet && it.hasVelocity && !it.interpolated }.map { it.velocity }
            val direction: Double = median(measured)
            val out = ArrayList<DoubtfulStation>()
            for ((i, s) in stations.withIndex()) {
                if (!(s.isWet && s.included)) continue
                val reasons = ArrayList<DoubtReason>()
                if (!s.hasVelocity) {
                    if (!s.interpolated) reasons.add(DoubtReason.noVelocity)
                } else if (!s.interpolated) {
                    if (s.velocityStd.isFinite() && abs(s.velocity) > 0.05 && s.velocityStd / abs(s.velocity) > 0.5) {
                        reasons.add(DoubtReason.highVariability)
                    }
                    if (direction.isFinite() && direction != 0.0 && s.velocity * direction < 0 && abs(s.velocity) > 0.05) {
                        reasons.add(DoubtReason.reverse)
                    }
                    val l: Double? = neighbour(stations, i, -1)
                    val r: Double? = neighbour(stations, i, 1)
                    if (l != null && r != null) {
                        val m: Double = (l + r) / 2
                        if (abs(s.velocity - m) > max(0.5 * abs(m), 0.25)) reasons.add(DoubtReason.jump)
                    } else {
                        val n = l ?: r
                        if (n != null) {
                            // Last wet station before a bank: it may be slower than its inner neighbour (velocity
                            // drops towards the bank), not much faster.
                            if (abs(s.velocity) > 1.5 * abs(n) && abs(s.velocity) - abs(n) > 0.25) reasons.add(DoubtReason.jump)
                        }
                    }
                }
                if (reasons.isNotEmpty()) out.add(DoubtfulStation(i, reasons))
            }
            return out
        }

        private fun neighbour(stations: List<GaugingStation>, i: Int, step: Int): Double? {
            val j: Int = i + step
            if (j < 0 || j >= stations.size) return null
            val s: GaugingStation = stations[j]
            return if (s.isWet && s.hasVelocity) s.velocity else null
        }

        internal fun median(x: List<Double>): Double {
            val v: List<Double> = x.filter { it.isFinite() }.sorted()
            if (v.isEmpty()) return Double.NaN
            val n: Int = v.size
            return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
        }
    }
}
