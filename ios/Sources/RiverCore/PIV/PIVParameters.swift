import Foundation

/// PIV processing options, mirroring the `processing` block of `settings.json` (docs/desktop-format.md §2.1) plus the
/// options desktop hard-codes when it runs `analyze-all`. Defaults are the GUI defaults
/// (`gui/src/store/data/dataSlice.ts`) and the CLI defaults (`river/cli/commands/orchestrator.py`).
public struct PIVParameters: Equatable, Sendable {
    /// First-pass interrogation window (px). GUI choices: 512, 256, 128 (default), 64. Must be even and >= 8.
    public var interrogationArea1: Int
    /// Second-pass window (px). `nil` = `interrogationArea1 / 2`, which is what desktop always passes to the CLI
    /// (`getQuiver.ts`: `--interrogation-area-2 step1/2`), whatever `settings.processing.interrogation_area_2` says.
    /// The pass-2 window actually used is `Int(round(ia2 / 2) * 2)` (Python banker's rounding), as upstream.
    public var interrogationArea2: Int?
    /// First-pass grid step (px). `nil` = `interrogationArea1 / 2` (desktop never sets it). Pass 2 always uses
    /// half the pass-2 window, as upstream.
    public var step: Int?
    /// `roi_height` (m): used by the ROI mask / STIV, not by PIV. Kept so settings round-trip.
    public var roiHeight: Double?
    /// Subtract the (truncated) mean of all frames before CLAHE.
    public var removeBackground: Bool
    /// CLAHE (OpenCV, 8x8 tiles) with `clipLimit`.
    public var clahe: Bool
    public var clipLimit: Double
    /// Global standard-deviation filter: values outside mean ± threshold·std become outliers.
    public var stdFiltering: Bool
    public var stdThreshold: Double
    /// Normalised median test (Westerweel & Scarano 2005) on the 8 neighbours.
    public var medianTestFiltering: Bool
    public var medianTestEpsilon: Double
    public var medianTestThreshold: Double
    /// Second pass with window deformation. Desktop never disables it, and RIVeR 3.8.3 ignores `false`
    /// (`piv_fftmulti.py:158-175` always runs pass 2). RIVEM honours it (PLAN.md defect #3).
    public var multipass: Bool
    /// PIVlab's "auto mask" of the correlation plane: attenuate the zero-lag 3x3 block in pass 1, limit the peak
    /// search to a radius-4 disk in pass 2. Always on in desktop.
    public var maskAuto: Bool

    public init(interrogationArea1: Int = 128, interrogationArea2: Int? = nil, step: Int? = nil,
                roiHeight: Double? = nil, removeBackground: Bool = false, clahe: Bool = true, clipLimit: Double = 5,
                stdFiltering: Bool = true, stdThreshold: Double = 4,
                medianTestFiltering: Bool = true, medianTestEpsilon: Double = 0.02, medianTestThreshold: Double = 2,
                multipass: Bool = true, maskAuto: Bool = true) {
        self.interrogationArea1 = interrogationArea1
        self.interrogationArea2 = interrogationArea2
        self.step = step
        self.roiHeight = roiHeight
        self.removeBackground = removeBackground
        self.clahe = clahe
        self.clipLimit = clipLimit
        self.stdFiltering = stdFiltering
        self.stdThreshold = stdThreshold
        self.medianTestFiltering = medianTestFiltering
        self.medianTestEpsilon = medianTestEpsilon
        self.medianTestThreshold = medianTestThreshold
        self.multipass = multipass
        self.maskAuto = maskAuto
    }

    /// Reads `settings.json` → `processing`. Missing keys keep the defaults. `interrogation_area_2` is NOT used as the
    /// second window (desktop passes `interrogation_area_1 / 2`), so `interrogationArea2` stays `nil`.
    public init(processing o: JSONObject) {
        self.init()
        if let v = o["interrogation_area_1"]?.intValue { interrogationArea1 = v }
        if let v = o["roi_height"]?.doubleValue { roiHeight = v }
        if let v = o["remove_background"]?.boolValue { removeBackground = v }
        if let v = o["clahe"]?.boolValue { clahe = v }
        if let v = o["clip_limit"]?.doubleValue { clipLimit = v }
        if let v = o["std_filtering"]?.boolValue { stdFiltering = v }
        if let v = o["std_threshold"]?.doubleValue { stdThreshold = v }
        if let v = o["median_test_filtering"]?.boolValue { medianTestFiltering = v }
        if let v = o["median_test_epsilon"]?.doubleValue { medianTestEpsilon = v }
        if let v = o["median_test_threshold"]?.doubleValue { medianTestThreshold = v }
    }

    /// Pass-1 grid step actually used.
    public var effectiveStep: Int { step ?? interrogationArea1 / 2 }

    /// Pass-2 window actually used: `int(round(ia2 / 2) * 2)` with Python's round-half-to-even.
    public var effectiveInterrogationArea2: Int {
        let ia2: Double = interrogationArea2.map { Double($0) } ?? Double(interrogationArea1) / 2
        return Int((ia2 / 2).rounded(.toNearestOrEven)) * 2
    }

    /// Throws if the windows cannot be processed (odd or too small windows, non-positive step).
    public func validate() throws {
        guard interrogationArea1 >= 8, interrogationArea1 % 2 == 0 else {
            throw PIVError.invalidParameters("interrogation_area_1 debe ser par y >= 8")
        }
        guard effectiveStep >= 1 else { throw PIVError.invalidParameters("step debe ser >= 1") }
        if multipass {
            guard effectiveInterrogationArea2 >= 8 else {
                throw PIVError.invalidParameters("interrogation_area_2 debe ser >= 8")
            }
        }
        guard clipLimit >= 0, stdThreshold > 0, medianTestThreshold > 0, medianTestEpsilon >= 0 else {
            throw PIVError.invalidParameters("umbrales no válidos")
        }
    }
}

/// Errors of the PIV module.
public enum PIVError: Error, Equatable, CustomStringConvertible {
    case invalidParameters(String)
    /// The region of interest is smaller than one interrogation window.
    case regionTooSmall
    case imageSizeMismatch
    case noFrames
    case cancelled

    public var description: String {
        switch self {
        case let .invalidParameters(why): return "Parámetros PIV no válidos: \(why)."
        case .regionTooSmall: return "La región de interés es más chica que la ventana de interrogación."
        case .imageSizeMismatch: return "Los frames no tienen todos el mismo tamaño."
        case .noFrames: return "No hay frames legibles."
        case .cancelled: return "Procesamiento PIV cancelado."
        }
    }
}
