import Foundation

/// A frame pair that could not be processed (unreadable frame, size mismatch, processing error).
public struct PIVFailedPair: Equatable, Sendable {
    /// Pair index `i` (frames `i` and `i + 1`).
    public let index: Int
    public let reason: String

    public init(index: Int, reason: String) {
        self.index = index
        self.reason = reason
    }
}

public struct PIVSequenceResult: Sendable {
    /// Field for `piv_results.json`. Failed pairs are present as all-NaN rows (so pair indices keep matching frames).
    public var results: PIVResults
    public var failedPairs: [PIVFailedPair]
}

/// Runs PIV over consecutive frame pairs (i, i+1), like `piv_pipeline.run_analyze_all` + `piv_loop`.
///
/// Each frame is preprocessed (`PIVPreprocessing`: optional background subtraction, CLAHE) and each pair goes
/// through `PIVFFTMulti` (including the mask). `u_median` / `v_median` are `nanmedian` over pairs; `gradient` is
/// upstream's per-window gradient indicator of the last pass.
///
/// Deviations from RIVeR 3.8.3:
/// - PLAN.md #5 / #11: a pair whose frames cannot be read or whose processing throws does not abort the run (upstream
///   lost the whole analysis, or silently processed black frames). It is reported in `failedPairs` and stored as
///   all-NaN (u, v, gradient), so it does not bias the medians.
/// - The background (`remove_background`) is the truncated mean of the frames that could be read (upstream crashed on
///   an unreadable frame). Pass `background` to reuse a stored `background.jpg`.
public enum PIVSequence {
    /// - Parameters:
    ///   - frameCount: number of frames (pairs = frameCount − 1).
    ///   - frame: returns frame `i` (grayscale, 0–255). Called from several threads at once when `parallel` is true.
    ///   - bbox: `bbox.json` `[x, y, w, h]` (nil = whole frame).
    ///   - mask: `mask.npy` (nil = everything analysed).
    ///   - parallel: processes pairs concurrently (`DispatchQueue.concurrentPerform`); results are identical to the
    ///     sequential run (each pair writes its own slot).
    ///   - progress: `(pairsDone, pairsTotal)`, possibly from worker threads.
    ///   - isCancelled: polled before each frame/pair; when it returns true the run throws `PIVError.cancelled`.
    public static func run(frameCount: Int, frame: @escaping (Int) throws -> GrayImage, parameters: PIVParameters,
                           bbox: [Double]? = nil, mask: PIVMask? = nil, background: GrayImage? = nil,
                           parallel: Bool = false, progress: ((Int, Int) -> Void)? = nil,
                           isCancelled: (() -> Bool)? = nil) throws -> PIVSequenceResult {
        try parameters.validate()
        guard frameCount >= 2 else { throw PIVError.noFrames }
        let cancelled: () -> Bool = { isCancelled?() ?? false }

        // Reference size and background.
        var width = 0, height = 0
        var bg: GrayImage? = background
        if parameters.removeBackground && bg == nil {
            var acc: PIVBackgroundAccumulator? = nil
            for i in 0..<frameCount {
                if cancelled() { throw PIVError.cancelled }
                guard let img = try? frame(i) else { continue }
                if acc == nil { acc = PIVBackgroundAccumulator(width: img.width, height: img.height) }
                acc?.add(img)
            }
            guard let a = acc else { throw PIVError.noFrames }
            width = a.width
            height = a.height
            bg = a.background()
        } else {
            var found = false
            for i in 0..<frameCount {
                if cancelled() { throw PIVError.cancelled }
                if let img = try? frame(i) {
                    width = img.width
                    height = img.height
                    found = true
                    break
                }
            }
            if !found { throw PIVError.noFrames }
        }
        let grid = try PIVFFTMulti.outputGrid(width: width, height: height, parameters: parameters, bbox: bbox)
        let nodes = grid.rows * grid.columns
        let pairs = frameCount - 1
        let slots = PIVSlots(count: pairs)
        let bgImage = bg
        let refWidth = width
        let refHeight = height

        func prepare(_ i: Int) throws -> GrayImage {
            let img = try frame(i)
            guard img.width == refWidth, img.height == refHeight else { throw PIVError.imageSizeMismatch }
            return PIVPreprocessing.preprocess(img, parameters: parameters, background: bgImage)
        }
        func process(_ i: Int, first: GrayImage?) -> (PIVPairField?, String?, GrayImage?) {
            var second: GrayImage? = nil
            do {
                let a: GrayImage
                if let f = first {
                    a = f
                } else {
                    a = try prepare(i)
                }
                let b: GrayImage = try prepare(i + 1)
                second = b
                let field = try PIVFFTMulti.run(image1: a, image2: b, parameters: parameters, bbox: bbox, mask: mask)
                guard field.rows == grid.rows, field.columns == grid.columns else {
                    return (nil, "unexpected grid", second)
                }
                return (field, nil, second)
            } catch {
                return (nil, String(describing: error), second)
            }
        }

        if parallel {
            DispatchQueue.concurrentPerform(iterations: pairs) { (i: Int) in
                if slots.isCancelled || cancelled() {
                    slots.cancel()
                    return
                }
                let r = process(i, first: nil)
                let done = slots.store(i, field: r.0, error: r.1)
                progress?(done, pairs)
            }
            if slots.isCancelled { throw PIVError.cancelled }
        } else {
            var cached: GrayImage? = nil
            for i in 0..<pairs {
                if cancelled() { throw PIVError.cancelled }
                let r = process(i, first: cached)
                cached = r.2
                let done = slots.store(i, field: r.0, error: r.1)
                progress?(done, pairs)
            }
        }

        // Assemble.
        let nanRow = [Double](repeating: .nan, count: nodes)
        var u: [[Double]] = []
        var v: [[Double]] = []
        var gradient: [[Double]] = []
        var failed: [PIVFailedPair] = []
        for i in 0..<pairs {
            if let f = slots.fields[i] {
                u.append(f.u)
                v.append(f.v)
                gradient.append(f.gradient.count == nodes ? f.gradient : nanRow)
            } else {
                u.append(nanRow)
                v.append(nanRow)
                gradient.append(nanRow)
                failed.append(PIVFailedPair(index: i, reason: slots.errors[i] ?? "unknown error"))
            }
        }
        let results = PIVResults(rows: grid.rows, columns: grid.columns, x: grid.x, y: grid.y, u: u, v: v,
                                 uMedian: nanMedian(u, nodes: nodes), vMedian: nanMedian(v, nodes: nodes),
                                 gradient: gradient)
        return PIVSequenceResult(results: results, failedPairs: failed)
    }

