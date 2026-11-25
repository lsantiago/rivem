package org.rivem.core.piv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Complex FFT in pure Kotlin (split real/imaginary `Float` buffers).
 *
 * Power-of-two sizes use an iterative radix-2 transform; any other size goes through Bluestein's chirp-z algorithm
 * (an exact DFT of that size computed with a power-of-two FFT), so results match `scipy.fft` for every size, as
 * upstream's `rfftn` does. Desktop only offers windows of 64–512 px (powers of two), so Bluestein is a fallback.
 *
 * Conventions (same as NumPy): forward `X[k] = Σ x[j]·e^(−2πi·jk/n)`; inverse is unnormalised
 * (`Σ X[k]·e^(+2πi·jk/n)`), callers divide by `n`.
 *
 * Not thread-safe (owns scratch buffers): use one instance per thread.
 */
class PIVFFT(val count: Int) {
    private val radix: PIVRadix2?
    private val bluestein: PIVBluestein?

    init {
        require(count >= 1) { "PIVFFT size must be >= 1" }
        if (PIVRadix2.isPowerOfTwo(count)) {
            radix = PIVRadix2(count)
            bluestein = null
        } else {
            radix = null
            bluestein = PIVBluestein(count)
        }
    }

    /** In-place transform of `count` complex samples starting at `offset` (Swift's pointer variant). */
    fun transform(re: FloatArray, im: FloatArray, offset: Int, inverse: Boolean) {
        val r = radix
        if (r != null) {
            r.run(re, im, offset, inverse)
        } else {
            bluestein?.run(re, im, offset, inverse)
        }
    }

    /** Whole-array convenience wrapper (arrays of exactly `count` samples). */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size == count && im.size == count) { "PIVFFT buffer size mismatch" }
        transform(re, im, 0, inverse)
    }
}

/** Square 2-D complex FFT on row-major `size × size` buffers (rows, then columns). Unnormalised inverse.
 *  Not thread-safe. */
class PIVFFT2D(val size: Int) {
    private val fft = PIVFFT(size)
    private val colRe = FloatArray(size)
    private val colIm = FloatArray(size)

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size >= size * size && im.size >= size * size) { "PIVFFT2D buffer size mismatch" }
        val n = size
        for (r in 0 until n) {
            fft.transform(re, im, r * n, inverse)
        }
        val colRe = colRe
        val colIm = colIm
        for (c in 0 until n) {
            var idx = c
            for (r in 0 until n) {
                colRe[r] = re[idx]
                colIm[r] = im[idx]
                idx += n
            }
            fft.transform(colRe, colIm, 0, inverse)
            idx = c
            for (r in 0 until n) {
                re[idx] = colRe[r]
                im[idx] = colIm[r]
                idx += n
            }
        }
    }
}

/** Iterative in-place radix-2 FFT with precomputed twiddles and bit-reversal table. */
internal class PIVRadix2(val count: Int) {
    private val cosT: FloatArray
    private val sinT: FloatArray
    private val rev: IntArray

    companion object {
        fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
    }

    init {
        require(isPowerOfTwo(count)) { "radix-2 size must be a power of two" }
        val n = count
        val half = max(n / 2, 1)
        cosT = FloatArray(half)
        sinT = FloatArray(half)
        for (k in 0 until half) {
            val a: Double = 2.0 * PI * k.toDouble() / n.toDouble()
            cosT[k] = cos(a).toFloat()
            sinT[k] = sin(a).toFloat()
        }
        rev = IntArray(n)
        var bits = 0
        while ((1 shl bits) < n) bits += 1
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun run(re: FloatArray, im: FloatArray, off: Int, inverse: Boolean) {
        val n = count
        if (n < 2) return
        val rev = rev
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[off + i]
                re[off + i] = re[off + j]
                re[off + j] = tr
                val ti = im[off + i]
                im[off + i] = im[off + j]
                im[off + j] = ti
            }
        }
        val cosT = cosT
        val sinT = sinT
        val sign: Float = if (inverse) 1f else -1f
        var len = 2
        while (len <= n) {
            val half = len / 2
            val tstep = n / len
            var start = 0
            while (start < n) {
                for (j in 0 until half) {
                    val wr: Float = cosT[j * tstep]
                    val wi: Float = sign * sinT[j * tstep]
                    val a = off + start + j
                    val b = a + half
                    val br = re[b]
                    val bi = im[b]
                    val tr: Float = br * wr - bi * wi
                    val ti: Float = br * wi + bi * wr
                    val ar = re[a]
                    val ai = im[a]
                    re[b] = ar - tr
                    im[b] = ai - ti
                    re[a] = ar + tr
                    im[a] = ai + ti
                }
                start += len
            }
            len *= 2
        }
    }
}

