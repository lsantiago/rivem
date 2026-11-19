import RiverCore
import SwiftUI

/// Velocity colour scale of the Results screen (cyan → green → yellow → red, like desktop's), for t in 0…1.
enum VelocityColors {
    private static let stops: [(t: Double, r: Double, g: Double, b: Double)] = [
        (0.00, 0.33, 0.80, 0.95),
        (0.35, 0.40, 0.82, 0.45),
        (0.70, 0.98, 0.78, 0.28),
        (1.00, 0.93, 0.33, 0.30),
    ]

    static func color(_ t: Double) -> Color {
        let x: Double = t.isFinite ? min(max(t, 0), 1) : 0
        for k in 1..<stops.count where x <= stops[k].t {
            let a = stops[k - 1], b = stops[k]
            let f: Double = (x - a.t) / max(b.t - a.t, 1e-9)
            return Color(red: a.r + f * (b.r - a.r), green: a.g + f * (b.g - a.g), blue: a.b + f * (b.b - a.b))
        }
        let last = stops[stops.count - 1]
        return Color(red: last.r, green: last.g, blue: last.b)
    }

    static var gradient: Gradient {
        Gradient(stops: stops.map { Gradient.Stop(color: Color(red: $0.r, green: $0.g, blue: $0.b), location: $0.t) })
    }
}

/// A station of a section, as selected on the canvas.
struct StationRef: Equatable {
    let section: Int
    let station: Int
}

/// Animated chevrons along each section: one group per station with velocity, pointing downstream
/// (`streamwise_x/y`), moving at a speed proportional to the surface velocity and coloured on `range`.
/// Unchecked stations are grey. The section line and its banks (L red, R green) are drawn underneath.
enum ChevronLayer {
    static func draw(_ context: inout GraphicsContext, layout: CanvasLayout, results: [SectionResult],
                     range: ClosedRange<Double>, time: Double, selected: StationRef?) {
        for (si, r) in results.enumerated() {
            let n: Int = min(r.stationX.count, r.stationY.count)
            guard n >= 2 else { continue }
            let a: CGPoint = layout.view(Point2(r.stationX[0], r.stationY[0]))
            let b: CGPoint = layout.view(Point2(r.stationX[n - 1], r.stationY[n - 1]))
            var line = Path()
            line.move(to: a)
            line.addLine(to: b)
            context.stroke(line, with: .color(.black.opacity(0.35)), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            context.stroke(line, with: .color(.yellow), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
            bank(&context, at: a, label: "I", color: .red)
            bank(&context, at: b, label: "D", color: .green)

            let span: Double = max(range.upperBound - range.lowerBound, 1e-9)
            for i in 0..<min(n, r.streamX.count, r.streamY.count) {
                guard let v = r.value(r.velocity, i), r.stationX[i].isFinite, r.stationY[i].isFinite,
                      r.streamX[i].isFinite, r.streamY[i].isFinite else { continue }
                let p: CGPoint = layout.view(Point2(r.stationX[i], r.stationY[i]))
                let q: CGPoint = layout.view(Point2(r.stationX[i] + r.streamX[i], r.stationY[i] + r.streamY[i]))
                let length: CGFloat = hypot(q.x - p.x, q.y - p.y)
                guard length > 1e-6 else { continue }
                let u = CGPoint(x: (q.x - p.x) / length, y: (q.y - p.y) / length)
                let t: Double = (v - range.lowerBound) / span
                let checked: Bool = i >= r.check.count || r.check[i]
                let color: Color = checked ? VelocityColors.color(t) : .gray
                chevrons(&context, at: p, direction: u, color: color, speed: 5 + 28 * min(max(t, 0), 1), time: time)
                if selected == StationRef(section: si, station: i) {
                    marker(&context, at: p, number: i + 1)
                }
            }
        }
    }

    /// Three chevrons travelling through the station, fading in and out.
    private static func chevrons(_ context: inout GraphicsContext, at p: CGPoint, direction u: CGPoint, color: Color,
                                 speed: Double, time: Double) {
        let spacing: CGFloat = 11
        let size: CGFloat = 6
        let normal = CGPoint(x: -u.y, y: u.x)
        let phase: CGFloat = CGFloat((time * speed).truncatingRemainder(dividingBy: Double(spacing)))
        for k in 0..<3 {
            let s: CGFloat = CGFloat(k - 1) * spacing + phase - spacing / 2
            let fade: CGFloat = max(0, 1 - abs(s) / (spacing * 1.6))
            let c = CGPoint(x: p.x + u.x * s, y: p.y + u.y * s)
            let tip = CGPoint(x: c.x + u.x * size * 0.6, y: c.y + u.y * size * 0.6)
            let left = CGPoint(x: c.x - u.x * size * 0.5 + normal.x * size, y: c.y - u.y * size * 0.5 + normal.y * size)
            let right = CGPoint(x: c.x - u.x * size * 0.5 - normal.x * size, y: c.y - u.y * size * 0.5 - normal.y * size)
            var path = Path()
            path.move(to: left)
            path.addLine(to: tip)
            path.addLine(to: right)
            context.stroke(path, with: .color(.black.opacity(0.35 * fade)), style: StrokeStyle(lineWidth: 4.5, lineCap: .round, lineJoin: .round))
            context.stroke(path, with: .color(color.opacity(Double(fade))), style: StrokeStyle(lineWidth: 2.8, lineCap: .round, lineJoin: .round))
        }
    }

    private static func bank(_ context: inout GraphicsContext, at p: CGPoint, label: String, color: Color) {
        let circle = Path(ellipseIn: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16))
        context.fill(circle, with: .color(color))
        context.stroke(circle, with: .color(.white), lineWidth: 1.5)
        context.draw(Text(label).font(.system(size: 9, weight: .bold)).foregroundColor(.white), at: p, anchor: .center)
    }

    private static func marker(_ context: inout GraphicsContext, at p: CGPoint, number: Int) {
        let circle = Path(ellipseIn: CGRect(x: p.x - 10, y: p.y - 10, width: 20, height: 20))
        context.fill(circle, with: .color(.black.opacity(0.75)))
        context.stroke(circle, with: .color(.white), lineWidth: 2)
        context.draw(Text("\(number)").font(.system(size: 10, weight: .bold)).foregroundColor(.white), at: p, anchor: .center)
    }

    /// Nearest station to an image point, within `radius` image pixels.
    static func hit(_ point: Point2, results: [SectionResult], radius: Double) -> StationRef? {
        var best: StationRef? = nil
        var bestDistance: Double = radius
        for (si, r) in results.enumerated() {
            for i in 0..<min(r.stationX.count, r.stationY.count) {
                let d: Double = hypot(r.stationX[i] - point.x, r.stationY[i] - point.y)
                if d.isFinite && d < bestDistance {
                    bestDistance = d
                    best = StationRef(section: si, station: i)
                }
            }
        }
        return best
    }
}

/// Colour bar with the range and unit, drawn over the canvas.
struct VelocityLegend: View {
    let range: ClosedRange<Double>
    let units: UnitDisplay

