import Foundation

/// A 2-D point. In pixel space `x` grows to the right and `y` grows downwards (image rows), origin at the
/// top-left corner of the frame. In real-world space `x` is East and `y` is North (y grows upwards).
public struct Point2: Equatable, Hashable, Codable, Sendable {
    public var x: Double
    public var y: Double

    public init(_ x: Double, _ y: Double) {
        self.x = x
        self.y = y
    }

    public static func - (a: Point2, b: Point2) -> Point2 { Point2(a.x - b.x, a.y - b.y) }
    public static func + (a: Point2, b: Point2) -> Point2 { Point2(a.x + b.x, a.y + b.y) }

    public func distance(to other: Point2) -> Double { hypot(x - other.x, y - other.y) }
}

/// 3x3 matrix stored row-major, the same layout RIVeR desktop writes to `transformation.json`
/// (a list of three rows).
public struct Matrix3: Equatable, Codable, Sendable {
    public var m: [Double]

    public init(rowMajor m: [Double]) {
        precondition(m.count == 9, "Matrix3 needs 9 values")
        self.m = m
    }

    public init(rows: [[Double]]) {
        precondition(rows.count == 3 && rows.allSatisfy { $0.count == 3 }, "Matrix3 needs 3 rows of 3 values")
        self.m = rows.flatMap { $0 }
    }

    public static let identity = Matrix3(rowMajor: [1, 0, 0, 0, 1, 0, 0, 0, 1])

    public var rows: [[Double]] { [Array(m[0..<3]), Array(m[3..<6]), Array(m[6..<9])] }

    public subscript(row: Int, col: Int) -> Double {
        get { m[row * 3 + col] }
        set { m[row * 3 + col] = newValue }
    }

    /// Applies the projective transform to a point (homogeneous divide included).
    public func apply(_ p: Point2) -> Point2 {
        let x = m[0] * p.x + m[1] * p.y + m[2]
        let y = m[3] * p.x + m[4] * p.y + m[5]
        let w = m[6] * p.x + m[7] * p.y + m[8]
        return Point2(x / w, y / w)
    }

    public static func * (a: Matrix3, b: Matrix3) -> Matrix3 {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 {
            for j in 0..<3 {
                r[i * 3 + j] = a[i, 0] * b[0, j] + a[i, 1] * b[1, j] + a[i, 2] * b[2, j]
            }
        }
        return Matrix3(rowMajor: r)
    }

    public var determinant: Double {
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    }

    public func inverse() throws -> Matrix3 {
        let det = determinant
        let scale = m.map(abs).max() ?? 0
        guard scale > 0, abs(det) > 1e-14 * scale * scale * scale else { throw RiverCoreError.singularMatrix }
        let c00 = m[4] * m[8] - m[5] * m[7]
        let c01 = m[2] * m[7] - m[1] * m[8]
        let c02 = m[1] * m[5] - m[2] * m[4]
        let c10 = m[5] * m[6] - m[3] * m[8]
        let c11 = m[0] * m[8] - m[2] * m[6]
        let c12 = m[2] * m[3] - m[0] * m[5]
        let c20 = m[3] * m[7] - m[4] * m[6]
        let c21 = m[1] * m[6] - m[0] * m[7]
        let c22 = m[0] * m[4] - m[1] * m[3]
        return Matrix3(rowMajor: [c00, c01, c02, c10, c11, c12, c20, c21, c22].map { $0 / det })
    }

    /// Scales the matrix so that m[2,2] == 1 (the usual normalisation for homographies).
    public func normalized() -> Matrix3 {
        guard m[8] != 0 else { return self }
        return Matrix3(rowMajor: m.map { $0 / m[8] })
    }
}
