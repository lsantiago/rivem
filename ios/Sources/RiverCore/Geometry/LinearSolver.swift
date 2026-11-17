import Foundation

/// Small dense linear algebra helpers (no Accelerate, so the core stays portable).
enum LinearSolver {
    /// Solves the square system `a * x = b` by Gaussian elimination with partial pivoting.
    static func solve(_ a: [[Double]], _ b: [Double]) throws -> [Double] {
        let n = b.count
        precondition(a.count == n && a.allSatisfy { $0.count == n }, "square system expected")
        var aug = a
        var rhs = b
        let scale = a.flatMap { $0 }.map(abs).max() ?? 0
        guard scale > 0 else { throw RiverCoreError.singularMatrix }
        for col in 0..<n {
            var pivot = col
            for row in (col + 1)..<n where abs(aug[row][col]) > abs(aug[pivot][col]) {
                pivot = row
            }
            guard abs(aug[pivot][col]) > 1e-12 * scale else { throw RiverCoreError.singularMatrix }
            if pivot != col {
                aug.swapAt(pivot, col)
                rhs.swapAt(pivot, col)
            }
            for row in (col + 1)..<n {
                let f = aug[row][col] / aug[col][col]
                if f == 0 { continue }
                for k in col..<n { aug[row][k] -= f * aug[col][k] }
                rhs[row] -= f * rhs[col]
            }
        }
        var x = [Double](repeating: 0, count: n)
        for row in stride(from: n - 1, through: 0, by: -1) {
            var s = rhs[row]
            for k in (row + 1)..<n { s -= aug[row][k] * x[k] }
            x[row] = s / aug[row][row]
        }
        return x
    }

    /// Least-squares solution of the overdetermined system `a * x ≈ b` through the normal equations.
    /// Callers must condition the problem first (see `Homography.fit`, which normalises the points).
    static func leastSquares(_ a: [[Double]], _ b: [Double]) throws -> [Double] {
        guard let cols = a.first?.count else { throw RiverCoreError.singularMatrix }
        var ata = [[Double]](repeating: [Double](repeating: 0, count: cols), count: cols)
        var atb = [Double](repeating: 0, count: cols)
        for (row, value) in zip(a, b) {
            for i in 0..<cols {
                atb[i] += row[i] * value
                for j in i..<cols { ata[i][j] += row[i] * row[j] }
            }
        }
        for i in 0..<cols { for j in 0..<i { ata[i][j] = ata[j][i] } }
        return try solve(ata, atb)
    }
}
