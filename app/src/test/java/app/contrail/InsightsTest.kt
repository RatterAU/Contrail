package app.contrail

import app.contrail.data.enrich.AirlineLogos
import app.contrail.data.feed.parseReadsb
import app.contrail.domain.FlightInsights
import app.contrail.domain.Geo
import app.contrail.domain.Phase
import app.contrail.domain.Track
import app.contrail.ui.map.MapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private fun track(json: String) = Track.start(parseReadsb("""{"ac":[$json]}""", 0).single(), 0)

    @Test fun phases() {
        assertEquals(Phase.CRUISING, FlightInsights.phase(track("""{"hex":"a","alt_baro":36000,"baro_rate":0,"gs":450,"lat":0,"lon":0}"""), null, 0))
        assertEquals(Phase.CLIMBING, FlightInsights.phase(track("""{"hex":"a","alt_baro":12000,"baro_rate":2000,"gs":300,"lat":0,"lon":0}"""), null, 0))
        assertEquals(Phase.DESCENDING, FlightInsights.phase(track("""{"hex":"a","alt_baro":20000,"baro_rate":-1500,"gs":350,"lat":0,"lon":0}"""), null, 0))
        assertEquals(Phase.APPROACH, FlightInsights.phase(track("""{"hex":"a","alt_baro":2000,"baro_rate":-700,"gs":150,"lat":0,"lon":0}"""), null, 0))
        assertEquals(Phase.TAXIING, FlightInsights.phase(track("""{"hex":"a","alt_baro":"ground","gs":15,"lat":0,"lon":0}"""), null, 0))
        assertEquals(Phase.PARKED, FlightInsights.phase(track("""{"hex":"a","alt_baro":"ground","gs":0,"lat":0,"lon":0}"""), null, 0))
    }

    @Test fun windComponents() {
        // Wind from straight ahead is all headwind; from behind, all tailwind; from the right, crosswind.
        FlightInsights.windComponents(90.0, 40.0, 90.0).let { (h, c) -> assertEquals(40.0, h, 0.01); assertEquals(0.0, c, 0.01) }
        FlightInsights.windComponents(270.0, 40.0, 90.0).let { (h, _) -> assertEquals(-40.0, h, 0.01) }
        FlightInsights.windComponents(180.0, 40.0, 90.0).let { (_, c) -> assertEquals(40.0, c, 0.01) }
    }

    @Test fun tailwindAndAutopilotTargetAreReported() {
        val t = track("""{"hex":"a","alt_baro":20000,"baro_rate":-1500,"gs":420,"track":90,"wd":270,"ws":60,"nav_altitude_mcp":10008,"lat":0,"lon":0}""")
        val texts = FlightInsights.insights(t, null, null, null, 0).map { it.text }
        assertTrue(texts.toString(), texts.any { "tailwind" in it })
        assertTrue(texts.toString(), texts.any { it.startsWith("Descending to") })
    }

    @Test fun missedApproachAltitudeIsNotReportedAsAClimb() {
        // On final with 3,008 ft set for the go-around: the sheet must not say it's climbing.
        val t = track("""{"hex":"a","alt_baro":1500,"baro_rate":-700,"gs":140,"nav_altitude_mcp":3008,"lat":0,"lon":0}""")
        val texts = FlightInsights.insights(t, null, null, null, 0).map { it.text }
        assertFalse(texts.toString(), texts.any { "Climbing" in it })
    }

    @Test fun ageNeedsAPlausibleYear() {
        assertEquals(6, FlightInsights.ageYears("2019", 2025))
        assertEquals(null, FlightInsights.ageYears("0000", 2025))
        assertEquals(null, FlightInsights.ageYears(null, 2025))
    }

    private fun image(w: Int, h: Int, fill: (Int, Int) -> Int) = IntArray(w * h) { fill(it % w, it / w) }

    @Test fun logosOnTransparencyPassBoxedLogosFail() {
        val mark = image(64, 64) { x, y -> if (x in 16..47 && y in 16..47) 0xFFFF0000.toInt() else 0 }
        assertTrue(AirlineLogos.isCleanLogo(mark, 64, 64))
        val boxed = image(64, 64) { _, _ -> 0xFFFFFFFF.toInt() }
        assertFalse(AirlineLogos.isCleanLogo(boxed, 64, 64))
        assertTrue(AirlineLogos.opaqueBounds(mark, 64, 64)!!.contentEquals(intArrayOf(16, 16, 48, 48)))
    }

    @Test fun pathAheadEndsAtTheDestinationEvenWhenPointingAway() {
        val dest = -33.946 to 151.177
        // Heading due west, away from Sydney.
        val pts = MapController.pathAhead(-34.9, 138.5, 270.0, dest)
        val end = pts.last()
        assertTrue(Geo.distanceNm(end.latitude(), end.longitude(), dest.first, dest.second) < 3.5)
        // It starts by continuing west (the turn), not by jumping onto the great circle.
        assertTrue(pts[1].longitude() < 138.5)
    }

    @Test fun projectionCoversEightMinutesAtCurrentSpeed() {
        val pts = MapController.projected(0.0, 0.0, 90.0, 480.0, null)
        val last = pts.last()
        assertEquals(64.0, Geo.distanceNm(0.0, 0.0, last.latitude(), last.longitude()), 1.0)
    }
}
