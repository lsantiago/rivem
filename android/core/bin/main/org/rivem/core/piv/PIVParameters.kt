package org.rivem.core.piv

import org.rivem.core.project.JSONObject

/**
 * PIV processing options, mirroring the `processing` block of `settings.json` (docs/desktop-format.md §2.1) plus the
 * options desktop hard-codes when it runs `analyze-all`. Defaults are the GUI defaults
 * (`gui/src/store/data/dataSlice.ts`) and the CLI defaults (`river/cli/commands/orchestrator.py`).
 */
data class PIVParameters(
    /** First-pass interrogation window (px). GUI choices: 512, 256, 128 (default), 64. Must be even and >= 8. */
    var interrogationArea1: Int = 128,
    /**
     * Second-pass window (px). `null` = `interrogationArea1 / 2`, which is what desktop always passes to the CLI
     * (`getQuiver.ts`: `--interrogation-area-2 step1/2`), whatever `settings.processing.interrogation_area_2` says.
     * The pass-2 window actually used is `Int(round(ia2 / 2) * 2)` (Python banker's rounding), as upstream.
     */
    var interrogationArea2: Int? = null,
    /** First-pass grid step (px). `null` = `interrogationArea1 / 2` (desktop never sets it). Pass 2 always uses
     *  half the pass-2 window, as upstream. */
    var step: Int? = null,
    /** `roi_height` (m): used by the ROI mask / STIV, not by PIV. Kept so settings round-trip. */
    var roiHeight: Double? = null,
    /** Subtract the (truncated) mean of all frames before CLAHE. */
    var removeBackground: Boolean = false,
    /** CLAHE (OpenCV, 8x8 tiles) with `clipLimit`. */
    var clahe: Boolean = true,
    var clipLimit: Double = 5.0,
    /** Global standard-deviation filter: values outside mean ± threshold·std become outliers. */
    var stdFiltering: Boolean = true,
    var stdThreshold: Double = 4.0,
    /** Normalised median test (Westerweel & Scarano 2005) on the 8 neighbours. */
    var medianTestFiltering: Boolean = true,
    var medianTestEpsilon: Double = 0.02,
    var medianTestThreshold: Double = 2.0,
    /**
     * Second pass with window deformation. Desktop never disables it, and RIVeR 3.8.3 ignores `false`
     * (`piv_fftmulti.py:158-175` always runs pass 2). RIVEM honours it (PLAN.md defect #3).
     */
    var multipass: Boolean = true,
    /**
     * PIVlab's "auto mask" of the correlation plane: attenuate the zero-lag 3x3 block in pass 1, limit the peak
     * search to a radius-4 disk in pass 2. Always on in desktop.
     */
    var maskAuto: Boolean = true,
) {
    /**
     * Reads `settings.json` → `processing`. Missing keys keep the defaults. `interrogation_area_2` is NOT used as the
     * second window (desktop passes `interrogation_area_1 / 2`), so `interrogationArea2` stays `null`.
     */
    constructor(processing: JSONObject) : this() {
        val o = processing
        o["interrogation_area_1"]?.intValue?.let { interrogationArea1 = it }
        o["roi_height"]?.doubleValue?.let { roiHeight = it }
        o["remove_background"]?.boolValue?.let { removeBackground = it }
        o["clahe"]?.boolValue?.let { clahe = it }
        o["clip_limit"]?.doubleValue?.let { clipLimit = it }
        o["std_filtering"]?.boolValue?.let { stdFiltering = it }
        o["std_threshold"]?.doubleValue?.let { stdThreshold = it }
        o["median_test_filtering"]?.boolValue?.let { medianTestFiltering = it }
        o["median_test_epsilon"]?.doubleValue?.let { medianTestEpsilon = it }
        o["median_test_threshold"]?.doubleValue?.let { medianTestThreshold = it }
    }

    /** Pass-1 grid step actually used. */
    val effectiveStep: Int get() = step ?: (interrogationArea1 / 2)

    /** Pass-2 window actually used: `int(round(ia2 / 2) * 2)` with Python's round-half-to-even. */
    val effectiveInterrogationArea2: Int
        get() {
            val ia2: Double = interrogationArea2?.toDouble() ?: (interrogationArea1.toDouble() / 2)
            return Math.rint(ia2 / 2).toInt() * 2
        }

    /** Throws [PIVError.InvalidParameters] if the windows cannot be processed (odd or too small windows,
     *  non-positive step). */
    fun validate() {
        if (!(interrogationArea1 >= 8 && interrogationArea1 % 2 == 0)) {
            throw PIVError.InvalidParameters("interrogation_area_1 debe ser par y >= 8")
        }
        if (effectiveStep < 1) throw PIVError.InvalidParameters("step debe ser >= 1")
        if (multipass) {
            if (effectiveInterrogationArea2 < 8) throw PIVError.InvalidParameters("interrogation_area_2 debe ser >= 8")
        }
        if (!(clipLimit >= 0 && stdThreshold > 0 && medianTestThreshold > 0 && medianTestEpsilon >= 0)) {
            throw PIVError.InvalidParameters("umbrales no válidos")
        }
    }
}

/** Errors of the PIV module (Swift `PIVError`); the message is Swift's `description`. */
sealed class PIVError(message: String) : Exception(message) {
    class InvalidParameters(val why: String) : PIVError("Parámetros PIV no válidos: $why.")

    /** The region of interest is smaller than one interrogation window. */
    class RegionTooSmall : PIVError("La región de interés es más chica que la ventana de interrogación.")
    class ImageSizeMismatch : PIVError("Los frames no tienen todos el mismo tamaño.")
    class NoFrames : PIVError("No hay frames legibles.")
    class Cancelled : PIVError("Procesamiento PIV cancelado.")

    /** Equatable like the Swift enum: same case and same associated values. */
    override fun equals(other: Any?): Boolean =
        other != null && other.javaClass == javaClass && (other as PIVError).message == message

    override fun hashCode(): Int = javaClass.hashCode() * 31 + (message?.hashCode() ?: 0)

    override fun toString(): String = message ?: javaClass.simpleName
}
