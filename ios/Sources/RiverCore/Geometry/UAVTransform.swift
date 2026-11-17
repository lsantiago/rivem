import Foundation

/// Nadir (UAV / drone) footage: a similarity transform (scale + rotation + translation, with the image y axis
/// flipped) from two control points. Port of `get_uav_transformation_matrix` in
/// `river/core/coordinate_transform.py` (RIVeR 3.8.3).
///
/// **Fix over desktop:** RIVeR measures the pixel angle with the image y axis pointing down and then flips y
/// in the scale matrix. That maps point 2 to the mirror image of its real-world position unless the real-world
/// segment 1→2 is horizontal (East–West). Here the pixel vector is flipped *before* measuring its angle,
/// so both control points always land on their real-world coordinates. For an East–West segment both
/// formulations give the same matrix, so desktop projects of that kind are reproduced exactly.
public enum UAVTransform {
    /// Real-world units per pixel (`get_pixel_size` in desktop).
    public static func pixelSize(pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2) throws -> Double {
        let dPix = pixel1.distance(to: pixel2)
        let dWorld = world1.distance(to: world2)
        guard dPix > 0 else { throw RiverCoreError.degenerateGeometry("los dos puntos en píxeles coinciden") }
        guard dWorld > 0 else { throw RiverCoreError.degenerateGeometry("los dos puntos reales coinciden") }
        return dWorld / dPix
    }

    /// Pixel → real-world matrix. `pixelSize` overrides the size derived from the two points (desktop allows it).
    public static func matrix(
        pixel1: Point2, pixel2: Point2, world1: Point2, world2: Point2, pixelSize: Double? = nil
    ) throws -> Matrix3 {
        let s = try pixelSize ?? Self.pixelSize(pixel1: pixel1, pixel2: pixel2, world1: world1, world2: world2)
        guard s > 0, s.isFinite else { throw RiverCoreError.degenerateGeometry("tamaño de píxel no válido") }

        // Pixel vector with y pointing up, so it lives in the same handedness as the real-world frame.
        let anglePix = atan2(-(pixel2.y - pixel1.y), pixel2.x - pixel1.x)
        let angleWorld = atan2(world2.y - world1.y, world2.x - world1.x)
        let theta = angleWorld - anglePix
        let c = cos(theta)
        let sn = sin(theta)

        // world = s * R(theta) * diag(1, -1) * pixel + t
        let a = s * c, b = s * sn
        let d = s * sn, e = -s * c
        let tx = world1.x - (a * pixel1.x + b * pixel1.y)
        let ty = world1.y - (d * pixel1.x + e * pixel1.y)
        return Matrix3(rowMajor: [a, b, tx, d, e, ty, 0, 0, 1])
    }
}
