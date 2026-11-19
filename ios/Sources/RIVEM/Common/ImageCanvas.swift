import CoreGraphics
import RiverCore
import SwiftUI

/// A point drawn on the canvas, in frame pixel coordinates.
struct CanvasPoint: Identifiable {
    let id: Int
    var point: Point2
    var label: String
    var color: Color
}

/// A segment (section line, vector, residual) in frame pixel coordinates.
struct CanvasSegment {
    var a: Point2
    var b: Point2
    var color: Color
    var width: CGFloat = 2
    var dashed: Bool = false
    var arrow: Bool = false
}

/// Maps frame pixels ↔ view points for a given zoom/offset.
struct CanvasLayout {
    let origin: CGPoint
    /// View points per frame pixel.
    let scale: CGFloat
    let imageSize: CGSize

    init(viewSize: CGSize, imageSize: CGSize, zoom: CGFloat, offset: CGSize) {
        self.imageSize = imageSize
        var fit: CGFloat = 1
        if imageSize.width > 0 && imageSize.height > 0 {
            fit = min(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        }
        scale = fit * zoom
        let w: CGFloat = imageSize.width * scale
        let h: CGFloat = imageSize.height * scale
        origin = CGPoint(x: (viewSize.width - w) / 2 + offset.width, y: (viewSize.height - h) / 2 + offset.height)
    }

    var imageRect: CGRect {
        CGRect(x: origin.x, y: origin.y, width: imageSize.width * scale, height: imageSize.height * scale)
    }

    func view(_ p: Point2) -> CGPoint {
        CGPoint(x: origin.x + CGFloat(p.x) * scale, y: origin.y + CGFloat(p.y) * scale)
    }

    func image(_ v: CGPoint) -> Point2 {
        guard scale > 0 else { return Point2(0, 0) }
        return Point2(Double((v.x - origin.x) / scale), Double((v.y - origin.y) / scale))
    }
}

/// Zoomable, pannable frame with points, segments and an optional overlay (mask). One finger: tap places a point
/// (`onTap`), dragging a point moves it (`onMove`), dragging elsewhere pans; two fingers zoom. Coordinates handed to
/// the callbacks are frame pixels (x right, y down), the space of every desktop geometry file.
struct ImageCanvas: View {
    let image: CGImage?
    /// Size of the full-resolution frame (the display image may be a downscaled copy).
    let imageSize: CGSize
    var points: [CanvasPoint] = []
    var segments: [CanvasSegment] = []
    var overlay: CGImage? = nil
    var onTap: ((Point2) -> Void)? = nil
    var onMove: ((Int, Point2) -> Void)? = nil
    /// Layer redrawn every frame (≤ 30 fps) on top of everything else, given the elapsed time in seconds. With
    /// "Reduce Motion" on, it is drawn once with time 0 (a still frame).
    var animated: ((inout GraphicsContext, CanvasLayout, Double) -> Void)? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum DragMode {
        case point(Int)
        case pan(CGSize)
    }

    @State private var zoom: CGFloat = 1
    @State private var pinchStart: CGFloat? = nil
    @State private var offset: CGSize = .zero
    @State private var dragMode: DragMode? = nil

    var body: some View {
        GeometryReader { geo in
            canvasBody(size: geo.size)
        }
        .clipped()
        .background(Color.black)
        .overlay(alignment: .bottomTrailing) { zoomControls }
    }

    private func canvasBody(size: CGSize) -> some View {
        layeredCanvas(size: size)
            .contentShape(Rectangle())
            .gesture(dragGesture(viewSize: size))
            .simultaneousGesture(magnifyGesture)
    }

    @ViewBuilder
    private func layeredCanvas(size: CGSize) -> some View {
        let layout = CanvasLayout(viewSize: size, imageSize: imageSize, zoom: zoom, offset: offset)
        if let layer = animated {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { (timeline: TimelineViewDefaultContext) in
                let t: Double = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate
                Canvas { (context: inout GraphicsContext, _: CGSize) in
                    draw(&context, layout: layout)
                    layer(&context, layout, t)
                }
            }
        } else {
            Canvas { (context: inout GraphicsContext, _: CGSize) in
                draw(&context, layout: layout)
            }
        }
    }

    private var zoomControls: some View {
        HStack(spacing: 6) {
            Button { setZoom(zoom / 1.6) } label: { Image(systemName: "minus.magnifyingglass") }
            Button { setZoom(zoom * 1.6) } label: { Image(systemName: "plus.magnifyingglass") }
            Button {
                zoom = 1
                offset = .zero
            } label: { Image(systemName: "arrow.up.left.and.down.right.magnifyingglass") }
        }
        .font(.title3)
        .padding(6)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
        .padding(8)
    }

    private func setZoom(_ z: CGFloat) {
        let clamped: CGFloat = min(max(z, 1), 20)
        let ratio: CGFloat = clamped / zoom
        offset = CGSize(width: offset.width * ratio, height: offset.height * ratio)
        zoom = clamped
    }

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .onChanged { (value: MagnifyGesture.Value) in
                let start: CGFloat = pinchStart ?? zoom
                if pinchStart == nil { pinchStart = zoom }
                zoom = min(max(start * value.magnification, 1), 20)
            }
            .onEnded { _ in
                pinchStart = nil
            }
    }

