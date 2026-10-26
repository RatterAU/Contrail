package app.contrail.domain

import app.contrail.data.enrich.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Keyless "times": everything here is derived from the live position and the route's airports.
 * There is no schedule, so nothing claims to be scheduled; estimates are flagged as such.
 */
data class FlightProgress(
    /** False when the plane's position doesn't fit the route; the route may be stale or wrong. */
    val verified: Boolean,
    val totalNm: Double,
    val flownNm: Double,
    val remainingNm: Double,
    val fraction: Float,
    val departedAt: Long?,
    val departedEstimated: Boolean,
    val eta: Long?,
    val landedAt: Long?,
)

object FlightProgressCalc {
    /**
     * Time to touchdown. The plane holds its current ground speed (which already includes the wind) until
     * ~100 nm out, then slows through descent and approach: about 300 kt average from cruise, less if it is
     * already slow. Plus 2 min for vectoring and the final turn.
     */
    /**
     * Ground speed at cruise: the fastest seen above FL200 if we watched it cruise, else typical for its size
     * class. Never the current speed alone: on approach it's ~150 kt, which would make a 3 h flight look like 9 h.
     */
    fun cruiseSpeedKt(track: Track): Double {
        track.trail.filter { (it.alt ?: 0) > 20_000 }.maxOfOrNull { it.gs ?: 0.0 }?.takeIf { it > 150 }?.let { return it }
        val a = track.aircraft
        val typical = when (a.category) {
            "A1" -> 140.0 // light
            "A2" -> 280.0 // small: turboprops and small regional jets
            "A3", "A4", "A5", "A6" -> 430.0 // airliners
            else -> 400.0
        }
        val now = if ((a.altBaro ?: 0) > 20_000) a.groundSpeed else null
        return now ?: max(typical, a.groundSpeed ?: 0.0)
    }

    fun flyingTimeMs(toDestNm: Double, gs: Double): Long {
        val final = min(toDestNm, 100.0)
        val finalAvg = (min(gs, 460.0) + 140) / 2
        val hours = (toDestNm - final) / gs + final / finalAvg
        return (hours * 3_600_000).toLong() + 2 * 60_000
    }

    fun compute(track: Track, route: Route, now: Long): FlightProgress {
        val a = track.aircraft
        val o = route.origin
        val d = route.destination
        // True dead-reckoned position, not the eased on-screen one.
        val (lat, lon) = Track.extrapolate(a, now)
        val total = Geo.distanceNm(o.lat, o.lon, d.lat, d.lon)
        val fromOrigin = Geo.distanceNm(o.lat, o.lon, lat, lon)
        val toDest = Geo.distanceNm(lat, lon, d.lat, d.lon)

        val verified = when {
            total < 5 -> false
            a.onGround -> fromOrigin < 5 || toDest < 5
            else -> {
                val crossTrack = abs(Geo.crossTrackNm(o.lat, o.lon, d.lat, d.lon, lat, lon))
                val onCorridor = crossTrack <= max(60.0, min(250.0, total * 0.15))
                val betweenEnds = fromOrigin + toDest <= total * 1.3 + 30
                val headingOk = toDest < 60 || a.track == null ||
                    Geo.angleDiff(a.track, Geo.bearing(lat, lon, d.lat, d.lon)) <= 75
                onCorridor && betweenEnds && headingOk
            }
        }

        val landedHere = a.onGround && toDest < 5
        val gs = a.groundSpeed
        val departed = track.takeoffAt
        val cruise = cruiseSpeedKt(track)
        // Climb-out mirrors the descent: the same speed profile, flown in reverse.
        val estDeparture = if (departed == null && verified && !a.onGround) now - flyingTimeMs(fromOrigin, cruise) else null

        val eta = if (verified && !a.onGround && gs != null && gs > 60) {
            // Still climbing far from the destination: it will speed up, so plan on cruise speed.
            now + flyingTimeMs(toDest, if ((a.altBaro ?: 0) < 20_000 && toDest > 150) max(gs, cruise) else gs)
        } else null

        return FlightProgress(
            verified = verified,
            totalNm = total,
            flownNm = fromOrigin,
            remainingNm = toDest,
            fraction = (fromOrigin / (fromOrigin + toDest)).toFloat().coerceIn(0f, 1f).let { if (landedHere) 1f else it },
            departedAt = departed ?: estDeparture,
            departedEstimated = departed == null,
            eta = eta,
            landedAt = if (landedHere) track.landedAt else null,
        )
    }
}
