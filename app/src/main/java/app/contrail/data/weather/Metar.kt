package app.contrail.data.weather

import app.contrail.data.feed.feedJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import app.contrail.domain.WindGrid
import app.contrail.domain.WindLevel
import app.contrail.domain.WindSample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Metar(
    val icaoId: String,
    val rawOb: String,
    val obsTime: Long? = null,
    val temp: Double? = null,
    val dewp: Double? = null,
    /** Degrees, or "VRB". */
    val wdir: JsonPrimitive? = null,
    val wspd: Double? = null,
    val wgst: Double? = null,
    /** Statute miles; "6+" means 10 km or more. */
    val visib: JsonPrimitive? = null,
    val altim: Double? = null,
    val clouds: List<Cloud> = emptyList(),
    val fltCat: String? = null,
) {
    val windDir: Double? get() = wdir?.doubleOrNull
    val windVariable get() = wdir?.content == "VRB"

    val visibilityText: String
        get() = when (val v = visib?.content) {
            null -> "—"
            "6+", "10+" -> "10 km+"
            else -> v.toDoubleOrNull()?.let { mi -> val km = mi * 1.609; if (km >= 10) "10 km+" else String.format("%.1f km", km) } ?: v
        }

    val cloudText: String
        get() = if (clouds.isEmpty()) "Clear" else clouds.joinToString(", ") { c ->
            val cover = when (c.cover) { "FEW" -> "Few"; "SCT" -> "Scattered"; "BKN" -> "Broken"; "OVC" -> "Overcast"; "CLR", "SKC", "NCD", "NSC", "CAVOK" -> "Clear"; else -> c.cover }
            c.base?.let { "$cover ${String.format("%,d", it)} ft" } ?: cover
        }
}

@Serializable
data class Cloud(val cover: String, val base: Int? = null)

@Serializable
data class Taf(val icaoId: String, val rawTAF: String)

@Serializable
private data class RainViewerMaps(val host: String, val radar: RainViewerRadar)

@Serializable
private data class RainViewerRadar(val past: List<RainViewerFrame> = emptyList())

@Serializable
private data class RainViewerFrame(val time: Long, val path: String)

/** An international SIGMET: a hazard area (turbulence, icing, storms, ash, cyclone) between two levels. */
@Serializable
data class Sigmet(
    val hazard: String = "",
    val qualifier: String? = null,
    /** Feet; either can be missing. */
    val base: Int? = null,
    val top: Int? = null,
    val validTimeTo: Long? = null,
    val firName: String? = null,
    /** A list of {lat, lon} for one area, or a list of such lists when the SIGMET covers several. */
    val coords: JsonElement? = null,
) {
    val areas: List<List<LatLon>>
        get() {
            val list = coords as? JsonArray ?: return emptyList()
            return if (list.firstOrNull() is JsonArray) list.map { pointsOf(it) } else listOf(pointsOf(list))
        }

    private fun pointsOf(e: JsonElement) = (e as? JsonArray).orEmpty().mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        LatLon(o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null, o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null)
    }
}

data class LatLon(val lat: Double, val lon: Double)

/** aviationweather.gov public data API, keyless; Open-Meteo for wind (keyless, CC BY 4.0). */
class WeatherRepository(private val http: HttpClient) {
    private val metarCache = HashMap<String, Pair<Metar?, Long>>()

    suspend fun metar(icao: String): Metar? {
        val now = System.currentTimeMillis()
        synchronized(metarCache) { metarCache[icao]?.takeIf { now - it.second < 10 * 60_000 } }?.let { return it.first }
        val res = http.get("https://aviationweather.gov/api/data/metar?ids=$icao&format=json")
        // 204 = no report for this station.
        val m = if (res.status.isSuccess() && res.status.value != 204) feedJson.decodeFromString<List<Metar>>(res.bodyAsText()).firstOrNull() else null
        synchronized(metarCache) { metarCache[icao] = m to now }
        return m
    }

