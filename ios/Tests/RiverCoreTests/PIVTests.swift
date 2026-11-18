import Foundation
import XCTest
@testable import RiverCore

/// Deterministic RNG for the synthetic images (SplitMix64).
private struct PIVTestRNG {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func uniform(_ lo: Double, _ hi: Double) -> Double {
        let u = Double(next() >> 11) / Double(UInt64(1) << 53)
        return lo + (hi - lo) * u
    }
}

/// Speckle image of Gaussian particles (σ = 1.2 px) displaced by (dx, dy), clipped to 0…255.
private func renderSpeckle(width: Int, height: Int, particles: Int, seed: UInt64, dx: Double, dy: Double) -> GrayImage {
    var rng = PIVTestRNG(seed: seed)
    var px = [Double](repeating: 0, count: width * height)
    let s2 = 2.0 * 1.2 * 1.2
    for _ in 0..<particles {
        let x = rng.uniform(-10, Double(width) + 10) + dx
        let y = rng.uniform(-10, Double(height) + 10) + dy
        let a = rng.uniform(80, 160)
        let xi = Int(x.rounded(.down)), yi = Int(y.rounded(.down))
        let y0 = max(0, yi - 4), y1 = min(height - 1, yi + 4)
        let x0 = max(0, xi - 4), x1 = min(width - 1, xi + 4)
        if y0 > y1 || x0 > x1 { continue }
        for yy in y0...y1 {
            for xx in x0...x1 {
                let ddx = Double(xx) - x, ddy = Double(yy) - y
                px[yy * width + xx] += a * exp(-(ddx * ddx + ddy * ddy) / s2)
            }
        }
    }
    let out: [Float] = px.map { (v: Double) -> Float in Float(min(255, v)) }
    return GrayImage(width: width, height: height, pixels: out)
}

private func median(_ values: [Double]) -> Double {
    let s = values.filter { !$0.isNaN }.sorted()
    if s.isEmpty { return .nan }
    let n = s.count
    return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2
}

private func percentile(_ values: [Double], _ p: Double) -> Double {
    let s = values.filter { !$0.isNaN }.sorted()
    if s.isEmpty { return .nan }
    let idx = Int((p * Double(s.count - 1)).rounded())
    return s[min(max(idx, 0), s.count - 1)]
}

final class PIVFFTTests: XCTestCase {
    private func naiveDFT(_ re: [Float], _ im: [Float], inverse: Bool) -> ([Double], [Double]) {
        let n = re.count
        var outR = [Double](repeating: 0, count: n)
        var outI = [Double](repeating: 0, count: n)
        let sign: Double = inverse ? 1 : -1
        for k in 0..<n {
            var sr = 0.0, si = 0.0
            for j in 0..<n {
                let a = sign * 2 * Double.pi * Double((j * k) % n) / Double(n)
                sr += Double(re[j]) * cos(a) - Double(im[j]) * sin(a)
                si += Double(re[j]) * sin(a) + Double(im[j]) * cos(a)
            }
            outR[k] = sr
            outI[k] = si
        }
        return (outR, outI)
    }

    func testFFTMatchesNaiveDFT() {
        var rng = PIVTestRNG(seed: 7)
        for n in [1, 2, 8, 64, 7, 12, 96] {
            var re = (0..<n).map { _ in Float(rng.uniform(-1, 1)) }
            var im = (0..<n).map { _ in Float(rng.uniform(-1, 1)) }
            let ref = naiveDFT(re, im, inverse: false)
            let refInv = naiveDFT(re, im, inverse: true)
            let fft = PIVFFT(count: n)
            var r2 = re, i2 = im
            fft.transform(re: &re, im: &im, inverse: false)
            fft.transform(re: &r2, im: &i2, inverse: true)
            let tol = 1e-4 * Double(max(n, 4))
            for k in 0..<n {
                XCTAssertEqual(Double(re[k]), ref.0[k], accuracy: tol, "n=\(n) k=\(k)")
                XCTAssertEqual(Double(im[k]), ref.1[k], accuracy: tol, "n=\(n) k=\(k)")
                XCTAssertEqual(Double(r2[k]), refInv.0[k], accuracy: tol, "inverse n=\(n) k=\(k)")
                XCTAssertEqual(Double(i2[k]), refInv.1[k], accuracy: tol, "inverse n=\(n) k=\(k)")
            }
        }
    }

