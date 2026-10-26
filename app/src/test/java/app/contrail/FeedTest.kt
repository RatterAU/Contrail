package app.contrail

import app.contrail.data.feed.ADSB_FI
import app.contrail.data.feed.ADSB_LOL
import app.contrail.data.feed.AdsbSource
import app.contrail.data.feed.FeedScheduler
import app.contrail.data.feed.FeedsUnavailableException
import app.contrail.data.feed.parseReadsb
import app.contrail.domain.Aircraft
import app.contrail.domain.Geo
import app.contrail.domain.PositionSource
import app.contrail.domain.Track
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private const val NOW = 1_792_918_779_500L

// Trimmed from a real adsb.lol point response over Adelaide, 2026-10-25.
private val LOL = """
{"ac":[
{"hex":"7c5304","type":"adsb_icao","flight":"QLK589D ","r":"VH-QOM","t":"DH8D","alt_baro":16000,"alt_geom":16175,"gs":312.9,"ias":277,"tas":354,"mach":0.56,"wd":263,"ws":40,"oat":-10,"tat":7,"track":274.4,"baro_rate":64,"squawk":"7250","emergency":"none","category":"A2","nav_qnh":1012.8,"nav_altitude_mcp":2816,"nav_heading":267.89,"nav_modes":["autopilot","vnav"],"lat":-34.789837,"lon":136.84782,"nic":8,"nac_p":9,"sil":3,"version":2,"messages":18333,"seen":0,"seen_pos":0.5,"rssi":-18.5,"dst":83.176,"dir":275.7},
{"hex":"7c6de6","type":"adsb_icao","flight":"QFA777  ","r":"VH-VZS","t":"B738","alt_baro":"ground","gs":12.1,"track":90,"lat":-34.94,"lon":138.53,"seen":2,"seen_pos":2},
{"hex":"~2a1b3c","type":"tisb_other","lat":-35.0,"lon":138.6,"seen":1},
{"hex":"7c0000","type":"mode_s","alt_baro":3000,"seen":1}
],"msg":"No error","now":$NOW,"total":4,"ctime":0,"ptime":1}
""".trimIndent()

private val FI = """{"now":$NOW,"aircraft":[{"hex":"ae10e9","type":"mlat","flight":"C2003","t":"C130","dbFlags":1,"alt_baro":24000,"lat":-34.5,"lon":138.2,"seen":1,"seen_pos":1}],"resultCount":1,"ptime":1}"""

class ParserTest {
    @Test fun parsesAdsbLolPointResponse() {
        val list = parseReadsb(LOL, NOW)
        assertEquals("aircraft without a position are dropped", 3, list.size)
        val q = list.first { it.hex == "7c5304" }
        assertEquals("QLK589D", q.callsign)
        assertEquals("VH-QOM", q.registration)
        assertEquals(16000, q.altBaro)
        assertFalse(q.onGround)
        assertEquals(312.9, q.groundSpeed!!, 0.001)
        assertEquals(2816, q.navAltMcp)
        assertEquals(listOf("autopilot", "vnav"), q.navModes)
        assertNull("'none' is not an emergency", q.emergency)
        assertEquals(PositionSource.ADSB, q.source)
        assertEquals(NOW - 500, q.positionTime)
    }

    @Test fun groundAltitudeAndHexPrefix() {
        val list = parseReadsb(LOL, NOW)
        val g = list.first { it.hex == "7c6de6" }
        assertTrue(g.onGround)
        assertEquals(0, g.altBaro)
        val tisb = list.first { it.source == PositionSource.TISB }
        assertEquals("non-ICAO '~' prefix stripped", "2a1b3c", tisb.hex)
    }

    @Test fun parsesAdsbFiAircraftKeyAndFlags() {
        val a = parseReadsb(FI, NOW).single()
        assertEquals(PositionSource.MLAT, a.source)
        assertTrue(a.military)
        assertEquals("C2003", a.callsign)
    }

    @Test fun implausibleServerClockFallsBackToPhoneClock() {
        val a = parseReadsb(FI.replace("$NOW", "1000"), NOW).single()
        assertEquals(NOW - 1000, a.positionTime)
    }
}

class GeoTest {
    @Test fun adelaideToSydneyDistance() {
        // YPAD → YSSY is ~633 nm great-circle.
        assertEquals(633.0, Geo.distanceNm(-34.945, 138.531, -33.946, 151.177), 5.0)
    }

