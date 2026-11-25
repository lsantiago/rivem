package org.rivem.core.geometry

import org.rivem.core.RiverCoreError
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Nadir (UAV / drone) footage: a similarity transform (scale + rotation + translation, with the image y axis
 * flipped) from two control points. Port of `get_uav_transformation_matrix` in
 * `river/core/coordinate_transform.py` (RIVeR 3.8.3).
 *
 * **Fix over desktop:** RIVeR measures the pixel angle with the image y axis pointing down and then flips y
 * in the scale matrix. That maps point 2 to the mirror image of its real-world position unless the real-world
 * segment 1→2 is horizontal (East–West). Here the pixel vector is flipped *before* measuring its angle,
 * so both control points always land on their real-world coordinates. For an East–West segment both
 * formulations give the same matrix, so desktop projects of that kind are reproduced exactly.
 */
object UAVTransform {
    /** Real-world units per pixel (`get_pixel_size` in desktop). */
    fun pixelSize(pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2): Double {
        val dPix = pixel1.distanceTo(pixel2)
        val dWorld = world1.distanceTo(world2)
        if (!(dPix > 0)) throw RiverCoreError.DegenerateGeometry("los dos puntos en píxeles coinciden")
        if (!(dWorld > 0)) throw RiverCoreError.DegenerateGeometry("los dos puntos reales coinciden")
        return dWorld / dPix
    }

    /** Pixel → real-world matrix. `pixelSize` overrides the size derived from the two points (desktop allows it). */
    fun matrix(
        pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2, pixelSize: Double? = null,
    ): Matrix3 {
        val s = pixelSize ?: this.pixelSize(pixel1, pixel2, world1, world2)
        if (!(s > 0 && s.isFinite())) throw RiverCoreError.DegenerateGeometry("tamaño de píxel no válido")

        // Pixel vector with y pointing up, so it lives in the same handedness as the real-world frame.
        val anglePix = atan2(-(pixel2.y - pixel1.y), pixel2.x - pixel1.x)
        val angleWorld = atan2(world2.y - world1.y, world2.x - world1.x)
        val theta = angleWorld - anglePix
        val c = cos(theta)
        val sn = sin(theta)

        // world = s * R(theta) * diag(1, -1) * pixel + t
        val a = s * c
        val b = s * sn
        val d = s * sn
        val e = -s * c
        val tx = world1.x - (a * pixel1.x + b * pixel1.y)
        val ty = world1.y - (d * pixel1.x + e * pixel1.y)
        return Matrix3.rowMajor(a, b, tx, d, e, ty, 0.0, 0.0, 1.0)
    }
}
