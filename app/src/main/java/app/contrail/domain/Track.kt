package app.contrail.domain

import kotlin.math.abs

data class TrailPoint(val lat: Double, val lon: Double, val alt: Int?, val onGround: Boolean, val gs: Double?, val time: Long)

/** Where the plane was drawn when a fresh report arrived; the icon eases from here instead of jumping. */
data class Blend(val lat: Double, val lon: Double, val start: Long)

/**
 * An aircraft plus what this app has observed about it over time. Trace files on the public
 * feeds are bot-protected, so the trail and takeoff/landing times are recorded locally.
 */
data class Track(
    val aircraft: Aircraft,
    val trail: List<TrailPoint>,
    val firstSeen: Long,
    val takeoffAt: Long? = null,
    val landedAt: Long? = null,
    val blend: Blend? = null,
) {
    val hex get() = aircraft.hex

    fun staleness(now: Long) = now - aircraft.seenTime

    /** Dead-reckoned position at [now], eased from [blend] for the first [BLEND_MS]. */
    fun displayPosition(now: Long): Pair<Double, Double> {
        val p = extrapolate(aircraft, now)
        val b = blend ?: return p
        val f = (now - b.start).toDouble() / BLEND_MS
        if (f >= 1.0 || f < 0 || abs(b.lon - p.second) > 180) return p
        val e = 1 - (1 - f) * (1 - f) * (1 - f) // ease-out cubic
        return (b.lat + (p.first - b.lat) * e) to (b.lon + (p.second - b.lon) * e)
    }

    /** Merge a newer report into this track. Older or duplicate reports are ignored. */
    fun update(next: Aircraft, now: Long): Track {
        // A rough position is the receiver's, up to hundreds of nm off: it never replaces a real fix.
        if (next.source == PositionSource.ROUGH && aircraft.source != PositionSource.ROUGH) return this
        if (next.positionTime <= aircraft.positionTime) {
            return if (next.seenTime > aircraft.seenTime) copy(aircraft = aircraft.copy(seenTime = next.seenTime)) else this
        }
        val (dLat, dLon) = displayPosition(now)
        val last = trail.lastOrNull()
        val moved = last == null || Geo.distanceNm(last.lat, last.lon, next.lat, next.lon) > 0.05 ||
            last.onGround != next.onGround || next.positionTime - last.time > 30_000
        return copy(
            // adsb.fi omits registry fields that adsb.lol sends; don't let alternating feeds blank them.
            aircraft = next.copy(
                callsign = next.callsign ?: aircraft.callsign,
                registration = next.registration ?: aircraft.registration,
                typeCode = next.typeCode ?: aircraft.typeCode,
                description = next.description ?: aircraft.description,
                operator = next.operator ?: aircraft.operator,
                year = next.year ?: aircraft.year,
                category = next.category ?: aircraft.category,
            ),
            trail = if (moved) (trail + next.toTrailPoint()).takeLast(MAX_TRAIL) else trail,
            takeoffAt = if (aircraft.onGround && !next.onGround) next.positionTime else takeoffAt,
            landedAt = when {
                !aircraft.onGround && next.onGround -> next.positionTime
                !next.onGround -> null
                else -> landedAt
            },
            blend = Blend(dLat, dLon, now),
        )
    }

    /**
     * Prepend the path flown before the app saw the plane (see FlightHistory). If that path starts on the
     * ground, its first airborne point is the real takeoff time.
     */
    fun withHistory(history: List<TrailPoint>): Track {
        val first = trail.firstOrNull()?.time ?: Long.MAX_VALUE
        val older = history.filter { it.time < first - 1000 }
        if (older.isEmpty()) return this
        val start = older.first()
        val fromGround = start.onGround || (start.alt ?: Int.MAX_VALUE) < 1500
        val takeoff = if (fromGround) older.firstOrNull { !it.onGround && (it.alt ?: 0) > 200 }?.time else null
        val took = takeoffAt ?: takeoff
        // Landed before we started watching: the flown path ends at touchdown.
        val landed = landedAt ?: if (aircraft.onGround && took != null) older.last().time else null
        return copy(trail = (older + trail).takeLast(MAX_TRAIL), takeoffAt = took, landedAt = landed)
    }

    companion object {
        const val BLEND_MS = 900L
        const val MAX_TRAIL = 3000
        /** Don't extrapolate further than this past the last position fix. */
        const val MAX_EXTRAPOLATE_MS = 30_000L

        fun start(a: Aircraft, now: Long) = Track(a, listOf(a.toTrailPoint()), firstSeen = now)

        fun extrapolate(a: Aircraft, now: Long): Pair<Double, Double> {
            val gs = a.groundSpeed ?: return a.lat to a.lon
            val track = a.track ?: return a.lat to a.lon
            if (gs < 3) return a.lat to a.lon
            val dtMs = (now - a.positionTime).coerceIn(0, MAX_EXTRAPOLATE_MS)
            return Geo.destination(a.lat, a.lon, track, gs * dtMs / 3_600_000.0)
        }
    }
}

fun Aircraft.toTrailPoint() = TrailPoint(lat, lon, altBaro, onGround, groundSpeed, positionTime)