    private func dragGesture(viewSize: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { (value: DragGesture.Value) in
                let layout = CanvasLayout(viewSize: viewSize, imageSize: imageSize, zoom: zoom, offset: offset)
                if dragMode == nil {
                    if onMove != nil, let id = hitPoint(value.startLocation, layout: layout) {
                        dragMode = .point(id)
                    } else {
                        dragMode = .pan(offset)
                    }
                }
                guard let mode = dragMode else { return }
                switch mode {
                case let .point(id):
                    onMove?(id, clamp(layout.image(value.location)))
                case let .pan(start):
                    let moved: CGFloat = hypot(value.translation.width, value.translation.height)
                    if moved >= 8 {
                        offset = CGSize(width: start.width + value.translation.width, height: start.height + value.translation.height)
                    }
                }
            }
            .onEnded { (value: DragGesture.Value) in
                let moved: CGFloat = hypot(value.translation.width, value.translation.height)
                if let mode = dragMode, case let .pan(start) = mode, moved < 8 {
                    offset = start
                    let layout = CanvasLayout(viewSize: viewSize, imageSize: imageSize, zoom: zoom, offset: start)
                    let p: Point2 = layout.image(value.location)
                    if p.x >= 0, p.y >= 0, p.x <= Double(imageSize.width), p.y <= Double(imageSize.height) {
                        onTap?(p)
                    }
                }
                dragMode = nil
            }
    }

    private func hitPoint(_ location: CGPoint, layout: CanvasLayout) -> Int? {
        var best: Int? = nil
        var bestDistance: CGFloat = 30
        for p in points {
            let v: CGPoint = layout.view(p.point)
            let d: CGFloat = hypot(v.x - location.x, v.y - location.y)
            if d < bestDistance {
                bestDistance = d
                best = p.id
            }
        }
        return best
    }

    private func clamp(_ p: Point2) -> Point2 {
        Point2(min(max(p.x, 0), Double(imageSize.width)), min(max(p.y, 0), Double(imageSize.height)))
    }

    // MARK: Drawing

    private func draw(_ context: inout GraphicsContext, layout: CanvasLayout) {
        let rect: CGRect = layout.imageRect
        if let image = image {
            context.draw(Image(decorative: image, scale: 1), in: rect)
        }
        if let overlay = overlay {
            context.draw(Image(decorative: overlay, scale: 1), in: rect)
        }
        for s in segments {
            drawSegment(&context, s, layout: layout)
        }
        for p in points {
            let c: CGPoint = layout.view(p.point)
            let circle = Path(ellipseIn: CGRect(x: c.x - 9, y: c.y - 9, width: 18, height: 18))
            context.fill(circle, with: .color(p.color.opacity(0.35)))
            context.stroke(circle, with: .color(.white), lineWidth: 2)
            var cross = Path()
            cross.move(to: CGPoint(x: c.x - 4, y: c.y))
            cross.addLine(to: CGPoint(x: c.x + 4, y: c.y))
            cross.move(to: CGPoint(x: c.x, y: c.y - 4))
            cross.addLine(to: CGPoint(x: c.x, y: c.y + 4))
            context.stroke(cross, with: .color(p.color), lineWidth: 1.5)
            if !p.label.isEmpty {
                let text = Text(p.label).font(.caption.bold()).foregroundColor(.white)
                context.draw(text, at: CGPoint(x: c.x + 14, y: c.y - 14), anchor: .center)
            }
        }
    }

    private func drawSegment(_ context: inout GraphicsContext, _ s: CanvasSegment, layout: CanvasLayout) {
        let a: CGPoint = layout.view(s.a)
        let b: CGPoint = layout.view(s.b)
        var path = Path()
        path.move(to: a)
        path.addLine(to: b)
        let style = StrokeStyle(lineWidth: s.width, lineCap: .round, dash: s.dashed ? [6, 4] : [])
        context.stroke(path, with: .color(s.color), style: style)
        if s.arrow {
            let dx: CGFloat = b.x - a.x
            let dy: CGFloat = b.y - a.y
            let length: CGFloat = hypot(dx, dy)
            guard length > 2 else { return }
            let ux: CGFloat = dx / length
            let uy: CGFloat = dy / length
            let head: CGFloat = min(8, length * 0.4)
            var tip = Path()
            tip.move(to: b)
            tip.addLine(to: CGPoint(x: b.x - head * (ux - 0.5 * uy), y: b.y - head * (uy + 0.5 * ux)))
            tip.move(to: b)
            tip.addLine(to: CGPoint(x: b.x - head * (ux + 0.5 * uy), y: b.y - head * (uy - 0.5 * ux)))
            context.stroke(tip, with: .color(s.color), style: StrokeStyle(lineWidth: s.width, lineCap: .round))
        }
    }
}

/// Canvas on top (portrait) or on the left (landscape), controls in the rest of the screen. The canvas is outside
/// the scrolling form so its gestures never fight with scrolling.
struct CanvasStepLayout<CanvasContent: View, Controls: View>: View {
    let canvas: CanvasContent
    let controls: Controls

    init(@ViewBuilder canvas: () -> CanvasContent, @ViewBuilder controls: () -> Controls) {
        self.canvas = canvas()
        self.controls = controls()
    }

    var body: some View {
        GeometryReader { geo in
            if geo.size.width > geo.size.height {
                HStack(spacing: 0) {
                    canvas.frame(width: geo.size.width * 0.58)
                    controls.frame(maxWidth: .infinity)
                }
            } else {
                VStack(spacing: 0) {
                    canvas.frame(height: geo.size.height * 0.45)
                    controls.frame(maxHeight: .infinity)
                }
            }
        }
    }
}

/// Decimal keyboard on iOS (no-op elsewhere).
extension View {
    func decimalKeyboard() -> some View {
        #if os(iOS)
        return self.keyboardType(.numbersAndPunctuation)
        #else
        return self
        #endif
    }

    func integerKeyboard() -> some View {
        #if os(iOS)
        return self.keyboardType(.numberPad)
        #else
        return self
        #endif
    }
}
