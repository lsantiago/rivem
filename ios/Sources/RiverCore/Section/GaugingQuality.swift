import Foundation

// RIVEM addition (not in desktop RIVeR): quality of a gauging and uncertainty of its discharge. Nothing here is
// written to the project files; it is recomputed from `xsections.json` whenever the results are shown.

/// One station of a computed section, as the quality and uncertainty estimates need it (SI units).
public struct GaugingStation: Equatable, Sendable {
    /// Distance along the section (m).
    public var distance: Double
    /// Water depth (m); 0 on dry banks.
    public var depth: Double
    /// Wetted area of the station (m²).
    public var area: Double
    /// Surface velocity used for the discharge (m/s), NaN when the station has none.
    public var velocity: Double
    /// Standard deviation of the surface velocity over the PIV pairs (m/s), NaN when unknown.
    public var velocityStd: Double
    /// Discharge of the station (m³/s), NaN when it has none.
    public var discharge: Double
    /// Counts in the section discharge.
    public var included: Bool
    /// Velocity filled from the neighbours (no own measurement).
    public var interpolated: Bool

    public init(distance: Double, depth: Double, area: Double, velocity: Double, velocityStd: Double, discharge: Double,
                included: Bool, interpolated: Bool) {
        self.distance = distance
        self.depth = depth
        self.area = area
        self.velocity = velocity
        self.velocityStd = velocityStd
        self.discharge = discharge
        self.included = included
        self.interpolated = interpolated
    }

    var isWet: Bool { depth.isFinite && depth > 0 }
    var hasVelocity: Bool { velocity.isFinite }
    var contributes: Bool { included && discharge.isFinite }
}

// MARK: - Uncertainty

/// Standard uncertainties of the inputs (1σ). Defaults follow common LSPIV practice: staff gauge read to ±1 cm,
/// bathymetry to ±2 cm, surface coefficient 0.85 ± 0.05 (ISO 748 / Le Coz et al. 2010), 0.5 % scale error of the
/// georeferencing, 20 % on velocities filled by interpolation.
public struct UncertaintySources: Equatable, Sendable {
    /// Water level (m): common to every station.
    public var levelStd: Double
    /// Bathymetry depth (m): independent per station.
    public var depthStd: Double
    /// Velocity coefficient α (absolute).
    public var alphaStd: Double
    /// Relative scale error of the georeferencing (lengths and velocities scale together).
    public var scaleRelativeStd: Double
    /// Relative error of an interpolated velocity.
    public var interpolatedRelativeStd: Double
    /// PIV pairs averaged per station: the time-averaging error of a measured velocity is σ/√(pairs/2) (consecutive
    /// pairs share a frame, so only about half of them are independent).
    public var pairCount: Int

    public init(levelStd: Double = 0.01, depthStd: Double = 0.02, alphaStd: Double = 0.05, scaleRelativeStd: Double = 0.005,
                interpolatedRelativeStd: Double = 0.20, pairCount: Int) {
        self.levelStd = levelStd
        self.depthStd = depthStd
        self.alphaStd = alphaStd
        self.scaleRelativeStd = scaleRelativeStd
        self.interpolatedRelativeStd = interpolatedRelativeStd
        self.pairCount = pairCount
    }
}

public enum UncertaintySource: String, CaseIterable, Sendable {
    case velocity, alpha, level, bathymetry, georeference, interpolation
}

public struct DischargeUncertainty: Equatable, Sendable {
    /// Section discharge from the stations (m³/s), the same as `total_Q`.
    public var discharge: Double
    public var mean: Double
    public var std: Double
    /// 2.5 % and 97.5 % quantiles of the simulated discharge.
    public var lower: Double
    public var upper: Double
    /// Fraction of the variance due to each source (sums to 1 when there is any).
    public var shares: [UncertaintySource: Double]

    /// Expanded uncertainty at 95 %: half the width of the 95 % interval (m³/s).
    public var expanded: Double { (upper - lower) / 2 }
    /// `expanded` relative to the discharge.
    public var relative: Double { discharge != 0 ? expanded / abs(discharge) : .nan }

