package org.rivem.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rivem.core.imaging.GrayImage
import org.rivem.core.piv.PIVBackgroundAccumulator
import org.rivem.core.piv.PIVCorrelator
import org.rivem.core.piv.PIVError
import org.rivem.core.piv.PIVFFT
import org.rivem.core.piv.PIVFFT2D
import org.rivem.core.piv.PIVFFTMulti
import org.rivem.core.piv.PIVMask
import org.rivem.core.piv.PIVParameters
import org.rivem.core.piv.PIVPreprocessing
import org.rivem.core.piv.PIVSequence
import org.rivem.core.project.JSON
import org.rivem.core.project.JSONObject
import org.rivem.core.project.JSONValue
import java.util.Base64
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Twin of `ios/Tests/RiverCoreTests/PIVTests.swift`: `PIVFFTTests` (prefix `fft`), `PIVPreprocessingTests`
 * (`preprocessing`), `PIVAlgorithmTests` (`algorithm`) and `PIVGoldenTests` (`golden`).
 */
class PIVTests {
    /** Deterministic RNG for the synthetic images (SplitMix64). */
    private class PIVTestRNG(private var state: Long) {
        fun next(): Long {
            state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }

        fun uniform(lo: Double, hi: Double): Double {
            val u = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
            return lo + (hi - lo) * u
        }
    }

    /** Speckle image of Gaussian particles (σ = 1.2 px) displaced by (dx, dy), clipped to 0…255. */
    private fun renderSpeckle(width: Int, height: Int, particles: Int, seed: Long, dx: Double, dy: Double): GrayImage {
        val rng = PIVTestRNG(seed)
        val px = DoubleArray(width * height)
        val s2 = 2.0 * 1.2 * 1.2
        for (p in 0 until particles) {
            val x = rng.uniform(-10.0, width.toDouble() + 10) + dx
            val y = rng.uniform(-10.0, height.toDouble() + 10) + dy
            val a = rng.uniform(80.0, 160.0)
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()
            val y0 = max(0, yi - 4)
            val y1 = min(height - 1, yi + 4)
            val x0 = max(0, xi - 4)
            val x1 = min(width - 1, xi + 4)
            if (y0 > y1 || x0 > x1) continue
            for (yy in y0..y1) {
                for (xx in x0..x1) {
                    val ddx = xx.toDouble() - x
                    val ddy = yy.toDouble() - y
                    px[yy * width + xx] += a * exp(-(ddx * ddx + ddy * ddy) / s2)
                }
            }
        }
        val out = FloatArray(px.size) { min(255.0, px[it]).toFloat() }
        return GrayImage(width, height, out)
    }

