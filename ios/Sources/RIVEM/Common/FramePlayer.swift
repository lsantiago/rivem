import CoreGraphics
import Foundation
import Observation
import RiverCore
import SwiftUI

/// Plays the project's extracted frames (desktop's `FramePlayer`): current index, its image (a display-size copy
/// decoded off the main thread) and play/pause at a given rate.
@MainActor
@Observable
final class FrameSequence {
    private(set) var frames: [URL] = []
    /// Frames shown per second while playing.
    private(set) var rate: Double = 10
    private(set) var index: Int = 0
    private(set) var image: CGImage?
    private(set) var playing = false
    private var loadedIndex: Int = -1
    private var playTask: Task<Void, Never>?

    var count: Int { frames.count }

    func configure(frames: [URL], rate: Double) {
        guard frames != self.frames else { return }
        stop()
        self.frames = frames
        self.rate = min(max(rate, 1), 30)
        index = 0
        loadedIndex = -1
        image = nil
        Task { await load(0) }
    }

    func show(_ i: Int) {
        guard count > 0 else { return }
        let k: Int = min(max(i, 0), count - 1)
        index = k
        Task { await load(k) }
    }

    func toggle() {
        if playing { stop() } else { play() }
    }

    func stop() {
        playing = false
        playTask?.cancel()
        playTask = nil
    }

    private func play() {
        guard count > 1 else { return }
        playing = true
        playTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let s = self, s.playing else { return }
                let next: Int = (s.index + 1) % s.count
                s.index = next
                let started = Date()
                await s.load(next)
                let wait: Double = max(0, 1 / s.rate - Date().timeIntervalSince(started))
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            }
        }
    }

    private func load(_ k: Int) async {
        guard k < frames.count, k != loadedIndex else { return }
        let url: URL = frames[k]
        let decoded: CGImage? = await Task.detached(priority: .userInitiated) { () -> CGImage? in
            ImageCoding.thumbnail(contentsOf: url, maxPixel: 1280)
        }.value
        if index == k, let img = decoded {
            image = img
            loadedIndex = k
        }
    }
}

/// Play/pause, scrubber and position of a `FrameSequence`, drawn over a canvas.
struct FramePlayerBar<Accessory: View>: View {
    let sequence: FrameSequence
    var caption: String? = nil
    @ViewBuilder var accessory: () -> Accessory

    var body: some View {
        HStack(spacing: 10) {
            Button {
                sequence.toggle()
            } label: {
                Image(systemName: sequence.playing ? "pause.fill" : "play.fill")
                    .frame(width: 26, height: 26)
            }
            .accessibilityLabel(sequence.playing ? "Pausar" : "Reproducir")
            if sequence.count > 1 {
                Slider(value: Binding<Double>(
                    get: { Double(sequence.index) },
                    set: { (v: Double) in
                        sequence.stop()
                        sequence.show(Int(v.rounded()))
                    }
                ), in: 0...Double(sequence.count - 1), step: 1)
            }
            Text(caption ?? "\(sequence.index + 1)/\(sequence.count)")
                .font(.caption.monospacedDigit())
                .lineLimit(1)
                .fixedSize()
            accessory()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
        .environment(\.colorScheme, .dark)
        .padding(8)
    }
}

extension FramePlayerBar where Accessory == EmptyView {
    init(sequence: FrameSequence, caption: String? = nil) {
        self.sequence = sequence
        self.caption = caption
        self.accessory = { EmptyView() }
    }
}

/// Builds the arrows of a PIV field (median or one pair) for the canvas, with their speed.
enum QuiverBuilder {
    /// Arrow scale so the median vector is ~4 % of the frame width.
    nonisolated static func scale(_ piv: PIVResults, imageWidth: Double) -> Double {
        var magnitudes: [Double] = []
        let n: Int = min(piv.uMedian.count, piv.vMedian.count)
        for k in 0..<n {
            let m: Double = hypot(piv.uMedian[k], piv.vMedian[k])
            if m.isFinite { magnitudes.append(m) }
        }
        guard !magnitudes.isEmpty else { return 10 }
        magnitudes.sort()
        let typical: Double = max(magnitudes[magnitudes.count / 2], 1e-6)
        return min(max(0.04 * imageWidth / typical, 1), 200)
    }

    /// Arrows of the median field (`pair == nil`) or of one pair, at most ~1200, with speed in m/s (or px per pair
    /// without matrix / frame interval).
    nonisolated static func vectors(_ piv: PIVResults, pair: Int?, scale: Double, matrix: Matrix3?,
                                    frameInterval: Double?) -> [QuiverVector] {
        let us: [Double]
        let vs: [Double]
        if let p = pair, p >= 0, p < piv.u.count, p < piv.v.count {
            us = piv.u[p]
            vs = piv.v[p]
        } else {
            us = piv.uMedian
            vs = piv.vMedian
        }
        let n: Int = min(piv.x.count, piv.y.count, us.count, vs.count)
        let stride: Int = max(1, Int((Double(n) / 1200).squareRoot().rounded(.up)))
        var out: [QuiverVector] = []
        for row in Swift.stride(from: 0, to: piv.rows, by: stride) {
            for col in Swift.stride(from: 0, to: piv.columns, by: stride) {
                let k: Int = row * piv.columns + col
                guard k < n else { continue }
                let x: Double = piv.x[k], y: Double = piv.y[k]
                let u: Double = us[k], v: Double = vs[k]
                guard x.isFinite, y.isFinite, u.isFinite, v.isFinite else { continue }
                var speed: Double = hypot(u, v)
                if let m = matrix, let dt = frameInterval, dt > 0 {
                    speed = m.apply(Point2(x, y)).distance(to: m.apply(Point2(x + u, y + v))) / dt
                }
                guard speed.isFinite else { continue }
                out.append(QuiverVector(a: Point2(x, y), b: Point2(x + u * scale, y + v * scale), speed: speed))
            }
        }
        return out
    }

    /// 2nd–98th percentile of the speeds (colour range).
    nonisolated static func range(_ vectors: [QuiverVector]) -> ClosedRange<Double> {
        let speeds: [Double] = vectors.map { $0.speed }.sorted()
        guard !speeds.isEmpty else { return 0...1 }
        let lo: Double = speeds[Int(Double(speeds.count - 1) * 0.02)]
        let hi: Double = max(speeds[Int(Double(speeds.count - 1) * 0.98)], lo + 1e-6)
        return lo...hi
    }

    static func segments(_ vectors: [QuiverVector], range: ClosedRange<Double>) -> [CanvasSegment] {
        let lo: Double = range.lowerBound
        let span: Double = max(range.upperBound - lo, 1e-9)
        return vectors.map { (f: QuiverVector) -> CanvasSegment in
            CanvasSegment(a: f.a, b: f.b, color: VelocityColors.color((f.speed - lo) / span), width: 1.4, arrow: true)
        }
    }
}