    func test2DRoundTrip() {
        var rng = PIVTestRNG(seed: 11)
        for n in [16, 12] {
            let re0 = (0..<(n * n)).map { _ in Float(rng.uniform(0, 255)) }
            var re = re0
            var im = [Float](repeating: 0, count: n * n)
            let fft = PIVFFT2D(size: n)
            fft.transform(re: &re, im: &im, inverse: false)
            fft.transform(re: &re, im: &im, inverse: true)
            for k in 0..<(n * n) {
                XCTAssertEqual(re[k] / Float(n * n), re0[k], accuracy: 1e-2)
                XCTAssertEqual(im[k] / Float(n * n), 0, accuracy: 1e-2)
            }
        }
    }

    /// The packed two-window correlation equals the direct circular cross-correlation with upstream's fftshift.
    func testCorrelatorMatchesDirectCorrelation() {
        var rng = PIVTestRNG(seed: 3)
        let n = 16
        var w: [[Float]] = []
        for _ in 0..<4 { w.append((0..<(n * n)).map { _ in Float(rng.uniform(0, 1)) }) }
        var o1 = [Float](repeating: 0, count: n * n)
        var o2 = [Float](repeating: 0, count: n * n)
        let corr = PIVCorrelator(size: n)
        w[0].withUnsafeBufferPointer { a1 in
            w[1].withUnsafeBufferPointer { b1 in
                w[2].withUnsafeBufferPointer { a2 in
                    w[3].withUnsafeBufferPointer { b2 in
                        o1.withUnsafeMutableBufferPointer { p1 in
                            o2.withUnsafeMutableBufferPointer { p2 in
                                corr.correlate(a1: a1.baseAddress!, b1: b1.baseAddress!, out1: p1.baseAddress!,
                                               a2: a2.baseAddress!, b2: b2.baseAddress!, out2: p2.baseAddress!)
                            }
                        }
                    }
                }
            }
        }
        func direct(_ a: [Float], _ b: [Float], _ dy: Int, _ dx: Int) -> Double {
            var s = 0.0
            for y in 0..<n {
                for x in 0..<n {
                    s += Double(a[y * n + x]) * Double(b[((y + dy + n) % n) * n + (x + dx + n) % n])
                }
            }
            return s
        }
        for r in 0..<n {
            for c in 0..<n {
                let dy = r - n / 2, dx = c - n / 2
                XCTAssertEqual(Double(o1[r * n + c]), direct(w[0], w[1], dy, dx), accuracy: 1e-3)
                XCTAssertEqual(Double(o2[r * n + c]), direct(w[2], w[3], dy, dx), accuracy: 1e-3)
            }
        }
    }
}

final class PIVPreprocessingTests: XCTestCase {
    func testCLAHEUniformImageStaysUniform() {
        let px = [UInt8](repeating: 100, count: 120 * 90)
        let out = PIVPreprocessing.clahe(px, width: 120, height: 90, clipLimit: 5)
        XCTAssertEqual(out.count, px.count)
        XCTAssertEqual(Set(out).count, 1, "a uniform image must map to a single value")
    }

    func testCLAHEBoundedAndMonotonicWithinTile() {
        var rng = PIVTestRNG(seed: 5)
        let w = 101, h = 77
        let px: [UInt8] = (0..<(w * h)).map { _ in UInt8(rng.uniform(0, 255.99)) }
        let out = PIVPreprocessing.clahe(px, width: w, height: h, clipLimit: 2.5)
        XCTAssertEqual(out.count, px.count)
        XCTAssertTrue(out.contains { $0 > 0 })
        // Same input value at the same pixel position mapping is a monotone LUT: compare the brightest and darkest.
        let iMax = px.firstIndex(of: px.max()!)!
        let iMin = px.firstIndex(of: px.min()!)!
        XCTAssertGreaterThanOrEqual(out[iMax], out[iMin])
    }

