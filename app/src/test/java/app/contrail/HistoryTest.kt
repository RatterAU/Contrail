package app.contrail

import app.contrail.data.feed.FlightHistory
import app.contrail.data.feed.parseReadsb
import app.contrail.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HistoryTest {
    // Sydney gate, climb-out, then a 30 min coverage gap to over the Bight (as seen on QFA655).
    private val json = """{"icao24":"7c6dde","path":[
        [1000,-33.9374,151.1737,0,348,false],
        [1020,-33.9280,151.1713,304,347,false],
        [1600,-33.5000,149.0000,10972,260,false],
        [3400,-34.8000,138.0000,10972,265,false]]}"""

    @Test fun parsesConvertsAndBridgesGaps() {
        val pts = FlightHistory.parseOpenSkyTrack(json, 3_500_000)!!
        assertEquals(1_000_000L, pts.first().time)
        assertEquals(997, pts[1].alt) // 304 m in feet
        // No step bigger than the trail's 0.8° gap rule, so the line is drawn whole.
        pts.zipWithNext().forEach { (a, b) -> assertTrue("${a.lon} -> ${b.lon}", abs(a.lon - b.lon) < 0.8 && abs(a.lat - b.lat) < 0.8) }
        assertTrue(pts.zipWithNext().all { (a, b) -> b.time >= a.time })
        // Ground speed derived from the fixes: ~560 nm in 30 min.
        assertEquals(1120.0, pts.last().gs!!, 120.0)
    }

    @Test fun anEarlierFlightIsIgnored() {
        assertNull(FlightHistory.parseOpenSkyTrack(json, 3_400_000 + 16 * 60_000))
    }

    @Test fun historyPrependsAndGivesTheRealTakeoffTime() {
        val live = Track.start(parseReadsb("""{"ac":[{"hex":"7c6dde","lat":-34.81,"lon":137.9,"alt_baro":36000,"gs":460,"seen_pos":0}]}""", 3_410_000).single(), 3_410_000)
        val t = live.withHistory(FlightHistory.parseOpenSkyTrack(json, 3_500_000)!!)
        assertEquals(1_020_000L, t.takeoffAt)
        assertEquals(live.trail.last(), t.trail.last())
        assertTrue(t.trail.size > 4)
        // Merging the same history again changes nothing.
        assertEquals(t.trail.size, t.withHistory(FlightHistory.parseOpenSkyTrack(json, 3_500_000)!!).trail.size)
    }

    @Test fun pastFlightsAreThisPlanesOnlyNewestFirst() {
        // A shard holds every aircraft whose hex starts 7c4e; blank airports were out of coverage.
        val shard = "7c4ee8\tQFA1\tYSSY\t100\tWSSS\t580\n7c4ee8\tQFA2\tWSSS\t700\t\t~1200\n7c4ee9\tVOZ1\tYMML\t50\tYSSY\t140\n"
        val list = FlightHistory.parsePastFlights(shard, "7c4ee8")
        assertEquals(listOf("QFA2", "QFA1"), list.map { it.callsign })
        assertNull(list[0].to)
        assertEquals(1200 * 60_000L, list[0].arrived)
        assertEquals(false, list[0].arrivedSeen) // "~": when it was last seen, not when it landed
        assertTrue(list[1].arrivedSeen)
        assertEquals(100 * 60_000L, list[1].departed)
    }

    @Test fun pastFlightPathFromAdsbLolTrace() {
        val f = FlightHistory.parsePastFlights("7c6d97\tQFA683\tYMML\t29884000\tYPAD\t29884051\t20261026.0.ab.1500.120 20261027.0.aa.9.8\n", "7c6d97").single()
        assertEquals(listOf("20261026.0.ab.1500.120", "20261027.0.aa.9.8"), f.traces)
        assertEquals(
            "https://github.com/adsblol/globe_history_2026/releases/download/v2026.10.26-planes-readsb-prod-0/v2026.10.26-planes-readsb-prod-0.tar.ab",
            FlightHistory.traceUrl(f.traces[0]),
        )
        assertNull(FlightHistory.traceUrl("garbage"))
        // Rows are seconds after `timestamp`; "ground" and null altitudes both parse.
        val base = 29884000L * 60
        val pts = FlightHistory.parseTrace(
            """{"icao":"7c6d97","timestamp":$base,"trace":[[0,-37.67,144.84,"ground",12,0,0,null,null],[60,-37.6,144.9,3000,180.5,0,0,1500,null],
            [1800,-36,141,35000,450,0,0,0,null],[1810,-35.99,141.1,35000,9999,0,0,0,null],[3000,-34.95,138.53,null,140,0,0,0,null],[9999,-34.9,138.5,0,0,0,0,0,null]]}""",
        )
        assertEquals(6, pts.size)
        assertTrue(pts[0].onGround && pts[0].alt == 0)
        assertEquals(3000, pts[1].alt)
        assertNull(pts[4].alt)
        assertEquals((base + 60) * 1000, pts[1].time)
        // The flight's window drops the row from the next flight hours later.
        val path = FlightHistory.slice(pts, f)
        assertEquals(5, path.size)
        val st = FlightHistory.stats(path)
        assertEquals(35000, st.topAltFt)
        assertTrue(st.distanceNm > 300 && st.distanceNm < 600)
        assertEquals(450.0, st.topSpeedKt!!, 0.0) // the 9999 kt glitch is ignored
    }
}
