package app.contrail.data.feed

import app.contrail.domain.Geo
import app.contrail.domain.TrailPoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * One earlier flight: airports are ICAO idents from the bundled table, null where the plane wasn't seen there. Times
 * are takeoff and landing when seen; otherwise when the plane was first or last seen. [scheduledFrom] and
 * [scheduledTo] fill an unseen end from the callsign's published route, only where the flown path agrees with it.
 */
data class PastFlight(
    val callsign: String?,
    val from: String?,
    val departed: Long,
    val to: String?,
    val arrived: Long,
    val departedSeen: Boolean = true,
    val arrivedSeen: Boolean = true,
    /** Where this plane's trace for each day of the flight sits in adsb.lol's daily release ("YYYYMMDD.N.aa.offset.size"). */
    val traces: List<String> = emptyList(),
    val scheduledFrom: String? = null,
    val scheduledTo: String? = null,
)

/** Figures from a past flight's path. Top speed ignores impossible reports and the fastest 1%, where glitches show. */
data class PastStats(val distanceNm: Double, val topAltFt: Int?, val topSpeedKt: Double?)

/**
 * The path a plane flew before the app saw it, from OpenSky's anonymous tracks endpoint (keyless;
 * ~400 requests a day, so it's fetched once per tapped plane and cached). The readsb feeds' own
 * trace files are bot-protected.
 *
 * Earlier flights (up to yesterday, 14 days) come from this repo's own GitHub Pages: tools/history.py turns
 * adsb.lol's daily open-data dump into one small file per first four hex digits.
 */
