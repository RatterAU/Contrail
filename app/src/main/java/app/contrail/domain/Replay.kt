package app.contrail.domain

/**
 * Where a plane was at a moment of a recorded flight, for replaying it: between the two fixes either side,
 * position and figures are interpolated, and the heading is the direction it was moving.
 */
object Replay {
    /** [gap]: no fix within [GAP_MS] either side, so the position is a straight-line guess across a coverage hole. */
    data class Frame(
        val lat: Double,
        val lon: Double,
        val alt: Int?,
        val onGround: Boolean,
        val gs: Double?,
        val heading: Double,
        val time: Long,
        val gap: Boolean,
    )

    const val GAP_MS = 5 * 60_000L

    /** [path] oldest first; [t] is clamped to its span. Null for fewer than two points. */
    fun at(path: List<TrailPoint>, t: Long): Frame? {
        if (path.size < 2) return null
        val time = t.coerceIn(path.first().time, path.last().time)
        // First fix after the moment (or the last), so path[i - 1] is at or before it.
        val i = path.binarySearchBy(time) { it.time }.let { if (it >= 0) it + 1 else -it - 1 }.coerceIn(1, path.lastIndex)
        val a = path[i - 1]
        val b = path[i]
        val f = if (b.time > a.time) ((time - a.time).toDouble() / (b.time - a.time)).coerceIn(0.0, 1.0) else 0.0
        // Across the dateline, the short way round.
        val dLon = (b.lon - a.lon + 540) % 360 - 180
        val lon = (a.lon + dLon * f + 540) % 360 - 180
        val alt = when {
            a.alt != null && b.alt != null -> (a.alt + (b.alt - a.alt) * f).toInt()
            else -> if (f < 0.5) a.alt else b.alt
        }
        val gs = if (a.gs != null && b.gs != null) a.gs + (b.gs - a.gs) * f else a.gs ?: b.gs
        return Frame(a.lat + (b.lat - a.lat) * f, lon, alt, if (f < 0.5) a.onGround else b.onGround, gs, heading(path, i), time, b.time - a.time > GAP_MS)
    }

    /**
     * Direction of travel into path[i]: from the nearest earlier fix at least 0.2 nm back (or forward, at the
     * start), so a taxiing or GPS-jittering plane doesn't spin.
     */
    private fun heading(path: List<TrailPoint>, i: Int): Double {
        val b = path[i]
        for (j in i - 1 downTo maxOf(0, i - 20)) {
            val a = path[j]
            if (Geo.distanceNm(a.lat, a.lon, b.lat, b.lon) >= 0.2) return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
        }
        for (j in i + 1..minOf(path.lastIndex, i + 20)) {
            val c = path[j]
            if (Geo.distanceNm(b.lat, b.lon, c.lat, c.lon) >= 0.2) return Geo.bearing(b.lat, b.lon, c.lat, c.lon)
        }
        return Geo.bearing(path[i - 1].lat, path[i - 1].lon, b.lat, b.lon)
    }
}
