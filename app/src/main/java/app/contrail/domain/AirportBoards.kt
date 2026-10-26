package app.contrail.domain

import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Route
import app.contrail.data.static.Runway

enum class BoardStatus { EN_ROUTE, APPROACH, LANDED, TAXIING, ON_GROUND, DEPARTED, JUST_DEPARTED }

data class BoardRow(
    val track: Track,
    val route: Route?,
    val status: BoardStatus,
    /** ETA for arrivals, takeoff time for departures, landing time for landed. */
    val time: Long?,
    val timeEstimated: Boolean,
    val distanceNm: Double,
)

data class Boards(val arrivals: List<BoardRow>, val departures: List<BoardRow>)

/**
 * Live arrivals/departures built from aircraft in the air or on the ground nearby. Without a
 * schedule this only covers flights within feed range (~250 nm), which the UI states.
 */
object AirportBoards {
    private const val AT_AIRPORT_NM = 4.0
    private const val CIRCUIT_NM = 15.0

    fun build(airport: Airport, tracks: Collection<Track>, routes: Map<String, Route?>, now: Long): Boards {
        val arrivals = ArrayList<BoardRow>()
        val departures = ArrayList<BoardRow>()
        for (t in tracks) {
            val a = t.aircraft
            val dist = Geo.distanceNm(airport.lat, airport.lon, a.lat, a.lon)
            val route = a.callsign?.let { routes[it] }
            val toHere = route != null && route.destination.matches(airport)
            val fromHere = route != null && route.origin.matches(airport)
            val p = route?.let { FlightProgressCalc.compute(t, it, now) }
            val climbing = (a.verticalRate ?: 0.0) > 300
            val descending = (a.verticalRate ?: 0.0) < -300
            val low = (a.altBaro ?: Int.MAX_VALUE) < 6000

            when {
                toHere && a.onGround && dist < AT_AIRPORT_NM ->
                    arrivals += BoardRow(t, route, BoardStatus.LANDED, t.landedAt, false, dist)
                toHere && !a.onGround && p?.verified == true ->
                    arrivals += BoardRow(t, route, if (dist < CIRCUIT_NM && low) BoardStatus.APPROACH else BoardStatus.EN_ROUTE, p.eta, true, dist)
                fromHere && a.onGround && dist < AT_AIRPORT_NM ->
                    departures += BoardRow(t, route, if ((a.groundSpeed ?: 0.0) > 3) BoardStatus.TAXIING else BoardStatus.ON_GROUND, null, false, dist)
                fromHere && !a.onGround && p?.verified == true ->
                    departures += BoardRow(t, route, BoardStatus.DEPARTED, p.departedAt, p.departedEstimated, dist)
                // No usable route: infer from behaviour close to the field.
                route == null || p?.verified == false -> if (!a.onGround && dist < CIRCUIT_NM && low) {
                    when {
                        descending -> arrivals += BoardRow(t, null, BoardStatus.APPROACH, null, false, dist)
                        climbing -> departures += BoardRow(t, null, BoardStatus.JUST_DEPARTED, t.takeoffAt, false, dist)
                    }
                }
            }
        }
        val arrOrder = compareBy<BoardRow>({ if (it.status == BoardStatus.LANDED) 0 else 1 }, { it.time ?: Long.MAX_VALUE }, { it.distanceNm })
        val depOrder = compareBy<BoardRow>({ if (it.status == BoardStatus.TAXIING || it.status == BoardStatus.ON_GROUND) 0 else 1 }, { -(it.time ?: 0) })
        return Boards(arrivals.sortedWith(arrOrder), departures.sortedWith(depOrder))
    }

    private fun Airport.matches(other: Airport) =
        (icao != null && icao == other.icao) || (!iata.isNullOrEmpty() && iata == other.iata)

    data class ActiveRunway(val ident: String, val fromTraffic: Boolean)

    /**
     * Runway in use: the end most low, aligned aircraft are using. Falls back to the end facing
     * most directly into the wind.
     */
    fun activeRunway(airport: Airport, runways: List<Runway>, tracks: Collection<Track>, windDir: Double?): ActiveRunway? {
        val ends = runways.flatMap { r -> listOfNotNull(r.leHeading?.let { r.leIdent to it }, r.heHeading?.let { r.heIdent to it }) }
        if (ends.isEmpty()) return null
        val votes = HashMap<String, Int>()
        for (t in tracks) {
            val a = t.aircraft
            val trk = a.track ?: continue
            if (a.onGround || (a.altBaro ?: Int.MAX_VALUE) > 3000) continue
            if (Geo.distanceNm(airport.lat, airport.lon, a.lat, a.lon) > 8) continue
            ends.filter { Geo.angleDiff(trk, it.second) < 20 }.forEach { votes.merge(it.first, 1, Int::plus) }
        }
        votes.maxByOrNull { it.value }?.let { return ActiveRunway(it.key, true) }
        windDir ?: return null
        return ends.minByOrNull { Geo.angleDiff(it.second, windDir) }?.let { ActiveRunway(it.first, false) }
    }
}