    func testBackgroundIsTruncatedMeanAndSubtractionSaturates() {
        var acc = PIVBackgroundAccumulator(width: 2, height: 1)
        acc.add(GrayImage(width: 2, height: 1, pixels: [10, 200]))
        acc.add(GrayImage(width: 2, height: 1, pixels: [11, 255]))
        let bg = acc.background()!
        XCTAssertEqual(bg.pixels, [10, 227]) // 10.5 → 10, 227.5 → 227
        var px: [UInt8] = [5, 250]
        PIVPreprocessing.subtractBackground(&px, background: [10, 227])
        XCTAssertEqual(px, [0, 23])
    }

    func testParametersFromSettings() {
        let o = JSONObject([("interrogation_area_1", .int(64)), ("interrogation_area_2", .int(64)),
                            ("clahe", .bool(false)), ("clip_limit", .double(3.5)),
                            ("median_test_threshold", .int(3))])
        let p = PIVParameters(processing: o)
        XCTAssertEqual(p.interrogationArea1, 64)
        XCTAssertNil(p.interrogationArea2)
        XCTAssertEqual(p.effectiveInterrogationArea2, 32) // desktop passes ia1/2
        XCTAssertFalse(p.clahe)
        XCTAssertEqual(p.clipLimit, 3.5)
        XCTAssertEqual(p.medianTestThreshold, 3)
        XCTAssertEqual(PIVParameters().effectiveInterrogationArea2, 64)
        XCTAssertEqual(PIVParameters(interrogationArea1: 128, interrogationArea2: 50).effectiveInterrogationArea2, 50)
        XCTAssertThrowsError(try PIVParameters(interrogationArea1: 63).validate())
    }
}

final class PIVAlgorithmTests: XCTestCase {
    private let shift = (dx: 2.3, dy: -1.7)

    private func syntheticPair() -> (GrayImage, GrayImage) {
        let a = renderSpeckle(width: 256, height: 256, particles: 1800, seed: 42, dx: 0, dy: 0)
        let b = renderSpeckle(width: 256, height: 256, particles: 1800, seed: 42, dx: shift.dx, dy: shift.dy)
        return (a, b)
    }

    /// F3 acceptance: a known sub-pixel displacement is recovered within 0.05 px (median of the field).
    func testSyntheticSubpixelShiftIsRecovered() throws {
        let (a, b) = syntheticPair()
        let p = PIVParameters(interrogationArea1: 64, clahe: false)
        let f = try PIVFFTMulti.run(image1: a, image2: b, parameters: p)
        XCTAssertEqual(f.rows, 15)
        XCTAssertEqual(f.columns, 15)
        XCTAssertEqual(median(f.u), shift.dx, accuracy: 0.05)
        XCTAssertEqual(median(f.v), shift.dy, accuracy: 0.05)
    }

    /// PLAN.md #3: `multipass = false` really stops after pass 1 (pass-1 grid, 64-px windows every 32 px).
    func testMultipassFalseRunsOnePass() throws {
        let (a, b) = syntheticPair()
        let p = PIVParameters(interrogationArea1: 64, clahe: false, multipass: false)
        let f = try PIVFFTMulti.run(image1: a, image2: b, parameters: p)
        XCTAssertEqual(f.rows, 7)
        XCTAssertEqual(f.columns, 7)
        XCTAssertEqual(f.x[1] - f.x[0], 32)
        let grid = try PIVFFTMulti.outputGrid(width: 256, height: 256, parameters: p)
        XCTAssertEqual(grid.rows, 7)
        XCTAssertEqual(grid.x, f.x)
        // One pass with 64-px windows is less precise, but must be in the right place.
        XCTAssertEqual(median(f.u), shift.dx, accuracy: 0.15)
        XCTAssertEqual(median(f.v), shift.dy, accuracy: 0.15)
    }

    func testZeroDisplacementOfIdenticalFramesIsNotForcedAway() throws {
        // mask_auto attenuates the zero-lag peak in pass 1 (as upstream): a large displacement must still win.
        let a = renderSpeckle(width: 128, height: 128, particles: 500, seed: 9, dx: 0, dy: 0)
        let b = renderSpeckle(width: 128, height: 128, particles: 500, seed: 9, dx: 5, dy: 3)
        let f = try PIVFFTMulti.run(image1: a, image2: b, parameters: PIVParameters(interrogationArea1: 64, clahe: false))
        XCTAssertEqual(median(f.u), 5, accuracy: 0.1)
        XCTAssertEqual(median(f.v), 3, accuracy: 0.1)
    }

