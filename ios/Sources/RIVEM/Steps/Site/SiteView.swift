import Charts
import RiverCore
import SwiftUI

/// RIVEM addition: the gaugings of a site (`RIVeR/<name>/`, one per dated folder with results) and its stage–discharge
/// curve Q = a·(h − h₀)ᵇ, with a calculator that estimates Q from a level read on the staff gauge. Gaugings can be
/// left out of the fit (tap). Nothing is written: the curve is refitted from the projects every time.
struct SiteView: View {
    let siteFolder: URL
    let units: UnitDisplay

    @State private var gaugings: [SiteGauging] = []
    @State private var excluded: Set<URL> = []
    @State private var levelText: String = ""

    private var included: [SiteGauging] { gaugings.filter { !excluded.contains($0.folder) } }

    private var curve: RatingCurve? {
        RatingCurve.fit(included.map { RatingPoint(level: $0.level, discharge: $0.discharge) })
    }

    var body: some View {
        let c: RatingCurve? = curve
        List {
            Section {
                if gaugings.isEmpty {
                    Text("Este sitio aún no tiene aforos con resultados.").foregroundStyle(.secondary)
                } else {
                    chart(c)
                        .frame(height: 240)
                        .padding(.vertical, 4)
                }
                if let c = c {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Q = \(Fmt.num(c.a, 3)) · (h − \(Fmt.num(c.h0, 3)))^\(Fmt.num(c.b, 3))")
                            .font(.body.monospaced())
                        Text("R² \(Fmt.num(c.r2, 4)) · error relativo RMS \(Fmt.percent(c.relativeRMS, 1)) · \(c.pointCount) aforos · h y h₀ en m, Q en m³/s")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if c.pointCount < 5 {
                        Label("Con menos de 5 aforos la curva es orientativa: el ajuste tiene 3 parámetros.", systemImage: "info.circle")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                } else if !gaugings.isEmpty {
                    Text("Hacen falta al menos \(RatingCurve.minimumPoints) aforos con resultados a niveles distintos (hay \(included.count)).")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Curva nivel–caudal")
            }

            if let c = c {
                Section("Estimar el caudal desde el nivel") {
                    HStack {
                        Text("Nivel")
                        TextField("nivel", text: $levelText)
                            .multilineTextAlignment(.trailing)
                            #if os(iOS)
                            .keyboardType(.decimalPad)
                            #endif
                        Text(units.lengthUnit).foregroundStyle(.secondary)
                    }
                    if let typed = Fmt.parse(levelText) {
                        let h: Double = units.lengthToSI(typed)
                        LabeledContent("Caudal estimado", value: units.dischargeText(c.discharge(at: h), 2))
                            .font(.headline)
                        if let lo = included.map(\.level).min(), let hi = included.map(\.level).max(), h < lo || h > hi {
                            Text("Fuera del rango aforado (\(units.lengthText(lo, 2)) – \(units.lengthText(hi, 2))): extrapolación.")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                    }
                }
            }

            Section {
                ForEach(gaugings) { (g: SiteGauging) in
                    Button {
                        toggle(g)
                    } label: {
                        row(g, curve: c)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Aforos del sitio")
            } footer: {
                Text("Toca un aforo para sacarlo del ajuste. Cada visita es una carpeta con fecha dentro de RIVeR/\(siteFolder.lastPathComponent)/. "
                     + "El nivel debe estar en la misma referencia en todas (la de la batimetría).")
            }
        }
        .navigationTitle(siteFolder.lastPathComponent)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            gaugings = SitePipeline.gaugings(siteFolder: siteFolder)
            if levelText.isEmpty, let last = gaugings.last { levelText = Fmt.num(units.length(last.level), 2) }
        }
    }

    private func toggle(_ g: SiteGauging) {
        if excluded.contains(g.folder) { excluded.remove(g.folder) } else { excluded.insert(g.folder) }
    }

    private func row(_ g: SiteGauging, curve: RatingCurve?) -> some View {
        let isIn: Bool = !excluded.contains(g.folder)
        return HStack(spacing: 10) {
            Image(systemName: isIn ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(isIn ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(g.date.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? g.stamp)
                Text("h \(units.lengthText(g.level, 3)) · Q \(units.dischargeText(g.discharge, 2))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if let c = curve, isIn {
                let r: Double = (c.discharge(at: g.level) - g.discharge) / g.discharge
                Text((r >= 0 ? "+" : "") + Fmt.percent(r, 1))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(abs(r) > 0.1 ? Color.orange : Color.secondary)
                    .accessibilityLabel("Desvío de la curva \(Fmt.percent(r, 1))")
            }
        }
        .contentShape(Rectangle())
    }

    private struct CurveSample: Identifiable {
        let id: Int
        let q: Double
        let h: Double
    }

    private func chart(_ c: RatingCurve?) -> some View {
        let xLabel: String = "Caudal (\(units.dischargeUnit))"
        let yLabel: String = "Nivel (\(units.lengthUnit))"
        var samples: [CurveSample] = []
        if let c = c, let lo = included.map(\.level).min(), let hi = included.map(\.level).max() {
            let span: Double = max(hi - lo, 0.05)
            let start: Double = max(c.h0, lo - 0.3 * span)
            let end: Double = hi + 0.2 * span
            for k in 0...60 {
                let h: Double = start + (end - start) * Double(k) / 60
                samples.append(CurveSample(id: k, q: units.discharge(c.discharge(at: h)), h: units.length(h)))
            }
        }
        return Chart {
            ForEach(samples) { (s: CurveSample) in
                LineMark(x: .value(xLabel, s.q), y: .value(yLabel, s.h))
                    .foregroundStyle(Color.accentColor)
                    .interpolationMethod(.catmullRom)
            }
            ForEach(gaugings) { (g: SiteGauging) in
                PointMark(x: .value(xLabel, units.discharge(g.discharge)), y: .value(yLabel, units.length(g.level)))
                    .foregroundStyle(excluded.contains(g.folder) ? Color.gray : Color.orange)
                    .symbolSize(60)
            }
        }
        .chartXAxisLabel(xLabel)
        .chartYAxisLabel(yLabel)
        .chartYScale(domain: .automatic(includesZero: false))
    }
}
