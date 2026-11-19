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
