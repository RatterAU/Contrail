package app.contrail

import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Route
import app.contrail.data.feed.parseReadsb
import app.contrail.domain.FlightProgressCalc
import app.contrail.domain.Geo
import app.contrail.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightProgressTest {
    private val adl = Airport("YPAD", "ADL", "Adelaide", "Adelaide", "AU", -34.945, 138.531, 20)
    private val syd = Airport("YSSY", "SYD", "Sydney", "Sydney", "AU", -33.946, 151.177, 21)
    private val route = Route("QFA736", "QF736", null, adl, syd)

    private val base = parseReadsb(
        """{"ac":[{"hex":"7c1234","flight":"QFA736","alt_baro":36000,"gs":450,"track":80,"lat":-34.5,"lon":144.0,"seen":0,"seen_pos":0}],"now":0}""",
        0,
    ).single()

    private fun trackAt(lat: Double, lon: Double, trk: Double = 0.0, ground: Boolean = false, gs: Double = 450.0): Track {
        val heading = if (trk == 0.0) Geo.bearing(lat, lon, syd.lat, syd.lon) else trk
        return Track.start(base.copy(lat = lat, lon = lon, track = heading, onGround = ground, groundSpeed = gs, positionTime = 0, seenTime = 0), 0)
    }

    @Test fun midRouteIsVerifiedWithEta() {
        val (lat, lon) = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, syd.lat, syd.lon), 300.0)
        val p = FlightProgressCalc.compute(trackAt(lat, lon), route, 0)
        assertTrue(p.verified)
        assertEquals(300.0, p.flownNm, 1.0)
        assertEquals(0.47f, p.fraction, 0.02f)
        // ~333 nm at 450 kt ≈ 44 min, plus a 6–8 min approach buffer.
        val etaMin = p.eta!! / 60_000.0
        assertTrue("eta $etaMin", etaMin in 48.0..54.0)
        assertTrue(p.departedEstimated)
        assertNotNull(p.departedAt)
    }

    @Test fun departureEstimateHoldsWhenThePlaneSlowsOnApproach() {
        val brg = Geo.bearing(adl.lat, adl.lon, syd.lat, syd.lon)
        val (lat, lon) = Geo.destination(adl.lat, adl.lon, brg, 600.0)
        val cruising = trackAt(lat, lon, gs = 450.0)
        // Same spot, now slowed to 150 kt, with the cruise fix still on the recorded trail.
        val slowed = cruising.update(cruising.aircraft.copy(groundSpeed = 150.0, positionTime = 1, seenTime = 1), 1)
        val before = FlightProgressCalc.compute(cruising, route, 0).departedAt!!
        val after = FlightProgressCalc.compute(slowed, route, 0).departedAt!!
        assertEquals(before, after)
    }

    @Test fun departureEstimateForAJetFirstSeenOnApproachUsesTypicalCruise() {
        // 1,400 nm flown, only ever seen at 3,000 ft and 178 kt: assume airliner cruise, not 178 kt for 8 hours.
        val far = Airport("YPDN", "DRW", "Darwin", "Darwin", "AU", -12.41, 130.88, 100)
        val r = Route("JST689", "JQ689", null, far, adl)
        val (lat, lon) = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, far.lat, far.lon), 10.0)
        val t = Track.start(base.copy(lat = lat, lon = lon, altBaro = 3000, groundSpeed = 178.0, category = "A3", baroRate = -900.0,
            track = Geo.bearing(lat, lon, adl.lat, adl.lon), positionTime = 0, seenTime = 0), 0)
        val p = FlightProgressCalc.compute(t, r, 0)
        val hours = -p.departedAt!! / 3_600_000.0
        assertTrue("took off $hours h ago", hours in 3.0..3.8)
        // And it lands in a few minutes, not after a long cruise.
        assertTrue("eta ${p.eta!! / 60_000.0} min", p.eta!! / 60_000.0 in 4.0..9.0)
    }

    @Test fun planeFarOffCorridorIsUnverified() {
        // Over Perth while "flying" ADL→SYD: a stale route.
        val p = FlightProgressCalc.compute(trackAt(-31.9, 115.9), route, 0)
        assertFalse(p.verified)
        assertNull(p.eta)
    }

    @Test fun longHaulWellOffTheGreatCircleIsVerified() {
        // EY445 Sydney to Abu Dhabi crosses Adelaide, 280 nm off the great circle: still its route.
        val auh = Airport("OMAA", "AUH", "Abu Dhabi", "Abu Dhabi", "AE", 24.433, 54.651, 88)
        val ey = Route("ETD445", "EY445", null, syd, auh)
        assertTrue(FlightProgressCalc.compute(trackAt(adl.lat, adl.lon, trk = 290.0), ey, 0).verified)
        // Heading back east over Adelaide isn't that flight.
        assertFalse(FlightProgressCalc.compute(trackAt(adl.lat, adl.lon, trk = 100.0), ey, 0).verified)
        // Just after takeoff, a departure procedure pointing south still counts.
        val (lat, lon) = Geo.destination(syd.lat, syd.lon, 180.0, 15.0)
        assertTrue(FlightProgressCalc.compute(trackAt(lat, lon, trk = 160.0), ey, 0).verified)
    }

    @Test fun headingAwayFromDestinationIsUnverified() {
        val (lat, lon) = Geo.destination(adl.lat, adl.lon, 85.0, 300.0)
        val p = FlightProgressCalc.compute(trackAt(lat, lon, trk = 265.0), route, 0)
        assertFalse(p.verified)
    }

    @Test fun observedTakeoffBeatsEstimate() {
        val t = trackAt(adl.lat, adl.lon, ground = true, gs = 15.0)
            .update(base.copy(lat = adl.lat + 0.05, lon = adl.lon + 0.1, positionTime = 120_000, seenTime = 120_000), 120_000)
        val p = FlightProgressCalc.compute(t, route, 120_000)
        assertEquals(120_000L, p.departedAt)
        assertFalse(p.departedEstimated)
    }

    @Test fun landedAtDestination() {
        var t = trackAt(syd.lat + 0.3, syd.lon - 0.3)
        t = t.update(base.copy(lat = syd.lat, lon = syd.lon, onGround = true, groundSpeed = 20.0, positionTime = 60_000, seenTime = 60_000), 60_000)
        val p = FlightProgressCalc.compute(t, route, 60_000)
        assertTrue(p.verified)
        assertEquals(60_000L, p.landedAt)
        assertEquals(1f, p.fraction)
    }
}
