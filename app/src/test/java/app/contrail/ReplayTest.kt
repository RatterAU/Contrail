package app.contrail

import app.contrail.domain.Replay
import app.contrail.domain.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private fun p(t: Long, lat: Double, lon: Double, alt: Int? = 10_000, gs: Double? = 300.0, ground: Boolean = false) =
        TrailPoint(lat, lon, alt, ground, gs, t)

    @Test fun interpolatesBetweenFixes() {
        val path = listOf(p(0, 0.0, 0.0, 0, 100.0), p(60_000, 0.0, 1.0, 6_000, 300.0))
        val f = Replay.at(path, 30_000)!!
        assertEquals(0.5, f.lon, 1e-9)
        assertEquals(3_000, f.alt)
        assertEquals(200.0, f.gs!!, 1e-9)
        assertEquals(90.0, f.heading, 0.5) // flying east
        assertFalse(f.gap)
    }

    @Test fun clampsToThePathAndNeedsTwoPoints() {
        val path = listOf(p(1_000, 0.0, 0.0), p(2_000, 1.0, 0.0))
        assertEquals(1_000L, Replay.at(path, 0)!!.time)
        assertEquals(1.0, Replay.at(path, 9_999)!!.lat, 1e-9)
        assertNull(Replay.at(path.take(1), 1_000))
    }

    @Test fun crossesTheDatelineTheShortWay() {
        val f = Replay.at(listOf(p(0, -20.0, 179.0), p(60_000, -20.0, -179.0)), 30_000)!!
        assertTrue("${f.lon}", f.lon > 179.9 || f.lon < -179.9)
        assertEquals(90.0, f.heading, 1.0)
    }

    @Test fun aCoverageHoleIsFlagged() {
        val f = Replay.at(listOf(p(0, -34.0, 138.0), p(Replay.GAP_MS + 60_000, -30.0, 140.0)), 120_000)!!
        assertTrue(f.gap)
    }

    @Test fun headingIgnoresJitterOnTheGround() {
        // Lined up heading north, then a GPS wobble a few metres west while stopped.
        val path = listOf(p(0, -35.0, 138.0, ground = true), p(30_000, -34.99, 138.0, ground = true), p(40_000, -34.98999, 137.99999, ground = true))
        val h = Replay.at(path, 35_000)!!.heading
        assertTrue("$h", h < 1.0 || h > 359.0)
    }
}
