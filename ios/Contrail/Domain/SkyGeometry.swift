import Foundation

/// Where an aircraft appears in the sky from an observer on the ground.
struct SkyPosition: Hashable, Sendable {
    var groundNm: Double
    var slantNm: Double
    /// Compass bearing from the observer, degrees true.
    var bearing: Double
    /// Degrees above the horizon (negative = below, e.g. on the ground far away).
    var elevation: Double
}

/// `r` throughout is a device-to-world rotation, row-major 3×3, world axes east-north-up (rows) by device axes
/// x-right, y-up, z-out-of-screen (columns). CoreMotion's attitude converts to it with `fromCoreMotion`.
enum SkyGeometry {
    private static let ftPerNm = 6076.12

    static func locate(obsLat: Double, obsLon: Double, obsAltFt: Double, _ a: Aircraft, at pos: Coord? = nil) -> SkyPosition {
        let p = pos ?? a.coord
        let ground = Geo.distanceNm(obsLat, obsLon, p.lat, p.lon)
        let altFt = a.onGround ? obsAltFt : Double(a.altGeom ?? a.altBaro ?? 0)
        // Earth curvature: the target sits lower by d²/2R relative to the observer's horizon plane.
        let drop = ground * ground / (2 * Geo.earthNm) * ftPerNm
        let heightFt = altFt - obsAltFt - drop
        let groundFt = ground * ftPerNm
        return SkyPosition(groundNm: ground, slantNm: (groundFt * groundFt + heightFt * heightFt).squareRoot() / ftPerNm,
                           bearing: Geo.bearing(obsLat, obsLon, p.lat, p.lon), elevation: Geo.deg(atan2(heightFt, groundFt)))
    }

    /// Unit vector in local east-north-up for a bearing/elevation.
    static func enu(_ bearing: Double, _ elevation: Double) -> (Double, Double, Double) {
        let b = Geo.rad(bearing), e = Geo.rad(elevation)
        return (sin(b) * cos(e), cos(b) * cos(e), sin(e))
    }

    /// CoreMotion's `xTrueNorthZVertical` rotation matrix maps world (north, west, up) into device axes; this
    /// transposes it into the east-north-up layout above.
    static func fromCoreMotion(m11: Double, m12: Double, m13: Double, m21: Double, m22: Double, m23: Double,
                               m31: Double, m32: Double, m33: Double) -> [Double] {
        [-m12, -m22, -m32,
         m11, m21, m31,
         m13, m23, m33]
    }

    /// Projects a sky direction onto the camera image. The back camera looks along device −z, screen-up is +y.
    /// Returns normalised screen coords (0...1, 0...1), or nil when behind the camera.
    static func project(_ r: [Double], bearing: Double, elevation: Double, hFovDeg: Double, aspect: Double) -> (x: Double, y: Double)? {
        let v = enu(bearing, elevation)
        // World to device is the transpose of device to world.
        let dx = r[0] * v.0 + r[3] * v.1 + r[6] * v.2
        let dy = r[1] * v.0 + r[4] * v.1 + r[7] * v.2
        let dz = r[2] * v.0 + r[5] * v.1 + r[8] * v.2
        if dz >= -0.05 { return nil }
        let fx = 0.5 / tan(Geo.rad(hFovDeg / 2))
        return (0.5 + dx / -dz * fx, 0.5 - dy / -dz * fx * aspect)
    }

    /// Heading and pitch of the back camera.
    static func cameraPointing(_ r: [Double]) -> (heading: Double, pitch: Double) {
        let east = -r[2], north = -r[5], up = -r[8]
        return ((Geo.deg(atan2(east, north)) + 360).truncatingRemainder(dividingBy: 360), Geo.deg(asin(min(max(up, -1), 1))))
    }
}