/** Bluestein (chirp-z) DFT of arbitrary size through a power-of-two convolution. */
internal class PIVBluestein(val count: Int) {
    private val m: Int
    private val radix: PIVRadix2
    private val wRe: FloatArray
    private val wIm: FloatArray
    private val bRe: FloatArray
    private val bIm: FloatArray
    private val aRe: FloatArray
    private val aIm: FloatArray

    init {
        val n = count
        var mm = 1
        while (mm < 2 * n - 1) mm = mm shl 1
        m = mm
        radix = PIVRadix2(mm)
        wRe = FloatArray(n)
        wIm = FloatArray(n)
        val twoN = 2 * n
        for (k in 0 until n) {
            // w[k] = exp(-i·π·k²/n), with k² reduced modulo 2n to keep the angle accurate.
            val k2 = (k.toLong() * k.toLong() % twoN.toLong()).toInt()
            val a: Double = PI * k2.toDouble() / n.toDouble()
            wRe[k] = cos(a).toFloat()
            wIm[k] = (-sin(a)).toFloat()
        }
        bRe = FloatArray(mm)
        bIm = FloatArray(mm)
        for (k in 0 until n) {
            // b[t] = conj(w[t]) for t = -(n-1)...(n-1), stored circularly.
            bRe[k] = wRe[k]
            bIm[k] = -wIm[k]
            if (k > 0) {
                bRe[mm - k] = wRe[k]
                bIm[mm - k] = -wIm[k]
            }
        }
        aRe = FloatArray(mm)
        aIm = FloatArray(mm)
        radix.run(bRe, bIm, 0, false)
    }

    fun run(re: FloatArray, im: FloatArray, off: Int, inverse: Boolean) {
        val n = count
        val mm = m
        // Inverse DFT = conj(forward(conj(x))).
        val s: Float = if (inverse) -1f else 1f
        val pa = aRe
        val pi = aIm
        val wr = wRe
        val wi = wIm
        val br = bRe
        val bi = bIm
        for (k in 0 until mm) {
            pa[k] = 0f
            pi[k] = 0f
        }
        for (k in 0 until n) {
            val xr: Float = re[off + k]
            val xi: Float = s * im[off + k]
            pa[k] = xr * wr[k] - xi * wi[k]
            pi[k] = xr * wi[k] + xi * wr[k]
        }
        radix.run(pa, pi, 0, false)
        for (k in 0 until mm) {
            val xr: Float = pa[k]
            val xi: Float = pi[k]
            pa[k] = xr * br[k] - xi * bi[k]
            pi[k] = xr * bi[k] + xi * br[k]
        }
        radix.run(pa, pi, 0, true)
        val scale: Float = 1f / mm.toFloat()
        for (k in 0 until n) {
            val cr: Float = pa[k] * scale
            val ci: Float = pi[k] * scale
            re[off + k] = cr * wr[k] - ci * wi[k]
            im[off + k] = s * (cr * wi[k] + ci * wr[k])
        }
    }
}

/**
 * FFT cross-correlation of interrogation windows, as upstream's `compute_convolution`:
 * `corr = fftshift(irfft2(conj(FFT(a)) · FFT(b)))`, i.e. `corr[r][c] = Σ a[y][x] · b[y + dy][x + dx]` (circular)
 * with lag `(dy, dx) = (r − n/2, c − n/2)` for even `n`. No mean subtraction or normalisation (as upstream).
 *
 * Two real windows are packed into one complex FFT (`a + i·b`), and two correlation planes are recovered from one
 * inverse FFT (real and imaginary parts), so a window costs 1.5 complex 2-D FFTs instead of 3. Not thread-safe.
 */
