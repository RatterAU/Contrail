package app.contrail.data.weather

import app.contrail.data.feed.feedJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

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

/** aviationweather.gov public data API; no key. */
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

    suspend fun taf(icao: String): Taf? {
        val res = http.get("https://aviationweather.gov/api/data/taf?ids=$icao&format=json")
        if (!res.status.isSuccess() || res.status.value == 204) return null
        return feedJson.decodeFromString<List<Taf>>(res.bodyAsText()).firstOrNull()
    }
}
