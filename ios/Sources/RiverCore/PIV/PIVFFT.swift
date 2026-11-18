import Foundation

/// Complex FFT in pure Swift (split real/imaginary `Float` buffers).
///
/// Power-of-two sizes use an iterative radix-2 transform; any other size goes through Bluestein's chirp-z algorithm
/// (an exact DFT of that size computed with a power-of-two FFT), so results match `scipy.fft` for every size, as
/// upstream's `rfftn` does. Desktop only offers windows of 64–512 px (powers of two), so Bluestein is a fallback.
///
/// Conventions (same as NumPy): forward `X[k] = Σ x[j]·e^(−2πi·jk/n)`; inverse is unnormalised
/// (`Σ X[k]·e^(+2πi·jk/n)`), callers divide by `n`.
///
/// Not thread-safe (owns scratch buffers): use one instance per thread.
public final class PIVFFT {
    public let count: Int
    private let radix: PIVRadix2?
    private let bluestein: PIVBluestein?

    public init(count: Int) {
        precondition(count >= 1, "PIVFFT size must be >= 1")
        self.count = count
        if PIVRadix2.isPowerOfTwo(count) {
            radix = PIVRadix2(count: count)
            bluestein = nil
        } else {
            radix = nil
            bluestein = PIVBluestein(count: count)
        }
    }

    /// In-place transform of `count` complex samples.
    public func transform(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>, inverse: Bool) {
        if let r = radix {
            r.run(re, im, inverse: inverse)
        } else if let b = bluestein {
            b.run(re, im, inverse: inverse)
        }
    }

    /// Array convenience wrapper.
    public func transform(re: inout [Float], im: inout [Float], inverse: Bool) {
        precondition(re.count == count && im.count == count, "PIVFFT buffer size mismatch")
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { i in
                transform(r.baseAddress!, i.baseAddress!, inverse: inverse)
            }
        }
    }
}

/// Square 2-D complex FFT on row-major `size × size` buffers (rows, then columns). Unnormalised inverse.
/// Not thread-safe.
public final class PIVFFT2D {
    public let size: Int
    private let fft: PIVFFT
    private let colRe: UnsafeMutablePointer<Float>
    private let colIm: UnsafeMutablePointer<Float>

    public init(size: Int) {
        self.size = size
        fft = PIVFFT(count: size)
        colRe = UnsafeMutablePointer<Float>.allocate(capacity: size)
        colIm = UnsafeMutablePointer<Float>.allocate(capacity: size)
    }

    deinit {
        colRe.deallocate()
        colIm.deallocate()
    }

    public func transform(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>, inverse: Bool) {
        let n = size
        for r in 0..<n {
            fft.transform(re + r * n, im + r * n, inverse: inverse)
        }
        for c in 0..<n {
            var idx = c
            for r in 0..<n {
                colRe[r] = re[idx]
                colIm[r] = im[idx]
                idx += n
            }
            fft.transform(colRe, colIm, inverse: inverse)
            idx = c
            for r in 0..<n {
                re[idx] = colRe[r]
                im[idx] = colIm[r]
                idx += n
            }
        }
    }

    public func transform(re: inout [Float], im: inout [Float], inverse: Bool) {
        precondition(re.count == size * size && im.count == size * size, "PIVFFT2D buffer size mismatch")
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { i in
                transform(r.baseAddress!, i.baseAddress!, inverse: inverse)
            }
        }
    }
}

/// Iterative in-place radix-2 FFT with precomputed twiddles and bit-reversal table.
final class PIVRadix2 {
    let count: Int
    private let cosT: UnsafeMutablePointer<Float>
    private let sinT: UnsafeMutablePointer<Float>
    private let rev: UnsafeMutablePointer<Int>

    static func isPowerOfTwo(_ n: Int) -> Bool { n > 0 && (n & (n - 1)) == 0 }

    init(count n: Int) {
        precondition(PIVRadix2.isPowerOfTwo(n), "radix-2 size must be a power of two")
        count = n
        let half = max(n / 2, 1)
        cosT = UnsafeMutablePointer<Float>.allocate(capacity: half)
        sinT = UnsafeMutablePointer<Float>.allocate(capacity: half)
        for k in 0..<half {
            let a: Double = 2.0 * Double.pi * Double(k) / Double(n)
            cosT[k] = Float(cos(a))
            sinT[k] = Float(sin(a))
        }
        rev = UnsafeMutablePointer<Int>.allocate(capacity: n)
        var bits = 0
        while (1 << bits) < n { bits += 1 }
        for i in 0..<n {
            var x = i
            var r = 0
            for _ in 0..<bits {
                r = (r << 1) | (x & 1)
                x >>= 1
            }
            rev[i] = r
        }
    }

    deinit {
        cosT.deallocate()
        sinT.deallocate()
        rev.deallocate()
    }