    private fun median(values: List<Double>): Double {
        val s = values.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return Double.NaN
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun median(values: DoubleArray): Double = median(values.toList())

    private fun percentile(values: List<Double>, p: Double): Double {
        val s = values.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return Double.NaN
        // Swift `.rounded()` = half away from zero (non-negative here).
        val idx = floor(p * (s.size - 1).toDouble() + 0.5).toInt()
        return s[min(max(idx, 0), s.size - 1)]
    }

    // region PIVFFTTests

    private fun naiveDFT(re: FloatArray, im: FloatArray, inverse: Boolean): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outR = DoubleArray(n)
        val outI = DoubleArray(n)
        val sign: Double = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (j in 0 until n) {
                val a = sign * 2 * PI * ((j * k) % n).toDouble() / n.toDouble()
                sr += re[j].toDouble() * cos(a) - im[j].toDouble() * sin(a)
                si += re[j].toDouble() * sin(a) + im[j].toDouble() * cos(a)
            }
            outR[k] = sr
            outI[k] = si
        }
        return Pair(outR, outI)
    }

    @Test
    fun fftMatchesNaiveDFT() {
        val rng = PIVTestRNG(7)
        for (n in listOf(1, 2, 8, 64, 7, 12, 96)) {
            val re = FloatArray(n) { rng.uniform(-1.0, 1.0).toFloat() }
            val im = FloatArray(n) { rng.uniform(-1.0, 1.0).toFloat() }
            val ref = naiveDFT(re, im, inverse = false)
            val refInv = naiveDFT(re, im, inverse = true)
            val fft = PIVFFT(n)
            val r2 = re.copyOf()
            val i2 = im.copyOf()
            fft.transform(re, im, inverse = false)
            fft.transform(r2, i2, inverse = true)
            val tol = 1e-4 * max(n, 4).toDouble()
            for (k in 0 until n) {
                assertEquals("n=$n k=$k", ref.first[k], re[k].toDouble(), tol)
                assertEquals("n=$n k=$k", ref.second[k], im[k].toDouble(), tol)
                assertEquals("inverse n=$n k=$k", refInv.first[k], r2[k].toDouble(), tol)
                assertEquals("inverse n=$n k=$k", refInv.second[k], i2[k].toDouble(), tol)
            }
        }
    }

    @Test
    fun fft2DRoundTrip() {
        val rng = PIVTestRNG(11)
        for (n in listOf(16, 12)) {
            val re0 = FloatArray(n * n) { rng.uniform(0.0, 255.0).toFloat() }
            val re = re0.copyOf()
            val im = FloatArray(n * n)
            val fft = PIVFFT2D(n)
            fft.transform(re, im, inverse = false)
            fft.transform(re, im, inverse = true)
            for (k in 0 until n * n) {
                assertEquals(re0[k], re[k] / (n * n).toFloat(), 1e-2f)
                assertEquals(0f, im[k] / (n * n).toFloat(), 1e-2f)
            }
        }
    }

    /** The packed two-window correlation equals the direct circular cross-correlation with upstream's fftshift. */
    @Test
    fun fftCorrelatorMatchesDirectCorrelation() {
        val rng = PIVTestRNG(3)
        val n = 16
        val w = List(4) { FloatArray(n * n) { rng.uniform(0.0, 1.0).toFloat() } }
        val o1 = FloatArray(n * n)
        val o2 = FloatArray(n * n)
        val corr = PIVCorrelator(n)
        corr.correlate(a1 = w[0], b1 = w[1], out1 = o1, a2 = w[2], b2 = w[3], out2 = o2)
        fun direct(a: FloatArray, b: FloatArray, dy: Int, dx: Int): Double {
            var s = 0.0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    s += a[y * n + x].toDouble() * b[((y + dy + n) % n) * n + (x + dx + n) % n].toDouble()
                }
            }
            return s
        }
        for (r in 0 until n) {
            for (c in 0 until n) {
                val dy = r - n / 2
                val dx = c - n / 2
                assertEquals(direct(w[0], w[1], dy, dx), o1[r * n + c].toDouble(), 1e-3)
                assertEquals(direct(w[2], w[3], dy, dx), o2[r * n + c].toDouble(), 1e-3)
            }
        }
    }

    // endregion

    // region PIVPreprocessingTests

    @Test
    fun preprocessingCLAHEUniformImageStaysUniform() {
        val px = ByteArray(120 * 90) { 100 }
        val out = PIVPreprocessing.clahe(px, width = 120, height = 90, clipLimit = 5.0)
        assertEquals(px.size, out.size)
        assertEquals("a uniform image must map to a single value", 1, out.toSet().size)
    }

    @Test
    fun preprocessingCLAHEBoundedAndMonotonicWithinTile() {
        val rng = PIVTestRNG(5)
        val w = 101
        val h = 77
        val px = ByteArray(w * h) { rng.uniform(0.0, 255.99).toInt().toByte() }
        val out = PIVPreprocessing.clahe(px, width = w, height = h, clipLimit = 2.5)
        assertEquals(px.size, out.size)
        assertTrue(out.any { (it.toInt() and 0xFF) > 0 })
        // Same input value at the same pixel position mapping is a monotone LUT: compare the brightest and darkest.
        val unsigned = px.map { it.toInt() and 0xFF }
        val iMax = unsigned.indexOf(unsigned.maxOrNull()!!)
        val iMin = unsigned.indexOf(unsigned.minOrNull()!!)
        assertTrue((out[iMax].toInt() and 0xFF) >= (out[iMin].toInt() and 0xFF))
    }

    @Test
    fun preprocessingBackgroundIsTruncatedMeanAndSubtractionSaturates() {
        val acc = PIVBackgroundAccumulator(width = 2, height = 1)
        acc.add(GrayImage(2, 1, floatArrayOf(10f, 200f)))
        acc.add(GrayImage(2, 1, floatArrayOf(11f, 255f)))
        val bg = acc.background()!!
        assertTrue(bg.pixels.contentEquals(floatArrayOf(10f, 227f))) // 10.5 → 10, 227.5 → 227
        val px = byteArrayOf(5, 250.toByte())
        PIVPreprocessing.subtractBackground(px, background = byteArrayOf(10, 227.toByte()))
        assertTrue(px.contentEquals(byteArrayOf(0, 23)))
    }

    @Test
    fun preprocessingParametersFromSettings() {
        val o = JSONObject(
            "interrogation_area_1" to JSONValue.int(64), "interrogation_area_2" to JSONValue.int(64),
            "clahe" to JSONValue.bool(false), "clip_limit" to JSONValue.double(3.5),
            "median_test_threshold" to JSONValue.int(3),
        )
        val p = PIVParameters(processing = o)
        assertEquals(64, p.interrogationArea1)
        assertNull(p.interrogationArea2)
        assertEquals(32, p.effectiveInterrogationArea2) // desktop passes ia1/2
        assertFalse(p.clahe)
        assertEquals(3.5, p.clipLimit, 0.0)
        assertEquals(3.0, p.medianTestThreshold, 0.0)
        assertEquals(64, PIVParameters().effectiveInterrogationArea2)
        assertEquals(50, PIVParameters(interrogationArea1 = 128, interrogationArea2 = 50).effectiveInterrogationArea2)
        assertThrows(PIVError::class.java) { PIVParameters(interrogationArea1 = 63).validate() }
    }

    // endregion

    // region PIVAlgorithmTests

    private val shiftDx = 2.3
    private val shiftDy = -1.7

    private fun syntheticPair(): Pair<GrayImage, GrayImage> {
        val a = renderSpeckle(256, 256, particles = 1800, seed = 42, dx = 0.0, dy = 0.0)
        val b = renderSpeckle(256, 256, particles = 1800, seed = 42, dx = shiftDx, dy = shiftDy)
        return Pair(a, b)
    }

    /** F3 acceptance: a known sub-pixel displacement is recovered within 0.05 px (median of the field). */
    @Test
    fun algorithmSyntheticSubpixelShiftIsRecovered() {
        val (a, b) = syntheticPair()
        val p = PIVParameters(interrogationArea1 = 64, clahe = false)
        val f = PIVFFTMulti.run(a, b, p)
        assertEquals(15, f.rows)
        assertEquals(15, f.columns)
        assertEquals(shiftDx, median(f.u), 0.05)
        assertEquals(shiftDy, median(f.v), 0.05)
    }

    /** PLAN.md #3: `multipass = false` really stops after pass 1 (pass-1 grid, 64-px windows every 32 px). */
    @Test
    fun algorithmMultipassFalseRunsOnePass() {
        val (a, b) = syntheticPair()
        val p = PIVParameters(interrogationArea1 = 64, clahe = false, multipass = false)
        val f = PIVFFTMulti.run(a, b, p)
        assertEquals(7, f.rows)
        assertEquals(7, f.columns)
        assertEquals(32.0, f.x[1] - f.x[0], 0.0)
        val grid = PIVFFTMulti.outputGrid(256, 256, p)
        assertEquals(7, grid.rows)
        assertTrue(grid.x.contentEquals(f.x))
        // One pass with 64-px windows is less precise, but must be in the right place.
        assertEquals(shiftDx, median(f.u), 0.15)
        assertEquals(shiftDy, median(f.v), 0.15)
    }

    @Test
    fun algorithmZeroDisplacementOfIdenticalFramesIsNotForcedAway() {
        // mask_auto attenuates the zero-lag peak in pass 1 (as upstream): a large displacement must still win.
        val a = renderSpeckle(128, 128, particles = 500, seed = 9, dx = 0.0, dy = 0.0)
        val b = renderSpeckle(128, 128, particles = 500, seed = 9, dx = 5.0, dy = 3.0)
        val f = PIVFFTMulti.run(a, b, PIVParameters(interrogationArea1 = 64, clahe = false))
        assertEquals(5.0, median(f.u), 0.1)
        assertEquals(3.0, median(f.v), 0.1)
    }

    @Test
    fun algorithmMaskSetsVectorsToNaN() {
        val (a, b) = syntheticPair()
        val values = ByteArray(256 * 256) { 1 }
        for (y in 0 until 256) for (x in 0 until 128) values[y * 256 + x] = 0
        val mask = PIVMask(256, 256, values)
        val f = PIVFFTMulti.run(a, b, PIVParameters(interrogationArea1 = 64, clahe = false), mask = mask)
        for (k in f.u.indices) {
            if (f.x[k] < 128) {
                assertTrue(f.u[k].isNaN() && f.v[k].isNaN())
            } else {
                assertFalse(f.u[k].isNaN())
            }
        }
        assertTrue(f.gradient[0].isNaN()) // upstream quirk kept for parity
    }

    private class Unreadable : Exception()

    /** PLAN.md #5/#11: an unreadable frame turns its two pairs into NaN and lists them; the run goes on. */
    @Test
    fun algorithmFailedPairIsNaNAndListed() {
        val frames = List(4) { i ->
            renderSpeckle(256, 256, particles = 1800, seed = 77, dx = i.toDouble() * 1.5, dy = i.toDouble() * 0.5)
        }
        val provider: (Int) -> GrayImage = { i ->
            if (i == 2) throw Unreadable()
            frames[i]
        }
        val p = PIVParameters(interrogationArea1 = 64)
        var progressCalls = 0
        val seq = PIVSequence.run(frameCount = 4, frame = provider, parameters = p,
                                  progress = { _, _ -> progressCalls += 1 })
        assertEquals(listOf(1, 2), seq.failedPairs.map { it.index })
        assertEquals(3, progressCalls)
        val r = seq.results
        assertEquals(3, r.pairCount)
        assertTrue(r.u[1].all { it.isNaN() })
        assertTrue(r.v[2].all { it.isNaN() })
        assertTrue(r.gradient[1].all { it.isNaN() })
        assertTrue(r.u[0].any { !it.isNaN() })
        // Medians come from the good pair only.
        for (k in r.uMedian.indices) {
            if (!r.u[0][k].isNaN()) assertEquals(r.u[0][k], r.uMedian[k], 0.0)
        }
        assertEquals(1.5, median(r.u[0]), 0.25)
        assertEquals(0.5, median(r.v[0]), 0.25)

        // Parallel run: identical result (NaN-aware comparison).
        val par = PIVSequence.run(frameCount = 4, frame = provider, parameters = p, parallel = true)
        assertEquals(listOf(1, 2), par.failedPairs.map { it.index })
        fun same(a: List<List<Double>>, b: List<List<Double>>): Boolean {
            if (a.size != b.size) return false
            for ((pp, q) in a.zip(b)) {
                if (pp.size != q.size) return false
                for (k in pp.indices) if (!(pp[k] == q[k] || (pp[k].isNaN() && q[k].isNaN()))) return false
            }
            return true
        }
        assertTrue(same(par.results.u, r.u))
        assertTrue(same(par.results.v, r.v))
        assertTrue(same(par.results.gradient, r.gradient))
        assertTrue(same(listOf(par.results.uMedian), listOf(r.uMedian)))
    }

    @Test
    fun algorithmCancellationThrows() {
        val frames = List(3) { i -> renderSpeckle(64, 64, particles = 120, seed = 5, dx = i.toDouble(), dy = 0.0) }
        val error = assertThrows(PIVError::class.java) {
            PIVSequence.run(frames = frames, parameters = PIVParameters(interrogationArea1 = 32), isCancelled = { true })
        }
        assertEquals(PIVError.Cancelled(), error)
    }

    // endregion

    // region PIVGoldenTests

    /*
     * Comparisons against RIVeR 3.8.3 run in CI by tools/golden/piv.py (ci/testdata/golden/piv/). Tolerances and
     * their rationale: see the Swift `PIVGoldenTests` (median |Δ| < 0.1 px, p95 < 0.5 px — 1.0 px for `background` —,
     * NaN pattern differing in at most 5 % of the nodes, exact grid, gradient p95 relative error < 0.1; CLAHE exact).
     */

    private fun goldenFile(name: String): java.io.File {
        val f = TestData.file("golden/piv/$name")
        assumeTrue("Falta $name: generar con tools/golden/run_all.py (workflow golden.yml)", f.isFile)
        return f
    }

    private fun bytes(v: JSONValue?): ByteArray {
        val s = v?.stringValue
        val d = try {
            s?.let { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            null
        }
        assumeTrue("base64 no válido en la referencia", d != null)
        return d!!
    }

    private class Frames(val width: Int, val height: Int, val frames: List<ByteArray>)

    private fun loadFrames(): Frames {
        val o = JSON.read(goldenFile("canuelas_frames.json")).objectValue
        val w = o?.get("width")?.intValue
        val h = o?.get("height")?.intValue
        val list = o?.get("frames")?.arrayValue
        assumeTrue("canuelas_frames.json incompleto", w != null && h != null && list != null)
        val frames = ArrayList<ByteArray>()
        for (item in list!!) {
            val b = bytes(item)
            assertEquals(w!! * h!!, b.size)
            frames.add(b)
        }
        return Frames(w!!, h!!, frames)
    }

    private fun gray(b: ByteArray, w: Int, h: Int): GrayImage =
        GrayImage(w, h, FloatArray(b.size) { (b[it].toInt() and 0xFF).toFloat() })

    @Test
    fun goldenCLAHEMatchesOpenCVExactly() {
        val input = loadFrames()
        val cases = JSON.read(goldenFile("canuelas_clahe.json")).objectValue?.get("cases")?.arrayValue
        assumeTrue("canuelas_clahe.json incompleto", cases != null)
        var checked = 0
        for (c in cases!!) {
            val o = c.objectValue ?: continue
            val fi = o["frame"]?.intValue ?: continue
            val clip = o["clip_limit"]?.doubleValue ?: continue
            var src = input.frames[fi]
            var w = input.width
            var h = input.height
            val crop = o["crop"]?.arrayValue?.mapNotNull { it.intValue }
            if (crop != null && crop.size == 4) {
                val sub = ByteArray(crop[2] * crop[3])
                var n = 0
                for (y in crop[1] until crop[1] + crop[3]) {
                    for (x in crop[0] until crop[0] + crop[2]) sub[n++] = src[y * w + x]
                }
                src = sub
                w = crop[2]
                h = crop[3]
            }
            val expected = bytes(o["output"])
            val got = PIVPreprocessing.clahe(src, w, h, clip)
            var mismatches = 0
            for (i in 0 until min(got.size, expected.size)) if (got[i] != expected[i]) mismatches += 1
            assertEquals(expected.size, got.size)
            assertEquals("CLAHE clip=$clip frame=$fi size=${w}x$h", 0, mismatches)
            checked += 1
        }
        println("PIV golden CLAHE: $checked cases bit-exact")
    }

    @Test
    fun goldenPIVDefault() = compareCase("default")

    @Test
    fun goldenPIVSmallWindows() = compareCase("ia64")

    @Test
    fun goldenPIVBackground() = compareCase("background")

    @Test
    fun goldenPIVBBoxAndMask() = compareCase("bbox_mask")

    private fun compareCase(name: String) {
        val input = loadFrames()
        val o = JSON.read(goldenFile("canuelas_piv_$name.json")).objectValue
        assumeTrue("caso $name incompleto", o != null)
        o!!
        assumeTrue("Referencia $name sin el parche de normalize_to_uint8: regenerar con golden.yml",
                   o["normalization_fix"]?.boolValue == true)
        val p = PIVParameters()
        p.interrogationArea1 = o["interrogation_area_1"]?.intValue ?: 128
        p.removeBackground = o["remove_background"]?.boolValue ?: false
        p.clahe = o["clahe"]?.boolValue ?: true
        p.clipLimit = o["clip_limit"]?.doubleValue ?: 5.0
        p.stdFiltering = o["std_filtering"]?.boolValue ?: true
        p.stdThreshold = o["std_threshold"]?.doubleValue ?: 4.0
        p.medianTestFiltering = o["median_test_filtering"]?.boolValue ?: true
        p.medianTestEpsilon = o["median_test_epsilon"]?.doubleValue ?: 0.02
        p.medianTestThreshold = o["median_test_threshold"]?.doubleValue ?: 2.0
        val bbox: List<Double>? = o["bbox"]?.doubleArray
        var mask: PIVMask? = null
        if (o["mask"] != null) {
            val mb = bytes(o["mask"])
            mask = PIVMask(input.width, input.height, mb)
        }
        val frames = input.frames.map { gray(it, input.width, input.height) }
        var bg: GrayImage? = null
        if (p.removeBackground) {
            val acc = PIVBackgroundAccumulator(input.width, input.height)
            for (f in frames) acc.add(f)
            bg = acc.background()
        }
        val seq = PIVSequence.run(frames = frames, parameters = p, bbox = bbox, mask = mask, background = bg)
        assertTrue(seq.failedPairs.isEmpty())
        val r = seq.results

        val shape = o["shape"]?.arrayValue?.mapNotNull { it.intValue }
        val xs = o["x"]?.doubleArray
        val ys = o["y"]?.doubleArray
        val pairs = o["pairs"]?.arrayValue
        assumeTrue("caso $name incompleto", shape != null && shape.size == 2 && xs != null && ys != null && pairs != null)
        assertEquals(name, shape!![0], r.rows)
        assertEquals(name, shape[1], r.columns)
        if (!(r.rows == shape[0] && r.columns == shape[1])) return
        for (k in xs!!.indices) {
            assertEquals(xs[k], r.x[k], 1e-9)
            assertEquals(ys!![k], r.y[k], 1e-9)
        }
        assertEquals(pairs!!.size, r.pairCount)
        val diffs = ArrayList<Double>()
        var nanMismatch = 0
        var total = 0
        val gradRel = ArrayList<Double>()
        for ((i, pair) in pairs.withIndex()) {
            if (i >= r.pairCount) continue
            val po = pair.objectValue ?: continue
            val ue = po["u"]?.doubleArray ?: continue
            val ve = po["v"]?.doubleArray ?: continue
            val ge = po["gradient"]?.doubleArray ?: continue
            for (k in ue.indices) {
                total += 1
                val refNaN = ue[k].isNaN() || ve[k].isNaN()
                val gotNaN = r.u[i][k].isNaN() || r.v[i][k].isNaN()
                if (refNaN != gotNaN) {
                    nanMismatch += 1
                } else if (!refNaN) {
                    diffs.add(abs(r.u[i][k] - ue[k]))
                    diffs.add(abs(r.v[i][k] - ve[k]))
                }
                if (!ge[k].isNaN() && !r.gradient[i][k].isNaN() && ge[k] != 0.0) {
                    gradRel.add(abs(r.gradient[i][k] - ge[k]) / abs(ge[k]))
                }
            }
            assertTrue(r.gradient[i][0].isNaN() && ge[0].isNaN())
        }
        assertFalse(name, diffs.isEmpty())
        val med = median(diffs)
        val p95 = percentile(diffs, 0.95)
        val stats = String.format(
            Locale.ROOT, "median |d| %.4f px, p95 %.4f px, NaN mismatch %d/%d, gradient p95 rel %.4f",
            med, p95, nanMismatch, total, percentile(gradRel, 0.95),
        )
        println("PIV golden $name: $stats")
        assertTrue(name, med < 0.1)
        val p95Limit: Double = if (name == "background") 1.0 else 0.5
        assertTrue(name, p95 < p95Limit)
        assertTrue(name, nanMismatch.toDouble() <= 0.05 * total.toDouble())
        // The gradient only depends on the windows (and, in pass 2, the warp): it must agree closely.
        assertTrue(name, percentile(gradRel, 0.95) < 0.1)
    }

    // endregion
}