    /// Monte Carlo propagation of the input errors through the mid-section discharge: each station's Q = α·v·A is
    /// perturbed through α, v (time-averaging or interpolation error), A (level + bathymetry depth) and the
    /// georeferencing scale (v and the width both scale, so Q scales with its square). Deterministic for a given seed.
    public static func estimate(stations: [GaugingStation], alpha: Double, sources: UncertaintySources,
                                samples: Int = 4000, seed: UInt64 = 0x52_49_56_45_4D) -> DischargeUncertainty {
        let active: [GaugingStation] = stations.filter { $0.contributes }
        let nominal: Double = active.reduce(0) { $0 + $1.discharge }
        let all: Set<UncertaintySource> = Set(UncertaintySource.allCases)
        let total: [Double] = simulate(active, alpha: alpha, sources: sources, enabled: all, samples: samples, seed: seed)
        var variances: [UncertaintySource: Double] = [:]
        for s in UncertaintySource.allCases {
            let q: [Double] = simulate(active, alpha: alpha, sources: sources, enabled: [s], samples: samples, seed: seed)
            variances[s] = variance(q)
        }
        let sum: Double = variances.values.reduce(0, +)
        var shares: [UncertaintySource: Double] = [:]
        for (k, v) in variances { shares[k] = sum > 0 ? v / sum : 0 }
        let sorted: [Double] = total.sorted()
        let m: Double = total.isEmpty ? nominal : total.reduce(0, +) / Double(total.count)
        return DischargeUncertainty(discharge: nominal, mean: m, std: variance(total).squareRoot(),
                                    lower: quantile(sorted, 0.025), upper: quantile(sorted, 0.975), shares: shares)
    }

    static func simulate(_ stations: [GaugingStation], alpha: Double, sources: UncertaintySources,
                         enabled: Set<UncertaintySource>, samples: Int, seed: UInt64) -> [Double] {
        var rng = GaussianRandom(seed: seed)
        let effectivePairs: Double = max(1, Double(sources.pairCount) / 2)
        let a0: Double = alpha.isFinite && alpha > 0 ? alpha : 0.85
        var out: [Double] = []
        out.reserveCapacity(max(samples, 0))
        for _ in 0..<max(samples, 0) {
            // Draw every variable every time, so a source switched off does not shift the others' random streams.
            let dAlpha: Double = rng.normal() * sources.alphaStd
            let dLevel: Double = rng.normal() * sources.levelStd
            let dScale: Double = rng.normal() * sources.scaleRelativeStd
            let alphaFactor: Double = enabled.contains(.alpha) ? max(0, a0 + dAlpha) / a0 : 1
            let scale: Double = enabled.contains(.georeference) ? max(0, 1 + dScale) : 1
            var q: Double = 0
            for s in stations {
                let zVelocity: Double = rng.normal()
                let zDepth: Double = rng.normal()
                var velocityFactor: Double = 1
                if s.hasVelocity, abs(s.velocity) > 1e-9 {
                    if s.interpolated {
                        if enabled.contains(.interpolation) {
                            velocityFactor = 1 + zVelocity * sources.interpolatedRelativeStd
                        }
                    } else if enabled.contains(.velocity), s.velocityStd.isFinite, s.velocityStd > 0 {
                        let sem: Double = s.velocityStd / effectivePairs.squareRoot()
                        velocityFactor = (s.velocity + zVelocity * sem) / s.velocity
                    }
                }
                var areaFactor: Double = 1
                if s.isWet {
                    var d: Double = s.depth
                    if enabled.contains(.level) { d += dLevel }
                    if enabled.contains(.bathymetry) { d += zDepth * sources.depthStd }
                    areaFactor = max(0, d) / s.depth
                }
                q += s.discharge * alphaFactor * velocityFactor * areaFactor * scale * scale
            }
            out.append(q)
        }
        return out
    }

    static func variance(_ x: [Double]) -> Double {
        guard x.count > 1 else { return 0 }
        let m: Double = x.reduce(0, +) / Double(x.count)
        return x.reduce(0) { $0 + ($1 - m) * ($1 - m) } / Double(x.count - 1)
    }