    /** RainViewer past radar frames as tile URL templates, oldest first. */
    suspend fun radarFrames(): List<Pair<Long, String>> {
        val res = http.get("https://api.rainviewer.com/public/weather-maps.json")
        if (!res.status.isSuccess()) return emptyList()
        val maps = feedJson.decodeFromString<RainViewerMaps>(res.bodyAsText())
        return maps.radar.past.map { it.time * 1000 to "${maps.host}${it.path}/256/{z}/{x}/{y}/2/1_1.png" }
    }

    private var sigmetCache: Pair<List<Sigmet>, Long>? = null

    /** Worldwide international SIGMETs in force, cached 10 min. */
    suspend fun sigmets(): List<Sigmet> {
        val now = System.currentTimeMillis()
        sigmetCache?.takeIf { now - it.second < 10 * 60_000 }?.let { return it.first }
        val res = http.get("https://aviationweather.gov/api/data/isigmet?format=json")
        if (!res.status.isSuccess()) return sigmetCache?.first.orEmpty()
        val list = if (res.status.value == 204) emptyList() else feedJson.decodeFromString<List<Sigmet>>(res.bodyAsText())
        sigmetCache = list to now
        return list
    }

    private val windCache = HashMap<Long, Pair<WindSample, Long>>()
    private var windStep = 0.0
    private var windBlockedUntil = 0L

    /**
     * Wind at every level for the grid [points] (indices at [step]°). Points fetched in the last hour are reused;
     * the rest come in one Open-Meteo request. Returns what it has even if the request fails.
     */
    suspend fun wind(points: List<Pair<Int, Int>>, step: Double): WindGrid {
        val now = System.currentTimeMillis()
        val have = synchronized(windCache) {
            if (step != windStep) { windCache.clear(); windStep = step }
            windCache.entries.removeAll { now - it.value.second > 3_600_000 }
            windCache.keys.toSet()
        }
        val missing = points.filter { WindGrid.key(it.first, it.second, step) !in have }.take(WindGrid.MAX_POINTS)
        if (missing.isNotEmpty() && now >= windBlockedUntil) {
            val coords = missing.map { WindGrid.latLon(it.first, it.second, step) }
            val vars = WindLevel.entries.flatMap { listOf(it.speedVar, it.dirVar) }.joinToString(",")
            val res = http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=${coords.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it.first) }}" +
                    "&longitude=${coords.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it.second) }}&current=$vars&wind_speed_unit=kn",
            )
            // Over the daily limit (429): stop asking for a while rather than hammering it.
            if (res.status.value == 429) windBlockedUntil = now + 15 * 60_000
            if (res.status.isSuccess()) {
                val body = feedJson.parseToJsonElement(res.bodyAsText())
                val rows = (body as? JsonArray)?.map { it.jsonObject } ?: listOf(body.jsonObject)
                synchronized(windCache) {
                    rows.zip(missing).forEach { (row, p) -> parseWind(row)?.let { windCache[WindGrid.key(p.first, p.second, step)] = it to now } }
                }
            }
        }
        return WindGrid(step, synchronized(windCache) { windCache.mapValues { it.value.first } })
    }

    private fun parseWind(row: JsonObject): WindSample? {
        val cur = row["current"]?.jsonObject ?: return null
        fun f(k: String) = cur[k]?.jsonPrimitive?.doubleOrNull?.toFloat()
        val speed = WindLevel.entries.map { f(it.speedVar) ?: return null }
        val dir = WindLevel.entries.map { f(it.dirVar) ?: return null }
        return WindSample(speed.toFloatArray(), dir.toFloatArray())
    }

    suspend fun taf(icao: String): Taf? {
        val res = http.get("https://aviationweather.gov/api/data/taf?ids=$icao&format=json")
        if (!res.status.isSuccess() || res.status.value == 204) return null
        return feedJson.decodeFromString<List<Taf>>(res.bodyAsText()).firstOrNull()
    }
}
