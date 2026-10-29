package app.contrail.data.enrich

import app.contrail.data.feed.feedJson
import app.contrail.data.static.StaticData
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import app.contrail.data.feed.FlightHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

data class Airport(
    val icao: String?,
    val iata: String?,
    val name: String,
    val city: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
    val elevationFt: Int?,
    /** IANA zone, e.g. "Australia/Adelaide"; from the bundled airport table. */
    val tz: String? = null,
    /** 'L'arge, 'M'edium or 'S'mall, from OurAirports. */
    val size: Char? = null,
) {
    val code get() = iata?.ifEmpty { null } ?: icao ?: "?"
    val shortName get() = city?.ifEmpty { null } ?: name
}

data class Airline(val name: String, val icao: String?, val iata: String?, val country: String?)

data class Route(val callsign: String, val flightNumber: String?, val airline: Airline?, val origin: Airport, val destination: Airport)

data class AircraftInfo(
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val icaoType: String?,
    val owner: String?,
    val ownerCountry: String?,
) {
    val fullName get() = listOfNotNull(manufacturer?.let(::shortMaker)?.takeUnless { type?.startsWith(it, ignoreCase = true) == true }, type)
        .joinToString(" ").ifEmpty { null }
}

/**
 * The registry's manufacturer as people say it: "Embraer - Empresa Brasileira De Aeronautica S.a." is Embraer,
 * "Piper Aircraft, Inc." is Piper, "Avions de Transport Regional" is ATR.
 */
fun shortMaker(name: String): String {
    var s = name.substringBefore(" - ").trim()
    if (s.equals("Avions de Transport Regional", ignoreCase = true)) return "ATR"
    while (true) {
        val t = s.replace(Regex("""(,?\s+(Inc\.?|Corp\.?|Corporation|Company|Industries|Aircraft|S\.a\.|GmbH|Ltd\.?))$""", RegexOption.IGNORE_CASE), "")
        if (t == s || t.isEmpty()) return s
        s = t
    }
}

data class Photo(val thumbUrl: String, val fullUrl: String, val photographer: String?, val pageUrl: String?, val site: String)

/** [legs]: each leg of the callsign's route from adsb.lol (SYD-SIN-LHR is two), for when [route] doesn't fit or is missing. */
/** [legsPending]: adsbdb answered (about 1 s) but adsb.lol's legs haven't (it can take several seconds). */
data class Enrichment(val info: AircraftInfo?, val route: Route?, val photos: List<Photo>, val legs: List<Route> = emptyList(), val legsPending: Boolean = false)

/**
 * Keyless lookups: adsbdb.com (aircraft + callsign route), adsb.lol (route legs) and airport-data.com (photos).
 * Results are cached in memory per (hex, callsign); callers get the cached value instantly.
 */