    /// Linear-interpolated quantile of sorted values.
    static func quantile(_ sorted: [Double], _ p: Double) -> Double {
        guard !sorted.isEmpty else { return .nan }
        let h: Double = Double(sorted.count - 1) * p
        let i: Int = Int(h.rounded(.down))
        let f: Double = h - Double(i)
        return i + 1 < sorted.count ? sorted[i] + f * (sorted[i + 1] - sorted[i]) : sorted[i]
    }
}

/// Reproducible normal deviates: SplitMix64 + Box–Muller.
struct GaussianRandom {
    private var state: UInt64
    private var spare: Double? = nil

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z: UInt64 = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Uniform in [0, 1).
    mutating func uniform() -> Double { Double(next() >> 11) * (1.0 / 9_007_199_254_740_992.0) }

    mutating func normal() -> Double {
        if let s = spare {
            spare = nil
            return s
        }
        var u1: Double = uniform()
        while u1 <= 0 { u1 = uniform() }
        let u2: Double = uniform()
        let r: Double = (-2 * log(u1)).squareRoot()
        spare = r * sin(2 * Double.pi * u2)
        return r * cos(2 * Double.pi * u2)
    }
}

// MARK: - Quality

public enum QualityLevel: Int, Comparable, Sendable {
    case good = 0, fair = 1, poor = 2

    public static func < (a: QualityLevel, b: QualityLevel) -> Bool { a.rawValue < b.rawValue }
}

/// How well the georeferencing fits its own measurements.
public enum GeoreferenceFit: Equatable, Sendable {
    /// Oblique: RMS of the six distance residuals over their mean length.
    case relativeDistanceResidual(Double)
    /// 3D camera: mean reprojection error of the control points (px).
    case reprojectionPixels(Double)
    /// UAV (two points): exact by construction, nothing to check.
    case none
}

public enum QualityCriterion: String, CaseIterable, Sendable {
    /// Fraction of the discharge measured (not interpolated).
    case measured
    /// Largest share of the discharge in one station (ISO 748: ≤ 10 %).
    case maxStationShare
    /// Wet stations with a velocity.
    case wetStations
    /// Median coefficient of variation of the station velocities over the pairs.
    case variability
    /// Stations that look wrong (`DoubtfulStation`).
    case doubtful
    /// Expanded uncertainty (95 %) relative to the discharge.
    case uncertainty
    /// Georeferencing residuals.
    case georeference
}

public struct QualityCheck: Equatable, Sendable {
    public var criterion: QualityCriterion
    /// Value in the criterion's unit (fractions as 0…1, pixels, counts).
    public var value: Double
    public var level: QualityLevel
}

public enum DoubtReason: String, CaseIterable, Sendable {
    /// Wet, counted, but no velocity measured (and not interpolated).
    case noVelocity
    /// Velocity varies a lot between pairs (σ / |v| > 0.5).
    case highVariability
    /// Far from the mean of both neighbours (> 50 % and > 0.25 m/s), or, next to a bank, 50 % faster than its only
    /// wet neighbour.
    case jump
    /// Flows against the rest of the section.
    case reverse
}

public struct DoubtfulStation: Equatable, Sendable {
    public var index: Int
    public var reasons: [DoubtReason]
}

public struct GaugingQuality: Equatable, Sendable {
    public var checks: [QualityCheck]
    public var doubtful: [DoubtfulStation]

    /// The worst level of all checks.
    public var overall: QualityLevel { checks.map(\.level).max() ?? .good }

    public func check(_ c: QualityCriterion) -> QualityCheck? { checks.first { $0.criterion == c } }

