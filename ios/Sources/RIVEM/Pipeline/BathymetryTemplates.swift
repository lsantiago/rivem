import Foundation
import RiverCore

/// Bathymetries built in the app when the user has no surveyed profile: simple channel shapes from the water width,
/// depth and banks, with the water level they were built for.
enum BathymetryTemplates {
    enum Shape: String, CaseIterable, Identifiable {
        case rectangular, trapezoidal
        var id: String { rawValue }
        var label: String { self == .rectangular ? "Rectangular" : "Trapezoidal" }
    }

    /// Walls of the "rectangular" shape: almost vertical (1 mm horizontal per metre), so stations stay strictly
    /// increasing (desktop's Python interpolates on them).
    static let rectangularSlope: Double = 0.001

    /// Channel symmetric about its centre line, stations from 0 at the left bank top.
    /// - Parameters:
    ///   - waterWidth: width of the water surface at `level` (m).
    ///   - depth: maximum depth below `level` (m).
    ///   - bankSlope: horizontal / vertical of the banks (trapezoidal only; 2 means 2 m out per 1 m up).
    ///   - freeboard: bank height above the water (m); 0 = the profile ends at the water edges.
    ///   - level: water level elevation (m), in the datum of the stages.
    static func make(_ shape: Shape, waterWidth: Double, depth: Double, bankSlope: Double, freeboard: Double,
                     level: Double) throws -> Bathymetry {
        guard waterWidth > 0, depth > 0, waterWidth.isFinite, depth.isFinite else {
            throw PipelineError("El ancho y la profundidad deben ser mayores que cero.")
        }
        guard freeboard >= 0, freeboard.isFinite, level.isFinite else { throw PipelineError("Altura de orilla o nivel no válidos.") }
        let z: Double = shape == .rectangular ? rectangularSlope : bankSlope
        guard z > 0, z.isFinite else { throw PipelineError("El talud debe ser mayor que cero.") }
        let bottom: Double = waterWidth - 2 * z * depth
        guard bottom > 0 else {
            throw PipelineError(String(format: "Con ese talud el fondo quedaría con ancho %.2f m: reduce el talud o la profundidad.", bottom))
        }
        let x0: Double = z * freeboard   // left water edge
        var stations: [Double] = []
        var stages: [Double] = []
        if freeboard > 0 {
            stations.append(0)
            stages.append(level + freeboard)
        }
        stations.append(contentsOf: [x0, x0 + z * depth, x0 + waterWidth - z * depth, x0 + waterWidth])
        stages.append(contentsOf: [level, level - depth, level - depth, level])
        if freeboard > 0 {
            stations.append(x0 + waterWidth + z * freeboard)
            stages.append(level + freeboard)
        }
        return Bathymetry(stations: stations, elevations: stages)
    }

    /// Wetted area of a template at its level (for checks): trapezoid (W + bottom) / 2 · depth.
    static func area(_ shape: Shape, waterWidth: Double, depth: Double, bankSlope: Double) -> Double {
        let z: Double = shape == .rectangular ? rectangularSlope : bankSlope
        return (waterWidth + (waterWidth - 2 * z * depth)) / 2 * depth
    }

    /// Rows typed or pasted by the user (station, stage), sorted by station; at least two distinct stations.
    static func fromRows(_ rows: [(Double, Double)]) throws -> Bathymetry {
        let valid: [(Double, Double)] = rows.filter { $0.0.isFinite && $0.1.isFinite }
        let sorted: [(Double, Double)] = valid.enumerated().sorted { l, r in
            l.element.0 != r.element.0 ? l.element.0 < r.element.0 : l.offset < r.offset
        }.map { $0.element }
        guard sorted.count >= 2, let first = sorted.first, let last = sorted.last, last.0 > first.0 else {
            throw PipelineError("Se necesitan al menos dos puntos con estaciones distintas.")
        }
        return Bathymetry(stations: sorted.map { $0.0 }, elevations: sorted.map { $0.1 })
    }
}
