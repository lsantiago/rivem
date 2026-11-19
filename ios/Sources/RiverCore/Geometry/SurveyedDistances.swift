import Foundation

// RIVEM addition (not in desktop RIVeR): the six oblique distances from points surveyed in 3D (ARKit / LiDAR).

/// A point in a gravity-aligned 3D frame (metres): `y` is up, `x` and `z` horizontal (ARKit's world frame).
public struct Point3: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var z: Double

    public init(_ x: Double, _ y: Double, _ z: Double) {
        self.x = x
        self.y = y
        self.z = z
    }
}

/// One surveyed point from several readings (raycasts over a fraction of a second).
public struct AveragedPoint: Equatable, Sendable {
    public var point: Point3
    /// RMS distance of the kept readings to `point` (m): how steady the aim and the depth were.
    public var spread: Double
    public var used: Int
    public var total: Int

    /// Median per axis, readings farther than 3× the median distance (and > 1 cm) dropped as outliers, then the
    /// mean of the rest. Nil without finite readings.
    public static func from(_ readings: [Point3]) -> AveragedPoint? {
        let r: [Point3] = readings.filter { $0.x.isFinite && $0.y.isFinite && $0.z.isFinite }
        guard !r.isEmpty else { return nil }
        func median(_ v: [Double]) -> Double {
            let s: [Double] = v.sorted()
            return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
        }
        let m = Point3(median(r.map(\.x)), median(r.map(\.y)), median(r.map(\.z)))
        func dist(_ a: Point3, _ b: Point3) -> Double {
            ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).squareRoot()
        }
        let limit: Double = max(3 * median(r.map { dist($0, m) }), 0.01)
        let kept: [Point3] = r.filter { dist($0, m) <= limit }
        let n: Double = Double(kept.count)
        let mean = Point3(kept.reduce(0) { $0 + $1.x } / n, kept.reduce(0) { $0 + $1.y } / n, kept.reduce(0) { $0 + $1.z } / n)
        let rms: Double = (kept.reduce(0) { $0 + dist($1, mean) * dist($1, mean) } / n).squareRoot()
        return AveragedPoint(point: mean, spread: rms, used: kept.count, total: r.count)
    }
}

public struct SurveyedDistances: Equatable, Sendable {
    /// d12, d23, d34, d41, d13, d24 measured horizontally.
    public var distances: ObliqueDistances
    /// Highest minus lowest point (m). The oblique method assumes the four points on the water plane: a large spread
    /// means some point is not at water level.
    public var heightSpread: Double
    /// Largest difference between the straight (slant) and horizontal distance of a pair (m), what a tape held
    /// along the slope would have added.
    public var largestSlantExcess: Double

    /// The oblique solution places the four points on one horizontal plane (the water surface), so the distances it
    /// needs are horizontal: the vertical difference between surveyed points is dropped. Nil unless four points.
    public static func from(_ points: [Point3]) -> SurveyedDistances? {
        guard points.count == 4, points.allSatisfy({ $0.x.isFinite && $0.y.isFinite && $0.z.isFinite }) else { return nil }
        let pairs: [(Int, Int)] = [(0, 1), (1, 2), (2, 3), (3, 0), (0, 2), (1, 3)]
        var horizontal: [Double] = []
        var excess: Double = 0
        for (i, j) in pairs {
            let a: Point3 = points[i]
            let b: Point3 = points[j]
            let h: Double = hypot(b.x - a.x, b.z - a.z)
            let slant: Double = (h * h + (b.y - a.y) * (b.y - a.y)).squareRoot()
            horizontal.append(h)
            excess = max(excess, slant - h)
        }
        guard let d = ObliqueDistances(horizontal) else { return nil }
        let ys: [Double] = points.map(\.y)
        return SurveyedDistances(distances: d, heightSpread: (ys.max() ?? 0) - (ys.min() ?? 0), largestSlantExcess: excess)
    }
}