    public static func assess(stations: [GaugingStation], measuredFraction: Double?, uncertainty: DischargeUncertainty?,
                              georeference: GeoreferenceFit) -> GaugingQuality {
        var checks: [QualityCheck] = []
        func add(_ c: QualityCriterion, _ v: Double, good: Double, fair: Double, higherIsBetter: Bool) {
            guard v.isFinite else { return }
            let level: QualityLevel
            if higherIsBetter {
                level = v >= good ? .good : (v >= fair ? .fair : .poor)
            } else {
                level = v <= good ? .good : (v <= fair ? .fair : .poor)
            }
            checks.append(QualityCheck(criterion: c, value: v, level: level))
        }

        if let m = measuredFraction { add(.measured, m, good: 0.9, fair: 0.75, higherIsBetter: true) }

        let contributing: [GaugingStation] = stations.filter { $0.contributes }
        let total: Double = contributing.reduce(0) { $0 + $1.discharge }
        if total != 0 {
            let share: Double = contributing.map { abs($0.discharge / total) }.max() ?? 0
            add(.maxStationShare, share, good: 0.10, fair: 0.20, higherIsBetter: false)
        }

        let wet: Int = stations.filter { $0.isWet && $0.hasVelocity && $0.included }.count
        add(.wetStations, Double(wet), good: 12, fair: 8, higherIsBetter: true)

        var cvs: [Double] = []
        for s in stations where s.isWet && s.included && !s.interpolated && s.hasVelocity && abs(s.velocity) > 0.05 {
            if s.velocityStd.isFinite { cvs.append(s.velocityStd / abs(s.velocity)) }
        }
        if !cvs.isEmpty { add(.variability, median(cvs), good: 0.25, fair: 0.5, higherIsBetter: false) }

        let doubtful: [DoubtfulStation] = doubtfulStations(stations)
        add(.doubtful, Double(doubtful.count), good: 0, fair: 2, higherIsBetter: false)

        if let u = uncertainty { add(.uncertainty, u.relative, good: 0.10, fair: 0.20, higherIsBetter: false) }

        switch georeference {
        case .relativeDistanceResidual(let r): add(.georeference, r, good: 0.005, fair: 0.02, higherIsBetter: false)
        case .reprojectionPixels(let px): add(.georeference, px, good: 1, fair: 3, higherIsBetter: false)
        case .none: break
        }
        return GaugingQuality(checks: checks, doubtful: doubtful)
    }

    /// Stations whose velocity looks wrong, so the user can review them (and untick them in the table).
    public static func doubtfulStations(_ stations: [GaugingStation]) -> [DoubtfulStation] {
        let measured: [Double] = stations.filter { $0.isWet && $0.hasVelocity && !$0.interpolated }.map(\.velocity)
        let direction: Double = median(measured)
        var out: [DoubtfulStation] = []
        for (i, s) in stations.enumerated() where s.isWet && s.included {
            var reasons: [DoubtReason] = []
            if !s.hasVelocity {
                if !s.interpolated { reasons.append(.noVelocity) }
            } else if !s.interpolated {
                if s.velocityStd.isFinite, abs(s.velocity) > 0.05, s.velocityStd / abs(s.velocity) > 0.5 {
                    reasons.append(.highVariability)
                }
                if direction.isFinite, direction != 0, s.velocity * direction < 0, abs(s.velocity) > 0.05 {
                    reasons.append(.reverse)
                }
                let l: Double? = neighbour(stations, from: i, step: -1)
                let r: Double? = neighbour(stations, from: i, step: 1)
                if let l = l, let r = r {
                    let m: Double = (l + r) / 2
                    if abs(s.velocity - m) > max(0.5 * abs(m), 0.25) { reasons.append(.jump) }
                } else if let n = l ?? r {
                    // Last wet station before a bank: it may be slower than its inner neighbour (velocity drops
                    // towards the bank), not much faster.
                    if abs(s.velocity) > 1.5 * abs(n) && abs(s.velocity) - abs(n) > 0.25 { reasons.append(.jump) }
                }
            }
            if !reasons.isEmpty { out.append(DoubtfulStation(index: i, reasons: reasons)) }
        }
        return out
    }

    private static func neighbour(_ stations: [GaugingStation], from i: Int, step: Int) -> Double? {
        let j: Int = i + step
        guard j >= 0, j < stations.count else { return nil }
        let s: GaugingStation = stations[j]
        return s.isWet && s.hasVelocity ? s.velocity : nil
    }

    static func median(_ x: [Double]) -> Double {
        let v: [Double] = x.filter { $0.isFinite }.sorted()
        guard !v.isEmpty else { return .nan }
        let n: Int = v.count
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2
    }
}