class EnrichmentRepository(private val http: HttpClient) {
    private val cache = object : LinkedHashMap<String, Enrichment>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Enrichment>) = size > 300
    }
    private val mutex = Mutex()

    fun cached(hex: String, callsign: String?): Enrichment? = synchronized(cache) { cache[key(hex, callsign)] }

    /** [partial] gets adsbdb's answer as soon as it's in, before adsb.lol's slower legs. */
    suspend fun lookup(hex: String, callsign: String?, partial: suspend (Enrichment) -> Unit = {}): Enrichment {
        cached(hex, callsign)?.let { return it }
        return mutex.withLock {
            cached(hex, callsign) ?: coroutineScope {
                val adsbdb = async { retrying { adsbdb(hex, callsign) } }
                val photos = async { safe { airportDataPhotos(hex) }.orEmpty() }
                val legs = async { if (callsign == null) emptyList() else retrying { adsbLolLegs(callsign) } }
                val seen = async { callsign?.let { safe { seenRoutes(it) } }.orEmpty() }
                val db = adsbdb.await()
                val (info, listed, fallbackPhoto) = db ?: Triple(null, null, null)
                // adsbdb rejects the whole request when it doesn't know the airframe (VOZ092's 7cad46), route included.
                var routeFailed = false
                val route = listed ?: callsign?.let { cs -> retrying { routeOrThrow(cs) }.also { routeFailed = it == null }?.getOrNull() }
                val pics = photos.await().ifEmpty { listOfNotNull(fallbackPhoto) }
                if (callsign != null && !legs.isCompleted) partial(Enrichment(info, route, pics, legsPending = true))
                val lol = legs.await()
                // Routes this callsign was seen flying lately come after the listed ones: published routes go stale.
                val candidates = lol.orEmpty() + seen.await().filter { r -> lol.orEmpty().none { it.origin.icao == r.origin.icao && it.destination.icao == r.destination.icao } }
                val e = Enrichment(info, route, pics, candidates.map {
                    it.copy(flightNumber = route?.flightNumber ?: it.flightNumber, airline = route?.airline ?: it.airline)
                })
                // A source that failed (busy, timed out, offline) isn't an answer: left uncached, the next look asks again.
                if (db != null && lol != null && !routeFailed) synchronized(cache) { cache[key(hex, callsign)] = e }
                e
            }
        }
    }

    private fun key(hex: String, cs: String?) = "$hex|${cs.orEmpty()}"

    /**
     * [block] up to three times, 2 s then 5 s apart; null if every try failed. adsb.lol answers 429 when the feed
     * polling has used its share, and adsbdb times out under load.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T? {
        for (wait in longArrayOf(0, 2_000, 5_000)) {
            delay(wait)
            safe(block)?.let { return it }
        }
        return null
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun adsbdb(hex: String, callsign: String?): Triple<AircraftInfo?, Route?, Photo?> {
        var res = http.get("https://api.adsbdb.com/v0/aircraft/$hex" + (callsign?.let { "?callsign=${it.encodeURLPathPart()}" } ?: ""))
        // adsbdb 404s the whole request when only the callsign is unknown; retry for the aircraft alone.
        if (res.status == HttpStatusCode.NotFound && callsign != null) res = http.get("https://api.adsbdb.com/v0/aircraft/$hex")
        if (res.status == HttpStatusCode.NotFound) return Triple(null, null, null) // airframe unknown
        if (!res.status.isSuccess()) throw java.io.IOException("adsbdb ${res.status.value}")
        val response = feedJson.parseToJsonElement(res.bodyAsText()).jsonObject["response"] as? JsonObject ?: return Triple(null, null, null)
        val ac = response["aircraft"]?.let { feedJson.decodeFromJsonElement<AdsbdbAircraft>(it) }
        val fr = response["flightroute"]?.let { feedJson.decodeFromJsonElement<AdsbdbRoute>(it) }
        val info = ac?.let { AircraftInfo(it.registration, it.manufacturer, it.type, it.icaoType, it.registeredOwner, it.registeredOwnerCountryName) }
        val photo = ac?.urlPhoto?.let { Photo(ac.urlPhotoThumbnail ?: it, it, null, null, "airport-data.com") }
        return Triple(info, fr?.toRoute(), photo)
    }

    private val routeCache = HashMap<String, Pair<Route?, Long>>()

    /** Route by callsign alone (for airport boards). Cached 6 h, including "unknown" answers; errors aren't cached. */
    suspend fun routeFor(callsign: String): Route? = safe { routeOrThrow(callsign) }?.getOrNull()

    /** Success(null) when adsbdb doesn't know the callsign; throws when it couldn't answer. */
    private suspend fun routeOrThrow(callsign: String): Result<Route?> {
        val now = System.currentTimeMillis()
        synchronized(routeCache) { routeCache[callsign]?.takeIf { now - it.second < 6 * 3_600_000 } }?.let { return Result.success(it.first) }
        val res = http.get("https://api.adsbdb.com/v0/callsign/${callsign.encodeURLPathPart()}")
        val route = if (res.status == HttpStatusCode.NotFound) null else {
            if (!res.status.isSuccess()) throw java.io.IOException("adsbdb ${res.status.value}")
            val response = feedJson.parseToJsonElement(res.bodyAsText()).jsonObject["response"] as? JsonObject
            response?.get("flightroute")?.let { feedJson.decodeFromJsonElement<AdsbdbRoute>(it).toRoute() }
        }
        synchronized(routeCache) { routeCache[callsign] = route to now }
        return Result.success(route)
    }

    private val seenCache = HashMap<String, Pair<String, Long>>()

    /**
     * Routes [callsign] was seen flying (both ends) at least twice in the last 14 days, most flown first, from this
     * repo's daily history (tools/history.py). One file per first four characters, shared by nearby flight numbers.
     */
    private suspend fun seenRoutes(callsign: String): List<Route> {
        if (callsign.length < 4 || !callsign.all { it.isLetterOrDigit() }) return emptyList()
        val prefix = callsign.take(4)
        val now = System.currentTimeMillis()
        val text = synchronized(seenCache) { seenCache[prefix]?.takeIf { now - it.second < 3_600_000 }?.first } ?: run {
            val res = http.get("${FlightHistory.SITE}r/$prefix.txt")
            val body = if (res.status == HttpStatusCode.NotFound) "" else if (res.status.isSuccess()) res.bodyAsText() else throw java.io.IOException("${res.status.value}")
            synchronized(seenCache) { seenCache[prefix] = body to now }
            body
        }
        val static = StaticData.now() ?: return emptyList()
        return parseSeenRoutes(text, callsign, static.airlineForCallsign(callsign), static::airport)
    }

    /** Empty when adsb.lol doesn't know the callsign (404); throws when it couldn't answer (429, 5xx, offline). */
    private suspend fun adsbLolLegs(callsign: String): List<Route> {
        val res = http.get("https://api.adsb.lol/api/0/route/${callsign.encodeURLPathPart()}")
        if (res.status == HttpStatusCode.NotFound) return emptyList()
        if (!res.status.isSuccess()) throw java.io.IOException("adsb.lol ${res.status.value}")
        return parseAdsbLolLegs(res.bodyAsText())
    }

    private fun AdsbdbRoute.toRoute(): Route? {
        val o = origin?.toAirport() ?: return null
        val d = destination?.toAirport() ?: return null
        return Route(callsignIcao ?: callsign, callsignIata, airline?.let { Airline(it.name, it.icao, it.iata, it.country) }, o, d)
    }

    private suspend fun airportDataPhotos(hex: String): List<Photo> {
        val res = http.get("https://www.airport-data.com/api/ac_thumb.json?m=${hex.uppercase()}&n=5")
        if (!res.status.isSuccess()) return emptyList()
        val body = feedJson.decodeFromString<AirportDataResponse>(res.bodyAsText())
        return body.data.map { p ->
            // Thumbnails live at /images/aircraft/thumbnails/001/672/001672596.jpg; the full image shares the id.
            val id = p.image.substringAfterLast('/').substringBefore('.')
            Photo(p.image, "https://image.airport-data.com/aircraft/$id.jpg", p.photographer, p.link, "airport-data.com")
        }
    }
}