class FlightHistory(private val http: HttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val cache = HashMap<String, Pair<List<TrailPoint>?, Long>>()
    // By shard, not plane: nearby planes often share one (Australian hexes all start 7c).
    private val pastCache = HashMap<String, Pair<String, Long>>()

    /** Earlier flights, newest first; empty if none are known, null if the request failed. */
    suspend fun past(hex: String): List<PastFlight>? {
        val now = clock()
        val h = hex.lowercase()
        val shard = h.take(4)
        synchronized(pastCache) { pastCache[shard]?.takeIf { now - it.second < PAST_CACHE_MS } }?.let { return parsePastFlights(it.first, h) }
        val text = try {
            val res = http.get("$PAST_URL$shard.txt")
            when {
                res.status.isSuccess() -> res.bodyAsText()
                res.status.value == 404 -> "" // nothing in this shard
                else -> return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        synchronized(pastCache) { pastCache[shard] = text to now }
        return parsePastFlights(text, h)
    }

    private val pathCache = object : LinkedHashMap<String, List<TrailPoint>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TrailPoint>>) = size > 20
    }

    /**
     * The path [f] flew, fetched straight from adsb.lol's daily open-data release: an HTTP range request for just this
     * plane's day (a few KB), not the 4 GB file. Empty if it wasn't recorded, null if a download failed.
     */
    suspend fun path(f: PastFlight): List<TrailPoint>? {
        val days = f.traces.map { ref ->
            synchronized(pathCache) { pathCache[ref] } ?: run {
                val url = traceUrl(ref) ?: return emptyList()
                val pts = try {
                    val (offset, size) = ref.split('.').let { it[3].toLong() to it[4].toLong() }
                    val res = http.get(url) { header(HttpHeaders.Range, "bytes=$offset-${offset + size - 1}") }
                    if (res.status.value != 206) return null // a full 4 GB answer would be wrong; never read it
                    val bytes = res.bodyAsBytes()
                    withContext(Dispatchers.Default) { parseTrace(java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return null
                }
                synchronized(pathCache) { pathCache[ref] = pts }
                pts
            }
        }
        return slice(days.flatten(), f)
    }

    /** The current flight's path, oldest first, or null if unknown, stale or the request failed. */
    suspend fun fetch(hex: String): List<TrailPoint>? {
        val now = clock()
        synchronized(cache) { cache[hex]?.takeIf { now - it.second < CACHE_MS } }?.let { return it.first }
        val pts = try {
            val res = http.get("https://opensky-network.org/api/tracks/all?icao24=${hex.lowercase()}&time=0")
            if (res.status.isSuccess()) res.bodyAsText().let { withContext(Dispatchers.Default) { parseOpenSkyTrack(it, now) } } else null
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
        private const val PAST_CACHE_MS = 60 * 60_000L // the files change once a day
        /** This repo's GitHub Pages: past flights in h/, routes seen by callsign in r/. */
        const val SITE = "https://ratterau.github.io/Contrail/"
        private const val PAST_URL = "${SITE}h/"

        /** "YYYYMMDD.N.aa.offset.size" to that part's download URL on adsb.lol's globe_history release. */
        fun traceUrl(ref: String): String? {
            val c = ref.split('.')
            if (c.size != 5 || c[0].length != 8) return null
            val tag = "v${c[0].take(4)}.${c[0].substring(4, 6)}.${c[0].substring(6)}-planes-readsb-prod-${c[1]}"
            return "https://github.com/adsblol/globe_history_${c[0].take(4)}/releases/download/$tag/$tag.tar.${c[2]}"
        }

        /** A readsb trace_full file: `timestamp` plus rows of [secs after it, lat, lon, alt ft | "ground" | null, gs, ...]. */
        fun parseTrace(json: String): List<TrailPoint> {
            val o = feedJson.parseToJsonElement(json).jsonObject
            val base = o["timestamp"]?.jsonPrimitive?.doubleOrNull ?: return emptyList()
            return o["trace"]?.jsonArray.orEmpty().mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                val secs = r[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val alt = r[3] as? kotlinx.serialization.json.JsonPrimitive
                val ground = alt?.isString == true && alt.content == "ground"
                TrailPoint(
                    lat = r[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    lon = r[2].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    alt = if (ground) 0 else alt?.takeIf { !it.isString }?.doubleOrNull?.toInt(),
                    onGround = ground,
                    gs = (r.getOrNull(4) as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull,
                    time = ((base + secs) * 1000).toLong(),
                )
            }
        }

        /** The part of a day's (or two days') trace that is this flight: from 10 min before it was first seen to 10 min after. */
        fun slice(pts: List<TrailPoint>, f: PastFlight): List<TrailPoint> =
            smooth(pts.filter { it.time in f.departed - 600_000..f.arrived + 600_000 }.sortedBy { it.time })

        /**
         * A single glitched report (an altitude thousands of feet off, a speed spike) takes the median of its
         * neighbours: altitude over three airborne points, ground speed over five.
         */
        fun smooth(pts: List<TrailPoint>): List<TrailPoint> = pts.mapIndexed { i, p ->
            if (p.onGround) return@mapIndexed p
            val alts = (i - 1..i + 1).mapNotNull { j -> pts.getOrNull(j)?.takeIf { !it.onGround }?.alt }
            val speeds = (i - 2..i + 2).mapNotNull { j -> pts.getOrNull(j)?.gs }
            p.copy(
                alt = if (p.alt != null && alts.size == 3) alts.sorted()[1] else p.alt,
                gs = if (p.gs != null && speeds.size >= 3) speeds.sorted()[speeds.size / 2] else p.gs,
            )
        }

        fun stats(pts: List<TrailPoint>): PastStats {
            val air = pts.filter { !it.onGround }
            // Over 750 kt is a glitch: an airliner riding the strongest jet stream peaks around 700.
            val speeds = air.mapNotNull { it.gs }.filter { it <= 750 }.sorted()
            return PastStats(
                distanceNm = pts.zipWithNext().sumOf { (a, b) -> Geo.distanceNm(a.lat, a.lon, b.lat, b.lon) },
                topAltFt = air.mapNotNull { it.alt }.maxOrNull(),
                topSpeedKt = speeds.getOrNull((speeds.size * 0.99).toInt().coerceAtMost(speeds.size - 1)),
            )
        }

        /**
         * Lines are `hex  callsign  from  departed  to  arrived  traces  scheduled-from  scheduled-to`, tab-separated,
         * times in unix minutes, "~" if not seen. Older files stop after traces.
         */
        fun parsePastFlights(text: String, hex: String): List<PastFlight> = text.lineSequence()
            .filter { it.startsWith(hex) }
            .mapNotNull { line ->
                val c = line.split('\t')
                if (c.size < 6 || c[0] != hex) return@mapNotNull null
                PastFlight(c[1].ifEmpty { null }, c[2].ifEmpty { null }, (c[3].removePrefix("~").toLongOrNull() ?: return@mapNotNull null) * 60_000,
                    c[4].ifEmpty { null }, (c[5].removePrefix("~").toLongOrNull() ?: return@mapNotNull null) * 60_000,
                    !c[3].startsWith('~'), !c[5].startsWith('~'), c.getOrNull(6)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
                    c.getOrNull(7)?.ifEmpty { null }, c.getOrNull(8)?.ifEmpty { null })
            }
            .sortedByDescending { it.departed }
            .toList()
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
