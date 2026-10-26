package app.contrail

import app.contrail.data.enrich.Airport
import app.contrail.data.feed.parseReadsb
import app.contrail.data.static.StaticData
import app.contrail.domain.FlightProgressCalc
import app.contrail.domain.TrailPoint
import app.contrail.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlownRouteTest {
    private val doh = Airport("OTHH", "DOH", "Hamad International", "Doha", "QA", 25.273, 51.608, 13, size = 'L')
    private val adl = Airport("YPAD", "ADL", "Adelaide", "Adelaide", "AU", -34.945, 138.531, 20, size = 'L')
    private val data = StaticData(listOf(doh, adl), emptyMap(), emptyMap(), emptyMap())

    // QTR65Q, 2026-10-26: adsbdb listed Doha to Colombo; it flew Doha to Adelaide.
    private val landed = parseReadsb(
        """{"ac":[{"hex":"06a10e","flight":"QTR65Q","alt_baro":"ground","gs":16,"track":53,"lat":-34.946,"lon":138.535,"seen":0,"seen_pos":0}]}""",
        1_000_000,
    )[0]
    private val history = listOf(
        TrailPoint(25.3035, 51.5844, 997, false, null, 1_000),
        TrailPoint(-34.9364, 138.5494, 0, false, null, 900_000),
    )

    @Test fun routeIsReadOffTheFlownPath() {
        val t = Track.start(landed, 1_000_000).withHistory(history)
        val r = FlightProgressCalc.flownRoute(t, null, data)!!
        assertEquals("DOH", r.origin.iata)
        assertEquals("ADL", r.destination.iata)
        assertEquals(900_000L, t.landedAt)
        assertTrue(FlightProgressCalc.compute(t, r, 1_000_000).verified)
    }

    @Test fun destinationIsReadOffTheFinalApproach() {
        // The same flight 10 nm out on final for runway 23, descending through 3,000 ft.
        fun at(alt: Int, gs: Int) = parseReadsb(
            """{"ac":[{"hex":"06a10e","flight":"QTR65Q","alt_baro":$alt,"baro_rate":-700,"gs":$gs,"track":229,"lat":-34.85,"lon":138.67,"seen":0,"seen_pos":0}]}""",
            1_000_000,
        )[0]
        val path = history.take(1)
        assertEquals("ADL", FlightProgressCalc.flownRoute(Track.start(at(3000, 160), 1_000_000).withHistory(path), null, data)?.destination?.iata)
        // Overflying at cruise is no evidence of where it's going.
        assertNull(FlightProgressCalc.flownRoute(Track.start(at(37_000, 480), 1_000_000).withHistory(path), null, data))
    }

    @Test fun noRouteWithoutAFlownPath() {
        assertNull(FlightProgressCalc.flownRoute(Track.start(landed, 1_000_000), null, data))
    }
}