    var body: some View {
        HStack(spacing: 6) {
            Text(Fmt.num(units.velocity(range.lowerBound), 2))
            Capsule()
                .fill(LinearGradient(gradient: VelocityColors.gradient, startPoint: .leading, endPoint: .trailing))
                .frame(width: 80, height: 6)
            Text(Fmt.num(units.velocity(range.upperBound), 2))
            Text(units.velocityUnit).foregroundStyle(.secondary)
        }
        .font(.caption2.monospacedDigit())
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(.ultraThinMaterial, in: Capsule())
        .padding(8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Escala de velocidad de \(Fmt.num(units.velocity(range.lowerBound), 2)) a \(Fmt.num(units.velocity(range.upperBound), 2)) \(units.velocityUnit)")
    }
}

/// Card of a tapped station (desktop's station tooltip): discharge and share, depth and area, bed stage, surface
/// velocity ± σ and its 5–95 % band.
struct StationCard: View {
    let result: SectionResult
    let station: Int
    let units: UnitDisplay
    let onClose: () -> Void

    var body: some View {
        let i: Int = station
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Estación \(i + 1)").font(.headline)
                if let d = result.value(result.distance, i) {
                    Text("x \(units.lengthText(d))").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.secondary)
                }
                .accessibilityLabel("Cerrar")
            }
            row("Caudal", dischargeText(i))
            row("Profundidad · área", "\(units.lengthText(result.value(result.depth, i))) · \(units.areaText(result.value(result.area, i)))")
            row("Cota del fondo", units.lengthText(stage(i)))
            row("Velocidad superficial", velocityText(i))
            if let lo = result.value(result.p5, i), let hi = result.value(result.p95, i) {
                row("P5–P95", "\(Fmt.num(units.velocity(lo), 2))–\(Fmt.num(units.velocity(hi), 2)) \(units.velocityUnit)")
            }
            if i < result.check.count && !result.check[i] {
                Text("Estación desmarcada: no entra en el caudal.").font(.caption).foregroundStyle(.orange)
            }
        }
        .font(.subheadline)
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .frame(maxWidth: 360)
        .padding(8)
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit().multilineTextAlignment(.trailing)
        }
    }

    private func stage(_ i: Int) -> Double? {
        guard let level = result.level, let d = result.value(result.depth, i) else { return nil }
        return level - d
    }

    private func dischargeText(_ i: Int) -> String {
        guard let q = result.value(result.q, i) else { return "—" }
        var text: String = units.dischargeText(q, 2)
        if let total = result.totalQ, total.isFinite, abs(total) > 1e-12 {
            text += " · " + Fmt.percent(q / total, 1)
        }
        return text
    }

    private func velocityText(_ i: Int) -> String {
        guard let v = result.value(result.velocity, i) else { return "—" }
        var text: String = units.velocityText(v, 2)
        if let hi = result.value(result.plusStd, i) {
            text += " ± " + Fmt.num(units.velocity(abs(hi - v)), 2)
        }
        return text
    }
}

/// Big total discharge with the measured / interpolated share (desktop's results header).
struct DischargeHero: View {
    let result: SectionResult
    let units: UnitDisplay
    let showName: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if showName { Text(result.name).font(.caption).foregroundStyle(.secondary) }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(Fmt.num(result.totalQ.map { units.discharge($0) }, 2))
                    .font(.system(size: 44, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text(units.dischargeUnit).font(.title3).foregroundStyle(.secondary)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(Fmt.percent(result.measuredQ, 1)) medido")
                    Text("\(Fmt.percent(interpolatedShare, 1)) interpolado")
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            if let std = result.totalQStd, std.isFinite {
                Text("± \(units.dischargeText(std, 2)) (desvío entre pares)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var interpolatedShare: Double? {
        if let i = result.interpolatedQ { return i }
        return result.measuredQ.map { 1 - $0 }
    }
}
