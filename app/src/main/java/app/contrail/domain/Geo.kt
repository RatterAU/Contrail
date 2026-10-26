package app.contrail.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth helpers. Distances in nautical miles, angles in degrees. */
object Geo {
    const val EARTH_NM = 3440.065

    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_NM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Point reached from (lat, lon) travelling [distNm] on initial [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distNm: Double): Pair<Double, Double> {
        val d = distNm / EARTH_NM
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Signed distance of point P from the great circle A→B (nm). */
    fun crossTrackNm(latA: Double, lonA: Double, latB: Double, lonB: Double, latP: Double, lonP: Double): Double {
        val d13 = distanceNm(latA, lonA, latP, lonP) / EARTH_NM
        val t13 = Math.toRadians(bearing(latA, lonA, latP, lonP))
        val t12 = Math.toRadians(bearing(latA, lonA, latB, lonB))
        return asin(sin(d13) * sin(t13 - t12)) * EARTH_NM
    }

    /** Smallest absolute difference between two headings. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    fun compass(deg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
}
