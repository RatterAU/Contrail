package app.contrail

import app.contrail.data.enrich.parseAdsbLolLegs
import app.contrail.data.feed.parseReadsb
import app.contrail.domain.FlightProgressCalc
import app.contrail.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteLegsTest {
    // Trimmed from adsb.lol /api/0/route/QFA1, 2026-10-27.
    private val qfa1 = parseAdsbLolLegs(
        """{"callsign":"QFA1","number":"1","airline_code":"QFA","airport_codes":"YSSY-WSSS-EGLL","_airports":[
          {"name":"Sydney Kingsford Smith International Airport","icao":"YSSY","iata":"SYD","location":"Sydney","countryiso2":"AU","lat":-33.946098,"lon":151.177002,"alt_feet":21,"alt_meters":6.4},
          {"name":"Singapore Changi Airport","icao":"WSSS","iata":"SIN","location":"Singapore","countryiso2":"SG","lat":1.35019,"lon":103.994003,"alt_feet":22,"alt_meters":6.7},
          {"name":"London Heathrow Airport","icao":"EGLL","iata":"LHR","location":"London","countryiso2":"GB","lat":51.4706,"lon":-0.461941,"alt_feet":83,"alt_meters":25.3}]}""",
    )

    private fun at(lat: Double, lon: Double, track: Int) = Track.start(
        parseReadsb("""{"ac":[{"hex":"7c4ee8","flight":"QFA1","alt_baro":38000,"gs":480,"track":$track,"lat":$lat,"lon":$lon,"seen":0,"seen_pos":0}]}""", 0)[0], 0,
    )

    @Test fun eachStopPairIsALeg() {
        assertEquals(listOf("SYD-SIN", "SIN-LHR"), qfa1.map { "${it.origin.iata}-${it.destination.iata}" })
        assertEquals("Singapore", qfa1[0].destination.city)
    }

    @Test fun theLegThePlaneIsOnIsShown() {
        // Over the Kimberley heading north-west: Sydney to Singapore.
        assertEquals("SIN", FlightProgressCalc.shownRoute(at(-17.0, 125.0, 300), null, 0, qfa1)?.destination?.iata)
        // Over the Bay of Bengal heading north-west: Singapore to London.
        assertEquals("LHR", FlightProgressCalc.shownRoute(at(15.0, 88.0, 300), null, 0, qfa1)?.destination?.iata)
    }
}
