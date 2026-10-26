package app.contrail.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where an aircraft appears in the sky from an observer on the ground. */
data class SkyPosition(
    val groundNm: Double,
    val slantNm: Double,
    /** Compass bearing from the observer, degrees true. */
    val bearing: Double,
    /** Degrees above the horizon (negative = below, e.g. on the ground far away). */
    val elevation: Double,
)

object SkyGeometry {
    private const val FT_PER_NM = 6076.12

    fun locate(obsLat: Double, obsLon: Double, obsAltFt: Double, a: Aircraft, lat: Double = a.lat, lon: Double = a.lon): SkyPosition {
        val ground = Geo.distanceNm(obsLat, obsLon, lat, lon)
        val altFt = (a.altGeom ?: a.altBaro ?: 0).toDouble().let { if (a.onGround) obsAltFt else it }
        // Earth curvature: the target sits lower by d²/2R relative to the observer's horizon plane.
        val drop = ground * ground / (2 * Geo.EARTH_NM) * FT_PER_NM
        val heightFt = altFt - obsAltFt - drop
        val groundFt = ground * FT_PER_NM
        val elevation = Math.toDegrees(atan2(heightFt, groundFt))
        val slant = sqrt(groundFt * groundFt + heightFt * heightFt) / FT_PER_NM
        return SkyPosition(ground, slant, Geo.bearing(obsLat, obsLon, lat, lon), elevation)
    }

    /** Unit vector in local east-north-up for a bearing/elevation. */
    fun enu(bearing: Double, elevation: Double): DoubleArray {
        val b = Math.toRadians(bearing)
        val e = Math.toRadians(elevation)
        return doubleArrayOf(sin(b) * cos(e), cos(b) * cos(e), sin(e))
    }

    /**
     * Projects a sky direction onto the camera image. [r] is the Android rotation matrix
     * (device → world, row-major 3×3). The back camera looks along device −Z, screen-up is +Y.
     * Returns normalised screen coords (0..1, 0..1) or null when behind the camera.
     */
    fun project(r: FloatArray, bearing: Double, elevation: Double, hFovDeg: Double, aspect: Double): Pair<Double, Double>? {
        val v = enu(bearing, elevation)
        // World → device is the transpose of device → world.
        val dx = r[0] * v[0] + r[3] * v[1] + r[6] * v[2]
        val dy = r[1] * v[0] + r[4] * v[1] + r[7] * v[2]
        val dz = r[2] * v[0] + r[5] * v[1] + r[8] * v[2]
        if (dz >= -0.05) return null
        val fx = 0.5 / kotlin.math.tan(Math.toRadians(hFovDeg / 2))
        val x = 0.5 + dx / -dz * fx
        val y = 0.5 - dy / -dz * fx * aspect
        return x to y
    }

    /** Heading and pitch of the back camera from the rotation matrix. */
    fun cameraPointing(r: FloatArray): Pair<Double, Double> {
        val east = -r[2].toDouble()
        val north = -r[5].toDouble()
        val up = -r[8].toDouble()
        return ((Math.toDegrees(atan2(east, north)) + 360) % 360) to Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
    }

    /** Which way the phone faces for a compass arrow: top edge when flat, camera when held upright. */
    fun phoneHeading(r: FloatArray): Double {
        val topUp = r[7]
        return if (kotlin.math.abs(topUp) < 0.7f) (Math.toDegrees(atan2(r[1].toDouble(), r[4].toDouble())) + 360) % 360
        else cameraPointing(r).first
    }
}
