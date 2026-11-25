package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.hypot

/**
 * A 2-D point. In pixel space `x` grows to the right and `y` grows downwards (image rows), origin at the top-left
 * corner of the frame. In real-world space `x` is East and `y` is North (y grows upwards).
 */
data class Point2(val x: Double, val y: Double) {
    operator fun minus(b: Point2): Point2 = Point2(x - b.x, y - b.y)
    operator fun plus(b: Point2): Point2 = Point2(x + b.x, y + b.y)
    fun distanceTo(other: Point2): Double = hypot(x - other.x, y - other.y)
}

/**
 * 3x3 matrix stored row-major, the same layout RIVeR desktop writes to `transformation.json` (a list of three
 * rows).
 */
class Matrix3(m: List<Double>) {
    val m: DoubleArray = m.toDoubleArray()

    init {
        require(this.m.size == 9) { "Matrix3 needs 9 values" }
    }

    companion object {
        fun rowMajor(vararg v: Double): Matrix3 = Matrix3(v.toList())

        fun fromRows(rows: List<List<Double>>): Matrix3 {
            require(rows.size == 3 && rows.all { it.size == 3 }) { "Matrix3 needs 3 rows of 3 values" }
            return Matrix3(rows.flatten())
        }

        val IDENTITY: Matrix3 get() = rowMajor(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }

    val rows: List<List<Double>> get() = listOf(m.slice(0..2), m.slice(3..5), m.slice(6..8))

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun set(row: Int, col: Int, value: Double) {
        m[row * 3 + col] = value
    }

    /** Applies the projective transform to a point (homogeneous divide included). */
    fun apply(p: Point2): Point2 {
        val x = m[0] * p.x + m[1] * p.y + m[2]
        val y = m[3] * p.x + m[4] * p.y + m[5]
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Point2(x / w, y / w)
    }

    operator fun times(b: Matrix3): Matrix3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                r[i * 3 + j] = this[i, 0] * b[0, j] + this[i, 1] * b[1, j] + this[i, 2] * b[2, j]
            }
        }
        return Matrix3(r.toList())
    }

    val determinant: Double
        get() = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /**
     * No threshold relative to the largest element: georeferencing matrices mix ~1e-5 terms with UTM translations
     * of ~1e7, so such a test rejects perfectly invertible matrices. Exact singularity or a non-finite result is
     * what we reject.
     */
    fun inverse(): Matrix3 {
        val det = determinant
        if (det == 0.0 || !det.isFinite()) throw RiverCoreError.SingularMatrix()
        val c = listOf(
            m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        ).map { it / det }
        if (!c.all { it.isFinite() }) throw RiverCoreError.SingularMatrix()
        return Matrix3(c)
    }

    /** Scales the matrix so that m[2,2] == 1 (the usual normalisation for homographies). */
    fun normalized(): Matrix3 = if (m[8] == 0.0) this else Matrix3(m.map { it / m[8] })

    override fun equals(other: Any?): Boolean = other is Matrix3 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = "Matrix3(${m.joinToString()})"
}