    @Test fun destinationRoundTrips() {
        val (lat, lon) = Geo.destination(-34.9, 138.5, 45.0, 100.0)
        assertEquals(100.0, Geo.distanceNm(-34.9, 138.5, lat, lon), 0.01)
        assertEquals(45.0, Geo.bearing(-34.9, 138.5, lat, lon), 0.5)
    }

    @Test fun crossTrack() {
        // A point on the equator path has ~0 cross-track; one 60 nm north is ~60 nm off.
        assertEquals(0.0, Geo.crossTrackNm(0.0, 0.0, 0.0, 10.0, 0.0, 5.0), 0.01)
        assertEquals(60.0, kotlin.math.abs(Geo.crossTrackNm(0.0, 0.0, 0.0, 10.0, 1.0, 5.0)), 0.5)
    }

    @Test fun compassAndAngles() {
        assertEquals("N", Geo.compass(359.0))
        assertEquals("NE", Geo.compass(40.0))
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
    }
}

class TrackTest {
    private fun plane(t: Long, lat: Double, ground: Boolean = false, gs: Double = 240.0) =
        parseReadsb(LOL, NOW).first { it.hex == "7c5304" }.copy(lat = lat, positionTime = t, seenTime = t, onGround = ground, groundSpeed = gs, track = 0.0)

    @Test fun extrapolatesAlongTrack() {
        val a = plane(0, 0.0)
        val (lat, _) = Track.extrapolate(a, 15_000) // 240 kt for 15 s = 1 nm north
        assertEquals(1.0 / 60, lat, 1e-4)
    }

    @Test fun extrapolationIsCapped() {
        val a = plane(0, 0.0)
        assertEquals(Track.extrapolate(a, Track.MAX_EXTRAPOLATE_MS), Track.extrapolate(a, 10 * 60_000))
    }

    @Test fun recordsTakeoffAndLanding() {
        var t = Track.start(plane(0, 0.0, ground = true, gs = 20.0), 0)
        t = t.update(plane(60_000, 0.1), 60_000)
        assertEquals(60_000L, t.takeoffAt)
        t = t.update(plane(3_600_000, 5.0, ground = true, gs = 30.0), 3_600_000)
        assertEquals(3_600_000L, t.landedAt)
        assertEquals(3, t.trail.size)
    }

    @Test fun ignoresOlderReports() {
        val t = Track.start(plane(10_000, 1.0), 10_000)
        assertEquals(t, t.update(plane(5_000, 0.5), 11_000))
    }

    @Test fun blendEasesFromOldDrawnPosition() {
        var t = Track.start(plane(0, 0.0, gs = 0.0), 0)
        t = t.update(plane(1_000, 0.01, gs = 0.0), 1_000)
        assertEquals(0.0, t.displayPosition(1_000).first, 1e-9)
        assertEquals(0.01, t.displayPosition(1_000 + Track.BLEND_MS).first, 1e-9)
    }
}

class SchedulerTest {
    private class FakeSource(name: String, val fail: Boolean) :
        AdsbSource(if (name == "a") ADSB_LOL else ADSB_FI, HttpClient(), { 0L }) {
        var calls = 0
        override suspend fun point(lat: Double, lon: Double, nm: Int): List<Aircraft> {
            calls++
            if (fail) throw IOException("HTTP 429")
            return emptyList()
        }
    }

    @Test fun failsOverAndBenchesBrokenSource() = runTest {
        val bad = FakeSource("a", fail = true)
        val good = FakeSource("b", fail = false)
        var clock = 0L
        val s = FeedScheduler(listOf(bad, good), clock = { clock }, minIntervalMs = 0)
        s.run { it.point(0.0, 0.0, 10) }
        assertEquals(1, bad.calls)
        assertEquals(1, good.calls)
        assertFalse(s.health.value.getValue(bad.name).ok)
        // While benched, the bad source is tried last, so the good one answers first.
        repeat(4) { clock += 1_000; s.run { it.point(0.0, 0.0, 10) } }
        assertEquals(1, bad.calls)
        assertNotNull(s.health.value[good.name]?.latencyMs)
    }

    @Test fun allDownThrows() = runTest {
        val s = FeedScheduler(listOf(FakeSource("a", true), FakeSource("b", true)), clock = { 0L }, minIntervalMs = 0)
        try {
            s.run { it.point(0.0, 0.0, 10) }
            fail("expected FeedsUnavailableException")
        } catch (_: FeedsUnavailableException) {
        }
    }
}
