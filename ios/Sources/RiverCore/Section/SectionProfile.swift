import Foundation

/// Bed profile against the water level: crossings, wet points, wet segments and the section's hydraulic
/// properties. Port of `_find_crossings`, `find_wet_segments` and (fixed) `calculate_river_section_properties`
/// of `compute_section.py`.
public enum SectionProfile {
    /// The bed profile with every crossing of the water level inserted, sorted by station.
    public struct Augmented: Equatable, Sendable {
        public var stations: [Double]
        public var stages: [Double]
        /// `stage <= level`.
        public var wet: [Bool]
    }

    /// `_find_crossings`: a crossing is inserted only where the bed goes strictly from below to strictly above the
    /// level (or back), so the interpolation never divides by zero; points exactly at the level are already in the
    /// profile. Sorting is stable (NumPy's default `argsort` is not, which only matters for repeated stations).
    public static func augmented(stations: [Double], stages: [Double], level: Double) -> Augmented {
        var s = stations
        var z = stages
        let n = min(stations.count, stages.count)
        if n >= 2 {
            for i in 0..<(n - 1) {
                let a = stages[i], b = stages[i + 1]
                if (a < level && b > level) || (a > level && b < level) {
                    let num: Double = (level - a) * (stations[i + 1] - stations[i])
                    let x: Double = stations[i] + num / (b - a)
                    s.append(x)
                    z.append(level)
                }
            }
        }
        let order = (0..<s.count).sorted { l, r in s[l] != s[r] ? s[l] < s[r] : l < r }
        let ss = order.map { s[$0] }
        let zz = order.map { z[$0] }
        return Augmented(stations: ss, stages: zz, wet: zz.map { $0 <= level })
    }

    /// `find_wet_segments`: (start, end) of every contiguous wet run, in bathymetry stations.
    public static func wetSegments(stations: [Double], stages: [Double], level: Double) -> [(Double, Double)] {
        let a = augmented(stations: stations, stages: stages, level: level)
        var segments: [(Double, Double)] = []
        var start: Int? = nil
        for i in 0..<a.wet.count {
            if a.wet[i] && start == nil {
                start = i
            } else if !a.wet[i], let s0 = start {
                segments.append((a.stations[s0], a.stations[i - 1]))
                start = nil
            }
        }
        if let s0 = start, let last = a.stations.last { segments.append((a.stations[s0], last)) }
        return segments
    }

    public struct Properties: Equatable, Sendable {
        public var area: Double
        public var width: Double
        public var maxDepth: Double
        public var averageDepth: Double
    }

    /// Wetted area, top width, maximum and average depth of the section — FIX of PLAN #8.
    ///
    /// Upstream (`calculate_river_section_properties`, `compute_section.py:1171-1214`) works on the raw bathymetry:
    /// a segment from a wet point to a dry one adds the trapezoid of the clipped depths (too much area, the water
    /// actually ends at the crossing) and the width adds a whole segment whenever its *left* point is wet, so the
    /// result depends on the direction of the profile and on where the points happen to be (in `pisco` the first
    /// segment, whose left point is exactly at the level, is left out: W = 21.155 m instead of 22.656 m).
    ///
    /// Here the same crossing points used for the stations (`augmented`) are inserted first, so every segment is
    /// either entirely wet or entirely dry: the trapezoids are exact for the piecewise-linear bed, and the width is
    /// the sum of the wet segments (equal to Σ(end − start) of `wetSegments`). A stretch lying exactly at the level
    /// (zero depth at both ends) adds neither area nor width. `averageDepth = area / width` (0 when dry).
    public static func properties(stations: [Double], stages: [Double], level: Double) -> Properties {
        let a = augmented(stations: stations, stages: stages, level: level)
        var area = 0.0
        var width = 0.0
        var maxDepth = 0.0
        for i in 0..<a.stations.count {
            let d = max(level - a.stages[i], 0)
            if d > maxDepth { maxDepth = d }
        }
        if a.stations.count >= 2 {
            for i in 0..<(a.stations.count - 1) {
                let d1 = level - a.stages[i]
                let d2 = level - a.stages[i + 1]
                let dx = a.stations[i + 1] - a.stations[i]
                if d1 >= 0 && d2 >= 0 && d1 + d2 > 0 {
                    area += (d1 + d2) / 2 * dx
                    width += dx
                }
            }
        }
        let average: Double = width > 0 ? area / width : 0
        return Properties(area: area, width: width, maxDepth: maxDepth, averageDepth: average)
    }

    /// Upstream's `calculate_river_section_properties`, kept to document and test the difference of the fix.
    public static func upstreamProperties(stations: [Double], stages: [Double], level: Double) -> Properties {
        let n = min(stations.count, stages.count)
        guard n >= 1 else { return Properties(area: 0, width: 0, maxDepth: 0, averageDepth: 0) }
        var depths: [Double] = []
        for i in 0..<n { depths.append(max(level - stages[i], 0)) }
        var area = 0.0
        var width = 0.0
        if n >= 2 {
            for i in 0..<(n - 1) {
                let dx = stations[i + 1] - stations[i]
                area += (depths[i] + depths[i + 1]) / 2 * dx
                if depths[i] > 0 { width += dx }
            }
        }
        let maxDepth = depths.max() ?? 0
        let average: Double = width > 0 ? area / width : 0
        return Properties(area: area, width: width, maxDepth: maxDepth, averageDepth: average)
    }
}
