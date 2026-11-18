import RiverCore
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// One editable point of the bed profile.
struct BathymetryRow: Identifiable, Equatable {
    let id: UUID
    var station: String
    var stage: String

    init(station: String, stage: String) {
        id = UUID()
        self.station = station
        self.stage = stage
    }
}

/// In-app bathymetry for a section with no surveyed file: a quick template (rectangular / trapezoidal channel from
/// water width, depth and banks), an editable table of (station, elevation) points, or a CSV pasted from the
/// clipboard. "Usar" hands back the profile (and the template's water level); the caller writes it into the project
/// as the same `station,stage` CSV an import produces.
struct BathymetryEditorView: View {
    let sectionName: String
    let initial: Bathymetry?
    let onSave: (Bathymetry, Double?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var rows: [BathymetryRow] = []
    @State private var shape: BathymetryTemplates.Shape = .trapezoidal
    @State private var widthText = "10"
    @State private var depthText = "1"
    @State private var slopeText = "2"
    @State private var freeboardText = "0.5"
    @State private var levelText = "10"
    /// Water level of the last template applied (nil once the user builds the table by hand).
    @State private var templateLevel: Double? = nil
    @State private var error: String? = nil
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                templateSection
                pointsSection
                if let b = current {
                    Section("Vista previa") {
                        BathymetryChart(bathymetry: b, level: templateLevel ?? SectionsPipeline.defaultLevel(b))
                            .frame(height: 180)
                    }
                }
                Section {
                    ErrorBanner(message: error ?? validationMessage)
                } footer: {
                    Text("Estación: distancia horizontal en metros desde la margen izquierda, creciente. Cota: elevación del lecho en metros, en el mismo sistema que el nivel del agua. Se guarda como \(SectionsPipeline.editorFileName(section: sectionName)) (station,stage), igual que en RIVeR escritorio.")
                }
            }
            .navigationTitle("Batimetría · \(sectionName)")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Usar") {
                        if let b = current {
                            onSave(b, templateLevel)
                            dismiss()
                        }
                    }
                    .disabled(current == nil)
                }
            }
            .onAppear(perform: load)
        }
    }

    private var templateSection: some View {
        Section {
            Picker("Forma", selection: $shape) {
                ForEach(BathymetryTemplates.Shape.allCases) { s in
                    Text(s.label).tag(s)
                }
            }
            .pickerStyle(.segmented)
            NumberField(title: "Ancho del agua", text: $widthText)
            NumberField(title: "Profundidad máxima", text: $depthText)
            if shape == .trapezoidal {
                NumberField(title: "Talud (horizontal por 1 vertical)", text: $slopeText, unit: "H:V")
            }
            NumberField(title: "Altura de las orillas sobre el agua", text: $freeboardText)
            NumberField(title: "Cota del nivel del agua", text: $levelText)
            Button {
                applyTemplate()
            } label: {
                Label("Generar perfil con la plantilla", systemImage: "wand.and.stars")
            }
        } header: {
            Text("Plantilla rápida")
        } footer: {
            Text("Reemplaza la tabla. El nivel del agua de la sección se fija en la cota indicada.")
        }
    }

    private var pointsSection: some View {
        Section {
            ForEach($rows) { $row in
                HStack {
                    TextField("estación", text: $row.station)
                        .decimalKeyboard()
                    Divider()
                    TextField("cota", text: $row.stage)
                        .decimalKeyboard()
                        .multilineTextAlignment(.trailing)
                }
            }
            .onDelete { offsets in
                rows.remove(atOffsets: offsets)
                templateLevel = nil
            }
            Button {
                rows.append(BathymetryRow(station: "", stage: ""))
            } label: {
                Label("Agregar punto", systemImage: "plus")
            }
            Button {
                sortRows()
            } label: {
                Label("Ordenar por estación", systemImage: "arrow.up.arrow.down")
            }
            Button {
                pasteCSV()
            } label: {
                Label("Pegar CSV del portapapeles", systemImage: "doc.on.clipboard")
            }
        } header: {
            Text("Puntos (estación m, cota m): \(rows.count)")
        }
    }

    /// The profile as typed, or nil if it is not usable yet.
    private var current: Bathymetry? {
        try? BathymetryTemplates.fromRows(parsedRows)
    }

    private var parsedRows: [(Double, Double)] {
        var out: [(Double, Double)] = []
        for r in rows {
            if let s = Fmt.parse(r.station), let z = Fmt.parse(r.stage) { out.append((s, z)) }
        }
        return out
    }

    private var validationMessage: String? {
        let incomplete: Int = rows.count - parsedRows.count
        if rows.isEmpty { return "Genera un perfil con la plantilla, pega un CSV o agrega puntos." }
        if incomplete > 0 { return "Hay \(incomplete) filas incompletas o con números no válidos (se ignoran)." }
        if current == nil { return "Se necesitan al menos dos puntos con estaciones distintas." }
        return nil
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let b = initial {
            setRows(b)
        }
    }

    private func setRows(_ b: Bathymetry) {
        var out: [BathymetryRow] = []
        let n: Int = min(b.stations.count, b.elevations.count)
        for i in 0..<n {
            out.append(BathymetryRow(station: Fmt.num(b.stations[i], 3), stage: Fmt.num(b.elevations[i], 3)))
        }
        rows = out
    }

    private func applyTemplate() {
        guard let w = Fmt.parse(widthText), let d = Fmt.parse(depthText), let fb = Fmt.parse(freeboardText),
              let level = Fmt.parse(levelText) else {
            error = "Completa ancho, profundidad, altura de orillas y nivel."
            return
        }
        let slope: Double = Fmt.parse(slopeText) ?? 0
        do {
            let b: Bathymetry = try BathymetryTemplates.make(shape, waterWidth: w, depth: d, bankSlope: slope,
                                                              freeboard: fb, level: level)
            setRows(b)
            templateLevel = level
            error = nil
        } catch {
            self.error = "\(error)"
        }
    }

    private func sortRows() {
        rows = rows.enumerated().sorted { l, r in
            let a: Double = Fmt.parse(l.element.station) ?? .infinity
            let b: Double = Fmt.parse(r.element.station) ?? .infinity
            return a != b ? a < b : l.offset < r.offset
        }.map { $0.element }
    }

    private func pasteCSV() {
        #if os(iOS)
        guard let text = UIPasteboard.general.string, !text.isEmpty else {
            error = "El portapapeles no contiene texto."
            return
        }
        do {
            let b: Bathymetry = try Bathymetry.parse(csv: text)
            setRows(b)
            templateLevel = nil
            error = nil
        } catch {
            self.error = "No se pudo leer el CSV pegado: \(error)"
        }
        #endif
    }
}