    func run(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>, inverse: Bool) {
        let n = count
        if n < 2 { return }
        for i in 0..<n {
            let j = rev[i]
            if j > i {
                let tr = re[i]
                re[i] = re[j]
                re[j] = tr
                let ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        let sign: Float = inverse ? 1 : -1
        var len = 2
        while len <= n {
            let half = len / 2
            let tstep = n / len
            var start = 0
            while start < n {
                for j in 0..<half {
                    let wr: Float = cosT[j * tstep]
                    let wi: Float = sign * sinT[j * tstep]
                    let a = start + j
                    let b = a + half
                    let br = re[b]
                    let bi = im[b]
                    let tr: Float = br * wr - bi * wi
                    let ti: Float = br * wi + bi * wr
                    let ar = re[a]
                    let ai = im[a]
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

/// Bluestein (chirp-z) DFT of arbitrary size through a power-of-two convolution.
final class PIVBluestein {
    let count: Int
    private let m: Int
    private let radix: PIVRadix2
    private var wRe: [Float]
    private var wIm: [Float]
    private var bRe: [Float]
    private var bIm: [Float]
    private var aRe: [Float]
    private var aIm: [Float]

    init(count n: Int) {
        count = n
        var mm = 1
        while mm < 2 * n - 1 { mm <<= 1 }
        m = mm
        radix = PIVRadix2(count: mm)
        wRe = [Float](repeating: 0, count: n)
        wIm = [Float](repeating: 0, count: n)
        let twoN = 2 * n
        for k in 0..<n {
            // w[k] = exp(-i·π·k²/n), with k² reduced modulo 2n to keep the angle accurate.
            let k2 = (k * k) % twoN
            let a: Double = Double.pi * Double(k2) / Double(n)
            wRe[k] = Float(cos(a))
            wIm[k] = Float(-sin(a))
        }
        bRe = [Float](repeating: 0, count: mm)
        bIm = [Float](repeating: 0, count: mm)
        for k in 0..<n {
            // b[t] = conj(w[t]) for t = -(n-1)...(n-1), stored circularly.
            bRe[k] = wRe[k]
            bIm[k] = -wIm[k]
            if k > 0 {
                bRe[mm - k] = wRe[k]
                bIm[mm - k] = -wIm[k]
            }
        }
        aRe = [Float](repeating: 0, count: mm)
        aIm = [Float](repeating: 0, count: mm)
        let r = radix
        bRe.withUnsafeMutableBufferPointer { br in
            bIm.withUnsafeMutableBufferPointer { bi in
                r.run(br.baseAddress!, bi.baseAddress!, inverse: false)
            }
        }
    }

    func run(_ re: UnsafeMutablePointer<Float>, _ im: UnsafeMutablePointer<Float>, inverse: Bool) {
        let n = count
        let mm = m
        // Inverse DFT = conj(forward(conj(x))).
        let s: Float = inverse ? -1 : 1
        let r = radix
        aRe.withUnsafeMutableBufferPointer { ar in
            aIm.withUnsafeMutableBufferPointer { ai in
                wRe.withUnsafeBufferPointer { wr in
                    wIm.withUnsafeBufferPointer { wi in
                        bRe.withUnsafeBufferPointer { br in
                            bIm.withUnsafeBufferPointer { bi in
                                let pa = ar.baseAddress!
                                let pi = ai.baseAddress!
                                for k in 0..<mm {
                                    pa[k] = 0
                                    pi[k] = 0
                                }
                                for k in 0..<n {
                                    let xr: Float = re[k]
                                    let xi: Float = s * im[k]
                                    pa[k] = xr * wr[k] - xi * wi[k]
                                    pi[k] = xr * wi[k] + xi * wr[k]
                                }
                                r.run(pa, pi, inverse: false)
                                for k in 0..<mm {
                                    let xr: Float = pa[k]
                                    let xi: Float = pi[k]
                                    pa[k] = xr * br[k] - xi * bi[k]
                                    pi[k] = xr * bi[k] + xi * br[k]
                                }
                                r.run(pa, pi, inverse: true)
                                let scale: Float = 1 / Float(mm)
                                for k in 0..<n {
                                    let cr: Float = pa[k] * scale
                                    let ci: Float = pi[k] * scale
                                    re[k] = cr * wr[k] - ci * wi[k]
                                    im[k] = s * (cr * wi[k] + ci * wr[k])
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/// FFT cross-correlation of interrogation windows, as upstream's `compute_convolution`:
/// `corr = fftshift(irfft2(conj(FFT(a)) · FFT(b)))`, i.e. `corr[r][c] = Σ a[y][x] · b[y + dy][x + dx]` (circular)
/// with lag `(dy, dx) = (r − n/2, c − n/2)` for even `n`. No mean subtraction or normalisation (as upstream).
///
/// Two real windows are packed into one complex FFT (`a + i·b`), and two correlation planes are recovered from one
/// inverse FFT (real and imaginary parts), so a window costs 1.5 complex 2-D FFTs instead of 3. Not thread-safe.
public final class PIVCorrelator {
    public let size: Int
    private let fft: PIVFFT2D
    private let z1Re: UnsafeMutablePointer<Float>
    private let z1Im: UnsafeMutablePointer<Float>
    private let z2Re: UnsafeMutablePointer<Float>
    private let z2Im: UnsafeMutablePointer<Float>

    public init(size: Int) {
        self.size = size
        fft = PIVFFT2D(size: size)
        let nn = size * size
        z1Re = UnsafeMutablePointer<Float>.allocate(capacity: nn)
        z1Im = UnsafeMutablePointer<Float>.allocate(capacity: nn)
        z2Re = UnsafeMutablePointer<Float>.allocate(capacity: nn)
        z2Im = UnsafeMutablePointer<Float>.allocate(capacity: nn)
    }

    deinit {
        z1Re.deallocate()
        z1Im.deallocate()
        z2Re.deallocate()
        z2Im.deallocate()
    }

    /// Correlates (a1, b1) into `out1` and, if given, (a2, b2) into `out2`. All buffers are `size × size` row-major.
    public func correlate(a1: UnsafePointer<Float>, b1: UnsafePointer<Float>, out1: UnsafeMutablePointer<Float>,
                          a2: UnsafePointer<Float>?, b2: UnsafePointer<Float>?, out2: UnsafeMutablePointer<Float>?) {
        let n = size
        let nn = n * n
        for k in 0..<nn {
            z1Re[k] = a1[k]
            z1Im[k] = b1[k]
        }
        fft.transform(z1Re, z1Im, inverse: false)
        let hasSecond = a2 != nil && b2 != nil && out2 != nil
        if hasSecond, let a2p = a2, let b2p = b2 {
            for k in 0..<nn {
                z2Re[k] = a2p[k]
                z2Im[k] = b2p[k]
            }
            fft.transform(z2Re, z2Im, inverse: false)
        }
        // P = conj(A)·B with A = (Z[k] + conj(Z[-k]))/2, B = (Z[k] − conj(Z[-k]))/(2i):
        // with S = Z + W, D = Z − W (W = conj(Z[-k])): P = ((Sr·Di − Si·Dr) − i(Sr·Dr + Si·Di)) / 4.
        // Then Q = P1 + i·P2. Q is written in place of z1 (each k reads k and -k: process pairs together).
        for r in 0..<n {
            let rn = (n - r) % n
            for c in 0..<n {
                let cn = (n - c) % n
                let k = r * n + c
                let kn = rn * n + cn
                if kn < k { continue }
                // Values at k and kn before overwriting.
                let zkr = z1Re[k], zki = z1Im[k], znr = z1Re[kn], zni = z1Im[kn]
                let p1k = PIVCorrelator.product(zr: zkr, zi: zki, wr: znr, wi: -zni)
                let p1n = PIVCorrelator.product(zr: znr, zi: zni, wr: zkr, wi: -zki)
                var qkr: Float = p1k.0, qki: Float = p1k.1
                var qnr: Float = p1n.0, qni: Float = p1n.1
                if hasSecond {
                    let p2k = PIVCorrelator.product(zr: z2Re[k], zi: z2Im[k], wr: z2Re[kn], wi: -z2Im[kn])
                    let p2n = PIVCorrelator.product(zr: z2Re[kn], zi: z2Im[kn], wr: z2Re[k], wi: -z2Im[k])
                    qkr -= p2k.1
                    qki += p2k.0
                    qnr -= p2n.1
                    qni += p2n.0
                }
                z1Re[k] = qkr
                z1Im[k] = qki
                z1Re[kn] = qnr
                z1Im[kn] = qni
            }
        }
        fft.transform(z1Re, z1Im, inverse: true)
        let scale: Float = 1 / Float(nn)
        let half = n / 2
        for r in 0..<n {
            // fftshift: out[i] = corr[(i − n/2) mod n]
            let sr = (r - half + n) % n
            for c in 0..<n {
                let sc = (c - half + n) % n
                let src = sr * n + sc
                out1[r * n + c] = z1Re[src] * scale
                if hasSecond, let o2 = out2 {
                    o2[r * n + c] = z1Im[src] * scale
                }
            }
        }
    }

    /// conj(A)·B for one frequency, given Z = Z[k] and W = conj(Z[-k]).
    @inline(__always)
    private static func product(zr: Float, zi: Float, wr: Float, wi: Float) -> (Float, Float) {
        let sr: Float = zr + wr
        let si: Float = zi + wi
        let dr: Float = zr - wr
        let di: Float = zi - wi
        let pr: Float = (sr * di - si * dr) * 0.25
        let pi: Float = -(sr * dr + si * di) * 0.25
        return (pr, pi)
    }
}
