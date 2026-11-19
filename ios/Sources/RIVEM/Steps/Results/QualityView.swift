import RiverCore
import SwiftUI

extension QualityLevel {
    var color: Color {
        switch self {
        case .good: return .green
        case .fair: return .orange
        case .poor: return .red
        }
    }

    var symbol: String {
        switch self {
        case .good: return "checkmark.circle.fill"
        case .fair: return "exclamationmark.circle.fill"
        case .poor: return "xmark.octagon.fill"
        }
    }
}

/// Three lamps, the one of `level` lit.
struct TrafficLight: View {
    let level: QualityLevel
    var size: CGFloat = 14

    var body: some View {
        HStack(spacing: size * 0.3) {
            ForEach([QualityLevel.poor, .fair, .good], id: \.self) { (l: QualityLevel) in
                Circle()
                    .fill(l.color.opacity(l == level ? 1 : 0.15))
                    .frame(width: size, height: size)
                    .shadow(color: l == level ? l.color.opacity(0.6) : .clear, radius: 3)
            }
        }
        .padding(.horizontal, size * 0.4)
        .padding(.vertical, size * 0.3)
        .background(Capsule().fill(Color.black.opacity(0.75)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Calidad \(QualityPipeline.levelText(level))")
    }
}

/// "Calidad del aforo" of one section: traffic light with Q ± U (95 %), one row per criterion (the hint of the ones
/// that are not good), the doubtful stations (tap to see them on the image) and where the uncertainty comes from.
struct QualitySection: View {
    let assessment: SectionAssessment
    let units: UnitDisplay
    let showName: Bool
    let onSelectStation: (Int) -> Void

    @State private var showBudget = false

    private var quality: GaugingQuality { assessment.quality }
    private var u: DischargeUncertainty { assessment.uncertainty }

    var body: some View {
        Section {
            HStack(spacing: 12) {
                TrafficLight(level: quality.overall, size: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(QualityPipeline.levelText(quality.overall))
                        .font(.headline)
                        .foregroundStyle(quality.overall.color)
                    Text("Q = \(units.dischargeText(u.discharge, 2)) ± \(units.dischargeText(u.expanded, 2)) (95 %)")
                        .font(.subheadline.monospacedDigit())
                }
            }
            .padding(.vertical, 2)
            ForEach(quality.checks, id: \.criterion) { (c: QualityCheck) in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Image(systemName: c.level.symbol).foregroundStyle(c.level.color)
                        Text(QualityPipeline.title(c.criterion))
                        Spacer()
                        Text(QualityPipeline.valueText(c)).monospacedDigit().foregroundStyle(.secondary)
                    }
                    if c.level != .good {
                        Text(QualityPipeline.hint(c.criterion, georeference: assessment.fit))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ForEach(quality.doubtful, id: \.index) { (d: DoubtfulStation) in
                Button {
                    onSelectStation(d.index)
                } label: {
                    Label("Estación \(d.index + 1): \(d.reasons.map(QualityPipeline.reasonText).joined(separator: ", "))",
                          systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .foregroundStyle(.orange)
                }
                .accessibilityHint("Muestra la estación en la imagen")
            }
            DisclosureGroup("De dónde viene la incertidumbre", isExpanded: $showBudget) {
                ForEach(budget) { (item: BudgetItem) in
                    HStack(spacing: 8) {
                        Text(QualityPipeline.sourceText(item.source)).font(.caption)
                        Spacer(minLength: 4)
                        GeometryReader { (g: GeometryProxy) in
                            Capsule().fill(Color.accentColor.opacity(0.2))
                                .overlay(alignment: .leading) {
                                    Capsule().fill(Color.accentColor).frame(width: g.size.width * item.share)
                                }
                        }
                        .frame(width: 80, height: 8)
                        Text(Fmt.percent(item.share)).font(.caption.monospacedDigit()).frame(width: 40, alignment: .trailing)
                    }
                    .accessibilityElement(children: .combine)
                }
                Text("Intervalo del 95 %: \(units.dischargeText(u.lower, 2)) – \(units.dischargeText(u.upper, 2)). "
                     + "Simulación Monte Carlo (4000 muestras) de α, la velocidad promedio de los pares, el nivel, la batimetría "
                     + "y la escala de la georreferenciación (\(Fmt.percent(assessment.scaleStd, 1)), según los residuos de los puntos).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text(showName ? "Calidad del aforo · \(assessment.name)" : "Calidad del aforo")
        }
    }

    private struct BudgetItem: Identifiable {
        let source: UncertaintySource
        let share: Double
        var id: String { source.rawValue }
    }

    /// Sources that contribute, largest first.
    private var budget: [BudgetItem] {
        UncertaintySource.allCases
            .map { BudgetItem(source: $0, share: u.shares[$0] ?? 0) }
            .filter { $0.share > 0.0005 }
            .sorted { $0.share > $1.share }
    }
}

/// The assumptions of the uncertainty (saved in the project).
struct UncertaintyAssumptionsSection: View {
    @Binding var assumptions: UncertaintyAssumptions
    let units: UnitDisplay
    let onChange: () -> Void

    var body: some View {
        Section {
            DisclosureGroup("Supuestos de la incertidumbre") {
                Stepper(value: $assumptions.levelStd, in: 0...0.2, step: 0.005) {
                    LabeledContent("Nivel del agua ±", value: units.lengthText(assumptions.levelStd, 3))
                }
                Stepper(value: $assumptions.depthStd, in: 0...0.5, step: 0.01) {
                    LabeledContent("Batimetría ±", value: units.lengthText(assumptions.depthStd, 2))
                }
                Stepper(value: $assumptions.alphaStd, in: 0...0.2, step: 0.01) {
                    LabeledContent("Coeficiente α ±", value: Fmt.num(assumptions.alphaStd, 2))
                }
                Text("Desviaciones estándar (1σ). Valores típicos: nivel leído en regla ±1 cm, batimetría ±2 cm, α = 0,85 ± 0,05 si no se calibró. Se guardan en el proyecto.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .onChange(of: assumptions) { onChange() }
    }
}
