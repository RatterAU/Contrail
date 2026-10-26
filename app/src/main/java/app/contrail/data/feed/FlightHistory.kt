package app.contrail.data.feed

import app.contrail.domain.Geo
import app.contrail.domain.TrailPoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.cos

/**
 * The path a plane flew before the app saw it, from OpenSky's anonymous tracks endpoint (keyless;
 * ~400 requests a day, so it's fetched once per tapped plane and cached). The readsb feeds' own
 * trace files are bot-protected.
 */
class FlightHistory(private val http: HttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val cache = HashMap<String, Pair<List<TrailPoint>?, Long>>()

    /** The current flight's path, oldest first, or null if unknown, stale or the request failed. */
    suspend fun fetch(hex: String): List<TrailPoint>? {
        val now = clock()
        synchronized(cache) { cache[hex]?.takeIf { now - it.second < CACHE_MS } }?.let { return it.first }
        val pts = try {
            val res = http.get("https://opensky-network.org/api/tracks/all?icao24=${hex.lowercase()}&time=0")
            if (res.status.isSuccess()) parseOpenSkyTrack(res.bodyAsText(), now) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null // offline or rate-limited: try again next time it's tapped
        }
        synchronized(cache) { cache[hex] = pts to now }
        return pts
    }

    companion object {
        private const val CACHE_MS = 10 * 60_000L
        private const val M_TO_FT = 3.28084

        /**
         * OpenSky path rows are [time s, lat, lon, baro alt m, track, onGround]. Coverage gaps (oceans, outback)
         * are bridged along the great circle so the line stays whole. A track that ended over 15 min ago
         * belongs to an earlier flight and is ignored.
         */
        fun parseOpenSkyTrack(json: String, now: Long): List<TrailPoint>? {
            val path = feedJson.parseToJsonElement(json).jsonObject["path"] as? JsonArray ?: return null
            val raw = path.mapNotNull { e ->
                val r = e.jsonArray
                val lat = r[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val lon = r[2].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val alt = (r[3] as? JsonNull)?.let { null } ?: r[3].jsonPrimitive.doubleOrNull
                TrailPoint(lat, lon, alt?.let { (it * M_TO_FT).toInt() }, r[5].jsonPrimitive.booleanOrNull ?: false, null,
                    (r[0].jsonPrimitive.longOrNull ?: return@mapNotNull null) * 1000)
            }
            if (raw.size < 2 || now - raw.last().time > 15 * 60_000) return null
            // OpenSky's fixes jitter: altitude by a few hundred feet, timestamps by seconds. A median of three
            // removes altitude spikes; speed is measured over at least 2 minutes so it doesn't saw up and down.
            val clean = raw.mapIndexed { i, p ->
                val alts = listOfNotNull(raw.getOrNull(i - 1)?.alt, p.alt, raw.getOrNull(i + 1)?.alt)
                p.copy(alt = if (alts.size == 3 && p.alt != null) alts.sorted()[1] else p.alt)
            }
            val out = ArrayList<TrailPoint>(raw.size)
            var from = 0
            for ((i, p) in clean.withIndex()) {
                val prev = clean.getOrNull(i - 1)
                while (from < i - 1 && p.time - clean[from + 1].time >= 120_000) from++
                val gs = if (i > 0) speedKt(clean[from], p) else null
                if (prev != null && (abs(p.lat - prev.lat) > 0.5 || abs(p.lon - prev.lon) * cos(Math.toRadians(p.lat)) > 0.5)) {
                    val dist = Geo.distanceNm(prev.lat, prev.lon, p.lat, p.lon)
                    val steps = (dist / 20).toInt().coerceIn(1, 100)
                    for (s in 1 until steps) {
                        val (lat, lon) = Geo.destination(prev.lat, prev.lon, Geo.bearing(prev.lat, prev.lon, p.lat, p.lon), dist * s / steps)
                        val alt = if (prev.alt != null && p.alt != null) prev.alt + (p.alt - prev.alt) * s / steps else p.alt
                        out += TrailPoint(lat, lon, alt, false, gs, prev.time + (p.time - prev.time) * s / steps)
                    }
                }
                out += p.copy(gs = gs ?: clean.getOrNull(1)?.let { speedKt(p, it) })
            }
            return out
        }

        private fun speedKt(a: TrailPoint, b: TrailPoint): Double? {
            val dt = (b.time - a.time) / 3_600_000.0
            return if (dt > 0) Geo.distanceNm(a.lat, a.lon, b.lat, b.lon) / dt else null
        }
    }
}
