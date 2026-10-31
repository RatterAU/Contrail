package app.contrail.data.feed

import app.contrail.domain.Aircraft
import app.contrail.domain.PositionSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** readsb v2 JSON, shared by adsb.lol and adsb.fi. adsb.fi names the list "aircraft" on point queries. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReadsbResponse(
    @JsonNames("ac", "aircraft") val ac: List<ReadsbAircraft> = emptyList(),
)

@Serializable
data class ReadsbAircraft(
    val hex: String,
    val type: String? = null,
    val flight: String? = null,
    val r: String? = null,
    val t: String? = null,
    val desc: String? = null,
    val ownOp: String? = null,
    val year: JsonPrimitive? = null,
    @SerialName("alt_baro") val altBaro: JsonPrimitive? = null,
    @SerialName("alt_geom") val altGeom: Double? = null,
    val gs: Double? = null,
    val ias: Double? = null,
    val tas: Double? = null,
    val mach: Double? = null,
    val wd: Double? = null,
    val ws: Double? = null,
    val oat: Double? = null,
    val tat: Double? = null,
    val track: Double? = null,
    @SerialName("track_rate") val trackRate: Double? = null,
    val roll: Double? = null,
    @SerialName("mag_heading") val magHeading: Double? = null,
    @SerialName("true_heading") val trueHeading: Double? = null,
    @SerialName("baro_rate") val baroRate: Double? = null,
    @SerialName("geom_rate") val geomRate: Double? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val category: String? = null,
    @SerialName("nav_qnh") val navQnh: Double? = null,
    @SerialName("nav_altitude_mcp") val navAltitudeMcp: Double? = null,
    @SerialName("nav_altitude_fms") val navAltitudeFms: Double? = null,
    @SerialName("nav_heading") val navHeading: Double? = null,
    @SerialName("nav_modes") val navModes: List<String>? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("rr_lat") val rrLat: Double? = null,
    @SerialName("rr_lon") val rrLon: Double? = null,
    val nic: Double? = null,
    @SerialName("nac_p") val nacP: Double? = null,
    val sil: Double? = null,
    val version: Double? = null,
    val messages: Double? = null,
    val seen: Double? = null,
    @SerialName("seen_pos") val seenPos: Double? = null,
    val rssi: Double? = null,
    val dbFlags: Int? = null,
)

val feedJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

fun parseReadsb(body: String, receivedAtMs: Long): List<Aircraft> {
    val res = feedJson.decodeFromString<ReadsbResponse>(body)
    // The phone's clock, not the feed's "now": ages and dead-reckoning compare against it, so a phone a minute or two
    // out would otherwise see every plane as long gone.
    return res.ac.mapNotNull { it.toAircraft(receivedAtMs) }
}

fun ReadsbAircraft.toAircraft(nowMs: Long): Aircraft? {
    val la = lat ?: rrLat ?: return null
    val lo = lon ?: rrLon ?: return null
    val ground = altBaro?.content == "ground"
    return Aircraft(
        hex = hex.lowercase().removePrefix("~"),
        callsign = flight?.trim()?.ifEmpty { null },
        registration = r?.ifBlank { null },
        typeCode = t?.ifBlank { null },
        description = desc?.ifBlank { null },
        operator = ownOp?.ifBlank { null },
        year = year?.content?.ifBlank { null },
        lat = la,
        lon = lo,
        altBaro = if (ground) 0 else altBaro?.doubleOrNull?.toInt(),
        onGround = ground,
        altGeom = altGeom?.toInt(),
        groundSpeed = gs,
        ias = ias, tas = tas, mach = mach,
        track = track ?: trueHeading,
        trackRate = trackRate, roll = roll,
        magHeading = magHeading, trueHeading = trueHeading,
        baroRate = baroRate, geomRate = geomRate,
        squawk = squawk, emergency = emergency?.takeIf { it != "none" },
        category = category,
        navQnh = navQnh, navAltMcp = navAltitudeMcp?.toInt(), navAltFms = navAltitudeFms?.toInt(),
        navHeading = navHeading, navModes = navModes.orEmpty(),
        windDir = wd, windSpeed = ws, oat = oat, tat = tat,
        source = PositionSource.from(type, rough = lat == null),
        nic = nic?.toInt(), nacP = nacP?.toInt(), sil = sil?.toInt(), adsbVersion = version?.toInt(),
        rssi = rssi, messages = messages?.toLong(),
        dbFlags = dbFlags ?: 0,
        positionTime = nowMs - ((seenPos ?: seen ?: 0.0) * 1000).toLong(),
        seenTime = nowMs - ((seen ?: 0.0) * 1000).toLong(),
    )
}
