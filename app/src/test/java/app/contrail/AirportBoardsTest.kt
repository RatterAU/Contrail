package app.contrail

import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Route
import app.contrail.data.feed.parseReadsb
import app.contrail.data.static.Runway
import app.contrail.domain.AirportBoards
import app.contrail.domain.BoardStatus
import app.contrail.domain.Geo
import app.contrail.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportBoardsTest {
    private val adl = Airport("YPAD", "ADL", "Adelaide", "Adelaide", "AU", -34.945, 138.531, 20)
    private val syd = Airport("YSSY", "SYD", "Sydney", "Sydney", "AU", -33.946, 151.177, 21)
    private val mel = Airport("YMML", "MEL", "Melbourne", "Melbourne", "AU", -37.673, 144.843, 434)

    private val proto = parseReadsb("""{"ac":[{"hex":"7c0001","flight":"X","alt_baro":10000,"gs":300,"track":0,"lat":0,"lon":0,"seen":0}],"now":0}""", 0).single()

    private fun t(hex: String, cs: String, lat: Double, lon: Double, alt: Int, trk: Double, ground: Boolean = false, gs: Double = 300.0, vr: Double = 0.0) =
        Track.start(proto.copy(hex = hex, callsign = cs, lat = lat, lon = lon, altBaro = alt, track = trk, onGround = ground, groundSpeed = gs, baroRate = vr, positionTime = 0, seenTime = 0), 0)

    @Test fun classifiesArrivalsAndDepartures() {
        val inbound = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, mel.lat, mel.lon), 120.0)
        val outbound = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, syd.lat, syd.lon), 60.0)
        val tracks = listOf(
            t("a1", "QFA680", inbound.first, inbound.second, 20000, Geo.bearing(inbound.first, inbound.second, adl.lat, adl.lon)),
            t("a2", "QFA736", outbound.first, outbound.second, 25000, Geo.bearing(outbound.first, outbound.second, syd.lat, syd.lon)),
            t("a3", "VOZ221", adl.lat, adl.lon + 0.01, 0, 90.0, ground = true, gs = 12.0),
            // No route: on final to runway 23 counts; a trainer descending over Parafield, 9 nm off, doesn't.
            Geo.destination(adl.lat, adl.lon, 50.0, 6.0).let { t("a4", "UNKNOWN1", it.first, it.second, 2000, 230.0, vr = -700.0) },
            t("a5", "UNKNOWN2", -34.793, 138.633, 1500, 180.0, vr = -500.0),
        )
        val routes = mapOf(
            "QFA680" to Route("QFA680", "QF680", null, mel, adl),
            "QFA736" to Route("QFA736", "QF736", null, adl, syd),
            "VOZ221" to Route("VOZ221", "VA221", null, adl, mel),
        )
        val b = AirportBoards.build(adl, tracks, routes, 0, listOf(Runway("YPAD", "05", "23", 10171, "ASP", 50.0, 230.0, null, null, null, null)))
        assertEquals(listOf("a4", "a1"), b.arrivals.map { it.track.hex }.sorted().reversed())
        assertEquals(BoardStatus.EN_ROUTE, b.arrivals.first { it.track.hex == "a1" }.status)
        assertEquals(BoardStatus.APPROACH, b.arrivals.first { it.track.hex == "a4" }.status)
        assertEquals(BoardStatus.TAXIING, b.departures.first().status)
        assertEquals(BoardStatus.DEPARTED, b.departures.first { it.track.hex == "a2" }.status)
        assertTrue(b.arrivals.first { it.track.hex == "a1" }.time != null)
    }

    @Test fun activeRunwayFromTrafficThenWind() {
        val rwy = Runway("YPAD", "05", "23", 10171, "ASP", 50.0, 230.0, null, null, null, null)
        val landing23 = t("b1", "QFA1", adl.lat + 0.05, adl.lon + 0.05, 1500, 228.0)
        assertEquals(AirportBoards.ActiveRunway("23", true), AirportBoards.activeRunway(adl, listOf(rwy), listOf(landing23), windDir = 40.0))
        assertEquals(AirportBoards.ActiveRunway("05", false), AirportBoards.activeRunway(adl, listOf(rwy), emptyList(), windDir = 40.0))
    }
}
