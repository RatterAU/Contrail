package app.contrail.domain

/** A feed lookup that a free-text query could mean. */
sealed interface RemoteQuery {
    data class Hex(val hex: String) : RemoteQuery
    data class Callsign(val callsign: String) : RemoteQuery
    data class Registration(val reg: String) : RemoteQuery
    data class Type(val icaoType: String) : RemoteQuery
    data class Squawk(val code: String) : RemoteQuery
}

object SearchQuery {
    private val HEX = Regex("^[0-9A-F]{6}$")
    private val ICAO_FLIGHT = Regex("^[A-Z]{3}[0-9]{1,4}[A-Z]{0,2}$")
    private val IATA_FLIGHT = Regex("^([A-Z0-9]{2})([0-9]{1,4}[A-Z]?)$")
    private val REG = Regex("^[A-Z0-9]{1,3}-[A-Z0-9]{1,5}$|^N[0-9][0-9A-Z]{1,4}$|^[A-Z]{1,2}[0-9]{4,6}$")
    private val TYPE = Regex("^[A-Z][A-Z0-9]{1,3}$")
    private val SQUAWK = Regex("^[0-7]{4}$")

    /**
     * Everything [raw] might be. "QF1" becomes callsign QFA1 via the airline table; "A388" is
     * both a possible type and hex-less text, so several lookups can run for one query.
     */
    fun classify(raw: String, icaoForIata: (String) -> List<String>): List<RemoteQuery> {
        val q = raw.trim().uppercase().replace(" ", "")
        if (q.length < 2) return emptyList()
        val out = LinkedHashSet<RemoteQuery>()
        if (SQUAWK.matches(q)) out += RemoteQuery.Squawk(q)
        if (HEX.matches(q)) out += RemoteQuery.Hex(q)
        IATA_FLIGHT.matchEntire(q)?.let { m ->
            icaoForIata(m.groupValues[1]).take(3).forEach { out += RemoteQuery.Callsign(it + m.groupValues[2].trimStart('0')) }
        }
        if (ICAO_FLIGHT.matches(q)) out += RemoteQuery.Callsign(q)
        if (REG.matches(q)) out += RemoteQuery.Registration(q)
        if (TYPE.matches(q) && !SQUAWK.matches(q)) out += RemoteQuery.Type(q)
        return out.toList()
    }
}
