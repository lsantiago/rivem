package org.rivem.core

/**
 * Kotlin port of `ios/Sources/RiverCore` (Swift), itself a port of the RIVeR (oruscam/RIVeR) processing core.
 *
 * Rules for everything under `org.rivem.core`:
 * - Pure JVM (no Android APIs), so it is tested with plain JUnit on any machine and the app only adds UI and media.
 * - Same names, structure and numbers as the Swift core: a Swift type `Foo` in `Geometry/Foo.swift` is `Foo` in
 *   `org.rivem.core.geometry`, and each Swift test case has a Kotlin twin on the same `ci/testdata` fixtures.
 * - Where desktop RIVeR has a known defect the port fixes it (docs/PLAN.md, "Errores de RIVeR corregidos").
 */
object RiverCore {
    /** Version of this core (same as the Swift core it mirrors). */
    const val VERSION = "0.2.0"

    /** RIVeR desktop release whose project files this core reads and writes. */
    const val DESKTOP_COMPATIBILITY = "3.8.3"
}

/** Errors of the geometry and numeric code (Swift `RiverCoreError`). */
sealed class RiverCoreError(message: String) : Exception(message) {
    class NotEnoughPoints(val needed: Int, val got: Int) :
        RiverCoreError("Se necesitan al menos $needed puntos (hay $got).")

    class MismatchedPointCounts :
        RiverCoreError("Las listas de puntos en píxeles y en coordenadas reales no tienen el mismo tamaño.")

    class DegenerateGeometry(val why: String) : RiverCoreError("Geometría degenerada: $why")

    class SingularMatrix : RiverCoreError("La matriz es singular.")
}