class PIVCorrelator(val size: Int) {
    private val fft = PIVFFT2D(size)
    private val z1Re = FloatArray(size * size)
    private val z1Im = FloatArray(size * size)
    private val z2Re = FloatArray(size * size)
    private val z2Im = FloatArray(size * size)

    /** Correlates (a1, b1) into `out1` and, if given, (a2, b2) into `out2`. All buffers are `size × size` row-major. */
    fun correlate(
        a1: FloatArray, b1: FloatArray, out1: FloatArray,
        a2: FloatArray? = null, b2: FloatArray? = null, out2: FloatArray? = null,
    ) {
        val n = size
        val nn = n * n
        val z1Re = z1Re
        val z1Im = z1Im
        val z2Re = z2Re
        val z2Im = z2Im
        System.arraycopy(a1, 0, z1Re, 0, nn)
        System.arraycopy(b1, 0, z1Im, 0, nn)
        fft.transform(z1Re, z1Im, false)
        val second: FloatArray? = if (a2 != null && b2 != null && out2 != null) out2 else null
        val hasSecond = second != null
        if (a2 != null && b2 != null && second != null) {
            System.arraycopy(a2, 0, z2Re, 0, nn)
            System.arraycopy(b2, 0, z2Im, 0, nn)
            fft.transform(z2Re, z2Im, false)
        }
        // P = conj(A)·B with A = (Z[k] + conj(Z[-k]))/2, B = (Z[k] − conj(Z[-k]))/(2i):
        // with S = Z + W, D = Z − W (W = conj(Z[-k])): P = ((Sr·Di − Si·Dr) − i(Sr·Dr + Si·Di)) / 4.
        // Then Q = P1 + i·P2. Q is written in place of z1 (each k reads k and -k: process pairs together).
        for (r in 0 until n) {
            val rn = (n - r) % n
            for (c in 0 until n) {
                val cn = (n - c) % n
                val k = r * n + c
                val kn = rn * n + cn
                if (kn < k) continue
                // Values at k and kn before overwriting.
                val zkr = z1Re[k]
                val zki = z1Im[k]
                val znr = z1Re[kn]
                val zni = z1Im[kn]
                var qkr: Float = productRe(zkr, zki, znr, -zni)
                var qki: Float = productIm(zkr, zki, znr, -zni)
                var qnr: Float = productRe(znr, zni, zkr, -zki)
                var qni: Float = productIm(znr, zni, zkr, -zki)
                if (hasSecond) {
                    val ykr = z2Re[k]
                    val yki = z2Im[k]
                    val ynr = z2Re[kn]
                    val yni = z2Im[kn]
                    qkr -= productIm(ykr, yki, ynr, -yni)
                    qki += productRe(ykr, yki, ynr, -yni)
                    qnr -= productIm(ynr, yni, ykr, -yki)
                    qni += productRe(ynr, yni, ykr, -yki)
                }
                z1Re[k] = qkr
                z1Im[k] = qki
                z1Re[kn] = qnr
                z1Im[kn] = qni
            }
        }
        fft.transform(z1Re, z1Im, true)
        val scale: Float = 1f / nn.toFloat()
        val half = n / 2
        for (r in 0 until n) {
            // fftshift: out[i] = corr[(i − n/2) mod n]
            val sr = (r - half + n) % n
            for (c in 0 until n) {
                val sc = (c - half + n) % n
                val src = sr * n + sc
                out1[r * n + c] = z1Re[src] * scale
                if (second != null) second[r * n + c] = z1Im[src] * scale
            }
        }
    }

    private companion object {
        // conj(A)·B for one frequency, given Z = Z[k] and W = conj(Z[-k]) (Swift returns both parts as a tuple).
        @Suppress("NOTHING_TO_INLINE")
        inline fun productRe(zr: Float, zi: Float, wr: Float, wi: Float): Float {
            val sr: Float = zr + wr
            val si: Float = zi + wi
            val dr: Float = zr - wr
            val di: Float = zi - wi
            return (sr * di - si * dr) * 0.25f
        }

        @Suppress("NOTHING_TO_INLINE")
        inline fun productIm(zr: Float, zi: Float, wr: Float, wi: Float): Float {
            val sr: Float = zr + wr
            val si: Float = zi + wi
            val dr: Float = zr - wr
            val di: Float = zi - wi
            return -(sr * dr + si * di) * 0.25f
        }
    }
}
