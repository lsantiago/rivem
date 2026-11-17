import Foundation

/// Platform-neutral port of the RIVeR (oruscam/RIVeR) processing core.
///
/// Rules for everything under `RiverCore`:
/// - Foundation only (no UIKit, SwiftUI, AVFoundation or Accelerate), so the code can also be built and tested
///   with the Swift toolchain on Windows/Linux and ported line by line to Kotlin for the Android app.
/// - Where the desktop code has a known defect, the port fixes it and the test that covers it says so
///   (see `docs/PLAN.md`, section "Errores de RIVeR corregidos").
public enum RiverCore {
    /// Version of this core.
    public static let version = "0.2.0"
    /// RIVeR desktop release whose project files this core reads and writes.
    public static let desktopCompatibility = "3.8.3"
}

public enum RiverCoreError: Error, Equatable, CustomStringConvertible {
    case notEnoughPoints(needed: Int, got: Int)
    case mismatchedPointCounts
    case degenerateGeometry(String)
    case singularMatrix

    public var description: String {
        switch self {
        case let .notEnoughPoints(needed, got): return "Se necesitan al menos \(needed) puntos (hay \(got))."
        case .mismatchedPointCounts: return "Las listas de puntos en píxeles y en coordenadas reales no tienen el mismo tamaño."
        case let .degenerateGeometry(why): return "Geometría degenerada: \(why)"
        case .singularMatrix: return "La matriz es singular."
        }
    }
}