    func testMaskSetsVectorsToNaN() throws {
        let (a, b) = syntheticPair()
        var values = [UInt8](repeating: 1, count: 256 * 256)
        for y in 0..<256 { for x in 0..<128 { values[y * 256 + x] = 0 } }
        let mask = PIVMask(width: 256, height: 256, values: values)
        let f = try PIVFFTMulti.run(image1: a, image2: b, parameters: PIVParameters(interrogationArea1: 64, clahe: false),
                                    mask: mask)
        for k in 0..<f.u.count {
            if f.x[k] < 128 {
                XCTAssertTrue(f.u[k].isNaN && f.v[k].isNaN)
            } else {
                XCTAssertFalse(f.u[k].isNaN)
            }
        }
        XCTAssertTrue(f.gradient[0].isNaN) // upstream quirk kept for parity
    }

    private struct Unreadable: Error {}

    /// PLAN.md #5/#11: an unreadable frame turns its two pairs into NaN and lists them; the run goes on.
    func testFailedPairIsNaNAndListed() throws {
        let frames: [GrayImage] = (0..<4).map { (i: Int) -> GrayImage in
            renderSpeckle(width: 128, height: 128, particles: 450, seed: 77, dx: Double(i) * 1.5, dy: Double(i) * 0.5)
        }
        let provider: (Int) throws -> GrayImage = { (i: Int) throws -> GrayImage in
            if i == 2 { throw Unreadable() }
            return frames[i]
        }
        let p = PIVParameters(interrogationArea1: 32)
        var progressCalls = 0
        let seq = try PIVSequence.run(frameCount: 4, frame: provider, parameters: p,
                                      progress: { (_: Int, _: Int) in progressCalls += 1 })
        XCTAssertEqual(seq.failedPairs.map(\.index), [1, 2])
        XCTAssertEqual(progressCalls, 3)
        let r = seq.results
        XCTAssertEqual(r.pairCount, 3)
        XCTAssertTrue(r.u[1].allSatisfy { $0.isNaN })
        XCTAssertTrue(r.v[2].allSatisfy { $0.isNaN })
        XCTAssertTrue(r.gradient[1].allSatisfy { $0.isNaN })
        XCTAssertTrue(r.u[0].contains { !$0.isNaN })
        // Medians come from the good pair only.
        for k in 0..<r.uMedian.count where !r.u[0][k].isNaN {
            XCTAssertEqual(r.uMedian[k], r.u[0][k])
        }
        XCTAssertEqual(median(r.u[0]), 1.5, accuracy: 0.25)
        XCTAssertEqual(median(r.v[0]), 0.5, accuracy: 0.25)

        // Parallel run: identical result (NaN-aware comparison).
        let par = try PIVSequence.run(frameCount: 4, frame: provider, parameters: p, parallel: true)
        XCTAssertEqual(par.failedPairs.map(\.index), [1, 2])
        func same(_ a: [[Double]], _ b: [[Double]]) -> Bool {
            guard a.count == b.count else { return false }
            for (p, q) in zip(a, b) {
                guard p.count == q.count else { return false }
                for k in 0..<p.count where !(p[k] == q[k] || (p[k].isNaN && q[k].isNaN)) { return false }
            }
            return true
        }
        XCTAssertTrue(same(par.results.u, r.u))
        XCTAssertTrue(same(par.results.v, r.v))
        XCTAssertTrue(same(par.results.gradient, r.gradient))
        XCTAssertTrue(same([par.results.uMedian], [r.uMedian]))
    }

    func testCancellationThrows() {
        let frames: [GrayImage] = (0..<3).map { (i: Int) -> GrayImage in
            renderSpeckle(width: 64, height: 64, particles: 120, seed: 5, dx: Double(i), dy: 0)
        }
        XCTAssertThrowsError(try PIVSequence.run(frames: frames, parameters: PIVParameters(interrogationArea1: 32),
                                                 isCancelled: { true })) { error in
            XCTAssertEqual(error as? PIVError, .cancelled)
        }
    }
}

