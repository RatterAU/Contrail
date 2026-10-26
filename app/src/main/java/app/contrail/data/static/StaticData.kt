package app.contrail.data.static

import android.content.Context
import app.contrail.data.enrich.Airline
import app.contrail.data.enrich.Airport
import app.contrail.domain.Geo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Runway(
    val airportIcao: String,
    val leIdent: String,
    val heIdent: String,
    val lengthFt: Int?,
    val surface: String,
    val leHeading: Double?,
    val heHeading: Double?,
    val leLat: Double?, val leLon: Double?, val heLat: Double?, val heLon: Double?,
)

/**
 * Bundled reference data (OurAirports + OpenFlights), built by a one-off script into the .tsv files in assets.
 * Small enough (~6k airports) to hold in memory; loaded once in the background at startup.
 */
class StaticData(
    val airports: List<Airport>,
    private val runways: Map<String, List<Runway>>,
    private val airlinesByIcao: Map<String, Airline>,
    private val airlineCallsigns: Map<String, String>,
    private val activeAirlines: Set<String> = emptySet(),
) {
    private val byIcao = airports.associateBy { it.icao }
    private val byIata = airports.filter { !it.iata.isNullOrEmpty() }.associateBy { it.iata }
    // A few rows carry a description instead of a town ("Private Airfield in the Shire of Campaspe").
    private val named = airports.filter { a -> a.city.let { !it.isNullOrEmpty() && listOf("Airfield", "Airport", "Shire of").none(it::contains) } }
    private val major = airports.filter { it.size == 'L' || it.size == 'M' }
    private val airlinesByIata = airlinesByIcao.values.filter { !it.iata.isNullOrEmpty() }.groupBy { it.iata!! }

    fun airport(code: String): Airport? = byIcao[code.uppercase()] ?: byIata[code.uppercase()]
    fun runways(icao: String): List<Runway> = runways[icao].orEmpty()
    fun airline(icao: String): Airline? = airlinesByIcao[icao.uppercase()]
    fun airlineCallsign(icao: String): String? = airlineCallsigns[icao.uppercase()]

    /** The operating airline from an airline-style callsign ("QFA123"), only for airlines still flying. */
    fun airlineForCallsign(callsign: String?): Airline? {
        val cs = callsign ?: return null
        if (cs.length < 4 || !cs[3].isDigit() || !cs.take(3).all { it in 'A'..'Z' }) return null
        return cs.take(3).takeIf { it in activeAirlines }?.let { airlinesByIcao[it] }
    }

    /** The airline ICAO prefix of an airline-style callsign ("QLK12" gives QLK), known to the table or not. For logos. */
    fun airlineCode(callsign: String?): String? {
        val cs = callsign ?: return null
        return cs.take(3).takeIf { cs.length >= 4 && cs[3].isDigit() && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * Nearest airport with a town name, for "12 km NE of Gawler" (unnamed strips would give "Private Airfield in the
     * Shire of Campaspe"). Scans ~18k rows; call off the main thread.
     */
    fun nearestPlace(lat: Double, lon: Double): Airport? {
        val k = kotlin.math.cos(Math.toRadians(lat))
        return named.minByOrNull { a -> (a.lat - lat).let { it * it } + ((a.lon - lon) * k).let { it * it } }
    }

    /** The closest large or medium airport within [nm], for reading a route off a flown path. */
    fun majorAirport(lat: Double, lon: Double, nm: Double): Airport? =
        major.minByOrNull { Geo.distanceNm(lat, lon, it.lat, it.lon) }?.takeIf { Geo.distanceNm(lat, lon, it.lat, it.lon) <= nm }

    /** IATA airline code → candidate ICAO codes (IATA codes are reused, so there can be several). */
    fun icaoForIataAirline(iata: String): List<String> = airlinesByIata[iata.uppercase()].orEmpty().mapNotNull { it.icao }

    fun searchAirports(q: String, limit: Int = 8): List<Airport> {
        val u = q.trim().uppercase()
        if (u.length < 2) return emptyList()
        val exact = listOfNotNull(byIata[u], byIcao[u])
        val rest = airports.asSequence()
            .filter { it !in exact && (it.name.uppercase().contains(u) || it.city?.uppercase()?.contains(u) == true) }
            .sortedBy { sizeRank(it) }
            .take(limit)
        return (exact + rest).distinct().take(limit)
    }

    private fun sizeRank(a: Airport) = when (a.size) { 'L' -> 0; 'M' -> 1; else -> 2 }

    companion object {
        private val ready = CompletableDeferred<StaticData>()

        fun preload(context: Context, scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) { ready.complete(load(context)) }
        }

        suspend fun get(): StaticData = ready.await()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun now(): StaticData? = if (ready.isCompleted) ready.getCompleted() else null

        private fun load(context: Context): StaticData {
            fun rows(name: String) = context.assets.open(name).bufferedReader().useLines { lines -> lines.map { it.split('\t') }.toList() }
            val airports = rows("airports.tsv").map { r ->
                Airport(
                    icao = r[0], iata = r[1].ifEmpty { null }, name = r[2], city = r[3].ifEmpty { null }, country = r[4],
                    lat = r[5].toDouble(), lon = r[6].toDouble(), elevationFt = r[7].toIntOrNull(),
                    tz = r[9].ifEmpty { null }, size = r[8].firstOrNull(),
                )
            }
            val runways = rows("runways.tsv").map { r ->
                Runway(r[0], r[1], r[2], r[3].toIntOrNull(), r[4], r[5].toDoubleOrNull(), r[6].toDoubleOrNull(),
                    r[7].toDoubleOrNull(), r[8].toDoubleOrNull(), r[9].toDoubleOrNull(), r[10].toDoubleOrNull())
            }.groupBy { it.airportIcao }
            val airlineRows = rows("airlines.tsv")
            val airlines = airlineRows.associate { r -> r[0] to Airline(r[2], r[0], r[1].ifEmpty { null }, r[4].ifEmpty { null }) }
            val callsigns = airlineRows.filter { it[3].isNotEmpty() }.associate { it[0] to it[3] }
            val active = airlineRows.filter { it.getOrNull(5) == "Y" }.mapTo(HashSet()) { it[0] }
            return StaticData(airports, runways, airlines, callsigns, active)
        }
    }
}