    /// Convenience over an in-memory frame list.
    public static func run(frames: [GrayImage], parameters: PIVParameters, bbox: [Double]? = nil,
                           mask: PIVMask? = nil, background: GrayImage? = nil, parallel: Bool = false,
                           progress: ((Int, Int) -> Void)? = nil,
                           isCancelled: (() -> Bool)? = nil) throws -> PIVSequenceResult {
        let list = frames
        return try run(frameCount: list.count, frame: { (i: Int) -> GrayImage in list[i] }, parameters: parameters,
                       bbox: bbox, mask: mask, background: background, parallel: parallel, progress: progress,
                       isCancelled: isCancelled)
    }

    /// `np.nanmedian(axis=pairs)`: NaN where every pair is NaN.
    static func nanMedian(_ rows: [[Double]], nodes: Int) -> [Double] {
        var out = [Double](repeating: .nan, count: nodes)
        var buf: [Double] = []
        buf.reserveCapacity(rows.count)
        for k in 0..<nodes {
            buf.removeAll(keepingCapacity: true)
            for r in rows {
                let x = r[k]
                if !x.isNaN { buf.append(x) }
            }
            if buf.isEmpty { continue }
            buf.sort()
            let n = buf.count
            out[k] = n % 2 == 1 ? buf[n / 2] : (buf[n / 2 - 1] + buf[n / 2]) / 2
        }
        return out
    }
}

/// Preallocated per-pair results shared by the workers (one slot per pair, guarded by a lock).
final class PIVSlots {
    private let lock = NSLock()
    private(set) var fields: [PIVPairField?]
    private(set) var errors: [String?]
    private var done = 0
    private var cancelledFlag = false

    init(count: Int) {
        fields = [PIVPairField?](repeating: nil, count: count)
        errors = [String?](repeating: nil, count: count)
    }

    /// Stores a result and returns how many pairs are done.
    func store(_ i: Int, field: PIVPairField?, error: String?) -> Int {
        lock.lock()
        defer { lock.unlock() }
        fields[i] = field
        errors[i] = field == nil ? (error ?? "unknown error") : nil
        done += 1
        return done
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelledFlag
    }

    func cancel() {
        lock.lock()
        cancelledFlag = true
        lock.unlock()
    }
}