/// Comparisons against RIVeR 3.8.3 run in CI by tools/golden/piv.py (ci/testdata/golden/piv/).
///
/// Tolerances: RIVEM fits the sub-pixel peak on the Float correlation, while RIVeR fits it on the correlation
/// quantised to 8 bits (PLAN.md #4). That changes vectors by a few hundredths of a pixel, the pass-2 predictor with
/// them, and occasionally which vectors the median test discards (then replaced by neighbours). So the fields are
/// compared statistically on vectors valid in both: median |Δ| < 0.1 px and 95th percentile < 0.5 px, the NaN
/// pattern may differ in at most 5 % of the nodes, and the grid must match exactly. CLAHE must be bit-exact.
final class PIVGoldenTests: XCTestCase {
    private func goldenURL(_ name: String) throws -> URL {
        let url = TestData.url("golden/piv/\(name)")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("Falta \(name): generar con tools/golden/run_all.py (workflow golden.yml)")
        }
        return url
    }

    private func bytes(_ v: JSONValue?) throws -> [UInt8] {
        guard let s = v?.stringValue, let d = Data(base64Encoded: s) else {
            throw XCTSkip("base64 no válido en la referencia")
        }
        return [UInt8](d)
    }

    private func loadFrames() throws -> (width: Int, height: Int, frames: [[UInt8]]) {
        guard let o = try JSON.read(goldenURL("canuelas_frames.json")).objectValue,
              let w = o["width"]?.intValue, let h = o["height"]?.intValue,
              let list = o["frames"]?.arrayValue else { throw XCTSkip("canuelas_frames.json incompleto") }
        var frames: [[UInt8]] = []
        for item in list {
            let b = try bytes(item)
            XCTAssertEqual(b.count, w * h)
            frames.append(b)
        }
        return (w, h, frames)
    }

    private func gray(_ b: [UInt8], _ w: Int, _ h: Int) -> GrayImage {
        GrayImage(width: w, height: h, pixels: b.map { (x: UInt8) -> Float in Float(x) })
    }

    func testCLAHEMatchesOpenCVExactly() throws {
        let input = try loadFrames()
        guard let cases = try JSON.read(goldenURL("canuelas_clahe.json")).objectValue?["cases"]?.arrayValue else {
            throw XCTSkip("canuelas_clahe.json incompleto")
        }
        for c in cases {
            guard let o = c.objectValue, let fi = o["frame"]?.intValue, let clip = o["clip_limit"]?.doubleValue else {
                continue
            }
            var src = input.frames[fi]
            var w = input.width, h = input.height
            if let crop = o["crop"]?.arrayValue?.compactMap(\.intValue), crop.count == 4 {
                var sub: [UInt8] = []
                for y in crop[1]..<(crop[1] + crop[3]) {
                    for x in crop[0]..<(crop[0] + crop[2]) { sub.append(src[y * w + x]) }
                }
                src = sub
                w = crop[2]
                h = crop[3]
            }
            let expected = try bytes(o["output"])
            let got = PIVPreprocessing.clahe(src, width: w, height: h, clipLimit: clip)
            var mismatches = 0
            for i in 0..<min(got.count, expected.count) where got[i] != expected[i] { mismatches += 1 }
            XCTAssertEqual(got.count, expected.count)
            XCTAssertEqual(mismatches, 0, "CLAHE clip=\(clip) frame=\(fi) size=\(w)x\(h)")
        }
    }

    func testPIVDefault() throws { try compareCase("default") }
    func testPIVSmallWindows() throws { try compareCase("ia64") }
    func testPIVBackground() throws { try compareCase("background") }
    func testPIVBBoxAndMask() throws { try compareCase("bbox_mask") }

    private func compareCase(_ name: String) throws {
        let input = try loadFrames()
        guard let o = try JSON.read(goldenURL("canuelas_piv_\(name).json")).objectValue else {
            throw XCTSkip("caso \(name) incompleto")
        }
        var p = PIVParameters()
        p.interrogationArea1 = o["interrogation_area_1"]?.intValue ?? 128
        p.removeBackground = o["remove_background"]?.boolValue ?? false
        p.clahe = o["clahe"]?.boolValue ?? true
        p.clipLimit = o["clip_limit"]?.doubleValue ?? 5
        p.stdFiltering = o["std_filtering"]?.boolValue ?? true
        p.stdThreshold = o["std_threshold"]?.doubleValue ?? 4
        p.medianTestFiltering = o["median_test_filtering"]?.boolValue ?? true
        p.medianTestEpsilon = o["median_test_epsilon"]?.doubleValue ?? 0.02
        p.medianTestThreshold = o["median_test_threshold"]?.doubleValue ?? 2
        let bbox: [Double]? = o["bbox"]?.doubleArray
        var mask: PIVMask? = nil
        if o["mask"] != nil {
            let mb: [UInt8] = try bytes(o["mask"])
            mask = PIVMask(width: input.width, height: input.height, values: mb)
        }
        let frames = input.frames.map { gray($0, input.width, input.height) }
        var bg: GrayImage? = nil
        if p.removeBackground {
            var acc = PIVBackgroundAccumulator(width: input.width, height: input.height)
            for f in frames { acc.add(f) }
            bg = acc.background()
        }
        let seq = try PIVSequence.run(frames: frames, parameters: p, bbox: bbox, mask: mask, background: bg)
        XCTAssertTrue(seq.failedPairs.isEmpty)
        let r = seq.results

        guard let shape = o["shape"]?.arrayValue?.compactMap(\.intValue), shape.count == 2,
              let xs = o["x"]?.doubleArray, let ys = o["y"]?.doubleArray,
              let pairs = o["pairs"]?.arrayValue else { throw XCTSkip("caso \(name) incompleto") }
        XCTAssertEqual(r.rows, shape[0], name)
        XCTAssertEqual(r.columns, shape[1], name)
        guard r.rows == shape[0], r.columns == shape[1] else { return }
        for k in 0..<xs.count {
            XCTAssertEqual(r.x[k], xs[k], accuracy: 1e-9)
            XCTAssertEqual(r.y[k], ys[k], accuracy: 1e-9)
        }
        XCTAssertEqual(r.pairCount, pairs.count)
        var diffs: [Double] = []
        var nanMismatch = 0
        var total = 0
        var gradRel: [Double] = []
        for (i, pair) in pairs.enumerated() where i < r.pairCount {
            guard let po = pair.objectValue, let ue = po["u"]?.doubleArray, let ve = po["v"]?.doubleArray,
                  let ge = po["gradient"]?.doubleArray else { continue }
            for k in 0..<ue.count {
                total += 1
                let refNaN = ue[k].isNaN || ve[k].isNaN
                let gotNaN = r.u[i][k].isNaN || r.v[i][k].isNaN
                if refNaN != gotNaN {
                    nanMismatch += 1
                } else if !refNaN {
                    diffs.append(abs(r.u[i][k] - ue[k]))
                    diffs.append(abs(r.v[i][k] - ve[k]))
                }
                if !ge[k].isNaN && !r.gradient[i][k].isNaN && ge[k] != 0 {
                    gradRel.append(abs(r.gradient[i][k] - ge[k]) / abs(ge[k]))
                }
            }
            XCTAssertTrue(r.gradient[i][0].isNaN && ge[0].isNaN)
        }
        XCTAssertFalse(diffs.isEmpty, name)
        let med = median(diffs), p95 = percentile(diffs, 0.95)
        let stats = String(format: "median |d| %.4f px, p95 %.4f px, NaN mismatch %ld/%ld, gradient p95 rel %.4f",
                           med, p95, nanMismatch, total, percentile(gradRel, 0.95))
        print("PIV golden \(name): \(stats)")
        XCTAssertLessThan(med, 0.1, name)
        XCTAssertLessThan(p95, 0.5, name)
        XCTAssertLessThanOrEqual(Double(nanMismatch), 0.05 * Double(total), name)
        // The gradient only depends on the windows (and, in pass 2, the warp): it must agree closely.
        XCTAssertLessThan(percentile(gradRel, 0.95), 0.1, name)
    }
}
