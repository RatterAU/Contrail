package app.contrail.data.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.contrail.domain.Aircraft
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import java.util.Locale

/** URL layout of one readsb-compatible public API. All paths are relative to [base]. */
data class FeedEndpoints(
    val name: String,
    val base: String,
    val point: (lat: Double, lon: Double, nm: Int) -> String,
    val hex: String = "hex",
    val callsign: String = "callsign",
    val reg: String = "reg",
    val type: String = "type",
    val squawk: String = "sqk",
    val military: String = "mil",
)

// Keyless feeds verified 2026-10-25. airplanes.live now refuses requests without prior email contact.
val ADSB_LOL = FeedEndpoints(
    name = "adsb.lol",
    base = "https://api.adsb.lol/v2/",
    point = { lat, lon, nm -> "point/${lat.fmt()}/${lon.fmt()}/$nm" },
)
val ADSB_FI = FeedEndpoints(
    name = "adsb.fi",
    base = "https://opendata.adsb.fi/api/v2/",
    point = { lat, lon, nm -> "lat/${lat.fmt()}/lon/${lon.fmt()}/dist/$nm" },
    reg = "registration",
)

private fun Double.fmt() = String.format(Locale.US, "%.4f", this)

class FeedHttpException(val status: Int, source: String) : IOException("$source returned HTTP $status")

open class AdsbSource(val endpoints: FeedEndpoints, private val client: HttpClient, private val clock: () -> Long) {
    val name get() = endpoints.name

    /** Aircraft within [nm] nautical miles (the APIs cap this at 250). */
    open suspend fun point(lat: Double, lon: Double, nm: Int) = fetch(endpoints.point(lat, lon, nm.coerceIn(1, 250)))
    open suspend fun hex(hex: String) = fetch("${endpoints.hex}/${hex.lowercase()}")
    open suspend fun callsign(cs: String) = fetch("${endpoints.callsign}/${cs.uppercase()}")
    open suspend fun registration(reg: String) = fetch("${endpoints.reg}/${reg.uppercase()}")
    open suspend fun type(icaoType: String) = fetch("${endpoints.type}/${icaoType.uppercase()}")
    open suspend fun squawk(sq: String) = fetch("${endpoints.squawk}/$sq")
    open suspend fun military() = fetch(endpoints.military)

    private suspend fun fetch(path: String): List<Aircraft> {
        val res = client.get(endpoints.base + path)
        if (!res.status.isSuccess()) throw FeedHttpException(res.status.value, name)
        val body = res.bodyAsText()
        // A wide view returns hundreds of aircraft; parse off the main thread.
        return withContext(Dispatchers.Default) { parseReadsb(body, clock()) }
    }
}