/** Lines `callsign  from  to  times-seen`, most flown first; [airport] looks up the bundled table. */
fun parseSeenRoutes(text: String, callsign: String, airline: Airline?, airport: (String) -> Airport?): List<Route> =
    text.lineSequence().map { it.split('\t') }.filter { it.size >= 4 && it[0] == callsign }
        .sortedByDescending { it[3].toIntOrNull() ?: 0 }
        .mapNotNull { c -> Route(callsign, null, airline, airport(c[1]) ?: return@mapNotNull null, airport(c[2]) ?: return@mapNotNull null) }
        .toList()

/** adsb.lol `/api/0/route/{callsign}`: its `_airports` in flight order, as one route per leg. */
fun parseAdsbLolLegs(json: String): List<Route> {
    val r = feedJson.decodeFromString<AdsbLolRoute>(json)
    val static = StaticData.now()
    val stops = r.airports.map { a ->
        // The bundled table adds time zone and size; keep the API's coordinates either way.
        val s = a.icao?.let { static?.airport(it) }
        Airport(a.icao, a.iata, a.name, a.location ?: s?.city, s?.country, a.lat, a.lon, a.altFeet?.toInt(), s?.tz, s?.size)
    }
    val airline = r.airlineCode?.let { static?.airline(it) }
    val number = r.number?.let { n -> airline?.iata?.let { "$it$n" } }
    return stops.zipWithNext { o, d -> Route(r.callsign, number, airline, o, d) }
}

@Serializable
private data class AdsbLolRoute(
    val callsign: String,
    val number: String? = null,
    @SerialName("airline_code") val airlineCode: String? = null,
    @SerialName("_airports") val airports: List<AdsbLolAirport> = emptyList(),
)

@Serializable
private data class AdsbLolAirport(
    val name: String,
    val icao: String? = null,
    val iata: String? = null,
    val location: String? = null,
    val lat: Double,
    val lon: Double,
    @SerialName("alt_feet") val altFeet: Double? = null,
)

@Serializable
private data class AirportDataResponse(val status: Int = 0, val data: List<AirportDataPhoto> = emptyList())

@Serializable
private data class AirportDataPhoto(val image: String, val link: String? = null, val photographer: String? = null)

@Serializable
private data class AdsbdbAircraft(
    val type: String? = null,
    @SerialName("icao_type") val icaoType: String? = null,
    val manufacturer: String? = null,
    val registration: String? = null,
    @SerialName("registered_owner") val registeredOwner: String? = null,
    @SerialName("registered_owner_country_name") val registeredOwnerCountryName: String? = null,
    @SerialName("url_photo") val urlPhoto: String? = null,
    @SerialName("url_photo_thumbnail") val urlPhotoThumbnail: String? = null,
)

@Serializable
private data class AdsbdbRoute(
    val callsign: String,
    @SerialName("callsign_icao") val callsignIcao: String? = null,
    @SerialName("callsign_iata") val callsignIata: String? = null,
    val airline: AdsbdbAirline? = null,
    val origin: AdsbdbAirport? = null,
    val destination: AdsbdbAirport? = null,
)

@Serializable
private data class AdsbdbAirline(val name: String, val icao: String? = null, val iata: String? = null, val country: String? = null)

@Serializable
private data class AdsbdbAirport(
    val name: String,
    @SerialName("icao_code") val icao: String? = null,
    @SerialName("iata_code") val iata: String? = null,
    val municipality: String? = null,
    @SerialName("country_name") val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val elevation: Int? = null,
) {
    fun toAirport(): Airport {
        // Time zone and size come from the bundled table.
        val s = icao?.let { StaticData.now()?.airport(it) }
        return Airport(icao, iata, name, municipality ?: s?.city, country, latitude, longitude, elevation, s?.tz, s?.size)
    }
}
