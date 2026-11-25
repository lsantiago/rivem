package org.rivem.core.geometry

import org.rivem.core.project.CrossSection

// The part of Swift's `ROIMask.swift` that depends on the `project` package: kept apart so the rest of `geometry`
// compiles on its own.

/**
 * From a desktop cross-section (`east_l, north_l, east_r, north_r`, `rw_length`); null if the line is missing.
 * Swift's failable `ROISection.init?(_ section: CrossSection, wetSegments:)`, called the same way:
 * `ROISection(section, wetSegments = wet)`.
 */
fun ROISection(section: CrossSection, wetSegments: List<ClosedFloatingPointRange<Double>> = emptyList()): ROISection? {
    val l = section.worldLeft ?: return null
    val r = section.worldRight ?: return null
    return ROISection(left = l, right = r, wetSegments = wetSegments, realWorldLength = section.realWorldLength)
}
